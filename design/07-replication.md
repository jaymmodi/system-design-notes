# Database Replication

## What is Replication?
Keeping copies of data on multiple machines. Provides **fault tolerance**, **read scaling**, and **geographic distribution**.

---

## Why Replicate?
- **High availability**: If primary fails, replica takes over
- **Read scaling**: Distribute read queries across replicas
- **Disaster recovery**: Data in multiple DCs
- **Analytics**: Run heavy queries on replica, not primary

---

## Replication Topologies

### 1. Single-Leader (Master-Slave / Primary-Replica)

One node accepts all writes (leader). Others replicate and serve reads (followers).

```
       Writes                  Reads
         ↓                     ↑
    [  Leader  ] → replicates → [Replica 1]
                              → [Replica 2]
                              → [Replica 3]
```

```java
// Application routes writes to leader, reads to replicas
public class SingleLeaderDataSource {
    private final DataSource leader;
    private final List<DataSource> replicas;
    private final AtomicInteger replicaCounter = new AtomicInteger(0);

    public DataSource getWriteConnection() {
        return leader; // all writes to leader
    }

    public DataSource getReadConnection() {
        // Round-robin across replicas
        int index = replicaCounter.getAndIncrement() % replicas.size();
        return replicas.get(index);
    }
}

// With Spring:
@Configuration
public class DataSourceConfig {
    @Bean
    @Primary
    public DataSource routingDataSource() {
        AbstractRoutingDataSource ds = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ? "replica" : "leader";
            }
        };
        ds.setTargetDataSources(Map.of("leader", primaryDS(), "replica", replicaDS()));
        return ds;
    }
}

// Usage
@Transactional(readOnly = true) // → routes to replica
public List<Order> getOrdersForUser(String userId) {
    return orderRepository.findByUserId(userId);
}

@Transactional // → routes to leader
public Order createOrder(Order order) {
    return orderRepository.save(order);
}
```

**Pros**: Simple, strong consistency on writes, easy to reason about
**Cons**: Leader is write bottleneck, replication lag causes stale reads

---

### 2. Multi-Leader (Master-Master)

Multiple nodes accept writes. Useful for multi-datacenter setups.

```
DC1: [Leader A] ←→ sync ←→ [Leader B] :DC2
      ↓                          ↓
  replicas                   replicas
```

```java
// Problem: same row updated on both leaders simultaneously → CONFLICT
public class MultiLeaderConflictResolver {

    // Strategy 1: Last Write Wins (LWW) — loses data
    public Order resolveWithLWW(Order a, Order b) {
        return a.getUpdatedAt().isAfter(b.getUpdatedAt()) ? a : b;
        // DANGER: if clocks are skewed, can pick wrong winner
    }

    // Strategy 2: Application-level merge
    public ShoppingCart mergeCart(ShoppingCart a, ShoppingCart b) {
        // Union of items from both carts (like Amazon's cart merge)
        Map<String, CartItem> merged = new HashMap<>(a.getItems());
        b.getItems().forEach((productId, item) ->
            merged.merge(productId, item, (existing, newItem) ->
                new CartItem(productId, Math.max(existing.getQty(), newItem.getQty()))
            )
        );
        return new ShoppingCart(merged);
    }

    // Strategy 3: Custom conflict-free (CRDT)
    // See 02-cap-theorem.md for CRDT GCounter example
}
```

**Pros**: Write to closest DC (low latency), no single write bottleneck
**Cons**: Conflict resolution is complex; avoid for anything needing strict consistency

---

### 3. Leaderless Replication (Dynamo-style)

Any replica accepts writes. Client writes to W replicas, reads from R replicas. If W + R > N (total replicas), reads always see latest write.

```java
// Quorum reads/writes
public class LeaderlessDataClient {
    private final List<ReplicaNode> allReplicas; // N total
    private final int W; // write quorum
    private final int R; // read quorum

    // W=2, R=2, N=3 → W+R > N → strong consistency
    // W=1, R=1, N=3 → fast but inconsistent
    // W=3, R=1, N=3 → slow writes, fast reads

    public void write(String key, String value, long version) {
        List<CompletableFuture<Void>> futures = allReplicas.stream()
            .map(r -> CompletableFuture.runAsync(() -> r.write(key, value, version)))
            .collect(toList());

        // Wait for W successful writes (ignore others)
        int successes = 0;
        for (CompletableFuture<Void> f : futures) {
            try { f.get(1, TimeUnit.SECONDS); successes++; }
            catch (Exception ignored) {}
            if (successes >= W) return; // quorum achieved
        }
        throw new WriteQuorumNotMetException("Only " + successes + " of " + W + " writes succeeded");
    }

    public String read(String key) {
        List<CompletableFuture<VersionedValue>> futures = allReplicas.stream()
            .map(r -> CompletableFuture.supplyAsync(() -> r.read(key)))
            .collect(toList());

        List<VersionedValue> responses = new ArrayList<>();
        for (CompletableFuture<VersionedValue> f : futures) {
            try { responses.add(f.get(1, TimeUnit.SECONDS)); }
            catch (Exception ignored) {}
            if (responses.size() >= R) break;
        }
        if (responses.size() < R) throw new ReadQuorumNotMetException();

        // Read repair: if replicas have different values, fix the stale ones
        VersionedValue latest = responses.stream()
            .max(Comparator.comparingLong(VersionedValue::getVersion))
            .orElseThrow();
        repairStaleReplicas(key, latest, responses);
        return latest.getValue();
    }

    private void repairStaleReplicas(String key, VersionedValue latest, List<VersionedValue> responses) {
        // Read repair: write latest value back to stale replicas asynchronously
        responses.stream()
            .filter(v -> v.getVersion() < latest.getVersion())
            .forEach(stale -> executor.submit(() ->
                stale.getReplica().write(key, latest.getValue(), latest.getVersion())
            ));
    }
}
```

---

## Replication Lag & Consistency Models

### Synchronous vs Asynchronous Replication

```java
// Synchronous: leader waits for replica to confirm before ack'ing client
// → No data loss on failover, but SLOWER writes

// Async: leader ack's client immediately, replicates in background
// → Fast writes, but can lose recent writes if leader crashes

// Semi-sync (MySQL default): wait for at least 1 replica to confirm
// → Balance between safety and performance
```

### Reading Your Own Writes

```java
// Problem: user writes a post, immediately reads it → might hit stale replica!
public class ReadYourWritesConsistency {
    // Solution 1: After write, always read from leader for 1 minute
    public Post getPost(String postId, String viewerId, String authorId) {
        if (viewerId.equals(authorId)) {
            // User might be reading their own content — use leader
            return leaderDB.findPost(postId);
        }
        return replicaDB.findPost(postId);
    }

    // Solution 2: Track replication position
    public Post getPostAfterWrite(String postId, long writeLSN) {
        // Wait for replica to catch up to the write's log sequence number
        DataSource ds = replicaDB.waitForLSN(writeLSN, Duration.ofSeconds(2));
        return ds.findPost(postId);
    }

    // Solution 3: Include timestamp in client, route to leader if data might be stale
}
```

### Monotonic Reads

```java
// Problem: User sees post, refreshes, post "disappears" (different replica, more lag)
// Solution: always route same user to same replica

public class MonotonicReadRouter {
    private final Map<String, DataSource> userToReplica = new ConcurrentHashMap<>();

    public DataSource getReplicaForUser(String userId) {
        return userToReplica.computeIfAbsent(userId, id -> {
            // Pick a replica and stick to it
            int index = Math.abs(id.hashCode()) % replicas.size();
            return replicas.get(index);
        });
    }
}
```

---

## Failover

### Automatic Failover Process

```java
public class LeaderFailoverManager {
    private volatile String currentLeader;
    private final HealthChecker healthChecker;

    // Option 1: External coordinator (ZooKeeper, etcd) detects failure and triggers election
    // Option 2: Built-in election (MySQL Group Replication, PostgreSQL Patroni)

    public void handleLeaderFailure() {
        // 1. Detect failure (health check timeout)
        // 2. Select new leader: usually most up-to-date replica
        DataSource newLeader = replicas.stream()
            .max(Comparator.comparingLong(this::getReplicationOffset))
            .orElseThrow();

        // 3. PROBLEM: old leader might come back thinking it's still leader
        // SOLUTION: STONITH (Shoot The Other Node In The Head) — fence the old leader
        // before promoting the new one
        fenceOldLeader(currentLeader);

        // 4. Promote new leader
        promoteToLeader(newLeader);
        currentLeader = newLeader.getAddress();

        // 5. Update all replicas to follow new leader
        replicas.forEach(r -> r.setLeader(currentLeader));

        // 6. Update routing / service discovery
        serviceRegistry.updateLeader(currentLeader);
    }
}
```

### Split Brain

The most dangerous failure mode: two nodes both think they're the leader.

```java
// Scenario:
// Leader A ←→ network partition ←→ Replica B (promoted to leader)
// A still accepts writes, B also accepts writes → DIVERGED DATA

// Prevention:
// 1. Require majority quorum: replica only promotes if it has > N/2 votes
// 2. Leader must have a lease: stops accepting writes when lease expires
// 3. STONITH: forcibly shut down old leader before promoting new one
// 4. Epoch numbers: each term has an epoch; replicas reject writes from lower epoch

public class LeaderElection {
    private long currentEpoch;

    public boolean isValidWrite(WriteRequest req) {
        if (req.getEpoch() < currentEpoch) {
            // Message from old leader — REJECT
            return false;
        }
        return true;
    }
}
```

---

## Change Data Capture (CDC)

Replicate changes to other systems using the DB's write-ahead log.

```java
// Debezium captures MySQL binlog changes → publishes to Kafka
// Downstream consumers (search index, cache, analytics) react to changes

// Example: Keep Elasticsearch in sync with MySQL
@Component
public class ProductCDCConsumer {
    @KafkaListener(topics = "mysql.products.changes")
    public void onProductChange(ProductChangeEvent event) {
        switch (event.getOperation()) {
            case INSERT, UPDATE -> elasticsearchClient.index(event.getProduct());
            case DELETE -> elasticsearchClient.delete(event.getProductId());
        }
    }
}

// CDC use cases:
// - Search index sync
// - Cache invalidation
// - Event sourcing
// - Audit logs
// - Cross-DC replication
```

---

## When to Use What

| Need | Topology |
|------|----------|
| Simple read scaling | Single-leader, async replicas |
| Multi-datacenter writes | Multi-leader (beware conflicts) |
| High write availability, tunable consistency | Leaderless (Cassandra, DynamoDB) |
| Zero data loss | Synchronous single-leader + STONITH |
| Analytics without impacting prod | Read replica dedicated for analytics |

---

## Interview Questions

1. **How does MySQL replication work?** Leader writes to binlog → replicas tail binlog and apply changes asynchronously. Row-based or statement-based.
2. **What is replication lag and how do you handle it?** Time between leader write and replica apply. Handle via: route reads to leader when freshness needed, or use synchronous replication.
3. **PostgreSQL vs MySQL replication?** PG: streaming replication (WAL), logical replication. MySQL: binlog-based. Both support async and semi-sync.
4. **What is Raft?** A consensus algorithm for electing leaders and replicating log. Used by etcd, CockroachDB, TiKV. Guarantees that only one leader exists at a time.
