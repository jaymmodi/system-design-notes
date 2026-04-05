# Redis Deep Dive

## What is Redis?
**Re**mote **Di**ctionary **S**erver — an in-memory data structure store used as a cache, message broker, session store, rate limiter, and more. Single-threaded (mostly), extremely fast (~100K-1M ops/sec), supports persistence.

---

## Core Data Structures

### 1. Strings
```java
// Simple key-value, atomic operations
jedis.set("user:1:name", "Alice");
jedis.get("user:1:name");                     // "Alice"
jedis.setex("session:abc123", 3600, "userId:1"); // with TTL
jedis.incr("page:home:views");                // atomic increment
jedis.incrby("user:1:score", 50);
jedis.setnx("lock:order:123", "1");           // SET if Not eXists — distributed lock primitive
jedis.getset("counter", "0");                 // get old value, set new one atomically
```

### 2. Hashes (like a nested map)
```java
// Store objects without serialization overhead
jedis.hset("user:1", Map.of(
    "name", "Alice",
    "email", "alice@example.com",
    "age", "30"
));
jedis.hget("user:1", "name");         // "Alice"
jedis.hmget("user:1", "name", "age"); // ["Alice", "30"]
jedis.hgetAll("user:1");              // entire object
jedis.hincrby("user:1", "loginCount", 1); // increment specific field
jedis.hdel("user:1", "tempToken");    // delete one field

// vs String with JSON:
// Hash → modify individual fields without read-modify-write
// JSON String → must fetch whole object, parse, modify, re-serialize
```

### 3. Lists (doubly-linked list)
```java
// Push to head or tail, pop from either end
jedis.rpush("notifications:user1", "You got a like");  // right push
jedis.lpush("notifications:user1", "New follower");    // left push (most recent first)
jedis.lrange("notifications:user1", 0, 9);             // get first 10
jedis.llen("notifications:user1");                     // length
jedis.lpop("notifications:user1");                     // pop from head

// Queue pattern: producer RPUSH, consumer BLPOP (blocking)
jedis.rpush("job:queue", serialize(job));
String task = jedis.blpop(0, "job:queue").get(1); // blocks until item available

// Capped list (keep only last 1000)
jedis.ltrim("activity:feed", 0, 999);
```

### 4. Sets (unordered, unique elements)
```java
// No duplicates, O(1) add/remove/check
jedis.sadd("users:online", "user:1", "user:2", "user:3");
jedis.sismember("users:online", "user:1");  // true
jedis.smembers("users:online");             // all members
jedis.srem("users:online", "user:1");       // remove
jedis.scard("users:online");               // count

// Set operations
jedis.sunion("tags:post:1", "tags:post:2");       // union
jedis.sinter("tags:post:1", "tags:post:2");       // intersection (common tags)
jedis.sdiff("tags:post:1", "tags:post:2");        // difference

// Mutual friends
jedis.sadd("friends:alice", "bob", "charlie", "diana");
jedis.sadd("friends:bob", "alice", "charlie", "eve");
Set<String> mutualFriends = jedis.sinter("friends:alice", "friends:bob"); // {charlie}
```

### 5. Sorted Sets (ZSets) — Most Powerful
```java
// Elements with scores, sorted by score. O(log N) operations.
// Perfect for: leaderboards, priority queues, rate limiting, time-series

// Leaderboard
jedis.zadd("leaderboard:game1", Map.of(
    "player:alice", 9500.0,
    "player:bob", 8200.0,
    "player:charlie", 9800.0
));
jedis.zincrby("leaderboard:game1", 500, "player:alice"); // alice gets 500 points

// Top 10 players (highest score first)
List<Tuple> top10 = jedis.zrevrangeWithScores("leaderboard:game1", 0, 9);
top10.forEach(t -> System.out.println(t.getElement() + ": " + t.getScore()));

// Player's rank (0-indexed)
long rank = jedis.zrevrank("leaderboard:game1", "player:alice"); // 1 (2nd place)

// Players in score range (paginated)
List<Tuple> page = jedis.zrangeByScoreWithScores("leaderboard:game1", 8000, 10000, 0, 20);

// Sliding window rate limiter
String key = "ratelimit:" + userId + ":" + (System.currentTimeMillis() / 60000); // per minute
jedis.zadd(key, System.currentTimeMillis(), UUID.randomUUID().toString());
jedis.expire(key, 120); // keep for 2 minutes
long count = jedis.zcount(key, System.currentTimeMillis() - 60000, System.currentTimeMillis());
if (count > 100) throw new RateLimitException();
```

### 6. HyperLogLog
```java
jedis.pfadd("unique_visitors:2024-01-15", userId);
long unique = jedis.pfcount("unique_visitors:2024-01-15"); // ~0.4% error
// PFMERGE for weekly totals
jedis.pfmerge("weekly_visitors", "uv:2024-01-09", "uv:2024-01-10" /* ... */);
```

### 7. Streams (Redis 5.0+)
```java
// Like Kafka, but simpler — append-only log with consumer groups
// Add event
Map<String, String> fields = Map.of("userId", "123", "action", "purchase");
String id = jedis.xadd("events", StreamEntryID.NEW_ENTRY, fields);

// Consumer group
jedis.xgroupCreate("events", "order-service", StreamEntryID.LAST_ENTRY, true);

// Consume messages
List<Map.Entry<String, List<StreamEntry>>> messages = jedis.xreadGroup(
    "order-service", "consumer-1",
    XReadGroupParams.xReadGroupParams().count(10).block(2000),
    Map.of("events", StreamEntryID.UNRECEIVED_ENTRY)
);
// Process and acknowledge
messages.forEach(e -> e.getValue().forEach(entry -> {
    processEvent(entry.getFields());
    jedis.xack("events", "order-service", entry.getID()); // mark processed
}));
```

---

## Distributed Patterns with Redis

### 1. Distributed Lock (Redlock)

```java
// Simple version (single Redis node)
public class RedisDistributedLock {
    private final JedisPool pool;
    private static final String LOCK_VALUE = "LOCKED";

    public boolean tryLock(String lockKey, int ttlSeconds) {
        try (Jedis jedis = pool.getResource()) {
            // SET key value NX EX ttl — atomic: set only if not exists
            String result = jedis.set(lockKey, LOCK_VALUE, SetParams.setParams().nx().ex(ttlSeconds));
            return "OK".equals(result);
        }
    }

    public void unlock(String lockKey) {
        try (Jedis jedis = pool.getResource()) {
            // Lua script ensures we only delete our own lock (atomic check-and-delete)
            String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                           "return redis.call('del', KEYS[1]) else return 0 end";
            jedis.eval(script, List.of(lockKey), List.of(LOCK_VALUE));
        }
    }
}

// Better: Redisson library handles Redlock properly
RLock lock = redisson.getLock("order:lock:" + orderId);
try {
    if (lock.tryLock(5, 30, TimeUnit.SECONDS)) { // wait 5s, hold 30s
        // critical section — process order
        processOrder(orderId);
    }
} finally {
    lock.unlock();
}
```

### 2. Session Store

```java
@Component
public class RedisSessionStore {
    private final StringRedisTemplate redis;
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    public String createSession(String userId, Map<String, Object> attributes) {
        String sessionId = UUID.randomUUID().toString();
        String key = "session:" + sessionId;

        Map<String, String> data = new HashMap<>();
        data.put("userId", userId);
        attributes.forEach((k, v) -> data.put(k, v.toString()));

        redis.opsForHash().putAll(key, data);
        redis.expire(key, SESSION_TTL);
        return sessionId;
    }

    public Map<Object, Object> getSession(String sessionId) {
        String key = "session:" + sessionId;
        Map<Object, Object> session = redis.opsForHash().entries(key);
        if (!session.isEmpty()) {
            redis.expire(key, SESSION_TTL); // sliding expiration
        }
        return session;
    }
}
```

### 3. Rate Limiter — Sliding Window

```java
public class SlidingWindowRateLimiter {
    private final JedisPool jedisPool;
    private final int maxRequests;
    private final long windowMs;

    public boolean isAllowed(String clientId) {
        String key = "ratelimit:" + clientId;
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;

        try (Jedis jedis = jedisPool.getResource()) {
            // Lua script — all operations atomic
            String script = """
                local key = KEYS[1]
                local now = tonumber(ARGV[1])
                local window = tonumber(ARGV[2])
                local limit = tonumber(ARGV[3])

                -- Remove expired entries
                redis.call('ZREMRANGEBYSCORE', key, '-inf', ARGV[4])

                -- Count remaining requests in window
                local count = redis.call('ZCARD', key)

                if count < limit then
                    -- Add this request
                    redis.call('ZADD', key, now, now .. '-' .. math.random())
                    redis.call('EXPIRE', key, math.ceil(window / 1000))
                    return 1
                else
                    return 0
                end
                """;

            Long result = (Long) jedis.eval(
                script,
                List.of(key),
                List.of(String.valueOf(now), String.valueOf(windowMs),
                        String.valueOf(maxRequests), String.valueOf(windowStart))
            );
            return result == 1;
        }
    }
}
```

### 4. Pub/Sub & Cache Invalidation

```java
// Publisher (when data changes)
@Service
public class ProductService {
    @Autowired private Jedis jedis;

    public void updateProduct(Product product) {
        db.save(product);
        jedis.del("product:" + product.getId()); // invalidate cache
        jedis.publish("product:changes", product.getId()); // notify subscribers
    }
}

// Subscriber (in other services)
@Component
public class ProductCacheInvalidator implements JedisPubSub {
    @Override
    public void onMessage(String channel, String productId) {
        localCache.invalidate("product:" + productId);
        log.info("Invalidated local cache for product {}", productId);
    }
}

// Start subscription in separate thread
jedis.subscribe(new ProductCacheInvalidator(), "product:changes");
```

---

## Persistence Modes

### RDB (Point-in-time Snapshots)
```
# redis.conf
save 900 1      # snapshot if 1 change in 900 seconds
save 300 10     # snapshot if 10 changes in 300 seconds
save 60 10000   # snapshot if 10000 changes in 60 seconds
```
- **Pros**: Fast restart, compact file, good for backups
- **Cons**: Can lose up to minutes of data between snapshots

### AOF (Append Only File)
```
# redis.conf
appendonly yes
appendfsync everysec  # fsync every second (balance safety vs performance)
# appendfsync always  # fsync every write (safest, slowest)
# appendfsync no      # OS decides (fastest, least safe)
```
- **Pros**: Near-zero data loss, log is humanly readable
- **Cons**: Larger files, slower startup, rewrite needed periodically

### Hybrid (RDB + AOF) — Recommended for Production
```
# Uses RDB for base snapshot, AOF for recent changes
aof-use-rdb-preamble yes
```

---

## Redis Cluster

```java
// Data partitioned across multiple masters using consistent hashing
// 16384 hash slots distributed across masters

JedisCluster cluster = new JedisCluster(
    Set.of(
        new HostAndPort("redis-1", 7000),
        new HostAndPort("redis-2", 7001),
        new HostAndPort("redis-3", 7002)
    )
);

// Cluster limitation: multi-key ops require same hash slot
// Force same slot using key tags: {user:1}:profile and {user:1}:settings
// Both hash to "user:1" → same slot → MGET/MSET works

cluster.set("{user:1}:profile", serializeProfile(profile));
cluster.set("{user:1}:settings", serializeSettings(settings));
List<String> values = cluster.mget("{user:1}:profile", "{user:1}:settings"); // OK!
```

---

## Lua Scripts — Atomic Operations

```java
// Lua scripts execute atomically on Redis — no other commands interleave
// Use for: check-and-set, multi-step operations

String incrementIfLess = """
    local current = tonumber(redis.call('GET', KEYS[1]) or '0')
    local max = tonumber(ARGV[1])
    if current < max then
        redis.call('INCR', KEYS[1])
        return current + 1
    else
        return -1
    end
    """;

// Load script and call by SHA (efficient — send once, call by hash)
String sha = jedis.scriptLoad(incrementIfLess);
Long result = (Long) jedis.evalsha(sha, List.of("concurrent:counter"), List.of("100"));
```

---

## Redis vs Memcached

| Feature | Redis | Memcached |
|---------|-------|-----------|
| Data types | 10+ (list, set, zset, hash, stream...) | String only |
| Persistence | RDB + AOF | No |
| Pub/Sub | Yes | No |
| Clustering | Native | Client-side |
| Lua scripting | Yes | No |
| Performance | ~100K-500K ops/sec | ~200K-1M ops/sec |
| Memory efficiency | Slightly less (richer types) | Slightly better |

**Choose Redis** almost always — richer feature set with minimal downside.
**Choose Memcached** only for ultra-simple string caching where memory efficiency is critical.

---

## When to Use Redis
- Session storage
- Caching (all patterns)
- Rate limiting
- Leaderboards (sorted sets)
- Distributed locks
- Real-time analytics (HLL, CMS)
- Job queues (lists, streams)
- Pub/Sub messaging

## When to Avoid Redis
- Primary data store for complex relational data (use PostgreSQL)
- Datasets larger than available RAM × number of nodes
- Need ACID transactions across multiple keys/tables (Redlock is best-effort)
- Complex queries (use a real DB; Redis is not a query engine)

---

## Interview Questions

1. **How does Redis handle expiration?** Two strategies: lazy (check on access) + active (periodically scan for expired keys)
2. **How does Redis remain single-threaded but be so fast?** I/O is event-loop based (like Node.js). Single thread avoids lock contention. Memory operations are fast.
3. **Redis 6+ is multi-threaded — what changed?** I/O threads for reading/writing sockets, but command execution is still single-threaded. Solves the network bandwidth bottleneck.
4. **How would you implement a leaderboard?** ZADD with player score, ZREVRANK for rank, ZREVRANGE for top-K, ZINCRBY for score updates.
5. **What is the N+1 problem with Redis and how do you fix it?** Issuing N separate GET calls in a loop — fix with MGET (multi-get) or pipelining.

---

## Commands Quick Reference

### Keys (universal)

| Command | Example | What it does |
|---------|---------|--------------|
| `DEL key` | `DEL user:1` | Delete key |
| `EXISTS key` | `EXISTS user:1` | 1 if exists, 0 if not |
| `EXPIRE key sec` | `EXPIRE session:x 3600` | Set TTL in seconds |
| `PEXPIRE key ms` | `PEXPIRE lock:x 500` | Set TTL in milliseconds |
| `TTL key` | `TTL session:x` | Remaining TTL (-1 = no expiry, -2 = gone) |
| `PERSIST key` | `PERSIST session:x` | Remove TTL, make permanent |
| `RENAME key newkey` | `RENAME tmp final` | Rename key |
| `TYPE key` | `TYPE user:1` | Returns: string/hash/list/set/zset/stream |
| `SCAN cursor MATCH pattern COUNT n` | `SCAN 0 MATCH user:* COUNT 100` | Iterate keys safely (never use KEYS * in prod) |

### Strings

| Command | Example | What it does |
|---------|---------|--------------|
| `SET key value` | `SET name "jay"` | Set value |
| `SET key value EX sec` | `SET token "abc" EX 3600` | Set with TTL |
| `SET key value NX` | `SET lock:x "1" NX` | Set only if NOT exists (atomic) |
| `SET key value XX` | `SET name "jay" XX` | Set only if EXISTS |
| `SET key value GET` | `SET name "new" GET` | Set and return OLD value (atomic) |
| `GET key` | `GET name` | Get value |
| `GETDEL key` | `GETDEL token` | Get and delete atomically |
| `MSET k1 v1 k2 v2` | `MSET a 1 b 2` | Set multiple keys |
| `MGET k1 k2` | `MGET a b` | Get multiple keys |
| `INCR key` | `INCR counter` | Increment integer by 1 |
| `INCRBY key n` | `INCRBY counter 5` | Increment by n |
| `DECR key` | `DECR counter` | Decrement by 1 |
| `APPEND key value` | `APPEND log "entry"` | Append to string |
| `STRLEN key` | `STRLEN name` | Length of string value |
| `SETNX key value` | `SETNX lock "1"` | Deprecated — use SET NX instead |

### Hashes (object / map)

| Command | Example | What it does |
|---------|---------|--------------|
| `HSET key field value` | `HSET user:1 name "jay"` | Set one field |
| `HSET key f1 v1 f2 v2` | `HSET user:1 name "jay" age 30` | Set multiple fields |
| `HGET key field` | `HGET user:1 name` | Get one field |
| `HMGET key f1 f2` | `HMGET user:1 name age` | Get multiple fields |
| `HGETALL key` | `HGETALL user:1` | Get all fields and values |
| `HDEL key field` | `HDEL user:1 age` | Delete a field |
| `HEXISTS key field` | `HEXISTS user:1 name` | 1 if field exists |
| `HLEN key` | `HLEN user:1` | Number of fields |
| `HKEYS key` | `HKEYS user:1` | All field names |
| `HVALS key` | `HVALS user:1` | All values |
| `HINCRBY key field n` | `HINCRBY user:1 age 1` | Increment integer field |
| `HSETNX key field value` | `HSETNX user:1 name "jay"` | Set field only if not exists |

### Lists (ordered, duplicates allowed)

| Command | Example | What it does |
|---------|---------|--------------|
| `LPUSH key val` | `LPUSH queue "task1"` | Push to HEAD (left) |
| `RPUSH key val` | `RPUSH queue "task1"` | Push to TAIL (right) |
| `LPOP key` | `LPOP queue` | Pop from HEAD |
| `RPOP key` | `RPOP queue` | Pop from TAIL |
| `BLPOP key sec` | `BLPOP queue 5` | Blocking pop from HEAD (waits up to 5s) |
| `BRPOP key sec` | `BRPOP queue 5` | Blocking pop from TAIL |
| `LRANGE key start stop` | `LRANGE queue 0 -1` | Get elements (0,-1 = all) |
| `LLEN key` | `LLEN queue` | Length of list |
| `LINDEX key i` | `LINDEX queue 0` | Get element at index |
| `LSET key i val` | `LSET queue 0 "new"` | Set element at index |
| `LREM key count val` | `LREM queue 1 "task1"` | Remove N occurrences of value |
| `LTRIM key start stop` | `LTRIM log 0 999` | Keep only elements in range |
| `LINSERT key BEFORE\|AFTER pivot val` | `LINSERT q BEFORE "b" "a"` | Insert before/after element |

**Patterns**: LPUSH + RPOP = queue. LPUSH + LPOP = stack. BRPOP = reliable blocking consumer.

### Sets (unordered, unique)

| Command | Example | What it does |
|---------|---------|--------------|
| `SADD key member` | `SADD online "user:1"` | Add member(s) |
| `SREM key member` | `SREM online "user:1"` | Remove member |
| `SISMEMBER key member` | `SISMEMBER online "user:1"` | 1 if member exists |
| `SMISMEMBER key m1 m2` | `SMISMEMBER online "u:1" "u:2"` | Check multiple members |
| `SMEMBERS key` | `SMEMBERS online` | All members (careful at scale) |
| `SCARD key` | `SCARD online` | Count of members |
| `SPOP key` | `SPOP online` | Remove and return random member |
| `SRANDMEMBER key n` | `SRANDMEMBER online 3` | Get n random members (no remove) |
| `SUNION k1 k2` | `SUNION set1 set2` | Union of sets |
| `SINTER k1 k2` | `SINTER set1 set2` | Intersection |
| `SDIFF k1 k2` | `SDIFF set1 set2` | Difference (in k1, not k2) |
| `SUNIONSTORE dest k1 k2` | `SUNIONSTORE result s1 s2` | Store union result |
| `SINTERSTORE dest k1 k2` | `SINTERSTORE result s1 s2` | Store intersection result |

### Sorted Sets / ZSETs (unique members + score)

| Command | Example | What it does |
|---------|---------|--------------|
| `ZADD key score member` | `ZADD leaderboard 1500 "jay"` | Add with score |
| `ZADD key NX score member` | `ZADD lb NX 1500 "jay"` | Add only if not exists |
| `ZADD key XX score member` | `ZADD lb XX 1600 "jay"` | Update only if exists |
| `ZSCORE key member` | `ZSCORE leaderboard "jay"` | Get score |
| `ZINCRBY key n member` | `ZINCRBY leaderboard 100 "jay"` | Increment score |
| `ZRANK key member` | `ZRANK leaderboard "jay"` | Rank (0 = lowest score) |
| `ZREVRANK key member` | `ZREVRANK leaderboard "jay"` | Rank (0 = highest score) |
| `ZRANGE key start stop` | `ZRANGE leaderboard 0 9` | Members by rank (low→high) |
| `ZRANGE key start stop REV` | `ZRANGE leaderboard 0 9 REV` | Members high→low (Redis 6.2+) |
| `ZREVRANGE key start stop` | `ZREVRANGE leaderboard 0 9` | Members high→low (older) |
| `ZRANGEBYSCORE key min max` | `ZRANGEBYSCORE lb 1000 2000` | Members in score range |
| `ZRANGEBYLEX key min max` | `ZRANGEBYLEX names [a [z` | Members in lex range (equal scores) |
| `ZREM key member` | `ZREM leaderboard "jay"` | Remove member |
| `ZREMRANGEBYSCORE key min max` | `ZREMRANGEBYSCORE lb 0 100` | Remove by score range |
| `ZCARD key` | `ZCARD leaderboard` | Count of members |
| `ZCOUNT key min max` | `ZCOUNT leaderboard 1000 2000` | Count in score range |
| `ZPOPMIN key n` | `ZPOPMIN leaderboard 3` | Pop n lowest score members |
| `ZPOPMAX key n` | `ZPOPMAX leaderboard 3` | Pop n highest score members |
| `ZUNIONSTORE dest n k1 k2` | `ZUNIONSTORE out 2 z1 z2` | Store union (sum scores) |
| `ZINTERSTORE dest n k1 k2` | `ZINTERSTORE out 2 z1 z2` | Store intersection |

**Internally**: skiplist + hashtable. O(log n) add/remove/rank, O(1) score lookup.

### Bitmaps

| Command | Example | What it does |
|---------|---------|--------------|
| `SETBIT key pos 0\|1` | `SETBIT visits 42 1` | Set bit at position |
| `GETBIT key pos` | `GETBIT visits 42` | Get bit at position (0 or 1) |
| `BITCOUNT key` | `BITCOUNT visits` | Count of set bits |
| `BITCOUNT key start end` | `BITCOUNT visits 0 3` | Count in byte range |
| `BITPOS key 0\|1` | `BITPOS visits 0` | First position of 0 or 1 |
| `BITOP AND dest k1 k2` | `BITOP AND out bm1 bm2` | Bitwise AND → dest |
| `BITOP OR dest k1 k2` | `BITOP OR out bm1 bm2` | Bitwise OR → dest |
| `BITOP XOR dest k1 k2` | `BITOP XOR out bm1 bm2` | Bitwise XOR → dest |
| `BITOP NOT dest key` | `BITOP NOT out bm1` | Bitwise NOT → dest |

**Use case**: user activity tracking (1 bit per user ID), bloom filter internals.

### HyperLogLog (distinct count)

| Command | Example | What it does |
|---------|---------|--------------|
| `PFADD key element` | `PFADD visitors "user:1"` | Add element |
| `PFADD key e1 e2 e3` | `PFADD visitors "u:1" "u:2"` | Add multiple |
| `PFCOUNT key` | `PFCOUNT visitors` | Estimated distinct count (~0.81% error) |
| `PFCOUNT k1 k2` | `PFCOUNT vis:mon vis:tue` | Distinct count across multiple HLLs |
| `PFMERGE dest k1 k2` | `PFMERGE week mon tue wed` | Merge HLLs → dest |

**Memory**: always ~12KB regardless of 1M or 1B elements.

### Streams

| Command | Example | What it does |
|---------|---------|--------------|
| `XADD key * f v` | `XADD events * type "click"` | Append entry, auto-generate ID |
| `XADD key MAXLEN ~ n * f v` | `XADD events MAXLEN ~ 10000 * ...` | Append + trim to ~n entries |
| `XLEN key` | `XLEN events` | Number of entries |
| `XRANGE key start end` | `XRANGE events - +` | Read entries (- = first, + = last) |
| `XREVRANGE key end start` | `XREVRANGE events + -` | Read in reverse |
| `XREAD COUNT n STREAMS key id` | `XREAD COUNT 10 STREAMS events 0` | Read from ID (0 = beginning) |
| `XREAD BLOCK ms STREAMS key $` | `XREAD BLOCK 5000 STREAMS events $` | Block for new entries ($ = only new) |
| `XGROUP CREATE key grp id` | `XGROUP CREATE events grp1 0` | Create consumer group |
| `XREADGROUP GROUP g c COUNT n STREAMS key >` | `XREADGROUP GROUP g1 c1 COUNT 10 STREAMS events >` | Read undelivered |
| `XACK key group id` | `XACK events grp1 1234-0` | Acknowledge processed message |
| `XPENDING key group - + n` | `XPENDING events grp1 - + 10` | List unacknowledged messages |
| `XCLAIM key group consumer ms id` | `XCLAIM events grp1 c2 3600000 1234-0` | Steal message idle > ms |
| `XDEL key id` | `XDEL events 1234-0` | Delete specific entry |
| `XTRIM key MAXLEN ~ n` | `XTRIM events MAXLEN ~ 5000` | Trim stream to n entries |

### Pub/Sub (fire-and-forget, no persistence)

| Command | Example | What it does |
|---------|---------|--------------|
| `SUBSCRIBE channel` | `SUBSCRIBE alerts` | Subscribe (blocks, receives messages) |
| `PSUBSCRIBE pattern` | `PSUBSCRIBE alert:*` | Subscribe to pattern |
| `PUBLISH channel message` | `PUBLISH alerts "deploy done"` | Publish to channel |
| `UNSUBSCRIBE channel` | `UNSUBSCRIBE alerts` | Unsubscribe |
| `PUBSUB CHANNELS pattern` | `PUBSUB CHANNELS alert:*` | List active channels |
| `PUBSUB NUMSUB channel` | `PUBSUB NUMSUB alerts` | Subscriber count per channel |

**vs Streams**: Pub/Sub has no persistence — if subscriber is offline, message is lost. Use Streams if you need delivery guarantees.

### Transactions

| Command | Example | What it does |
|---------|---------|--------------|
| `MULTI` | `MULTI` | Begin transaction (queue commands) |
| `EXEC` | `EXEC` | Execute all queued commands atomically |
| `DISCARD` | `DISCARD` | Abort transaction, discard queue |
| `WATCH key` | `WATCH balance` | Optimistic lock — EXEC fails if key changed |

```
WATCH balance           # if balance changes before EXEC, abort
MULTI
  DECRBY balance 100
  INCRBY other 100
EXEC                    # returns nil if WATCH key was modified → retry
```

**vs Lua**: MULTI/EXEC batches but doesn't abort on command errors. Lua is truly atomic.

### Server / Ops

| Command | Example | What it does |
|---------|---------|--------------|
| `INFO` | `INFO` | Full server stats |
| `INFO memory` | `INFO memory` | Memory usage breakdown |
| `INFO replication` | `INFO replication` | Master/replica status |
| `DBSIZE` | `DBSIZE` | Number of keys in current DB |
| `FLUSHDB` | `FLUSHDB` | Delete all keys in current DB (careful) |
| `FLUSHALL` | `FLUSHALL` | Delete all keys in all DBs (careful) |
| `OBJECT ENCODING key` | `OBJECT ENCODING user:1` | Internal encoding (listpack, hashtable, etc.) |
| `MEMORY USAGE key` | `MEMORY USAGE user:1` | Bytes used by key |
| `MONITOR` | `MONITOR` | Stream every command received (debug only) |
| `SLOWLOG GET n` | `SLOWLOG GET 10` | Last n slow commands |
| `CONFIG GET param` | `CONFIG GET maxmemory` | Read config |
| `CONFIG SET param val` | `CONFIG SET maxmemory 1gb` | Set config at runtime |
| `SAVE` | `SAVE` | Trigger RDB snapshot (blocking) |
| `BGSAVE` | `BGSAVE` | Trigger RDB snapshot (background) |
| `BGREWRITEAOF` | `BGREWRITEAOF` | Compact AOF file |

### Complexity Quick Reference

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| GET / SET / HGET / HSET | O(1) | |
| HGETALL | O(n fields) | |
| SADD / SREM / SISMEMBER | O(1) | |
| SMEMBERS | O(n) | Avoid on large sets |
| SINTER / SUNION | O(n×m) | n=smallest set, m=all sets |
| ZADD / ZREM / ZSCORE | O(log n) | skiplist |
| ZRANK / ZREVRANK | O(log n) | |
| ZRANGE | O(log n + k) | k = returned elements |
| LPUSH / RPUSH / LPOP / RPOP | O(1) | |
| LRANGE | O(n) | |
| SETBIT / GETBIT | O(1) | |
| BITCOUNT | O(n bytes) | |
| PFADD / PFCOUNT | O(1) | |
| PFMERGE | O(n registers) | always 16384 |
| XADD | O(1) amortized | |
| XRANGE | O(n) | |

### Common Patterns — One Line Each

```
Distributed lock:      SET lock:x "token" NX EX 30
Rate limiter:          INCR rate:ip:1.2.3.4  +  EXPIRE
Session store:         HSET session:<token> uid 42  +  EXPIRE
Leaderboard:           ZADD lb <score> <user>  →  ZREVRANGE lb 0 9
Pub/Sub fan-out:       PUBLISH channel msg  (fire and forget)
Reliable queue:        LPUSH + BRPOP  (blocking consumer)
Dedup (exact):         SADD seen:<id>  → SISMEMBER
Dedup (approx):        PFADD hll:<day> <id>  → PFCOUNT
Membership test:       SETBIT bloom:<key> <pos> 1  → GETBIT
Activity tracking:     SETBIT active:<date> <userId> 1  → BITCOUNT
```
