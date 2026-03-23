# Database Sharding

## What is Sharding?
Horizontal partitioning of data across multiple database instances (shards). Each shard holds a subset of the data, allowing you to scale storage and throughput beyond what a single machine can handle.

---

## Why Shard?
- Single DB can't handle the write volume
- Dataset too large for one machine
- Need to reduce query latency by reducing per-shard data size

**Don't shard prematurely** — vertical scaling, read replicas, and caching usually come first.

---

## Sharding Strategies

### 1. Range-Based Sharding
Keys are divided into ranges. Shard 1 holds A-M, Shard 2 holds N-Z, etc.

```java
public class RangeShardRouter {
    // Define range boundaries
    private final NavigableMap<String, DataSource> shards = new TreeMap<>();

    public RangeShardRouter() {
        shards.put("A", datasource1); // A-M → shard 1
        shards.put("N", datasource2); // N-Z → shard 2
    }

    public DataSource getShard(String userId) {
        // Find shard where userId >= boundary
        Map.Entry<String, DataSource> entry = shards.floorEntry(userId.toUpperCase());
        return entry != null ? entry.getValue() : shards.firstEntry().getValue();
    }
}

// For numeric keys (e.g., order IDs):
// Shard 1: 1 - 10,000,000
// Shard 2: 10,000,001 - 20,000,000
// Shard 3: 20,000,001 - ...
```

**Pros**: Range queries are efficient (all data in a range lives on one shard)
**Cons**: Hotspots! Sequential IDs → all writes go to last shard. New users (alphabetically recent) all go to same shard.

---

### 2. Hash-Based Sharding
Apply a hash function to the sharding key to determine shard.

```java
public class HashShardRouter {
    private final List<DataSource> shards;

    public HashShardRouter(List<DataSource> shards) {
        this.shards = shards;
    }

    public DataSource getShard(String key) {
        int hash = Math.abs(murmurHash(key));
        return shards.get(hash % shards.size());
    }

    // Problem: adding a new shard changes mapping for most keys
    // Solution: use consistent hashing (see 03-consistent-hashing.md)
}
```

**Pros**: Even distribution, avoids hotspots
**Cons**: Range queries span all shards (expensive), resharding is painful without consistent hashing

---

### 3. Directory-Based Sharding
A lookup service (directory) maps keys to shards.

```java
public class DirectoryShardRouter {
    private final Map<String, String> keyToShard = new ConcurrentHashMap<>(); // stored in Redis
    private final Map<String, DataSource> shardPool;

    public DataSource getShard(String userId) {
        String shardId = keyToShard.get(userId);
        if (shardId == null) {
            shardId = assignNewShard(userId); // assign to least-loaded shard
            keyToShard.put(userId, shardId);
        }
        return shardPool.get(shardId);
    }

    private String assignNewShard(String userId) {
        // Find shard with least data/connections
        return shardPool.entrySet().stream()
            .min(Comparator.comparingInt(e -> e.getValue().getRowCount()))
            .map(Map.Entry::getKey)
            .orElseThrow();
    }
}
```

**Pros**: Maximum flexibility — can move data between shards
**Cons**: Directory is a bottleneck/SPOF; extra lookup overhead

---

### 4. Geographic Sharding
Shard by user geography.

```java
public class GeoShardRouter {
    private final Map<String, DataSource> regionShards = Map.of(
        "US", usDatabase,
        "EU", euDatabase,
        "APAC", apacDatabase
    );

    public DataSource getShard(User user) {
        return regionShards.getOrDefault(user.getRegion(), usDatabase);
    }
}
// Pros: Data locality (EU users → EU DC), GDPR compliance
// Cons: Cross-shard queries hard; users who travel create issues
```

---

## Sharding Key Selection — Critical Decision

The sharding key determines data distribution. A bad key creates hotspots.

```java
// BAD sharding keys:
// - timestamp: all recent writes go to same shard
// - country: 90% of users in US → 90% on one shard
// - boolean (isActive): 95% active → massive imbalance

// GOOD sharding keys:
// - userId (high cardinality, random distribution)
// - combination key: userId XOR timestamp (for time-series per user)

// Example: Instagram shards by a combination of shardId + epoch + sequence
public class InstagramIdGenerator {
    // Format: 41 bits timestamp | 13 bits shard ID | 10 bits sequence
    private static final long EPOCH = 1314220021721L; // custom epoch

    public long generateId(int shardId) {
        long timestamp = System.currentTimeMillis() - EPOCH;
        long sequence = getAndIncrementSequence(); // per-shard sequence
        return (timestamp << 23) | ((long) shardId << 10) | sequence;
    }
    // Generated ID encodes which shard it belongs to → no lookup needed!
}
```

---

## Cross-Shard Queries — The Hard Part

```java
// PROBLEM: "Find all orders for customer X across all shards"
// If orders are sharded by orderId, customer's orders span all shards

// Solution 1: Scatter-Gather (fan-out)
public List<Order> getCustomerOrders(String customerId) {
    List<CompletableFuture<List<Order>>> futures = shards.stream()
        .map(shard -> CompletableFuture.supplyAsync(
            () -> shard.queryOrdersByCustomer(customerId)
        ))
        .collect(toList());

    return futures.stream()
        .map(CompletableFuture::join)
        .flatMap(List::stream)
        .sorted(Comparator.comparing(Order::getCreatedAt).reversed())
        .collect(toList());
    // Problem: O(N) DB queries, slow if many shards
}

// Solution 2: Shard by customerId (not orderId)
// All orders for a customer live on the same shard
// OrderId still unique globally (use Instagram-style IDs above)
public DataSource getShardForCustomer(String customerId) {
    return shards.get(murmurHash(customerId) % shards.size());
}

// Solution 3: Denormalized secondary index
// Maintain a separate table: customerId → [orderId, shardId]
// Route lookup to shardId, fetch order — two hops but targeted
```

---

## Resharding — Moving Data Between Shards

This is operationally complex. Plan for it from day one.

```java
public class ReshardingManager {
    // Double-write approach: write to both old and new shard during migration
    public void migrateData(String userId, DataSource oldShard, DataSource newShard) {
        // Phase 1: Copy data to new shard (while still writing to old)
        List<Row> data = oldShard.fetchAll(userId);
        newShard.batchInsert(data);

        // Phase 2: Enable double-writes
        // All new writes go to BOTH shards
        enableDoubleWrite(userId, oldShard, newShard);

        // Phase 3: Catch up lag (new shard gets all writes since copy)
        waitForReplicationCatchup(newShard);

        // Phase 4: Switch reads to new shard
        updateShardMapping(userId, newShard);

        // Phase 5: After validation, disable old shard writes
        disableOldShardWrites(userId, oldShard);

        // Phase 6: Verify and clean up old shard data
        verifyDataIntegrity(userId, oldShard, newShard);
        oldShard.deleteData(userId); // only after confirmed correct!
    }
}
```

---

## Shard Management in Practice

### Virtual Shards
Instead of directly mapping to physical servers, use virtual shards (like consistent hashing virtual nodes):

```java
// 1000 virtual shards → 10 physical servers
// Each physical server handles 100 virtual shards
// Adding a server: move some virtual shards to new server

public class VirtualShardRouter {
    private static final int NUM_VIRTUAL_SHARDS = 1024;
    private final Map<Integer, DataSource> virtualToPhysical = new HashMap<>();

    public DataSource getShard(String key) {
        int virtualShard = Math.abs(murmurHash(key)) % NUM_VIRTUAL_SHARDS;
        return virtualToPhysical.get(virtualShard);
    }

    public void addPhysicalShard(DataSource newShard, int numVirtualShardsToAssign) {
        // Reassign some virtual shards to new physical shard
        // Only move data for those virtual shards — much less data
        List<Integer> toMove = selectLeastLoadedVirtualShards(numVirtualShardsToAssign);
        for (int vs : toMove) {
            migrateVirtualShard(vs, virtualToPhysical.get(vs), newShard);
            virtualToPhysical.put(vs, newShard);
        }
    }
}
```

---

## Real-World Sharding Examples

| Company | System | Sharding Strategy |
|---------|--------|-------------------|
| Instagram | Photos | Shard by userId, virtual shards |
| Twitter | Tweets | Shard by tweetId, snowflake IDs |
| Uber | Trips | Shard by cityId + time window |
| Pinterest | Pins | Shard by userId |
| MongoDB Atlas | Collections | Range or hash on shard key |

---

## When to Shard

1. Single DB CPU/disk is maxed out
2. Dataset exceeds what one machine can store (even with compression)
3. Write throughput exceeds replication lag tolerance

## When NOT to Shard

- First, try: indexes, query optimization, caching, read replicas
- If < 100GB of data → probably don't need it
- If your queries frequently span multiple rows of different users → sharding will be painful

---

## Interview Questions

1. **How do you handle transactions across shards?** 2-phase commit (slow), Saga pattern (eventual consistency), or design to avoid cross-shard transactions
2. **What is a hotspot and how do you fix it?** A shard handling disproportionate load. Fix: better sharding key, add a random prefix to spread writes, virtual shards
3. **How does MongoDB sharding work?** Mongos router + config servers store chunk ranges; chunks are balanced across shards automatically
4. **How would you shard a Twitter-like system?** Shard tweets by tweetId (snowflake, encodes shardId). User timeline is fan-out-on-write to follower timelines (also sharded by userId)
