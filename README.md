# System Design Interview Prep — Netflix L5

> Target: Distributed Systems Engineer, Data Platform — Apr 8–9, 2026

---

## Folder Structure

```
system-design-notes/
├── README.md                         — this file
├── STUDY-PLAN.md                     — 18-day day-by-day sprint
├── CONCURRENCY-CODING-PRACTICE.md    — Java implementations to write cold
├── REVERSE-DESIGN-NARRATIVE.md       — scripted GuardDuty story for Infrastructure-DSI
├── RESUME-ANALYSIS.md                — strengths, gaps, STAR stories, positioning
├── NETFLIX-INTERVIEW-GUIDE.md        — process, rounds, what each round tests
├── NETFLIX-JOB-ANALYSIS.md           — JD breakdown, team analysis, questions to ask
├── NETFLIX-POPULAR-QUESTIONS.md      — sourced questions from Glassdoor/Blind/LeetCode
│
├── design/                           — distributed systems & architecture topics
│   ├── 01-scalability-fundamentals.md
│   ├── 02-cap-theorem.md
│   ├── 03-consistent-hashing.md
│   ├── 04-caching.md
│   ├── 05-load-balancing.md
│   ├── 06-database-sharding.md
│   ├── 07-replication.md
│   ├── 10-redis.md
│   ├── 11-message-queues.md
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
│   ├── 25-apache-iceberg-data-lake.md
│   ├── 26-netflix-data-platform.md
│   ├── 27-infrastructure-k8s.md
│   ├── 28-java-sizes-boe.md          — primitive/object/collection sizes for back-of-envelope
│   └── 29-system-design-questions.md — top interview questions with thought process + solutions
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
    ├── 01-concurrency.md             — CountDownLatch, CyclicBarrier, Semaphore, ExecutorService, Virtual Threads, CompletableFuture
    ├── 02-message-brokers.md         — Redis Streams vs Kafka vs Kinesis vs SQS: mental models, when to use which, Netflix angle
    └── 03-redis-commands-cheatsheet.md — all Redis commands by type: strings, hashes, sets, ZSETs, streams, bitmaps, HLL, Lua
```

---

## Start Here

| What you need | File |
|---------------|------|
| What to study each day | `STUDY-PLAN.md` |
| Concurrency code to practice | `CONCURRENCY-CODING-PRACTICE.md` |
| Your GuardDuty story (Infrastructure-DSI) | `REVERSE-DESIGN-NARRATIVE.md` |
| STAR stories + positioning statement | `RESUME-ANALYSIS.md` |
| Questions to ask Netflix | `NETFLIX-JOB-ANALYSIS.md` |

---

## Files by Interview Round

| Round | Primary Files |
|-------|--------------|
| **Problem Solving** (Apr 8, 9am) | `CONCURRENCY-CODING-PRACTICE.md` |
| **Infrastructure-DSI** (Apr 8, 10:30am) | `REVERSE-DESIGN-NARRATIVE.md`, `RESUME-ANALYSIS.md` |
| **Distributed Systems Design** (Apr 9, 9am) | `design/02-cap-theorem.md`, `design/03-consistent-hashing.md`, `design/07-replication.md`, `design/23-stream-processing.md`, `design/25-apache-iceberg-data-lake.md`, `design/26-netflix-data-platform.md` |
| **Operations & Reliability** (Apr 9, 11am) | `design/24-observability-operations.md`, `design/17-circuit-breaker.md`, `RESUME-ANALYSIS.md` |

---

## Design Topics — Priority for Your Role

| Priority | File | Why |
|----------|------|-----|
| 🔴 Must | `design/02-cap-theorem.md` | Every design question probes this |
| 🔴 Must | `design/03-consistent-hashing.md` | Core to KV store, caching, Kafka |
| 🔴 Must | `design/23-stream-processing.md` | Flink, Kafka, exactly-once |
| 🔴 Must | `design/25-apache-iceberg-data-lake.md` | Netflix's table format |
| 🔴 Must | `design/26-netflix-data-platform.md` | Netflix vocabulary |
| 🟡 High | `design/04-caching.md` + `design/10-redis.md` | EVCache / caching team |
| 🟡 High | `design/22-leases.md` | Partition ownership, fencing tokens |
| 🟡 High | `design/15-quorum-and-consensus.md` | Replication, consistency models |
| 🟡 High | `design/20-distributed-transactions.md` | Saga, 2PC, workflow orchestration |
| 🟢 Medium | `design/27-infrastructure-k8s.md` | ECS → K8s bridge |
| 🟢 Medium | `design/07-replication.md` | Leader-follower vs leaderless |

---

## Data Structures — Bonus Depth (Not Primary)

Know these well enough to **explain and compare** — you won't implement them from scratch:

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
