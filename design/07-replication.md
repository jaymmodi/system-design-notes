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

| Need | Topology | Examples |
|------|----------|---------|
| Simple read scaling | Single-leader, async replicas | MySQL, PostgreSQL, RDS |
| Multi-datacenter writes | Multi-leader (beware conflicts) | CockroachDB, MySQL Group Replication |
| High write availability, tunable consistency | Leaderless | Cassandra, Riak |
| Managed, per-partition leader election | Single-leader per partition (Paxos) | DynamoDB ← not leaderless |
| Zero data loss | Synchronous single-leader + STONITH | RDS Multi-AZ |
| Analytics without impacting prod | Read replica dedicated for analytics | Aurora read replicas |

### DynamoDB replication — clarification

DynamoDB is commonly misclassified as leaderless. It is **single-leader per partition**:

```
DynamoDB partition (one shard of your table):

  AZ-1a: [Replica — LEADER]   ← all writes go here
  AZ-1b: [Replica]             ← async replication from leader
  AZ-1c: [Replica]             ← async replication from leader

  Write path:  client → leader → ack (after leader + 1 replica durable)
  Read (eventual): any replica — may be slightly stale
  Read (strong):   leader only — always latest

  Leader election: Multi-Paxos — replicas vote, majority elects new leader
  Failover: ~seconds, automatic, managed by AWS
```

**Cassandra is truly leaderless:**
```
  Any node can be coordinator for any request
  No election, no designated leader per partition
  Client writes to W replicas directly (or via coordinator)
  Pure quorum: W + R > N for consistency
  Any node failure → other nodes absorb writes immediately
```

**Why DynamoDB chose leader per partition:**
- Simpler conflict resolution — only one writer at a time per key
- Stronger consistency option (strongly consistent reads) — only possible with a known leader
- Trade-off: leader = single write path, but AWS manages failover transparently

---

## Cassandra Replication — Deep Dive

### The Ring — how data is distributed

Every Cassandra node owns a range of tokens on a consistent hash ring. The partition key is hashed → maps to a position on the ring → goes to the node that owns that token range.

```
Token ring (0 to 2^64):

        Node A (owns 0–25%)
              │
    ┌─────────┴─────────┐
    │                   │
Node D              Node B
(75–100%)           (25–50%)
    │                   │
    └─────────┬─────────┘
              │
        Node C (owns 50–75%)

write("user:123") → hash = 38% → Node B owns this range → Node B is the first replica
```

### Replication Factor — how many copies

RF=3 means 3 nodes store each row. Cassandra walks clockwise around the ring:

```
RF=3, write("user:123") → hash lands on Node B

  Replica 1: Node B  (primary)
  Replica 2: Node C  (next clockwise)
  Replica 3: Node D  (next clockwise)
  Node A:    no copy for this row
```

### Coordinator Node — who handles the request

Client writes to one node (coordinator). Coordinator fans out to all replicas.

```
Client
  │  write("user:123", data)
  ↓
Node A  ← coordinator (any node — doesn't hold this data)
  │
  ├──→ Node B  (replica 1) ──→ ack
  ├──→ Node C  (replica 2) ──→ ack
  └──→ Node D  (replica 3) ──→ ack

Coordinator waits for W acks (W = consistency level)
W=QUORUM (2 of 3) → returns success after 2 acks, Node D's ack arrives later
```

**Any node can be coordinator** — no designated leader per partition. This is why Cassandra is truly leaderless.

### Consistency Levels — tunable W and R

```
N=3 (replication factor)

Write levels:
  ONE          → wait for 1 ack          (fastest, least safe)
  QUORUM       → wait for 2 of 3 acks    (balanced)
  LOCAL_QUORUM → quorum within local DC  ← Netflix uses this
  ALL          → wait for all 3 acks     (slowest, safest)

Read levels:
  ONE          → read from 1 replica     (may be stale)
  QUORUM       → read from 2, pick latest
  LOCAL_QUORUM → quorum within DC only

Strong consistency: W + R > N
  QUORUM + QUORUM = 2 + 2 > 3 ✓  → always sees latest write
  ONE + ONE = 1 + 1 ≤ 3          ✗  → may read stale
```

### Multi-DC replication — Netflix setup

```
us-east-1 (DC1, RF=3)              eu-west-1 (DC2, RF=3)
┌──────────────────────┐            ┌──────────────────────┐
│  Node A              │            │  Node E              │
│  Node B  ◄──async────┼───────────►│  Node F              │
│  Node C              │            │  Node G              │
└──────────────────────┘            └──────────────────────┘

Write with LOCAL_QUORUM:
  Client (us-east-1) → coordinator in DC1
  Waits for 2 of 3 acks within DC1 only (~1ms)
  Async replication to DC2 in background (~50-100ms)
  Client gets ack without waiting for DC2
```

```sql
CREATE KEYSPACE netflix WITH replication = {
  'class': 'NetworkTopologyStrategy',
  'us-east-1': 3,
  'eu-west-1': 3
};
```

### Hinted Handoff — when a replica is down

```
Node D is down during write (W=QUORUM, N=3):

  Coordinator → Node B ✓  ack
  Coordinator → Node C ✓  ack  ← QUORUM met, return success to client
  Coordinator → Node D ✗  down

  Coordinator stores hint locally:
    {key: "user:123", value: data, target: NodeD, timestamp: T}

  Node D comes back → coordinator replays hint → Node D catches up
  Hints stored up to 3 hours. If down longer → needs nodetool repair
```

### Read Repair — fixing stale replicas on reads

```
Read with QUORUM (reads 2 replicas):

  Node B → {name: "Jay", version: 5}
  Node C → {name: "Jay", version: 4}  ← stale

  Coordinator returns version 5 to client (latest wins)
  Background: writes version 5 back to Node C asynchronously
```

### Write path internals — inside each node

```
Write arrives at replica:

  1. Commit Log (WAL)   ← sequential disk write, durable, fast
          ↓
  2. Memtable           ← in-memory sorted structure
          ↓ (when full)
  3. SSTable on disk    ← immutable sorted file, flushed from memtable
          ↓ (background)
  4. Compaction         ← merge SSTables, remove tombstones

Why fast: append-only, no in-place updates = LSM tree pattern
Same as RocksDB, HBase, DynamoDB internals
```

### Netflix's actual usage

```
Table: viewing_history
  Partition key:  user_id      ← all rows for a user on same node set
  Clustering key: watched_at   ← sorted within partition, DESC for recency
  Value:          {show_id, progress, device, ...}

Query: "Get last 20 shows watched by user 123"
  → hash(user_id) → find replica nodes
  → LOCAL_QUORUM read
  → rows sorted by watched_at DESC LIMIT 20

Why Cassandra fits Netflix:
  Write-heavy (every play event = write)   → LSM, fast appends
  Time-series access pattern               → clustering key = natural sort
  Per-user locality                        → partition key = user_id, data co-located
  Multi-region                             → NetworkTopologyStrategy + LOCAL_QUORUM
  Petabyte scale                           → horizontal sharding via ring
```

### Failure scenarios

```
1. One replica down (RF=3, W=QUORUM):
   → Works — write to 2 of 3, hinted handoff queues for dead node

2. Two replicas down (RF=3, W=QUORUM):
   → FAILS — can only reach 1 node, quorum needs 2
   → Drop W to ONE to keep writing (lose consistency guarantee)

3. Full DC down (multi-DC, LOCAL_QUORUM):
   → LOCAL_QUORUM fails — can't reach quorum in local DC
   → App switches to QUORUM (cross-DC) → higher latency but works
   → Netflix: Route 53 failover to secondary DC

4. Network partition between nodes:
   → AP behavior: nodes on each side keep accepting writes
   → On heal: last-write-wins (LWW) by timestamp resolves conflicts
   → Risk: clock skew → wrong version wins → use NTP carefully
```

---

## Interview Questions

1. **How does MySQL replication work?** Leader writes to binlog → replicas tail binlog and apply changes asynchronously. Row-based or statement-based.
2. **What is replication lag and how do you handle it?** Time between leader write and replica apply. Handle via: route reads to leader when freshness needed, or use synchronous replication.
3. **PostgreSQL vs MySQL replication?** PG: streaming replication (WAL), logical replication. MySQL: binlog-based. Both support async and semi-sync.
4. **What is Raft?** A consensus algorithm for electing leaders and replicating log. Used by etcd, CockroachDB, TiKV. Guarantees that only one leader exists at a time.
5. **How does Cassandra guarantee no data loss when a replica is down?** Hinted handoff — coordinator stores write locally and replays to the recovered node. For longer outages, nodetool repair uses Merkle trees to sync diverged data.
6. **Why does Netflix use LOCAL_QUORUM instead of QUORUM?** QUORUM waits for acks across DCs (~100ms cross-region). LOCAL_QUORUM waits only within the local DC (~1ms), with async replication to other regions. Faster writes with acceptable eventual consistency across regions.
