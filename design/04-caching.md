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

## Interview Questions

1. **Cache vs CDN?** Cache = server-side computed data; CDN = geographically distributed static assets
2. **How do you handle cache invalidation across microservices?** Events/pub-sub, or accept eventual consistency with short TTLs
3. **What is a cold start problem?** Empty cache after restart → all requests hit DB → potential overload. Solution: cache warming (pre-populate)
4. **Redis vs Memcached?** Redis: persistence, rich data structures, pub/sub, clustering. Memcached: simpler, slightly faster for pure key-value
5. **How would you cache a leaderboard?** Redis Sorted Set (ZADD) — O(log N) updates, O(log N + k) range queries
