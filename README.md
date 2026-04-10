# System Design Notes

Personal reference notes on distributed systems, data structures, and Java internals.

---

## Folder Structure

```
system-design-notes/
├── README.md
│
├── design/                           — distributed systems & architecture topics
│   ├── 01-scalability-fundamentals.md
│   ├── 02-cap-theorem.md
│   ├── 03-consistent-hashing.md
│   ├── 04-caching.md
│   ├── 05-load-balancing.md
│   ├── 06-database-sharding.md
│   ├── 07-replication.md
│   ├── 10-redis.md                              — Redis deep dive: data structures, patterns, persistence, cluster + commands cheatsheet
│   ├── 11-message-queues.md                     — Redis Streams vs Kafka vs Kinesis vs SQS vs SNS: mental models, deep dives, Java examples, fan-out patterns
│   ├── 12-graph-databases.md
│   ├── 13-search-engines.md
│   ├── 14-rate-limiting.md
│   ├── 15-quorum-and-consensus.md
│   ├── 16-leader-election-gossip.md
│   ├── 17-circuit-breaker.md
│   ├── 18-api-gateway-service-discovery.md
│   ├── 19-sql-vs-nosql-indexes.md
│   ├── 20-distributed-transactions.md
│   ├── 21-back-of-envelope-cdn.md
│   ├── 22-leases.md
│   ├── 23-stream-processing.md
│   ├── 24-observability-operations.md
│   ├── 25-apache-iceberg-delta-lake.md
│   ├── 26-netflix-data-platform.md
│   ├── 27-infrastructure-k8s.md
│   ├── 28-java-sizes-boe.md                    — primitive/object/collection sizes for back-of-envelope
│   ├── 29-system-design-questions.md            — common design questions with thought process + solutions
│   ├── 30-cassandra-vs-dynamodb.md              — deep dive: data model, replication, consistency, ops, cost
│   ├── 31-dynamodb-key-design-best-practices.md — AWS docs: partition keys, sort keys, GSI/LSI patterns
│   └── 32-dynamodb-transactions.md              — TransactWriteItems/GetItems, serializability, linearizability, isolation levels, Java examples
│
├── data-structures/                  — internals: how storage engines work
│   ├── 08-bloom-filters.md
│   ├── 09-probabilistic-data-structures.md
│   ├── 28-skip-lists.md
│   ├── 29-lsm-trees.md
│   ├── 30-b-trees.md
│   └── 31-red-black-trees.md
│
└── java/                             — Java language deep dives
    └── 01-concurrency.md             — CountDownLatch, CyclicBarrier, Semaphore, ExecutorService, Virtual Threads, CompletableFuture
```

---

## Interview Delivery Framework

| File | Purpose |
|------|---------|
| `SYSTEM-DESIGN-DELIVERY.md` | Step-by-step flow diagram for every system design round (Requirements → Entities → API → High-Level → Deep Dive) |
| `SYSTEM-DESIGN-CORE-CONCEPTS.md` | Core concepts cheat sheet: networking, caching, sharding, CAP, consistent hashing, numbers to know |
| `NETFLIX-INTERVIEW-GUIDE.md` | Netflix-specific preparation guide |
| `NETFLIX-POPULAR-QUESTIONS.md` | Common Netflix system design questions |
| `STUDY-PLAN.md` | Study schedule and priorities |

---

## Design Topics

| Priority | File | Topic |
|----------|------|-------|
| Core | `design/33-dynamo-dynamodb-dsql.md` | Dynamo vs DynamoDB vs Aurora DSQL — architecture, consistency, multi-region, DSQL internals |
| Core | `design/02-cap-theorem.md` | Consistency vs availability tradeoffs |
| Core | `design/03-consistent-hashing.md` | Sharding, KV stores, Kafka partitioning |
| Core | `design/23-stream-processing.md` | Kafka, Flink, exactly-once semantics |
| Core | `design/04-caching.md` | Cache strategies, Redis vs Memcached, EVCache |
| Core | `design/15-quorum-and-consensus.md` | Replication, consistency models |
| Core | `design/20-distributed-transactions.md` | Saga, 2PC, workflow orchestration |
| Deep | `design/25-apache-iceberg-delta-lake.md` | Table format, ACID on object storage |
| Deep | `design/26-netflix-data-platform.md` | Kafka, Flink, Iceberg, EVCache, Maestro |
| Deep | `design/22-leases.md` | Partition ownership, fencing tokens |
| Supporting | `design/07-replication.md` | Leader-follower vs leaderless |
| Supporting | `design/27-infrastructure-k8s.md` | Kubernetes, service mesh |

---

## Data Structures

Know well enough to explain and compare:

| File | When It Comes Up |
|------|-----------------|
| `data-structures/29-lsm-trees.md` | RocksDB, Cassandra, DynamoDB internals |
| `data-structures/30-b-trees.md` | Database index internals |
| `data-structures/28-skip-lists.md` | Redis ZSET, ConcurrentSkipListMap, MemTable |
| `data-structures/31-red-black-trees.md` | TreeMap, why skiplist wins for concurrency |
| `data-structures/08-bloom-filters.md` | SSTable reads, membership checks |
| `data-structures/09-probabilistic-data-structures.md` | HyperLogLog, Count-Min Sketch |

---

## Quick Reference — When to Use What

| Need | Use |
|------|-----|
| Fast reads, reduce DB load | Cache (Redis / EVCache) |
| Distribute load across nodes | Consistent Hashing |
| Approximate membership test | Bloom Filter |
| Count unique items (approx) | HyperLogLog |
| Sorted map, concurrent | ConcurrentSkipListMap |
| Write-heavy storage engine | LSM Tree (RocksDB, Cassandra) |
| Read-heavy storage engine | B+ Tree (PostgreSQL, MySQL) |
| Async decoupling at scale | Kafka |
| Stateful stream processing | Flink (keyed state + checkpoints) |
| Exactly-once semantics | Kafka transactions + Flink checkpoints |
| ACID on object storage | Apache Iceberg |
| Distributed coordination | ZooKeeper / etcd |
| Handle partial failures | Circuit Breaker (Hystrix) |
| Partition ownership | Leases + fencing tokens |
| Producer-consumer backpressure | ArrayBlockingQueue + Virtual Threads |
