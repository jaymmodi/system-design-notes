# Cassandra vs DynamoDB — Deep Dive

## 30-second mental model

```
Cassandra:  You operate it. You own the ring, the nodes, the compaction,
            the repairs. Full control, full responsibility.
            Open source, runs anywhere (AWS, GCP, on-prem, laptop).

DynamoDB:   AWS operates it. You just write data.
            No nodes, no compaction, no repair, no capacity planning.
            Fully managed, AWS-only, pay per request.
```

---

## Data Model

Both are wide-column stores, but with key differences:

### Cassandra
```sql
CREATE TABLE viewing_history (
  user_id    text,          -- partition key  → hashed to ring node
  watched_at timestamp,     -- clustering key → sorted within partition
  show_id    text,
  progress   int,
  PRIMARY KEY (user_id, watched_at)
) WITH CLUSTERING ORDER BY (watched_at DESC);

-- One partition = all rows for one user, sorted by time
-- Entire partition lives on same set of nodes
-- Fast scan: SELECT * FROM viewing_history WHERE user_id = 'u123' LIMIT 20
```

### DynamoDB
```
Table: ViewingHistory
  PK: userId       (partition key)
  SK: watchedAt    (sort key)
  Attributes: showId, progress, device, ...  ← schema-less, flexible per item
```

### Side by side

|  | Cassandra | DynamoDB |
|--|-----------|----------|
| **Schema** | Strict (CQL DDL) | Schema-less (attributes flexible) |
| **Partition key** | Hash → ring node | Hash → internal partition |
| **Clustering key** | Yes, typed, ordered | Sort key |
| **Query language** | CQL (SQL-like) | API calls (GetItem, Query, Scan) |
| **Secondary indexes** | Materialized views, 2i | GSI (Global), LSI (Local) |
| **Max partition size** | Recommended < 100MB | 400KB per item, no partition limit |
| **Data types** | Rich (list, map, set, UDT) | String, Number, Binary, List, Map, Set |

---

## Replication

### Cassandra — you configure it
```
Ring-based consistent hashing. RF=3 → 3 nodes own each row (clockwise on ring).
Multi-DC: NetworkTopologyStrategy, RF per DC.
You run nodetool repair to fix divergence.

You decide:
  RF per DC, consistency level per query
  when to run repair
  compaction strategy per table
```

### DynamoDB — AWS handles it
```
Each partition: 3 replicas across 3 AZs, one leader (Multi-Paxos).
Writes → leader → synced to 2 of 3 replicas → ack to client.
No repair needed — AWS handles divergence internally.

AWS decides: replica placement, leader election, replication lag, repair
You decide:  eventual vs strong consistency per read
```

---

## Consistency Model

### Cassandra — tunable per query
```
Write/Read consistency levels:
  ONE          → 1 replica ack      (fastest, risk of stale read)
  QUORUM       → majority ack       (balanced)
  LOCAL_QUORUM → majority in local DC ← Netflix default
  ALL          → all replicas       (slowest, safest)

Strong consistency: W + R > N
  QUORUM write + QUORUM read = 2 + 2 > 3 ✓

// Tune per query:
session.execute(query.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM));
```

### DynamoDB — two modes only
```
Eventually consistent (default):
  → any replica, may be slightly stale
  → half the read capacity cost

Strongly consistent:
  → leader only, always latest
  → full read capacity cost
  → NOT available on GSI

GetItemRequest.builder()
    .tableName("ViewingHistory")
    .key(key)
    .consistentRead(true)   // strongly consistent
    .build();
```

```
Cassandra wins: granular control — tune per query, per table, per DC
DynamoDB wins:  simplicity — two options, no misconfiguration risk
```

---

## Write Path

### Cassandra
```
Client → Coordinator (any node) → fans out to RF replicas

Each replica:
  1. WAL (commit log) — sequential disk append
  2. Memtable (skiplist in RAM)
  3. Flush to SSTable when memtable full
  4. Background compaction (LSM tree)

Coordinator waits for W acks → returns to client
```

### DynamoDB
```
Client → DynamoDB endpoint → routes to partition leader

Leader:
  1. Writes to its own storage
  2. Replicates to 2 of 3 replicas (synchronous)
  3. Acks client after 2 replicas confirm

Note: DynamoDB is NOT LSM internally (common misconception).
Uses a custom B-tree variant. Internals hidden — you never configure it.
```

---

## Secondary Indexes

This is where they diverge most significantly.

### Cassandra — painful, requires denormalization
```
Cassandra can only query by partition key efficiently.
Any other query = full table scan.

Solution 1: Denormalize — separate table per query pattern
  Table 1: viewing_history_by_user  (PK: user_id)
  Table 2: viewing_history_by_show  (PK: show_id)  ← duped data
  Write to both on every insert — app logic must manage this

Solution 2: Materialized View (Cassandra 3.0+)
  CREATE MATERIALIZED VIEW history_by_show AS
    SELECT * FROM viewing_history
    WHERE show_id IS NOT NULL AND user_id IS NOT NULL
    PRIMARY KEY (show_id, user_id, watched_at);
  -- Cassandra maintains this automatically
  -- But: known bugs, many teams avoid in production

Solution 3: SASI index
  -- Better than regular 2i but avoid for high-cardinality, high-throughput
```

### DynamoDB — GSI is first-class
```
Main table: ViewingHistory
  PK: userId, SK: watchedAt

GSI: ByShowId
  PK: showId, SK: watchedAt   ← completely different key
  Attributes: projected

// Query GSI:
QueryRequest.builder()
    .tableName("ViewingHistory")
    .indexName("ByShowId")
    .keyConditionExpression("showId = :sid")
    .expressionAttributeValues(Map.of(":sid", AttributeValue.fromS("STRANGER_THINGS")))
    .build();

GSI properties:
  Eventually consistent (async from main table)
  Can add after table creation
  Max 20 GSIs per table
  Costs extra WCU (write to main + GSI)

LSI (Local Secondary Index):
  Same partition key, different sort key
  Must be created at table creation time
  Strongly consistent reads possible
  Max 5 per table
```

---

## Scaling

### Cassandra
```
Add node → joins ring → vnodes redistribute tokens
Streaming: existing nodes stream data to new node (takes hours for large clusters)

Linear scaling: 2× nodes = 2× write throughput

Hotspot problem:
  One hot partition key → one node overwhelmed
  Fix: add random suffix → scatter-gather on read
    "user:123:0" ... "user:123:9"
```

### DynamoDB
```
Provisioned mode:
  Set RCU/WCU manually + auto-scaling (min/max, target utilization %)
  1 RCU = 1 strongly consistent 4KB read/sec
  1 WCU = 1 write of 1KB/sec

On-demand mode:
  No capacity planning — pay per request, AWS scales instantly

Adaptive capacity:
  AWS detects hot partitions and gives them more throughput automatically
  But still has per-partition limits — add suffix for extreme hot keys
```

---

## Operations — the biggest practical difference

### Cassandra (you operate it)
```
Daily concerns:
  nodetool repair        ← run weekly or data diverges silently
  nodetool compaction    ← monitor compaction lag
  Disk space             ← SSTables + compaction temp space (2× peak)
  JVM GC tuning          ← GC pauses kill tail latency (G1GC tuning)
  Heap sizing            ← memtable + bloom filters + block cache
  Rolling upgrades       ← one node at a time
  Adding nodes           ← rebalancing takes hours

Team cost: 1+ dedicated SRE with Cassandra internals knowledge
```

### DynamoDB (AWS operates it)
```
Daily concerns: none.

You just:
  Monitor CloudWatch (consumed RCU/WCU, throttled requests)
  Set alarms on throttling
  Adjust capacity if needed

No repair, no compaction, no GC, no disk management.
AWS SLA: 99.999% availability, single-digit ms latency guaranteed.
```

---

## Cost Model

```
Cassandra (self-managed on AWS, 6-node cluster):
  i3.2xlarge × 6 = ~$2,700/mo EC2
  + Engineering time: ~$200K/yr for Cassandra expertise
  Good when: > 10TB, predictable high throughput, team has expertise

DynamoDB (on-demand):
  $1.25 per million writes
  $0.25 per million reads
  $0.25/GB/month storage
  GSI doubles WCU cost (write to main + GSI)
  Good when: variable traffic, < 10TB, no ops team

Break-even:
  < 1TB, spiky traffic     → DynamoDB cheaper
  > 10TB, steady traffic   → Cassandra cheaper (after ops cost)
  Startup / small team     → DynamoDB always
```

---

## Netflix — why they use both

```
Cassandra at Netflix:
  Viewing history    → time-series, write-heavy, user-partitioned, petabyte scale
  User ratings       → simple KV, huge scale
  Billing history    → append-only, ordered by time
  Why: petabyte scale, existing expertise, LOCAL_QUORUM multi-DC control

DynamoDB at Netflix:
  Per-session state  → small items, unpredictable traffic spikes
  Config store       → low volume, strongly consistent reads needed
  Canary metadata    → ChAP-style canary tracking per finding
  Lambda state       → serverless needs zero-ops storage
  Why: zero ops for lower-volume systems, strong consistency when needed
```

---

## Decision Framework

```
Use Cassandra when:
  ✓ Write throughput > 100K writes/sec sustained
  ✓ Data > 5TB
  ✓ Time-series or append-heavy access pattern
  ✓ Need LOCAL_QUORUM multi-DC fine-grained tuning
  ✓ Team has Cassandra expertise
  ✓ Running on-prem or multi-cloud

Use DynamoDB when:
  ✓ AWS-only deployment
  ✓ No dedicated ops/DBA team
  ✓ Variable or unpredictable traffic
  ✓ Need strong consistency on specific reads
  ✓ < 5TB data
  ✓ GSI covers all secondary access patterns
  ✓ Serverless / microservice, zero-ops storage

Neither is right for:
  ✗ Complex joins / analytics    → Redshift, Athena, Spark
  ✗ ACID multi-table transactions → PostgreSQL, Aurora
  ✗ Full-text search             → Elasticsearch / OpenSearch
```

---

## Interview one-liner

> "Both are wide-column stores for write-heavy, partition-key-based workloads at scale. Key difference: Cassandra is leaderless — any node coordinates, tunable W+R quorum per query, you operate the ring. DynamoDB has a leader per partition via Multi-Paxos, two consistency modes, fully AWS-managed. Cassandra gives full control at the cost of ops complexity; DynamoDB gives zero ops at the cost of flexibility and AWS lock-in. Netflix uses Cassandra for petabyte-scale time-series with LOCAL_QUORUM across DCs, and DynamoDB for lower-volume services where zero ops matters more than fine-grained tuning."
