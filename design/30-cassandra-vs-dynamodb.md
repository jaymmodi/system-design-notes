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

## Cassandra is Java — JVM in Operations

Cassandra is written entirely in Java. The JVM is central to how it behaves under load.

### What lives in the JVM heap
```
JVM Heap (default 8GB, recommended 8–16GB, never > 31GB):

  ┌─────────────────────────────────────────┐
  │  Memtable (skiplist)                    │ ← all writes buffer here
  │  Bloom filters (all SSTables)           │ ← one per SSTable, in RAM
  │  Key cache                              │ ← partition key → SSTable offset
  │  Row cache (if enabled)                 │ ← full rows cached
  │  Internal metadata, thread stacks       │
  └─────────────────────────────────────────┘

Off-heap (not in JVM heap):
  OS page cache      ← SSTable data blocks cached by OS
  Netty buffers      ← network I/O
  Compression buffers
```

### Why heap > 31GB is dangerous
```
JVM uses Compressed Object Pointers (CompressedOops) when heap < 32GB:
  Object reference = 4 bytes  ← cache-friendly, fits in register

Heap > 32GB:
  Object reference = 8 bytes  ← full 64-bit pointer
  Bloom filters + key cache grow 2×
  More GC pressure
  Performance DROPS despite more memory

Rule: keep heap ≤ 31GB. Give remaining RAM to OS page cache for SSTables.

  Machine: 128GB RAM
  JVM heap:      16GB   ← memtable, bloom filters, key cache
  OS page cache: 112GB  ← SSTables served at near-RAM speed
```

### GC — the real operational headache

GC stop-the-world pauses = request timeouts:

```
Normal:
  Write → memtable → ack in ~1ms

GC pause (stop-the-world):
  All Cassandra threads frozen
  Pending requests queue up
  Client sees timeout (default 2s)

Long GC pause (> 5s):
  Gossip heartbeats missed
  Other nodes mark this node DOWN
  Hinted handoff kicks in for missed writes
  → looks like node failure — hardware is fine, JVM paused
```

### GC algorithms

```
Old (pre-4.0):   CMS — low pause but fragmentation → eventual full GC
Current (4.0+):  G1GC (default)
  -Xms16G -Xmx16G              ← same min/max prevents resize pauses
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=300     ← target max pause 300ms

Experimental:    ZGC / Shenandoah
  Sub-millisecond pauses, concurrent collection
  Netflix and large operators test these for p99 improvement
```

### What causes GC pressure
```
1. Large memtables before flush
   Fix: tune memtable_heap_space, flush more frequently

2. Tombstone reads
   Reading partition with many deletes → loads tombstones into heap
   Fix: TTL-based expiry, limit tombstone_failure_threshold

3. Wide partition full scan
   SELECT * on millions of rows → loads all into heap
   Fix: always use LIMIT, paginate results

4. Too many SSTables
   More SSTables = more bloom filters in heap
   Fix: aggressive compaction, tune bloom_filter_fp_chance
```

### Thread model — SEDA architecture
```
Cassandra uses staged event-driven architecture:

  Netty I/O threads (non-blocking, off-heap)
        ↓
  Stage thread pools:
    Native Transport   ← receive client requests
    Mutation Stage     ← handle writes
    Read Stage         ← handle reads
    Compaction         ← background SSTable merging
    Memtable Flush     ← flush to SSTable
    Gossip Stage       ← cluster heartbeats
    HintedHandoff      ← replay hints to recovered nodes

Each stage has its own thread pool + queue.
Queue fills up → TooManyRequestsException
```

Monitor thread pool health:
```bash
nodetool tpstats

# Pool Name          Active  Pending  Completed  Blocked
# MutationStage         4        0    1234567       0   ← healthy
# ReadStage             2      150    9876543       0   ← 150 pending, watch this
# CompactionExecutor    1        8      45678       0   ← 8 pending compactions
```

### Diagnosing high p99 latency (all JVM-related)
```
"Node shows high p99 but CPU is fine"

Check in order:
  1. GC pause        → nodetool gcstats, GC logs
  2. Compaction lag  → too many SSTables → nodetool compactionstats
  3. Thread backpressure → nodetool tpstats, Pending > 0
  4. Tombstone storm → slow query log, check data model

Fix:
  GC pause    → tune G1GC, reduce heap pressure (smaller memtable)
  Compaction  → increase compaction throughput, switch strategy
  Thread pool → increase pool size carefully, or scale out
  Tombstones  → add LIMIT, fix data model, lower gc_grace_seconds
```

### DynamoDB has none of these concerns
```
No JVM. No GC. No heap tuning. No thread pool monitoring.
AWS handles all of it internally.

This is the single biggest operational difference:
  Cassandra: p99 spikes → is it GC? compaction? tombstones? thread pool?
  DynamoDB:  p99 spikes → is it throttling? hot partition? that's it.
```

---

## Interview one-liner

> "Both are wide-column stores for write-heavy, partition-key-based workloads at scale. Key difference: Cassandra is leaderless — any node coordinates, tunable W+R quorum per query, you operate the ring. DynamoDB has a leader per partition via Multi-Paxos, two consistency modes, fully AWS-managed. Cassandra gives full control at the cost of ops complexity — including JVM heap tuning, GC pause management, and compaction monitoring; DynamoDB gives zero ops at the cost of flexibility and AWS lock-in. Netflix uses Cassandra for petabyte-scale time-series with LOCAL_QUORUM across DCs, and DynamoDB for lower-volume services where zero ops matters more than fine-grained tuning."
