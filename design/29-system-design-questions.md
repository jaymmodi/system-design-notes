# System Design Interview Questions — Thought Process + Solutions

> For every question: clarify → estimate → design → deep dive → failures.
> Never jump to solution. Always spend 5 minutes on requirements first.

---

## The Universal Framework (apply to every question)

```
Step 1 — Clarify (5 min)
  Who uses this? What are the core actions?
  Read-heavy or write-heavy?
  What scale? (users, requests/sec, data size)
  Consistency vs availability trade-off?
  Any latency SLOs?

Step 2 — Estimate (3 min)
  DAU → requests/sec (DAU × actions/day / 86400)
  Storage growth per day/year
  Bandwidth in/out

Step 3 — High-level design (10 min)
  Draw the boxes: client → LB → service → DB
  Identify the hard problems in this specific system

Step 4 — Deep dive (20 min)
  Go deep on 2-3 components the interviewer cares about
  Always address: data model, scaling, failure modes

Step 5 — Wrap up (5 min)
  Bottlenecks, monitoring, what you'd do differently
```

---

## 1. URL Shortener (TinyURL / bit.ly)

### Clarifying questions to ask
```
"A few questions before I start:"
1. Do we need custom aliases (user picks the short URL)?
2. Can short URLs expire? Who sets TTL?
3. Read-heavy or write-heavy? (hint: reads >> writes, ~100:1)
4. Do we need analytics (click counts, geo, referrer)?
5. Should it be globally consistent or is eventual consistency OK?
```

### Requirements decided
- Shorten URL → get short code (7 chars)
- Redirect short URL → original URL
- Optional expiry
- Analytics: click count per URL (eventual consistency OK)

### Non-functional requirements
```
100M URLs created/day
10B redirects/day (100:1 read:write ratio)
URL must never map to wrong destination (strong consistency for reads)
Redirect latency: p99 < 10ms
Storage: 100M URLs/day × 500 bytes = 50 GB/day
```

### Back-of-envelope
```
Writes: 100M / 86400 ≈ 1,200 writes/sec
Reads:  10B  / 86400 ≈ 115,000 reads/sec

Short code space:
  7 chars × [a-z, A-Z, 0-9] = 62^7 ≈ 3.5 trillion codes  (enough for centuries)

Storage per URL: short_code(7) + long_url(500) + created_at(8) + ttl(8) ≈ 523 bytes
1 year: 100M × 365 × 523 bytes ≈ 19 TB
```

### High-level design
```
Client → Load Balancer → URL Service → Cache (Redis) → DB (Cassandra)
                                              ↓
                                     Analytics Service (async)
                                              ↓
                                     Kafka → ClickStream DB (Druid)
```

### Deep dive

**Generating the short code — 3 approaches:**
```
Option 1: Hash the long URL
  MD5(long_url) → take first 7 chars
  Problem: collisions — two different URLs could get same code
  Fix: append user_id or timestamp before hashing
  Problem: same URL by two users = different codes (inconsistent)

Option 2: Counter + Base62 encode
  Global counter: 1, 2, 3 ... → Base62 encode → "000001", "000002"
  Counter storage: Redis INCR (atomic, single node) or distributed ID (Snowflake)
  Problem: single Redis = SPOF; counter reveals volume
  Fix: range-based counters — each app server gets a range (1-1000, 1001-2000)

Option 3: Random + check collision  ← simplest, works well
  Generate random 7-char Base62 string
  Check if exists in DB → if yes, regenerate
  Collision probability at 3.5T space is negligible
  Use: DB unique constraint as safety net
```

**Data model:**
```sql
short_codes table (Cassandra, partition key = short_code):
  short_code   TEXT    (partition key — O(1) lookup)
  long_url     TEXT
  user_id      UUID
  created_at   TIMESTAMP
  expires_at   TIMESTAMP
  click_count  COUNTER  (or separate table)
```

**Redirect flow:**
```
1. Request: GET /abc1234
2. Check Redis cache: O(1) → cache hit → 301/302 redirect, done
3. Cache miss → query Cassandra → cache result → redirect
4. Async: publish click event to Kafka → analytics consumer updates counts

Cache TTL: 24 hours (most URLs are clicked soon after creation)
Cache size: 80/20 rule — 20% of URLs = 80% of traffic → cache hot 20%
```

**301 vs 302 redirect:**
```
301 Permanent: browser caches the redirect → fewer requests to your service
               Problem: can't track clicks accurately
302 Temporary: browser always asks your service → you see every click
               Problem: more load on your servers
Decision: use 302 if you need analytics, 301 if you want to reduce load
```

**Handling expiry:**
```
Option 1: TTL in Redis → auto-evicted, DB soft-delete by cron job
Option 2: Check expires_at on every read → return 410 Gone if expired
Option 3: Cassandra TTL column → row auto-deleted
```

---

## 2. Rate Limiter

### Clarifying questions
```
1. Where does it run? Client-side, server-side, or API gateway?
2. Per-user limit or per-IP or per-API key?
3. Hard limit (reject) or soft limit (throttle/queue)?
4. Distributed system (multiple servers) or single machine?
5. What happens when rate limiter itself is down? Fail open or fail closed?
```

### Requirements decided
- Distributed rate limiter used at API gateway layer
- Limit per user_id: 1000 req/minute
- Hard limit — return 429 Too Many Requests
- Accurate across all gateway nodes (no per-node counting)
- Latency overhead < 5ms

### Algorithms comparison
```
Fixed Window:
  Increment counter for current window (e.g., minute bucket)
  Simple, but edge problem: 1000 req at 0:59, 1000 req at 1:01 = 2000 in 2 seconds
  └── Counter key: "rate:user:42:2024-01-01-12:00"

Sliding Window Log:
  Store timestamp of each request in sorted set
  Remove timestamps older than 1 minute, count remaining
  Accurate but memory: stores every timestamp — 1000 req = 1000 timestamps
  └── ZADD rate:user:42 <timestamp> <request_id>
      ZREMRANGEBYSCORE rate:user:42 0 (now - 60s)
      ZCARD rate:user:42

Sliding Window Counter (best balance):
  current_count = current_window_count + prev_window_count × overlap_fraction
  overlap_fraction = (60s - elapsed_in_current_window) / 60s
  Memory: just 2 counters per user
  Error < 0.1% in practice

Token Bucket:
  Bucket holds up to N tokens, refills at rate R/sec
  Each request consumes 1 token
  Allows bursting (consume stored tokens)
  Good for: APIs where short bursts are OK
```

### Redis implementation (sliding window counter)
```lua
-- Atomic Lua script — runs as single Redis command
local key_curr = KEYS[1]   -- "rate:user:42:current_minute"
local key_prev = KEYS[2]   -- "rate:user:42:prev_minute"
local limit    = tonumber(ARGV[1])   -- 1000
local now_ms   = tonumber(ARGV[2])   -- current time ms
local window   = tonumber(ARGV[3])   -- 60000ms

local curr = tonumber(redis.call('GET', key_curr) or 0)
local prev = tonumber(redis.call('GET', key_prev) or 0)
local elapsed = now_ms % window
local weight = 1 - (elapsed / window)
local count = curr + (prev * weight)

if count >= limit then
    return 0  -- rejected
end

redis.call('INCR', key_curr)
redis.call('PEXPIRE', key_curr, window * 2)
return 1  -- allowed
```

### High-level design
```
Request → API Gateway → Rate Limiter Middleware
                               ↓
                        Redis Cluster (rate counters)
                               ↓
                    429 Too Many Requests  OR  forward to service

Headers to return:
  X-RateLimit-Limit: 1000
  X-RateLimit-Remaining: 243
  X-RateLimit-Reset: 1704067260
  Retry-After: 47  (only on 429)
```

### Failure handling
```
Redis down → fail open (allow all requests) — better than taking down the API
            OR fail closed (reject all) — if security is critical
Use circuit breaker around Redis call
Local fallback: per-node approximate counting if Redis unreachable
```

---

## 3. Design Twitter / Social Media Feed

### Clarifying questions
```
1. What features? Post tweets, follow users, view home timeline?
2. Should timeline be real-time (< 1s) or near-real-time (few seconds OK)?
3. What about celebrities with 100M followers? (fan-out problem)
4. Scale: how many users, tweets/day?
5. Media (photos/videos) or text only for now?
```

### Requirements decided
- Post tweet (text, media)
- Follow/unfollow users
- Home timeline: tweets from people I follow, reverse chronological
- Search tweets (stretch)

### Non-functional requirements
```
300M DAU, 500M tweets/day
Home timeline load: p99 < 200ms
Eventual consistency OK (few seconds delay is fine)
Storage: 500M tweets × 300 bytes = 150 GB/day text
         + media (separate CDN)
```

### Back-of-envelope
```
Writes: 500M / 86400 ≈ 5,800 tweets/sec
Reads: 300M users × 10 timeline loads/day / 86400 ≈ 35,000 timeline reads/sec

Average follows per user: 300
Fan-out on write: 5,800 tweets/sec × 300 followers = 1.74M feed writes/sec
```

### The core problem: fan-out

**Two approaches:**

```
Fan-out on WRITE (Push model):
  When Alice tweets → immediately write to all followers' feed lists
  Timeline read = O(1) (just read your pre-computed list)
  Problem: celebrity with 10M followers → 10M writes per tweet

Fan-out on READ (Pull model):
  Timeline read = fetch tweets from all N people you follow → merge sort
  Write = O(1)
  Problem: if you follow 1000 people → 1000 DB reads per timeline load

Hybrid (Twitter's actual approach):  ← correct answer
  Normal users (< 1M followers):  fan-out on WRITE → push to followers' feeds
  Celebrities (> 1M followers):   fan-out on READ  → followers pull on load

  On timeline load:
    1. Read pre-computed feed from Redis (has normal user tweets)
    2. Fetch latest tweets from celebrities you follow (pull)
    3. Merge the two lists → final timeline
```

### High-level design
```
[Tweet Service]──→ Kafka (tweet events)
                        ↓
                [Fan-out Service]
                  ↙           ↘
        Normal users         Celebrity
        LPUSH feed:userId    (skip — pulled on read)
              ↓
        Redis feed lists (pre-computed timelines)
              ↓
        [Timeline Service]
          1. LRANGE feed:userId 0 19  (Redis)
          2. Fetch celebrity tweets   (DB/cache)
          3. Merge + return
```

### Data model
```
tweets table (Cassandra — write heavy, partition by tweet_id):
  tweet_id    BIGINT    (Snowflake ID — contains timestamp)
  user_id     BIGINT
  content     TEXT
  media_urls  LIST<TEXT>
  created_at  TIMESTAMP

follows table (Cassandra — partition by follower_id):
  follower_id  BIGINT  (partition key)
  followee_id  BIGINT  (clustering key)
  created_at   TIMESTAMP

feed cache (Redis — per user):
  Key:   feed:<user_id>
  Type:  List (LPUSH new tweets, LTRIM to last 800)
  Value: tweet_id (fetch tweet details separately from tweet cache)
```

### Timeline read path
```
1. LRANGE feed:42 0 19  → list of tweet_ids (from Redis, O(1))
2. MGET tweet:id1 tweet:id2 ...  → tweet details (Redis cache, O(1) each)
3. Fetch celebrity tweets not in pre-computed feed
4. Merge sort by timestamp → return page 1

Why store tweet_ids not full tweets?
  Tweet can be edited/deleted → only one place to update
  feed list is just a pointer, tweet cache has the content
```

---

## 4. Design a Chat System (WhatsApp)

### Clarifying questions
```
1. 1-on-1 only or group chat too? Group size limit?
2. Message delivery guarantee? At-least-once? Exactly-once?
3. Online presence (last seen, typing indicators)?
4. Message history — how long retained?
5. Read receipts (delivered ✓, read ✓✓)?
6. Media (images, video)?
```

### Requirements decided
- 1-on-1 and group chat (max 500 members)
- Message delivery: at-least-once, dedup on client
- Online/offline presence
- Message history: 5 years
- Read receipts
- Push notifications when offline

### Non-functional requirements
```
500M DAU, 100B messages/day
Message delivery: p99 < 500ms
History reads: p99 < 1s
```

### Back-of-envelope
```
Messages: 100B / 86400 ≈ 1.16M messages/sec
Storage: 100B × 100 bytes = 10 TB/day
         10 TB × 365 × 5 years = 18 PB (compressed ~6 PB)
```

### The core problem: real-time delivery

**WebSocket vs polling:**
```
Short polling:  client asks "any new messages?" every N seconds
                Wasteful — 90% of requests return nothing

Long polling:   client hangs open HTTP request, server responds when message arrives
                One connection per user, works through HTTP/1.1

WebSocket:      persistent bidirectional TCP connection
                Best for chat — low latency, full-duplex
                Problem: stateful — user is connected to specific server
```

### High-level design
```
[User A] ──WebSocket──→ [Chat Server 1]
[User B] ──WebSocket──→ [Chat Server 2]

A sends to B:
  1. A → Chat Server 1 (via WebSocket)
  2. Chat Server 1 → looks up which server B is connected to
     (Redis: HSET user:conn userId → serverId)
  3. Server 1 → Server 2 (via message broker / internal API)
  4. Server 2 → B (via WebSocket)
  5. Both servers persist message to Cassandra (async)

B is offline:
  1. Message persisted to DB
  2. Push notification service → APNs (iOS) / FCM (Android)
  3. B comes online → fetches undelivered messages from DB
```

### Data model
```
messages table (Cassandra):
  Primary key: (channel_id, message_id)  ← partition by channel, cluster by time
  channel_id   UUID     (conversation or group ID)
  message_id   BIGINT   (Snowflake — time-ordered)
  sender_id    UUID
  content      TEXT
  type         TEXT     (text/image/video)
  created_at   TIMESTAMP

-- Read last 20 messages:
SELECT * FROM messages WHERE channel_id = ? ORDER BY message_id DESC LIMIT 20

Why Cassandra?
  Write-heavy (1M+/sec), time-series access pattern, wide rows per channel
  Cassandra is optimized for this: partition = channel, rows = messages in order
```

### Message delivery guarantees
```
At-least-once delivery:
  1. Client sends message → server ACKs
  2. If no ACK within 5s → client retries with same message_id
  3. Server checks: already stored this message_id? → skip duplicate, re-ACK
  4. Client-side dedup: if message_id already in local DB → discard

Read receipt flow:
  1. B's client renders message → sends DELIVERED receipt
  2. B reads message → sends READ receipt
  3. Receipts flow back through WebSocket to A's client
  4. A's UI updates ✓ → ✓✓
```

### Presence system
```
Online/offline:
  WebSocket connect → SET presence:userId "online" EX 30
  Heartbeat every 25s → EXPIRE presence:userId 30  (sliding expiry)
  WebSocket disconnect → DEL presence:userId

Last seen:
  On disconnect → SET lastseen:userId <timestamp>

Typing indicator:
  Ephemeral — send over WebSocket only, never persist
  Timeout: stop showing "typing..." after 5s of no updates
```

---

## 5. Design a Distributed Key-Value Store (DynamoDB / Cassandra style)

### Clarifying questions
```
1. What operations? GET, PUT, DELETE only?
2. Consistency requirement? Strong or eventual?
3. What's the expected key/value size? (affects storage layout)
4. Need range queries or just point lookups?
5. Single datacenter or multi-region?
```

### Requirements decided
- GET(key), PUT(key, value), DELETE(key)
- Eventual consistency (tunable: quorum reads/writes for stronger)
- Values up to 1MB
- Horizontal scalability — add nodes as data grows
- Fault tolerant — survive N node failures

### Core design decisions

**1. Data partitioning — Consistent Hashing:**
```
Naive: hash(key) % N  → adding/removing nodes reshuffles ALL keys
Consistent hashing: keys and nodes on a ring → add node = only neighbors affected

Ring with virtual nodes (vnodes):
  Each physical node gets 150 virtual nodes on the ring
  Why: more even distribution, faster rebalancing

  hash("user:42") → position 347 on ring
  → find next clockwise node → that's the primary replica

  Node A owns: positions 0–100, 250–350, 600–700
  Node B owns: positions 100–250, 350–500
  (virtual nodes give each server multiple ranges)
```

**2. Replication:**
```
Replication factor N=3:
  Primary: first node clockwise from key's hash position
  Replica 1: next node clockwise
  Replica 2: next node clockwise after that

Write path (W=2, quorum write):
  Client → Coordinator node
  Coordinator writes to 3 replicas simultaneously
  Wait for 2 ACKs → return success to client
  3rd replica ACKs asynchronously (hinted handoff if down)

Read path (R=2, quorum read):
  Coordinator reads from 3 replicas
  Wait for 2 responses → return latest version (by timestamp/vector clock)

Consistency rule: R + W > N ensures overlap
  N=3, W=2, R=2: R+W=4 > 3 → strong consistency
  N=3, W=1, R=1: R+W=2 < 3 → eventual consistency (faster, lower durability)
```

**3. Conflict resolution:**
```
Two writes to the same key on different replicas (network partition):
  Who wins?

Option A: Last Write Wins (LWW) — use timestamp
  Simple, but clock skew can cause lost writes
  DynamoDB uses this with vector clocks as tiebreaker

Option B: Vector Clocks — track causality
  Each update carries a version vector [NodeA:3, NodeB:2]
  If versions are concurrent (neither dominates) → conflict
  Return both to client → client resolves (like DynamoDB)

Option C: CRDTs (conflict-free replicated data types)
  Data structure designed so all merge operations converge
  e.g., counters, sets → merge = union or max
```

**4. Storage engine — LSM Tree:**
```
Write path (why LSM for KV stores):
  1. Write to WAL (append-only log, crash recovery)
  2. Write to MemTable (in-memory sorted skiplist)
  3. MemTable full (128MB) → flush to immutable SSTable on disk
  4. Background compaction: merge SSTables, remove tombstones

Read path:
  1. Check MemTable (in memory, O(log n))
  2. Check each SSTable newest-first (check Bloom filter first → skip if not present)
  3. Merge results → return latest version

Why not B-tree?
  B-tree writes: random I/O (update in place)
  LSM writes: sequential I/O (append to log + flush sorted file)
  At 100K+ writes/sec: sequential I/O is 10-100× faster than random
```

**High-level design:**
```
Client → [Coordinator Node]
              ↓        ↓         ↓
          [Node A]  [Node B]  [Node C]  (consistent hash ring)
              ↓
       [WAL] → [MemTable] → [SSTable 1] [SSTable 2] ...
                                  ↓
                            [Compaction]
```

---

## 6. Design Netflix / Video Streaming

### Clarifying questions
```
1. Focus on upload pipeline or playback or both?
2. What video qualities to support? (480p, 720p, 1080p, 4K)
3. Global audience or single region?
4. Live streaming or on-demand only?
5. How do we handle popular content (same video = millions of viewers)?
```

### Requirements decided
- Upload: creator uploads raw video → processed into multiple qualities
- Playback: user watches video with adaptive bitrate
- Global scale, CDN-backed
- On-demand only

### Non-functional requirements
```
5M videos uploaded/day, avg 1 GB raw = 5 PB upload/day
500M viewers, avg 1 hr/day watch time
Streaming: 500M × 1hr × 5 Mbps (avg bitrate) = 2.5 exabytes/day bandwidth
Playback start: < 2 seconds
Buffering ratio: < 0.1%
```

### Upload pipeline
```
Creator → Upload Service → Raw Video (S3)
                                ↓
                        Kafka (upload event)
                                ↓
                    [Transcoding Workers] (beefy EC2/K8s)
                    ├── Split into chunks (2s segments)
                    ├── Transcode each chunk: 360p / 480p / 720p / 1080p / 4K
                    ├── Generate thumbnail at key frames
                    └── Output: HLS segments (.ts files) + manifest (.m3u8)
                                ↓
                            CDN Origin (S3)
                                ↓
                        CDN Edge Nodes (CloudFront / Akamai)
```

**Why split into 2-second chunks?**
```
Adaptive bitrate streaming (ABR):
  Player downloads 2s chunk at 1080p
  Measures download speed: if slow → next chunk at 720p
  If fast → step up to 4K
  Chunking enables seamless quality switches mid-playback

  .m3u8 manifest file:
    #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
    /video/abc/1080p/segment_001.ts
    #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
    /video/abc/720p/segment_001.ts
```

### Playback path
```
1. Client requests video page → metadata from API (title, description, manifest URL)
2. Client fetches manifest (.m3u8) → CDN edge (closest PoP)
3. Client selects quality based on bandwidth
4. Client prefetches next 3 chunks → smooth playback
5. Every 2s: reassess bandwidth → adapt quality up/down

CDN cache hit rate target: 95%+
  Popular videos: cached at every edge node
  Long-tail videos: cached at regional CDN, served from S3 on miss
```

### Why CDN is non-negotiable
```
500M users × 5 Mbps = 2.5 Tbps sustained bandwidth
A single datacenter cannot serve this — physically impossible

CDN distributes load to 200+ edge nodes worldwide:
  User in Tokyo → served from Tokyo CDN, not AWS us-east-1
  Latency: 5ms (CDN edge) vs 200ms (cross-Pacific origin)

Netflix Open Connect:
  Netflix's own CDN — boxes deployed inside ISPs
  Large ISP gets a server with popular content pre-loaded
  User's video never leaves the ISP's network
```

---

## 7. Design a Notification System

### Clarifying questions
```
1. What channels? Push (mobile), email, SMS, in-app?
2. What triggers notifications? (order placed, new follower, price drop)
3. Guaranteed delivery? What if device is offline?
4. Rate limiting? (don't spam users)
5. User preferences? (some users opt out of email but keep push)
```

### Requirements decided
- Channels: push (iOS + Android), email, SMS
- Triggered by system events
- At-least-once delivery
- User preferences (opt-out per channel)
- Rate limiting per user

### High-level design
```
[Event Sources]  → Kafka → [Notification Service]
  - Order Service                    ↓
  - Social Service           [Preference Check]
  - Promo Service            (Redis: user prefs)
                                     ↓
                            [Rate Limiter]
                            (Redis: user:notif count)
                                     ↓
                    ┌────────────────┼────────────────┐
                    ↓                ↓                 ↓
              [Push Worker]   [Email Worker]    [SMS Worker]
                    ↓                ↓                 ↓
                APNs/FCM          SendGrid          Twilio
```

### Deep dive: push notification delivery
```
iOS (APNs):
  1. App registers with Apple → gets device token
  2. Token stored: device_tokens table (user_id → [token1, token2...])
  3. Server calls APNs HTTP/2 API with token + payload
  4. APNs delivers to device (queues if device offline, up to 30 days)
  5. APNs returns: success OR InvalidToken (remove stale token from DB)

Android (FCM): same flow, different endpoint

Payload size limit: 4KB (iOS), 4KB (FCM)
  For rich notifications: store content in DB, send just the ID
  App fetches full content on receipt

Failed delivery:
  APNs/FCM returns error → retry with exponential backoff
  After 3 failures → mark device token as invalid
  Dead letter queue → manual review / escalation
```

### User preference + rate limiting
```
Preference check (Redis Hash):
  HGET userprefs:42 push_enabled    → "1"
  HGET userprefs:42 email_enabled   → "0"  (user opted out)
  HGET userprefs:42 quiet_hours     → "22:00-08:00"

Rate limiting (sliding window):
  Max 10 push notifications/user/day
  INCR notif:push:42:2024-01-01  → current count
  EXPIRE notif:push:42:2024-01-01 86400

Priority queues:
  High priority (OTP, security alerts): bypass rate limiting
  Normal (social, recommendations): subject to limits
  Low (promotions): rate limited + quiet hours respected
```

---

## 8. Design Google Search Typeahead / Autocomplete

### Clarifying questions
```
1. How fast must suggestions appear? (target: < 100ms end-to-end)
2. Personalized (based on user history) or global trending?
3. How many suggestions shown? (5-10 typically)
4. Handle typos / fuzzy matching?
5. How often does the suggestion model update? Real-time or daily?
```

### Requirements decided
- Return top 5 suggestions for a given prefix
- Global trending (not personalized for now)
- < 100ms response including network
- Updated every hour (not real-time)

### Back-of-envelope
```
5B searches/day → 50B prefix queries (avg 10 chars × 1 query per char typed)
50B / 86400 ≈ 580,000 prefix queries/sec

Data size:
  10M unique popular search terms
  Each stored with frequency + metadata
  Trie nodes: ~10M terms × avg 10 chars = 100M nodes × 20 bytes = 2 GB
```

### Data structure: Trie + Top-K per node
```
Naive trie lookup: traverse to prefix node → DFS to find all completions → sort by freq
Problem: "sea" might have millions of completions → too slow

Optimization: store top-K results AT EACH NODE
  Node "s"   → [spotify, shopping, samsung, snapchat, stackoverflow]
  Node "se"  → [search, seahawks, sears, seattle, setup]
  Node "sea" → [search, seahawks, sears, seattle, seal]

Lookup: traverse to "sea" node → return pre-computed top-5
O(prefix length) lookup → O(1) effectively

Space tradeoff: each node stores K results = K × (avg term length) bytes extra
  100M nodes × 5 results × 20 bytes = 10 GB  → worth it for speed
```

### System design
```
[Search Logs] → Kafka → [Frequency Aggregator]
                              ↓ (hourly batch)
                      [Trie Builder Service]
                              ↓
                    New Trie (built in shadow)
                              ↓
                    Blue-Green swap to serving trie
                              ↓
                       [Trie Servers] (in-memory, read-only)
                       Consistent Hashing by prefix → which server
                              ↑
                       [Typeahead API] (< 100ms)
                              ↑
                        Client (debounced 50ms)
```

**Why not Redis sorted sets?**
```
ZADD prefix:sea <score> "search"
ZREVRANGE prefix:sea 0 4  → top 5

Works fine for small prefix sets but:
  Need one key per prefix
  "sea" → 3 chars, millions of 3-char prefixes
  "se" → 2 chars, tens of thousands of keys
  Total Redis memory: manageable but ops complexity high

Trie in memory on dedicated servers = simpler, faster, single lookup
```

---

## 9. Design a Distributed Cache (EVCache / Redis Cluster)

### Clarifying questions
```
1. Read-heavy or write-heavy? Typical cache = read-heavy (99:1)
2. Max item size? Max cache size?
3. Eviction policy? (LRU, LFU, TTL-based)
4. Consistency requirements? (cache stampede OK? stale reads OK?)
5. Multi-region? (latency vs consistency trade-off)
```

### Requirements decided
- Cache for DB query results / computed objects
- 99% read, 1% write
- LRU eviction, TTL per entry
- Eventual consistency (stale data for up to 1 minute OK)
- Multi-region (read local, write all regions)

### Core problems to address

**1. Cache eviction policies:**
```
LRU (Least Recently Used):
  Evict the entry not accessed for longest time
  Implementation: HashMap + doubly-linked list (O(1) get + put)
  Good for: temporal locality (recently accessed = likely accessed again)

LFU (Least Frequently Used):
  Evict the entry with lowest access count
  Good for: stable hot items (popular content that's always popular)
  Problem: new items start with count=1 → evicted before they get popular
  Fix: age decay the counts

TTL-based:
  Every entry has an expiry, evicted when expired
  Layered with LRU: LRU for capacity eviction, TTL for staleness
```

**2. Cache stampede (thundering herd):**
```
Scenario:
  Popular item expires at T=0
  1000 concurrent requests arrive at T=0
  All see cache miss → all query DB simultaneously → DB overwhelmed

Solutions:
  A) Probabilistic early expiry:
     At TTL - delta, with probability P: refresh in background
     Avoids hard expiry cliff — cache warms up gradually

  B) Mutex / distributed lock:
     First thread to see miss acquires lock, fetches from DB, populates cache
     Other threads: wait or return stale value
     SETNX lock:key 1 EX 10 → only one thread fetches

  C) Background refresh:
     Async job refreshes cache before expiry
     Serve stale while refreshing (best UX)
     Needs explicit TTL tracking, not pure LRU

  D) Jitter on TTL:
     Instead of TTL=3600, use TTL=3600 + rand(0,300)
     Staggers expiry so not all keys expire simultaneously
```

**3. Consistent hashing for cache nodes:**
```
4 cache nodes, user adds node 5:
  Without consistent hashing: rehash all keys → cache miss flood → DB overwhelmed
  With consistent hashing: only 20% of keys move → 80% still hit cache

Virtual nodes (150 per physical node):
  Even distribution even with heterogeneous node sizes
  Add node: gradually takes ownership of ranges
```

**4. Multi-region write strategy:**
```
Write: update DB → invalidate cache in ALL regions
Read: read from local region cache → local DB on miss

Why invalidate (not update)?
  Update: what if region A update arrives after region B update? → stale data wins
  Invalidate: next read re-fetches from DB → always consistent

  "Delete on write" pattern:
    1. Write to DB (primary)
    2. Send invalidation message via Kafka to all regions
    3. Each region deletes the key from local cache
    4. Next read in any region → cache miss → fetches from DB
```

---

## 10. Design Uber / Ride Sharing

### Clarifying questions
```
1. Focus on: rider requesting ride, driver matching, or both?
2. Real-time location updates — how frequent? (every 4-5 seconds)
3. How to match: nearest driver or other factors (rating, car type)?
4. ETA calculation needed?
5. Surge pricing?
```

### Requirements decided
- Rider requests ride → matched to nearest available driver
- Driver location updates every 5 seconds
- Match within 30 seconds
- ETA and price estimate before confirm

### The core problem: real-time geo-indexing

**Geospatial data at scale:**
```
500K active drivers, each updating location every 5 seconds
= 100K location updates/second

Need to answer: "Find all available drivers within 5km of point X"
Naively: scan all 500K drivers → compute distance → O(n) per query
At 100K queries/sec with 500K drivers = 50B comparisons/sec → impossible

Solution: Geohash
  Divide world into grid cells by recursive bisection
  Each cell has a string ID (prefix = parent cell)

  World → "9" (1 of 32 quadrants)
  "9" → "9q" (sub-quadrant)
  "9q" → "9q8" → "9q8j" → "9q8jy" (higher precision = smaller area)

  "9q8jy" covers ~1.2km² — all points in this cell have same prefix

  Driver at (37.77, -122.41) → geohash "9q8yy" (5 chars)

  Find nearby drivers:
    1. Compute geohash of rider's location → "9q8yy"
    2. Fetch all drivers in "9q8yy" AND 8 neighboring cells
    3. Return nearest K by actual distance
    4. Filter available only

Redis GEOADD / GEORADIUS (wraps geohash internally):
  GEOADD drivers:available <lng> <lat> "driver:42"
  GEORADIUS drivers:available <lng> <lat> 5 km COUNT 10 ASC
```

### Driver location update pipeline
```
Driver app → [Location Service]
                    ↓
             Kafka (location events, keyed by driver_id)
                    ↓
         [Location Processor] (Flink / Spark Streaming)
         - Update Redis GEOADD (current position)
         - Store history to Cassandra (for route replay)
         - Detect anomalies (driver not moving?)
                    ↓
              Redis Geo Index (queryable by riders)
```

### Matching algorithm
```
Rider requests ride at location L:

1. GEORADIUS drivers:available L 5km COUNT 20 ASC  → nearest 20 drivers
2. For each driver:
   - Filter: is driver actually available? (race condition check)
   - Score: distance + driver rating + estimated pickup time
3. Send ride request to top 3 drivers simultaneously
4. First to accept → matched (others get cancellation)
5. If no response in 15s → expand radius, try next batch

Distributed state:
  "driver:42 is available" → Redis key with TTL
  Driver goes offline → key expires → removed from pool automatically
  SETNX driver:42:trip <rider_id> EX 3600  → atomic claim
```

---

## Common Follow-up Questions (for any design)

### "How do you handle failures?"
```
Framework: detect → isolate → mitigate → recover

Detect:    health checks, metrics, alerting (p99 latency spike, error rate)
Isolate:   circuit breaker (stop calling failing service)
           bulkhead (separate thread pools per dependency)
Mitigate:  serve stale cache, degrade gracefully (show less data)
           retry with exponential backoff + jitter
Recover:   auto-restart (K8s), leader re-election, catch-up replication
```

### "How do you scale this 10×?"
```
Reads:    add cache layer, add read replicas, CDN for static content
Writes:   shard the DB, async writes via queue, batch writes
Compute:  horizontal scaling behind load balancer, stateless services
State:    move state to Redis/DB, keep services stateless
Hotspots: consistent hashing with virtual nodes, local caching for hot keys
```

### "How do you monitor this?"
```
The four golden signals:
  Latency:      p50/p99/p999 per endpoint
  Traffic:      requests/sec, messages/sec
  Errors:       error rate, error types
  Saturation:   CPU%, memory%, queue depth, DB connection pool usage

Key alerts:
  p99 latency > SLO threshold
  Error rate > 0.1%
  Queue depth growing (consumer falling behind)
  Cache hit rate drops (stampede or eviction spike)
```

### "What would you do differently if starting over?"
```
Good answer structure:
  "Given what I know now, I'd make three changes:
   1. [Specific architectural decision] — because [reason learned]
   2. [Technology choice] — because [trade-off I'd accept differently]
   3. [Operational concern] — observability/testing I'd build in from day 1"
```

---

## Interview Time Budget (45 min)

```
00:00 – 05:00  Clarify requirements, confirm scale
05:00 – 08:00  Back-of-envelope estimates (say numbers out loud)
08:00 – 18:00  High-level design (boxes + arrows, name the hard problems)
18:00 – 38:00  Deep dive into 2-3 hard components (interviewer guides this)
38:00 – 43:00  Address failure scenarios
43:00 – 45:00  Your questions to them
```
