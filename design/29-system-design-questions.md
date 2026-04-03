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

---

## 11. Design Airline Check-in System

### Clarifying questions
```
1. Web check-in only or airport kiosk too?
2. Can a user change their seat after check-in?
3. How far in advance does check-in open? (typically 24-48hrs before departure)
4. Group bookings — must all seats be adjacent?
5. What happens on overbooking?
```

### Requirements decided
- Authenticate via PNR + last name
- Show seat map with available/taken seats
- Select a seat — must be atomic (no double-booking)
- Check-in window: 48hrs to 1hr before departure

### Back-of-envelope
```
5M flights/day globally × 200 seats = 1B seat-selections possible/day
Peak: all check-in windows open simultaneously
  5M flights × 0.1% opening at same minute = 5K flights × 200 seats = 1M concurrent users
  Each user does 1-2 seat selections = ~2M writes/min = 33K writes/sec at peak
```

### Core problem: preventing double-booking (race condition)
```
Two users see seat 14A as available simultaneously:
  User A: SELECT seat WHERE id=14A AND status='available' → available
  User B: SELECT seat WHERE id=14A AND status='available' → available
  User A: UPDATE seat SET status='taken', pnr='ABC123' → success
  User B: UPDATE seat SET status='taken', pnr='XYZ456' → ALSO success ← double booking!

Fix 1: Optimistic locking (best for low contention)
  UPDATE seats SET status='taken', pnr=?, version=version+1
  WHERE id=14A AND status='available' AND version=?
  → affected rows = 0 means someone else took it → show error

Fix 2: Distributed lock (Redis SETNX) — for high contention seats (window seats)
  SETNX seat:lock:14A <pnr> EX 10  → only one user can proceed
  If lock fails → "seat just taken, pick another"

Fix 3: SELECT FOR UPDATE (PostgreSQL)
  BEGIN;
  SELECT * FROM seats WHERE id=14A FOR UPDATE;  ← row lock
  UPDATE seats SET status='taken' WHERE id=14A;
  COMMIT;
```

### High-level design
```
Client → API Gateway → Check-in Service
                             ↓
                    Redis (seat map cache)   ← read seat availability from here
                    PostgreSQL (source of truth for bookings)
                             ↓
                    Notification Service → email boarding pass

Seat map cache:
  HSET flight:AA123:seats 14A available 14B taken ...
  Refresh from DB every 30s + invalidate on write
  Read: O(1) HGETALL → render full seat map without DB hit
```

### Data model
```sql
seats (PostgreSQL):
  flight_id   VARCHAR
  seat_id     VARCHAR       -- "14A"
  status      ENUM(available, taken, blocked)
  pnr         VARCHAR NULL  -- who took it
  version     INT           -- optimistic lock
  PRIMARY KEY (flight_id, seat_id)

check_ins:
  pnr          VARCHAR PRIMARY KEY
  flight_id    VARCHAR
  seat_id      VARCHAR
  passenger_id VARCHAR
  checked_in_at TIMESTAMP
  boarding_pass_url VARCHAR
```

---

## 12. Design a Blogging Platform (Medium)

### Clarifying questions
```
1. Rich text editor or markdown?
2. Drafts? Publishing workflow?
3. Do readers need a feed/home page? (posts from authors they follow)
4. Analytics for authors (views, read time)?
5. Comments? Claps/likes?
```

### Requirements decided
- Create, edit, publish posts (markdown)
- Author profile + publication page
- Reader feed: posts from followed authors
- Tags/topics for discovery
- Read count, estimated read time

### Back-of-envelope
```
10M authors, 100M readers
50K posts/day = 0.6 posts/sec (write-light)
5M reads/day = 58 reads/sec (read-heavy, 100:1 ratio)

Storage per post: 10KB avg text + 500KB images = ~510KB
50K posts × 510KB = 25GB/day
```

### High-level design
```
[Write path]
Author → Editor → Post Service → PostgreSQL (post + metadata)
                              → S3 (images)
                              → Kafka (post.published event)
                                     ↓
                              [Search Indexer → Elasticsearch]
                              [Feed Service → pre-compute follower feeds in Redis]
                              [Tag Counter Service → update tag popularity]

[Read path]
Reader → CDN (cached HTML for published posts)
       → Feed Service → Redis (pre-computed feed) → PostgreSQL fallback
       → Search Service → Elasticsearch (tag/topic search)
```

### Key decisions
```
Post storage: PostgreSQL (structured metadata) + S3 (rendered HTML / images)
  Published post = static HTML → cacheable on CDN → near-zero origin load
  Draft = PostgreSQL only (no CDN)

Slug: human-readable URL = "how-to-design-redis-61dbc3f2"
  Base: title sanitized + short hash (avoids collision)
  Stored in posts table, unique index on slug

Read time estimate: word_count / 200 words/min (shown before reading)

Feed: same fan-out model as Twitter (push for normal authors, pull for popular)
```

---

## 13. Design Counting Impressions at Scale

### Clarifying questions
```
1. Unique visitors or total views? (very different — unique needs dedup)
2. "Last N units of time" — what granularity? (minutes, hours, days)
3. Real-time or near-real-time? (1 min delay OK?)
4. Per-ad, per-page, per-user breakdown needed?
5. Accuracy requirement? Approximate (±1%) OK?
```

### Requirements decided
- Count unique visitors in last N minutes/hours (sliding window)
- Per-ad impression counts
- Near-real-time: up to 1 minute lag
- Approximate OK (±1%)

### Back-of-envelope
```
Ad platform: 10B impressions/day = 115K impressions/sec
Each impression event: ad_id + user_id + timestamp + metadata ≈ 100 bytes
Event volume: 115K × 100B = 11.5 MB/sec ingest
```

### Core problem: counting UNIQUE visitors at scale
```
Naive: store every user_id in a set per ad → count set size
  10B impressions × user_id(8B) = 80GB/day per ad_id — too expensive

Solution 1: HyperLogLog (±1% error, fixed 12KB memory per counter)
  Redis: PFADD impressions:ad123:2024-01-01-12:00 <user_id>
         PFCOUNT impressions:ad123:2024-01-01-12:00  → ~unique count

Solution 2: Bloom filter (membership check — "did this user already see this ad?")
  Before counting: BLOOM EXISTS ad123:hour:00 <user_id>
  Already seen → skip (dedup), not seen → PFADD + BLOOM ADD

Sliding window "last N minutes":
  Bucket per minute: impressions:ad123:2024-01-01-12:37
  PFMERGE impressions:ad123:window impressions:ad123:12:37 ...12:38 ...12:39
  PFCOUNT impressions:ad123:window
  → expire minute buckets after 2 hours (sliding window only looks back 60 min)
```

### High-level design
```
Client/Ad Server → Kafka (impression events, partitioned by ad_id)
                        ↓
              [Flink Stream Processor]
              - dedup within window (Bloom filter state)
              - PFADD to Redis per minute bucket per ad_id
              - Aggregate to PostgreSQL every 5 min (durable storage)
                        ↓
              [Query API]
              - PFMERGE last N minute buckets → PFCOUNT → return
              - For longer windows (days): query PostgreSQL aggregates
```

---

## 14. Design Remote File Sync (Dropbox)

### Clarifying questions
```
1. Max file size? (4GB per spec)
2. What clients? (desktop, mobile, web)
3. Conflict resolution — two users edit same file simultaneously?
4. Sharing files between users?
5. Bandwidth efficiency — don't re-upload unchanged parts?
```

### Requirements decided
- Upload files up to 4GB, sync across all user's devices
- Resumable uploads/downloads (can pause and resume)
- Delta sync: only upload changed chunks, not whole file
- Conflict resolution: keep both versions, user resolves

### Back-of-envelope
```
50M users, avg 2GB stored = 100 PB total storage
100K uploads/day, avg 500MB = 50 TB/day ingest
Sync events: 50M users × 5 file changes/day / 86400 = 2,900 sync events/sec

Chunk size: 4MB → 4GB file = 1000 chunks
Resumable: track which chunks uploaded → restart from last good chunk
```

### Core problem: efficient sync with chunking
```
File → split into 4MB fixed-size chunks → hash each chunk (SHA-256)

Upload flow:
  1. Client computes chunk hashes → sends list to server
  2. Server: "which hashes I don't already have?" → dedup globally
  3. Client uploads ONLY missing chunks (delta upload)
  4. Server assembles file from chunk references

Dedup benefit:
  If two users upload same file → chunks stored once, referenced twice
  Common chunks (blank page, logo) → stored once globally

Resume:
  Track per upload: {upload_id, chunks_received: [0,1,2,null,4,...]}
  Client resumes from first null chunk
```

### High-level design
```
Client → [Upload Service] → chunk upload → S3 (content-addressed by SHA-256)
                         → metadata → PostgreSQL (files + chunk refs)
                         → sync event → Kafka
                                            ↓
                                   [Sync Service]
                                   → push change notification to all user's devices
                                        via WebSocket / long-poll

[Download/Sync flow]
Device comes online → long-poll Sync Service → "files changed since timestamp T"
                   → download only changed chunks → reconstruct file locally

Metadata DB:
  files:  file_id, user_id, path, version, created_at, chunk_list[]
  chunks: chunk_hash (PK), s3_key, size (content-addressed — shared globally)
```

### Conflict resolution
```
Device A and B both edit file.txt offline, then come online:
  1. First sync wins → written as file.txt
  2. Second sync → conflict detected (server version != base version client started from)
  3. Server stores as "file (conflicted copy 2024-01-01).txt"
  4. Both versions kept → user sees both in folder → resolves manually
  Like Dropbox conflict copies
```

---

## 15. Design Flash Sale

### Clarifying questions
```
1. How many units available? (e.g., 1,000)
2. Can same user buy multiple units? (no — 1 per user)
3. What's the purchase window? (5 min to complete payment)
4. Expected concurrent users at sale start? (could be 10M for 1K items)
5. What happens if user doesn't pay in 5 min? (slot released back)
```

### Requirements decided
- 1,000 units, 1 per user
- 5-minute payment window — if not paid, unit released
- Millions of users competing at sale start
- Fair ordering (first-come, first-served)

### Back-of-envelope
```
10M users hit "buy" in first 5 seconds = 2M req/sec at spike
1,000 items → 999,000 users will fail
Goal: handle 2M req/sec, fail fast, don't hit DB
```

### Core problem: inventory atomicity under extreme load
```
DON'T do this:
  SELECT stock WHERE item_id=X AND stock > 0  → check
  UPDATE stock SET stock=stock-1 WHERE item_id=X  → decrement
  Problem: race condition under concurrency — oversell

DO this — Redis atomic decrement:
  DECR inventory:item123  → returns new value
  If result < 0: INCR inventory:item123 (rollback) → out of stock, reject
  If result >= 0: user got a slot → 5-minute claim window starts

Claim window:
  SET claim:item123:user456 1 EX 300  ← 5 min TTL
  Separate job: every minute scan expired claims → INCR inventory back
```

### High-level design
```
[10M users] → Queue / Virtual Waiting Room
                    ↓ drain at controlled rate
             [Flash Sale Service]
             1. Check Redis: inventory > 0?
             2. DECR inventory:item123  ← atomic
             3. If success: SET claim:item:user EX 300
                            → redirect to payment
             4. If fail: "Sold out" → done
                    ↓
             [Payment Service] (5 min window)
             1. Payment success → ORDER confirmed, claim removed
             2. Timeout → release slot (INCR inventory + DEL claim)

Virtual waiting room:
  Assign users a queue position number (Redis INCR + timestamp)
  Drain positions 1-1000 immediately, rest wait
  Prevents thundering herd on backend
```

---

## 16. Design HashTag Service

### Clarifying questions
```
1. Extract hashtags from photos only, or also from captions?
2. Hashtag page: top 50 photos — ranked by recency or engagement?
3. How fast must hashtag counts update? Real-time or eventual?
4. Search by hashtag — prefix search or exact only?
```

### Requirements decided
- Extract hashtags from photo captions on upload
- Hashtag page: name, total count, top 50 photos (by engagement)
- 5M uploads/hour, avg 8 hashtags = 40M associations/hour = 11K/sec

### Back-of-envelope
```
Writes: 11K hashtag associations/sec
Read:   hashtag page loads — assume 1M hashtag page views/hour = 280/sec
Storage: 40M associations/hour × 24hr × (hashtag:20B + photo_id:8B) ≈ 27GB/day
Unique hashtags: ~10M active
```

### High-level design
```
[Photo Upload] → extract hashtags from caption (regex: #\w+)
              → Kafka (photo.uploaded + hashtag list)
                     ↓
           [Hashtag Worker]
           - For each hashtag:
             INCR hashtag:count:<tag>                          ← running total
             ZADD hashtag:photos:<tag> <engagement_score> <photo_id>  ← sorted set, top-50
             ZREMRANGEBYRANK hashtag:photos:<tag> 0 -51        ← keep only top 50

[Hashtag Page Read]
  GET hashtag:count:#sunset       → total count (Redis, O(1))
  ZREVRANGE hashtag:photos:#sunset 0 49  → top 50 photo_ids (Redis, O(1))
  MGET photo:meta:<id> for each   → photo metadata (Redis or Cassandra)

Persist to DB async:
  Kafka consumer → Cassandra (hashtag_counts table + hashtag_photos table)
  Redis is the fast read layer, Cassandra is durable storage
```

### Data model
```sql
hashtag_photos (Cassandra):
  PRIMARY KEY (hashtag, engagement_score DESC, photo_id)
  → scan top 50 by engagement in one partition

hashtag_counts (PostgreSQL/Cassandra):
  hashtag       TEXT PRIMARY KEY
  photo_count   BIGINT
  last_updated  TIMESTAMP
```

---

## 17. Design Image Service

### Clarifying questions
```
1. Upload only, or also resize/optimize on the fly?
2. Device-aware: different sizes for mobile vs desktop?
3. What image formats? (JPEG, PNG, WebP, AVIF)
4. Analytics: what patterns? (resolution breakdown, format breakdown)
5. Storage: forever or TTL?
```

### Requirements decided
- 5M uploads/hour ≈ 1,400 uploads/sec
- Serve device-optimized images (resize + format based on client)
- Analytics: track image request patterns (resolution, device type)

### Back-of-envelope
```
Upload: 1,400/sec × avg 3MB = 4.2 GB/sec ingest
Storage: 1,400/sec × 3MB × 86400 = 360 TB/day raw
Read: assume 100:1 read:write = 140K reads/sec
CDN offloads 95% → 7K requests/sec hit origin
```

### High-level design
```
[Upload]
Client → Upload Service → S3 (original image, immutable)
                       → Kafka (image.uploaded event)
                              ↓
                    [Processing Worker]
                    - Generate variants: 100px, 400px, 800px, 1200px
                    - Convert to WebP + AVIF (better compression)
                    - Store variants in S3
                    - Update metadata DB (image_id → variant URLs)

[Serve]
Client (mobile, width=400) → CDN → cache hit → serve WebP 400px
                                  → cache miss → Image Server
                                                  → read Accept header + User-Agent
                                                  → select correct variant from S3
                                                  → return + cache at CDN

On-the-fly resize (if variant missing):
  URL pattern: /images/{id}?w=400&fmt=webp
  Image Server: fetch original from S3 → resize → serve + async store new variant

Analytics:
  CDN access logs → Kafka → Flink → aggregate by resolution/format/device → ClickHouse
```

---

## 18. Design Text-based Live Commentary

### Clarifying questions
```
1. Who can write? One commentator per match or multiple?
2. How many readers per match? (1M for a World Cup final)
3. Latency from commentator writes to reader display? (< 2s)
4. Is order guaranteed? (yes — ball-by-ball sequence)
5. Historical commentary — read past balls?
```

### Requirements decided
- One professional commentator writes, millions read
- Ball-by-ball sequential entries, ordered
- Delivery to readers within 2 seconds
- Historical: readers can scroll up to see past balls

### Back-of-envelope
```
1 match: 300 balls/innings × 2 innings = 600 entries/match
10 concurrent major matches = 6,000 writes/day (write volume is tiny)
Readers: 1M concurrent per match × 10 matches = 10M concurrent connections
Each update: ~500 bytes of text
Fan-out: 1 write → 1M readers = delivery challenge, not write challenge
```

### Core problem: fan-out to 1M concurrent readers
```
Options:
  A) Polling: readers poll every 2s → 1M × 0.5 req/s = 500K req/sec → expensive, wasteful
  B) WebSocket: persistent connection per reader → 1M connections per match
     Problem: single server handles ~65K connections → need 15+ servers per match
  C) SSE (Server-Sent Events): unidirectional push, simpler than WebSocket
     Better for read-only streams (commentary is one-way)
  D) Pub/Sub fan-out: Redis pub/sub or Kafka consumer groups

Best: SSE + Redis pub/sub
  Commentator writes → PUBLISH match:icc_final <ball_entry>
  Reader servers subscribe → SUBSCRIBE match:icc_final
  Each reader server holds 50K SSE connections
  1M readers → 20 reader servers, each subscribed to Redis pub/sub channel
```

### High-level design
```
[Commentator] → Commentary Service → PostgreSQL (persistent, ordered by ball_id)
                                  → Redis PUBLISH match:<id> <ball_entry>
                                                ↓
                                  [SSE Servers] (subscribed to Redis pub/sub)
                                  → push to all connected SSE clients
                                                ↓
                                       [1M Readers]

[Reader connects]
  1. Fetch last 20 balls from PostgreSQL → display history
  2. Subscribe to SSE stream → receive future balls in real-time

[Ball entry format]
  { ball_id: 42, over: 7.0, runs: 4, commentary: "FOUR! Cover drive...", timestamp }
  ball_id ensures ordering — client can detect missed deliveries and refetch
```

---

## 19. Design a Load Balancer

### Clarifying questions
```
1. L4 (TCP) or L7 (HTTP-aware) load balancer?
2. What algorithms? (round-robin, least-connections, IP hash, weighted)
3. Health checking: active (probe) or passive (observe errors)?
4. Session persistence (sticky sessions) needed?
5. Scale: millions of concurrent TCP connections?
```

### Requirements decided
- L7 HTTP load balancer (inspect requests for routing decisions)
- Algorithms: round-robin, least-connections, consistent hash by IP
- Active health checks every 10s per backend
- Dynamic add/remove backends at runtime
- Millions of concurrent connections

### Back-of-envelope
```
10M concurrent connections → each connection = ~10KB TCP state = 100GB RAM needed
Solution: non-blocking I/O (single-threaded event loop like Nginx, not thread-per-connection)
100K new connections/sec → connection setup is the bottleneck
```

### Core design: non-blocking connection handling
```
Thread-per-connection (Apache old model):
  Each connection → 1 thread → 1M connections = 1M threads = OOM

Event-loop model (Nginx/HAProxy model):
  1 thread → event loop → handles 100K+ connections via epoll/kqueue
  Connection arrives → register with epoll → process when data ready
  No blocking I/O → no thread context switches

Data structure for backends:
  Round-robin: circular array, atomic index increment
  Least-connections: ConcurrentSkipListMap<(conn_count, server_id), Server>
    → O(log n) to get min, O(log n) to update on connect/disconnect
  Consistent hash: hash ring → sticky routing per client IP
```

### High-level design
```
Client → [LB] → Backend Pool
          ↓
     ┌────────────────────────────────────────┐
     │  Acceptor Thread: accept() new sockets  │
     │  Worker Threads (N=CPU cores):          │
     │    epoll_wait() → process I/O events    │
     │    select backend → proxy request       │
     └────────────────────────────────────────┘
          ↓
     [Health Checker Thread]
       every 10s: GET /health → backend
       if 3 consecutive failures → remove from pool
       if recovering → add back with low weight (slow start)

Dynamic config:
  Control plane API: POST /backends {host, port, weight}
  Worker threads read from atomic reference (no lock on hot path)
  Config reload: build new backend list → atomic swap → old connections drain
```

---

## 20. Newly Unread Message Indicator

### Clarifying questions
```
1. Count = unique senders or total messages?
2. Reset: on any acknowledgment or per-sender?
3. Real-time update? (count should go up as new messages arrive)
4. Multiple devices — sync state across devices?
5. Messages from many conversations or one inbox?
```

### Requirements decided
- Count of unique senders with unread messages
- Resets to 0 when user opens the message list (acknowledges all)
- Real-time: count increments as messages arrive
- Per-user, synced across devices

### Core problem: count unique senders, reset on ack
```
Naive: store Set<sender_id> per user → SCARD = unique count
  User gets 100 messages from 5 senders → Set has 5 entries → count = 5

Redis Set per user:
  New message from sender X:
    SADD unread:user123 senderX    ← add sender to set
    SCARD unread:user123           → count (O(1))

  User opens inbox (ack):
    DEL unread:user123             ← reset entire set

  Real-time push count to client:
    After SADD → publish new count via WebSocket to user's active sessions
```

### High-level design
```
[Message arrives] → Message Service
                  → SADD unread:<recipient_id> <sender_id>  (Redis)
                  → PUBLISH user:<recipient_id>:events {type:unread_count, count: SCARD}
                                                               ↓
                                                    [User's WebSocket server]
                                                    → push to active session
                                                    → client badge updates

[User opens inbox] → POST /messages/ack
                   → DEL unread:<user_id>           (Redis)
                   → PUBLISH count=0 to WebSocket
                   → Mark all messages read in DB   (async)

Multi-device sync:
  WebSocket connection per device → all subscribe to user:<id>:events channel
  Count push reaches all devices simultaneously
```

---

## 21. Design OnePic (Universal Profile Picture URL)

### Clarifying questions
```
1. How do other platforms embed the URL? (img src pointing to your service)
2. When user changes active photo, all platforms must show new photo — how fast?
3. 50K uploads/min — storage budget?
4. 100K:1 read:write — how many reads/sec?
```

### Requirements decided
- User uploads images, designates one as active
- Canonical URL (e.g., onepic.io/u/jay) always serves the active image
- Change active → all embedding platforms show new image (within TTL)
- 50K uploads/min ≈ 830 uploads/sec
- Reads: 830 × 100K = 83M reads/sec → must be CDN-served

### Back-of-envelope
```
83M reads/sec → cannot hit origin, must be CDN
CDN cache hit target: 99% → 830K reads/sec hit origin (still high)
Trick: use short CDN TTL (60s) → changes propagate within 1 minute
Image size: avg 500KB × 830/sec = 415 MB/sec upload
Storage: 830 × 500KB × 86400 = 36 TB/day
```

### Core design: URL indirection
```
Canonical URL: https://onepic.io/u/jay

Naive: redirect to current active image URL
  Problem: CDN caches the redirect → change not picked up until TTL expires
  Problem: redirect adds latency for every image load

Better: serve the image bytes directly from the canonical URL
  /u/jay → look up user jay's active_image_id → serve image from S3
  CDN caches /u/jay response with Cache-Control: max-age=60
  Change active: update DB → CDN cache invalidation API call → propagates in 60s

Even better: signed CDN URL with short TTL
  /u/jay → 302 redirect to CDN URL → CDN URL has 60s TTL
  Old CDN entry expires → next request gets new active image URL
  Platforms that cache img src: they refetch after 60s due to Cache-Control header
```

### High-level design
```
[Upload]
User → Upload Service → S3 (image stored, id=sha256)
                      → PostgreSQL (user_id → image list)
                      User marks image as active → UPDATE users SET active_image_id=X

[Serve]
Client → CDN (cache key: /u/jay, TTL=60s)
       → Origin: User Service
         - SELECT active_image_id FROM users WHERE username='jay'
         - Return 302 → S3/CDN URL of active image
         OR serve image bytes directly (better for CDN caching)

[Change active image]
User changes → UPDATE users.active_image_id = newId
             → CDN purge API: invalidate /u/jay
             → within 60s all CDN nodes serve new image
```

---

## 22. Design Online/Offline Indicator

### Clarifying questions
```
1. How fast must "went offline" be reflected? (spec: within 10s)
2. 5M concurrent online users — do all users see each other's status?
3. Only show to friends/contacts or global?
4. What triggers offline: app close, background, no heartbeat?
```

### Requirements decided
- Update status within 10 seconds of connection/disconnection
- Show online status to contacts only
- 5M concurrent users
- Heartbeat-based: app sends heartbeat every 5s, expire after 10s

### Back-of-envelope
```
5M concurrent users × 1 heartbeat/5s = 1M heartbeat writes/sec
Each heartbeat: SET user:online:<id> 1 EX 10 (Redis, O(1))
1M Redis writes/sec → Redis cluster (100K ops/sec per node → 10 nodes)

Status checks:
  User opens chat list with 200 contacts → 200 EXISTS checks
  5M users × 5 contact list opens/day / 86400 = 289 checks/sec → easy
```

### Core design: heartbeat + TTL
```
Online:
  App WebSocket connected → SET user:online:<id> 1 EX 10
  Background heartbeat every 5s → SET user:online:<id> 1 EX 10 (refresh TTL)

Offline (graceful):
  App closes → WebSocket disconnect → DEL user:online:<id>

Offline (ungraceful — app killed, network drop):
  No heartbeat → key expires after 10s → automatically "offline"
  Within 10s: status still shows online (acceptable per spec)

Check if user is online:
  EXISTS user:online:<id>  → 1 (online) / 0 (offline)
  For 200 contacts: MGET user:online:id1 user:online:id2 ... → batch check

Status change notification (real-time):
  On connect: PUBLISH presence:<user_id> online
  On disconnect: PUBLISH presence:<user_id> offline
  Friends subscribed to presence channels → real-time badge update via WebSocket
```

### High-level design
```
App → WebSocket Server
        ↓ (connect)     SET user:online:<id> 1 EX 10
        ↓ (heartbeat)   SET user:online:<id> 1 EX 10 (every 5s)
        ↓ (disconnect)  DEL user:online:<id>
                              ↓
                    Redis Cluster (presence store)
                              ↑
           [Presence Query Service]
           GET /presence?userIds=1,2,3... → MGET from Redis → return map

[Change notification]
Redis pub/sub channel per user → WebSocket servers subscribe
On status change → push to all WebSocket servers → broadcast to connected friends
```

---

## 23. Design Synchronized Queue Consumers

### Clarifying questions
```
1. Queue is a black box — can't modify it, no built-in locking?
2. Is message ordering important?
3. Throughput priority or exclusive consumption priority? (spec: exclusive)
4. What if a consumer crashes mid-processing?
5. How many consumers? How many messages in flight?
```

### Requirements decided
- External black-box queue — no concurrent consumption protection built in
- Exactly one consumer processes each message
- Consumer crash → message must be retried
- Throughput is secondary to correctness

### Core design: external coordination via distributed lock
```
Problem:
  Queue sends same message to Consumer A and Consumer B simultaneously
  Need: only one actually processes it

Solution: Two-phase claim
  Phase 1 (Claim):
    Consumer receives message from queue
    SETNX lock:msg:<message_id> <consumer_id> EX 60  ← try to claim
    If 0 (someone else has it): discard, poll again
    If 1 (claimed): proceed to process

  Phase 2 (Process + Ack):
    Process the message
    DELETE lock:msg:<message_id>  ← release lock
    ACK to queue (remove message)

  Crash during processing:
    Lock TTL expires after 60s
    Another consumer claims it and reprocesses
    → at-least-once processing (idempotent consumers required)
```

### High-level design
```
[External Queue] → Consumer A → SETNX lock:msg:X consumerA EX 60
                             → SUCCESS → process → DEL lock → ACK queue
                → Consumer B → SETNX lock:msg:X consumerB
                             → FAIL → discard (already claimed)

[Idempotency]
  Consumer must be idempotent — same message processed twice = same result
  Option: processed_messages table (message_id PK) → INSERT IGNORE before processing
  If insert fails: already processed → skip

[Coordinator — Redis]
  lock:msg:<id>  → consumer_id, TTL=processing_timeout
  Heartbeat: consumer renews lock every 10s while processing
    EXPIRE lock:msg:<id> 60  (prevents expiry on slow jobs)
```

---

## 24. Design Realtime Claps (Medium-style)

### Clarifying questions
```
1. Claps are cumulative (clap 50 times) or toggle?
2. Must all readers see the SAME count at the same time?
3. 10K readers on same article — all get live updates?
4. Persist to DB on every clap or batch?
```

### Requirements decided
- Cumulative claps (0 to 50 per reader)
- Eventually consistent across readers (few seconds lag OK)
- 10K concurrent readers on one article all see live count
- Batch writes to DB (don't write every single clap)

### Back-of-envelope
```
100K concurrent users platform-wide, 10K on one article
Clap rate: 10K users × 2 claps/min = 333 claps/sec on one article
Write to DB every clap: 333 writes/sec per article × 1000 articles = 333K writes/sec → too much
Batching: accumulate in Redis, flush to DB every 5s
```

### Core design: Redis counter + pub/sub fan-out
```
Clap event:
  Client clicks → POST /clap {article_id, count}
                → INCRBY article:claps:<id> count   ← Redis (atomic, fast)
                → PUBLISH article:updates:<id> {claps: HGET article:claps:<id>}

Readers receive:
  SSE connection to /articles/<id>/stream
  Server subscribed to Redis SUBSCRIBE article:updates:<id>
  Receives clap events → sends to all 10K SSE clients

Batch flush to DB:
  Every 5 seconds:
    count = GETDEL article:claps:<id>   ← atomic get+reset
    UPDATE articles SET claps = claps + count WHERE id = ?
```

### High-level design
```
[Clapper]   POST /clap → INCRBY redis → PUBLISH redis channel
[Readers]   SSE /stream → subscribe to redis channel → receive live count

[SSE servers]
  1 server handles 50K SSE connections (each ~10KB overhead)
  10K readers on 1 article → can fit on 1 SSE server
  100K platform-wide → 2 SSE servers

[DB flush worker]
  Scheduled every 5s: scan all article:claps:* keys
  Batch UPDATE to PostgreSQL
  Redis is the live counter, PostgreSQL is the durable store
```

---

## 25. Design Realtime Database (Firebase-style)

### Clarifying questions
```
1. What data model? KV only or hierarchical/tree?
2. Subscribe to a key or a table/collection?
3. How many concurrent subscribers per key?
4. Ordering guarantees on change delivery?
5. Offline support (local cache when disconnected)?
```

### Requirements decided
- KV store with change subscription
- Subscribe to a key or a table prefix
- Changes broadcast to all subscribers within 1s
- At-least-once delivery to subscribers

### Core design: change notification via pub/sub
```
Write path:
  Client → PUT /kv/<key> <value>
         → Write to storage (Redis or RocksDB)
         → PUBLISH changes:<key> {key, value, version, timestamp}

Subscribe path:
  Client → WebSocket → SUBSCRIBE changes:<key>
  Server: SUBSCRIBE changes:<key> on Redis pub/sub
  On change: fan-out to all WebSocket clients subscribed to that key

Table subscription (prefix watch):
  SUBSCRIBE changes:users:*  → any key under users/
  Use keyspace notifications or explicit pub/sub channel per table
  Redis keyspace notifications: CONFIG SET notify-keyspace-events KEA
    → receive notifications for every SET/DEL on any key

Versioning:
  Each value has a version (monotonically increasing)
  Client tracks last-seen version
  On reconnect: fetch all changes since last-seen version from WAL/log
```

### High-level design
```
Client A (writer) → KV Service → Redis (primary store)
                              → Append to change log (Kafka / Redis Stream)
                              → PUBLISH changes:<key> {new_value, version}

Client B (reader, subscribed to key) → WebSocket Server
                                      → Redis SUBSCRIBE changes:<key>
                                      → receive update → push to Client B

[Reconnect/catch-up]
  Client reconnects → sends {last_version: 42}
  Server: scan change log since version 42 → send missed changes
  Change log: Kafka (durable, ordered) or Redis Streams

[Scale]
  WebSocket servers: stateless (all subscribe to same Redis pub/sub channel)
  Add server → it subscribes to all active channels → serves its connected clients
  Redis pub/sub fan-out limit: ~100K subscribers per channel → shard if needed
```

---

## 26. Design Recent Searches

### Clarifying questions
```
1. Last N searches per user — what N? (spec: 10)
2. Unique only? (searching "laptop" twice shows only once)
3. Global recent (across devices) or per-device?
4. Latency requirement? (shown on search bar focus — must be instant)
```

### Requirements decided
- Last 10 unique search terms per user
- Global (same across all user's devices)
- p99 < 50ms (shown on search bar tap — feels instant)

### Back-of-envelope
```
500M users, 20 searches/day avg = 10B searches/day = 115K writes/sec
Storage per user: 10 searches × 50 chars = 500 bytes → 250GB total → fits in RAM
Read: 500M users × 5 search bar opens/day / 86400 = 29K reads/sec
```

### Core design: Redis List with dedup
```
Search ingestion (user searches "laptop"):
  1. LREM recent:user123 0 "laptop"   ← remove if exists (dedup)
  2. LPUSH recent:user123 "laptop"    ← push to front (most recent first)
  3. LTRIM recent:user123 0 9         ← keep only last 10

Fetch:
  LRANGE recent:user123 0 9  → ["laptop", "iphone", "nike shoes", ...]

Dedup alternative (preserve order with Sorted Set):
  ZADD recent:user123 <timestamp> "laptop"  ← score = timestamp = recency order
  ZREMRANGEBYRANK recent:user123 0 -11      ← keep 10 most recent by score
  ZREVRANGE recent:user123 0 9             ← get 10 most recent

Expiry: EXPIRE recent:user123 2592000  ← 30 days of inactivity → evict
```

### High-level design
```
[Write path]
User searches → Search Service → async → Kafka (search event)
                                            ↓
                                  [Recent Search Worker]
                                  - LREM + LPUSH + LTRIM into Redis
                                  - Async: write to PostgreSQL (durability)

[Read path]
User taps search bar → GET /recent-searches
  → L1: local Redis cluster (LRANGE → O(k), returns in <1ms)
  → L2: PostgreSQL (if Redis miss — cold start or eviction)

Why write async?
  Search is on the critical path → don't block it for recents update
  Slight eventual consistency OK (recent searches updated in <1s)
```

---

## 27. Design S3 (Blob Storage)

### Clarifying questions
```
1. Object size range? (bytes to TBs)
2. Durability requirement? (S3 is 11 nines = 99.999999999%)
3. Versioning needed?
4. Access control: public/private per object?
5. Multipart upload for large objects?
```

### Requirements decided
- Store arbitrary blobs (bytes to TBs)
- 11 nines durability (survive 3 simultaneous disk failures)
- Multipart upload for large objects
- Per-object ACL (public/private)

### Back-of-envelope
```
10B objects stored
Average object size: 1MB → 10 PB total storage
Upload: 100K uploads/sec × 1MB = 100 GB/sec ingest
Download: 1M downloads/sec × 1MB = 1 TB/sec egress (CDN serves most)

Durability:
  Erasure coding: Reed-Solomon(6,3) — store object in 6+3=9 chunks
  Lose any 3 nodes → reconstruct from remaining 6
  Replication factor equivalent: 9/6 = 1.5× storage overhead vs 3× for plain 3x replication
```

### Core design: separation of metadata and data
```
Two planes:
  1. Metadata plane: bucket/object index, ACL, version info → SQL/distributed DB
  2. Data plane:     actual bytes → distributed chunk servers

Upload flow:
  1. Client → API Server: "I want to store object X in bucket B"
  2. API: check ACL, generate object_id, select chunk servers
  3. Return pre-signed URLs for chunks → client uploads directly to chunk servers
  4. Client uploads all chunks → notifies API "done"
  5. API: records chunk locations in metadata DB → object now queryable

Chunk server:
  Stores raw bytes, content-addressed (SHA-256 of chunk = filename)
  Reports to master: used/free space, health
  Erasure coded: 1 object = 6 data chunks + 3 parity → spread across 9 different servers/racks
```

### High-level design
```
[Upload]
Client → API Server → MetadataDB (PostgreSQL / Spanner)
                   → ChunkServer selection (consistent hash on object_id)
                   → 9 ChunkServers (6 data + 3 parity shards)
                   ← ACK when 6/9 shards confirmed written

[Download]
Client → API Server → lookup MetadataDB → get chunk server locations
                   → fetch 6 data chunks in parallel from chunk servers
                   → reconstruct + stream to client

[Durability]
Chunk servers report health to Master → if node fails:
  Master detects: shard count for affected objects drops below 6
  Recovery: read remaining shards → reconstruct missing shard → replicate to new node
  Target: always maintain 9 shards across 9 different failure domains

[Metadata DB]
  buckets: bucket_id, owner, region, acl, versioning_enabled
  objects: object_id, bucket_id, key, size, etag, acl, version, chunk_refs[]
  chunks:  chunk_id, object_id, shard_index, server_id, checksum
```

---

## 28. Design SQL-backed Message Broker

### Clarifying questions
```
1. Ordering guaranteed? FIFO per topic or global?
2. Multiple consumers — competing consumers (one gets each msg) or fan-out (all get it)?
3. Message expiry? (spec: optional expiry timestamp)
4. At-least-once or exactly-once delivery?
5. Throughput target?
```

### Requirements decided
- Competing consumers (like SQS) — one consumer processes each message
- FIFO per producer (rough ordering)
- Messages ≤ 4KB
- Optional expiry
- At-least-once delivery

### Core design: SELECT FOR UPDATE SKIP LOCKED
```
messages table (PostgreSQL):
  id            BIGSERIAL PRIMARY KEY
  payload       JSONB          ← up to 4KB
  status        ENUM(pending, processing, done)
  visible_at    TIMESTAMP      ← for delay queues
  expires_at    TIMESTAMP NULL ← optional expiry
  created_at    TIMESTAMP
  consumer_id   VARCHAR NULL   ← who's processing it

Producer (enqueue):
  INSERT INTO messages (payload, expires_at) VALUES (?, ?)

Consumer (dequeue — multiple concurrent consumers, no double-delivery):
  BEGIN;
  SELECT id, payload FROM messages
  WHERE status = 'pending'
    AND visible_at <= NOW()
    AND (expires_at IS NULL OR expires_at > NOW())
  ORDER BY id
  LIMIT 1
  FOR UPDATE SKIP LOCKED;    ← key: skip rows locked by other consumers
  ← returns one row, locked. Other consumers skip this row automatically.

  UPDATE messages SET status='processing', consumer_id=? WHERE id=?;
  COMMIT;

  -- process the message --

  UPDATE messages SET status='done' WHERE id=?;

Crash recovery (visibility timeout):
  UPDATE messages SET status='pending', consumer_id=NULL
  WHERE status='processing'
    AND updated_at < NOW() - INTERVAL '5 minutes';  ← cron job
```

### Scaling
```
Single PostgreSQL table:
  ~5K dequeue/sec on one table (row locking overhead)
  For higher throughput: partition by topic_id (separate table per topic)
  Or: multiple queues tables sharded by consistent hash of topic

Partitioning by topic:
  messages_topic_A, messages_topic_B, ... — separate tables
  Each table independently locked → linear scaling by topic count

Archival:
  Completed messages: move to messages_archive after 24h (cron job)
  Keeps active table small → fast index scans
```

---

## 29. Design Distributed Task Scheduler

### Clarifying questions
```
1. One-time tasks or recurring (cron)? (both per spec)
2. How accurate? "Within 10 seconds" per spec
3. Task execution: does scheduler run tasks or just trigger an API?
4. What if a task fails? Retry policy?
5. What if the scheduler itself crashes?
```

### Requirements decided
- One-time and recurring (cron syntax) tasks
- Pick up within 10s of scheduled time
- Trigger a user-provided HTTP callback (not execute code)
- At-least-once delivery of trigger
- HA: scheduler crash must not lose tasks

### Back-of-envelope
```
1M tasks registered, 100K triggers/day = 1.2 triggers/sec (manageable)
Poll window: every 10s → must process all tasks due in next 10s
  100K/day / 86400 × 10s = 11 tasks per 10s window → very low volume
  Scale up: 1B tasks/day = 11K tasks per 10s window — need partitioning
```

### Core design: time-partitioned polling with distributed lock
```
tasks table (PostgreSQL):
  task_id       UUID PRIMARY KEY
  callback_url  VARCHAR
  schedule_type ENUM(once, cron)
  cron_expr     VARCHAR NULL         ← e.g. "0 * * * *"
  next_run_at   TIMESTAMP            ← pre-computed next execution time
  last_run_at   TIMESTAMP NULL
  status        ENUM(pending, running, done, failed)
  retry_count   INT DEFAULT 0

Scheduler worker (runs every 5s):
  BEGIN;
  SELECT task_id, callback_url FROM tasks
  WHERE next_run_at <= NOW() + INTERVAL '10 seconds'
    AND status = 'pending'
  FOR UPDATE SKIP LOCKED   ← multiple workers, no double-trigger
  LIMIT 100;

  UPDATE tasks SET status='running', last_run_at=NOW() WHERE task_id IN (?);
  COMMIT;

  -- For each task: POST callback_url --
  -- On success: compute next_run_at for cron tasks, SET status='pending'/'done'
  -- On failure: retry with backoff up to max_retries

HA: multiple scheduler instances, all poll same DB
  SKIP LOCKED ensures each task claimed by exactly one worker
  Worker crash → task left in 'running' → recovery job resets after timeout
```

### High-level design
```
[Registration]
Client → Task Service → INSERT into tasks (next_run_at computed from cron)
                     → ACK task_id

[Execution]
Scheduler Workers (3-5 instances, active-active):
  every 5s: SELECT FOR UPDATE SKIP LOCKED → batch of due tasks
          → parallel HTTP callbacks → update status
          → for cron: compute next next_run_at → reset to pending

[HA / Recovery]
  Watchdog job (every 60s):
    UPDATE tasks SET status='pending'
    WHERE status='running'
      AND last_run_at < NOW() - INTERVAL '5 minutes'
  ← restores stuck tasks

[Partitioning for scale]
  Partition tasks table by task_id % N
  Each scheduler instance owns M partitions
  Re-partition on instance add/remove (like consistent hashing)
```

---

## 30. Design Text-based Search Engine

### Clarifying questions
```
1. What corpus? (Wikipedia, web pages, internal docs)
2. Boolean queries (AND/OR/NOT) or relevance ranking?
3. Typo tolerance?
4. Real-time indexing or batch?
5. Scale: how many documents? Query volume?
```

### Requirements decided
- Index millions of documents
- Relevance ranking via TF-IDF
- Boolean expression support
- Batch indexing (near-real-time, few-minute lag OK)

### Core algorithm: Inverted Index + TF-IDF
```
Inverted index: for each term → list of (doc_id, positions[])

Corpus:
  Doc 1: "the quick brown fox"
  Doc 2: "the fox jumped over"
  Doc 3: "quick brown rabbits"

Inverted index:
  "quick"  → [(doc1, [2]), (doc3, [1])]
  "fox"    → [(doc1, [4]), (doc2, [2])]
  "brown"  → [(doc1, [3]), (doc3, [2])]

Query "quick AND fox":
  postings("quick") = {doc1, doc3}
  postings("fox")   = {doc1, doc2}
  intersection      = {doc1}            ← AND = intersect sorted lists

TF-IDF score for "quick" in doc1:
  TF  = term frequency in doc = 1/4 (1 occurrence in 4-word doc)
  IDF = log(total_docs / docs_containing_term) = log(3/2) = 0.41
  TF-IDF = 0.25 × 0.41 = 0.10

Rank results by sum of TF-IDF scores for all query terms.
```

### High-level design
```
[Indexing pipeline]
Documents → [Tokenizer: lowercase, remove stop words, stem]
           → [Index Builder: build inverted index segments]
           → [Merger: merge segments, sort postings by doc_id]
           → [Index Store: segments written to disk (like SSTables)]

[Query path]
Query → tokenize → look up postings for each term from index
     → intersect/union postings (for AND/OR)
     → score each result (TF-IDF) → sort → top-K

[Typo tolerance]
  Edit distance (Levenshtein) between query term and index terms
  BK-Tree for efficient fuzzy lookup
  Or: prefix expansion (query "quck" → also try "quick" via phonetic match)

[Distributed]
  Shard index by doc_id range → each shard handles subset of documents
  Query → broadcast to all shards → each shard returns top-K → merge → global top-K
```

---

## 31. Design User Affinity (Follow/Follower)

### Clarifying questions
```
1. Directed graph (follow) or undirected (friend)?
2. Celebrity problem: user with 100M followers?
3. Queries needed: "who do I follow?", "who follows me?", "do I follow X?"
4. Mutual follow (are we friends?) query?
5. Real-time or eventual consistency?
```

### Requirements decided
- Directed follow graph (Twitter-style)
- 50M users, anyone can follow anyone
- Queries: followees (I follow), followers (follow me), is_following(me, X)
- Celebrity accounts: millions of followers

### Back-of-envelope
```
50M users × avg 300 follows = 15B follow edges total
Storage: 15B edges × (follower_id:8B + followee_id:8B) = 240 GB → fits on disk, barely in RAM
Reads: 50M users × 10 feed loads/day / 86400 = 5,800 reads/sec (who do I follow?)
Writes: 50M × 5 follows/day / 86400 = 2,900 follow writes/sec
```

### Data model
```
follows table (Cassandra — write heavy, need both directions):
  Partition 1 (by follower_id): "who does user X follow?"
    follower_id   UUID   (partition key)
    followee_id   UUID   (clustering key)
    created_at    TIMESTAMP

  Partition 2 (by followee_id): "who follows user X?"
    followee_id   UUID   (partition key)
    follower_id   UUID   (clustering key)
    created_at    TIMESTAMP

  TWO tables — denormalized. Write to both on every follow/unfollow.

is_following(me, X):
  SELECT * FROM follows_by_follower WHERE follower_id=me AND followee_id=X
  → O(1) point lookup
```

### Celebrity problem
```
Taylor Swift has 100M followers:
  Storing 100M rows in one partition → 100M × 16B = 1.6GB partition → hot node

Solutions:
  A) Shard celebrity followers: follows:celebrity_id:shard_N (0-9)
     Follow: pick random shard → write to it
     Read followers: UNION all 10 shards → merge

  B) Separate storage for celebrities:
     celebrity_followers table: (celebrity_id, shard_id, follower_id)
     Detected: users with >1M followers → moved to celebrity storage

  C) Lazy loading: don't precompute celebrity follower list
     For feed generation: treat celebrity tweets as pull (not push) — same as Twitter hybrid
```

---

## 32. Design Word Dictionary

### Clarifying questions
```
1. No traditional database — what's allowed? (files, in-memory, CDN)
2. 1TB of data — all definitions or including audio/examples?
3. 5M requests/minute — what's the read pattern? (random or Zipf — popular words more read)
4. Weekly updates (1000 words) — how are they deployed?
5. Typo correction needed?
```

### Requirements decided
- 171K words, 1TB total (avg 6MB per word — includes audio, etymology, examples)
- 5M requests/min ≈ 83K req/sec
- No traditional database
- Weekly batch updates

### Back-of-envelope
```
83K req/sec for 171K words → Zipf distribution
  Top 1K words (0.6%) get ~60% of traffic = 50K req/sec
  Remaining 170K words share 40% = 33K req/sec

Approach: treat each word as a static file → CDN serves it
  CDN hit rate for top words: 99%+ (they're always in cache)
  CDN hit rate overall: ~95% → 4K req/sec to origin

1TB / 171K words = avg 6MB/word → large files, CDN storage is the bottleneck
```

### Core design: static files on CDN, no DB needed
```
Each word = one JSON file (with all definitions, examples, audio URL)
  /words/laptop.json → {word: "laptop", definitions: [...], audio: "...", ...}

Storage: S3 (origin) + CDN (edge)
  CDN cache key = word name → all servers cache same file
  Cache hit: CDN returns directly, no origin hit
  Cache miss: CDN fetches from S3 → cache → serve

In-memory index on origin servers (not a DB):
  Java: HashMap<String, String> wordIndex = load all 171K filenames at startup
  Memory: 171K × 20 chars avg = 3.4MB → negligible
  Lookup: O(1) → resolve word → S3 key → CDN URL

Weekly update flow:
  Batch job adds/updates word JSON files in S3
  CDN invalidation: purge affected word keys from CDN cache
  Origin servers: no restart needed (files are looked up dynamically)

Typo suggestion (within constraints):
  Trie of all 171K words in memory on origin servers (~10MB)
  On 404 (word not found): walk trie → find closest match → suggest correction
  No external DB needed — trie is in-process
```

### High-level design
```
Client → CDN (cache key: /words/<word>.json, TTL=7 days for stable words)
       → Origin server (on cache miss)
           → HashMap lookup → S3 key
           → Fetch from S3 → return + cache at CDN

[Weekly update]
  CI/CD pipeline → upload new/updated JSON files to S3
               → CDN purge for changed words
               → CDN re-warms automatically on next request

[Scale]
  83K req/sec → CDN handles it, origin sees ~4K req/sec
  Origin: 3-5 servers (stateless, S3+HashMap) → easy horizontal scale
  No DB = no connection pool, no schema migration, no backup complexity
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
