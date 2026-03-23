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
