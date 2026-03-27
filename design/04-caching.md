# Caching

## What is Caching?
Storing the result of expensive computations or frequent reads in fast storage (memory) so future requests are served faster without repeating the work.

**Rule of thumb**: 80% of traffic reads 20% of data — cache that 20%.

---

## Why Cache?
- Reduce DB load (DB often the bottleneck)
- Dramatically reduce latency (RAM: 100ns vs DB: 1-10ms)
- Improve throughput (serve from cache at 100K+ RPS)

---

## Cache Strategies (Write Policies)

### Strategy Diagrams

#### 1. Cache-Aside (Lazy Loading)
```
READ path:
  Client ──→ App ──→ Cache
                      │ HIT → return data ──→ Client
                      │ MISS
                      ↓
                     DB ──→ App ──writes──→ Cache ──→ Client

WRITE path:
  Client ──→ App ──→ DB (write)
                  └──→ Cache.delete(key)   ← invalidate, not update
```

#### 2. Write-Through
```
READ path:
  Client ──→ App ──→ Cache
                      │ HIT → return ──→ Client
                      │ MISS
                      ↓
                     DB ──→ App ──writes──→ Cache ──→ Client

WRITE path:
  Client ──→ App ──→ Cache (sync write)
                  └──→ DB    (sync write)
                  both must succeed — if DB fails, rollback cache
```

#### 3. Write-Behind (Write-Back)
```
READ path: same as cache-aside

WRITE path:
  Client ──→ App ──→ Cache (sync, instant ACK to client)
                      │
                  [write queue]
                      │ async, batched
                      ↓
                     DB

  ⚠ if cache crashes before flush → data lost
```

#### 4. Read-Through
```
READ path:
  Client ──→ App ──→ Cache
                      │ HIT → return ──→ Client
                      │ MISS — cache itself fetches from DB
                      ↓
                     DB ──→ Cache (populates itself) ──→ Client

  App never talks to DB directly — cache is the only interface
```

#### Strategy Comparison
```
                    Cache-Aside   Write-Through  Write-Behind  Read-Through
─────────────────────────────────────────────────────────────────────────
Who fetches DB?      App           App            App           Cache
Write latency        Fast          Slow (2 writes) Fastest      Fast
Read latency         Miss on cold  Miss on cold    Miss on cold  Miss on cold
Stale data risk      Yes (window)  No             Yes (crash)   Low
Data loss risk       No            No             Yes           No
Cache fills with     Hot data      All written     All written   Hot data
Best for             General       Read-after-     High write    Library/
                                   write consistency throughput  framework
```

---

### 1. Cache-Aside (Lazy Loading) — Most Common
Application manages cache explicitly. Cache only contains data that's been requested.

```java
public class UserService {
    private final RedisCache cache;
    private final UserRepository db;

    public User getUser(String userId) {
        // 1. Check cache first
        User cached = cache.get("user:" + userId);
        if (cached != null) {
            return cached; // Cache HIT — return immediately
        }

        // 2. Cache MISS — go to DB
        User user = db.findById(userId);
        if (user == null) return null;

        // 3. Populate cache for next time
        cache.set("user:" + userId, user, Duration.ofMinutes(30));
        return user;
    }

    public void updateUser(String userId, User updated) {
        db.save(updated);
        cache.delete("user:" + userId); // Invalidate — let next read repopulate
    }
}
```

**Pros**: Only caches accessed data; cache failures don't break reads
**Cons**: Cache miss on cold start; potential stale data between invalidation and repopulation

---

### 2. Write-Through
Write to cache AND database synchronously on every write.

```java
public class WriteThoughUserService {
    public void updateUser(String userId, User updated) {
        // Write to BOTH in same operation
        cache.set("user:" + userId, updated, Duration.ofMinutes(30));
        db.save(updated); // if DB fails, rollback cache? Complexity rises
    }

    public User getUser(String userId) {
        User cached = cache.get("user:" + userId);
        if (cached != null) return cached;
        // Cache should always have recent data — this is rare (after restart)
        User user = db.findById(userId);
        cache.set("user:" + userId, user, Duration.ofMinutes(30));
        return user;
    }
}
```

**Pros**: Cache always fresh; no stale reads
**Cons**: Every write hits both cache and DB; writes are slower; cache fills with rarely-read data

---

### 3. Write-Behind (Write-Back)
Write to cache immediately, write to database asynchronously.

```java
public class WriteBehindCache {
    private final Queue<WriteOperation> writeQueue = new LinkedBlockingQueue<>();
    private final RedisCache cache;
    private final Database db;

    public WriteBehindCache() {
        // Background thread drains write queue to DB
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
            this::flushToDB, 0, 100, TimeUnit.MILLISECONDS
        );
    }

    public void set(String key, Object value) {
        cache.set(key, value); // instant
        writeQueue.offer(new WriteOperation(key, value)); // async DB write
    }

    private void flushToDB() {
        List<WriteOperation> batch = new ArrayList<>();
        writeQueue.drainTo(batch, 100); // batch for efficiency
        if (!batch.isEmpty()) {
            db.batchWrite(batch);
        }
    }
}
```

**Pros**: Extremely fast writes; can batch DB writes
**Cons**: Data loss risk if cache fails before DB write; complex; consistency challenges

---

### 4. Read-Through
Cache sits in front of DB; cache itself fetches from DB on miss.

```java
// Caffeine's LoadingCache is a read-through cache
LoadingCache<String, User> userCache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(30, TimeUnit.MINUTES)
    .build(userId -> userRepository.findById(userId)); // called on cache miss

// Usage — always just ask the cache
User user = userCache.get("user123"); // cache handles miss internally
```

---

## Eviction Policies

| Policy | Description | Use When |
|--------|-------------|----------|
| **LRU** | Evict least recently used | General purpose — most common |
| **LFU** | Evict least frequently used | Stable hot data (frequent reuse) |
| **FIFO** | Evict oldest entry | Simple, sequential data |
| **TTL** | Evict based on age | Data has natural freshness window |
| **Random** | Evict random entry | When overhead of tracking matters |

```java
// LRU Cache implementation (also an interview question!)
public class LRUCache<K, V> {
    private final int capacity;
    // LinkedHashMap maintains insertion order with access-order mode
    private final LinkedHashMap<K, V> cache;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        // true = access-order (most recently accessed at tail)
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity; // auto-evict LRU when full
            }
        };
    }

    public synchronized V get(K key) {
        return cache.getOrDefault(key, null);
        // LinkedHashMap moves accessed entry to tail — O(1)
    }

    public synchronized void put(K key, V value) {
        cache.put(key, value); // if full, removeEldestEntry triggers
    }
}

// Usage
LRUCache<String, User> cache = new LRUCache<>(1000);
cache.put("user:1", user1);
cache.put("user:2", user2);
cache.get("user:1"); // access user:1 → moves to MRU position
// If capacity exceeded, user:2 would be evicted (LRU)
```

---

## Cache Invalidation Strategies

This is famously one of the "two hard problems in CS."

### 1. TTL-based Expiration
```java
// Simple: entries expire after fixed time
cache.set("product:123", product, Duration.ofMinutes(15));
// After 15 min, next read hits DB and repopulates
```

### 2. Event-Driven Invalidation
```java
// When data changes, publish an event → cache subscriber deletes
@EventListener
public class CacheInvalidationListener {
    public void onProductUpdated(ProductUpdatedEvent event) {
        cache.delete("product:" + event.getProductId());
        cache.delete("category:" + event.getCategoryId()); // invalidate related
    }
}
```

### 3. Cache Versioning
```java
// Instead of invalidating, add version to key
public String getCacheKey(String userId) {
    int version = db.getUserVersion(userId); // or store version in Redis
    return "user:" + userId + ":v" + version;
}
// Update user → increment version → old keys naturally become unreachable
```

---

## Cache Stampede (Thundering Herd) Problem

When a popular cached item expires, thousands of requests simultaneously hit the DB.

```java
// Solution 1: Mutex/Lock — only one request rebuilds cache
public User getUserWithLock(String userId) {
    String key = "user:" + userId;
    User cached = cache.get(key);
    if (cached != null) return cached;

    String lockKey = "lock:" + key;
    if (redis.setNx(lockKey, "1", Duration.ofSeconds(10))) {
        // Got the lock — this thread rebuilds cache
        try {
            User user = db.findById(userId);
            cache.set(key, user, Duration.ofMinutes(30));
            return user;
        } finally {
            redis.delete(lockKey);
        }
    } else {
        // Another thread is rebuilding — wait briefly and retry
        Thread.sleep(50);
        return cache.get(key); // probably populated now
    }
}

// Solution 2: Probabilistic Early Expiration (XFetch algorithm)
public User getUserWithEarlyExpiry(String userId) {
    CacheEntry entry = cache.getWithTTL("user:" + userId);
    if (entry != null) {
        double ttl = entry.getRemainingTTLSeconds();
        double delta = measureDBLatency(); // or use fixed value
        // Probabilistically refresh before expiry based on remaining TTL
        if (-delta * Math.log(Math.random()) <= ttl) {
            return entry.getValue(); // not yet time to refresh
        }
    }
    // Proactively refresh
    return refreshFromDB(userId);
}
```

---

## Distributed Caching Topologies

### 1. Client-Side Cache
```
App Server → [local in-memory cache] → DB
```
- Fastest (in-process, no network)
- Each server has its own cache → inconsistency between servers
- Use for: static config, reference data, expensive computed results

```java
// Caffeine — fast in-process cache
Cache<String, Product> localCache = Caffeine.newBuilder()
    .maximumSize(5_000)
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .recordStats() // enables hit rate metrics
    .build();
```

### 2. Distributed Cache (Redis/Memcached)
```
[Server1]  [Server2]  [Server3]
    \          |          /
     [   Redis Cluster   ]
             |
           [DB]
```
- Shared cache across all app servers — consistent view
- Network hop (~0.5ms) vs in-process (nanoseconds)
- Can survive app restarts

### 3. Two-Level Cache (L1 + L2)
```java
public class TwoLevelCache {
    private final Cache<String, Object> l1; // local Caffeine
    private final RedisClient l2;            // distributed Redis

    public Object get(String key) {
        // Check L1 first (fastest)
        Object result = l1.getIfPresent(key);
        if (result != null) return result;

        // Check L2 (distributed, slower)
        result = l2.get(key);
        if (result != null) {
            l1.put(key, result); // warm L1
            return result;
        }

        // Miss — go to DB
        result = db.get(key);
        if (result != null) {
            l2.set(key, result, Duration.ofMinutes(30));
            l1.put(key, result);
        }
        return result;
    }
}
```

---

## Cache Hit Rate & Sizing

```java
// Use Caffeine stats to monitor
CacheStats stats = cache.stats();
double hitRate = stats.hitRate(); // target: > 90%
long evictionCount = stats.evictionCount(); // high = cache too small
double avgLoadPenalty = stats.averageLoadPenalty(); // time to load on miss

// Rule of thumb for sizing:
// If hit rate is 80% with 1GB, doubling to 2GB might give 90%
// Diminishing returns after that — most hot data already cached
```

---

## When to Use
- Read-heavy workloads (> 80% reads)
- Data that changes infrequently
- Expensive computations or DB queries
- API responses from external services

## When to Avoid
- Write-heavy workloads (cache is constantly invalidated)
- Data that must always be fresh (financial balances — use strong consistency instead)
- Small datasets that fit in DB memory anyway
- When consistency is critical and eventual consistency is unacceptable

---

## Redis vs Memcached

### At a glance

| | Redis | Memcached |
|---|---|---|
| **Data structures** | String, Hash, List, Set, ZSet, Bitmap, HLL, Stream | String only (key → blob) |
| **Persistence** | RDB snapshots + AOF log | None — memory only |
| **Replication** | Primary/replica + Redis Cluster | No built-in replication |
| **Clustering** | Redis Cluster (hash slots, automatic sharding) | Client-side sharding only |
| **Pub/Sub** | Yes | No |
| **Lua scripting** | Yes (atomic multi-op) | No |
| **Transactions** | MULTI/EXEC + WATCH | No |
| **TTL** | Per-key TTL | Per-key TTL |
| **Memory efficiency** | Slightly higher overhead per key | More memory-efficient for pure strings |
| **Throughput** | ~100K–1M ops/sec | ~100K–1M ops/sec (slightly faster for GET/SET) |
| **Multithreading** | Single-threaded commands (I/O multithreaded since 6.0) | Fully multithreaded |
| **Max value size** | 512 MB | 1 MB |

---

### Architecture difference

```
Redis (single-threaded command execution):
  Network I/O thread ──→ Command queue ──→ Single worker thread ──→ Memory
                                                   ↑
                              no locking needed — one thread owns all data
                              → atomic by design

Memcached (multithreaded):
  Thread 1 ──→ Slab allocator ──→ Memory
  Thread 2 ──→ Slab allocator ──→ Memory   ← threads compete, fine-grained locking
  Thread 3 ──→ Slab allocator ──→ Memory
  → better CPU utilization on multi-core for pure GET/SET
```

### When Redis wins

**Any time you need more than a simple string cache:**

```
Leaderboard          → ZADD / ZRANGE (Sorted Set)
Rate limiting        → INCR + EXPIRE, or sliding window with ZSET
Session store        → HSET (hash per session, field per attribute)
Pub/Sub messaging    → PUBLISH / SUBSCRIBE
Distributed lock     → SET NX EX + Lua script
Counting uniques     → PFADD / PFCOUNT (HyperLogLog)
Stream processing    → XADD / XREADGROUP
Bloom filter         → SETBIT / GETBIT (or RedisBloom module)
Persistence needed   → RDB + AOF
```

### When Memcached wins

Narrow case — **pure caching of serialized blobs** with high QPS and many CPU cores:

```
Use case: Cache rendered HTML pages, serialized JSON responses
  - Every value is the same type (bytes)
  - No need for data structures
  - High write rate, many cores available
  - Memory efficiency matters more than features

Memcached slab allocator is more memory-efficient for uniform value sizes
Memcached multithreading utilizes all cores — Redis saturates one core at ~1M QPS
```

### Netflix's choice

Netflix uses **EVCache** — which is built on **Memcached**, not Redis:

```
Why EVCache uses Memcached:
  - Netflix caches are simple blobs (serialized Thrift/Protobuf objects)
  - Massive scale: millions of cache nodes, trillions of ops/day
  - Memory efficiency at that scale matters — Memcached wins on pure GET/SET
  - Replication handled by EVCache layer on top, not Memcached itself
  - No need for data structures — app logic handles everything

Why Netflix also uses Redis:
  - Rate limiting
  - Distributed locks
  - Real-time leaderboards / sorted data
  - Pub/Sub within services
```

### Real-world decision tree

```
Need persistence or replication?          → Redis
Need Pub/Sub or Streams?                  → Redis
Need data structures (ZSet, Hash, etc)?   → Redis
Need distributed lock?                    → Redis
Need Lua atomicity?                       → Redis

Pure blob cache, huge scale, many cores?  → Memcached
Already using EVCache / Netflix stack?    → Memcached

Default for new projects?                 → Redis
  (more features, better ops tooling,
   persistence safety net, active community)
```

### One-liner for interviews

> "Memcached is faster for pure GET/SET at scale due to multithreading and simpler memory model. Redis wins everything else — data structures, persistence, pub/sub, cluster replication, atomic Lua scripts. Default to Redis; use Memcached only when you've profiled that Redis is your bottleneck and your access pattern is purely key-value blobs. Netflix uses both — EVCache (Memcached-based) for object caching at massive scale, Redis for rate limiting, locks, and sorted data."

---

## Interview Questions

1. **Cache vs CDN?** Cache = server-side computed data; CDN = geographically distributed static assets
2. **How do you handle cache invalidation across microservices?** Events/pub-sub, or accept eventual consistency with short TTLs
3. **What is a cold start problem?** Empty cache after restart → all requests hit DB → potential overload. Solution: cache warming (pre-populate)
4. **Redis vs Memcached?** Redis: persistence, rich data structures, pub/sub, clustering. Memcached: simpler, slightly faster for pure key-value
5. **How would you cache a leaderboard?** Redis Sorted Set (ZADD) — O(log N) updates, O(log N + k) range queries
