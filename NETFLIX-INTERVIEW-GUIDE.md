# Netflix Data Platform — L5 Distributed Systems Engineer
## Complete Interview Guide

---

## Your 4-Hour Onsite Structure

| Round | Duration | Focus | Your Notes |
|-------|----------|-------|------------|
| **Distributed System Design** | 60 min | Design a real-world data platform system | 22-stream-processing, 25-iceberg, 26-netflix-platform |
| **Scaling & Operations** | 60 min | Observability, reliability, incident response | 24-observability-operations |
| **Coding (DSA)** | 60 min | Data structures, algorithms, Java/Python | See DSA section below |
| **Infrastructure** | 60 min | Containers, K8s, compute for analytics | 27-infrastructure-k8s |

---

## GAP ANALYSIS: Your Current Notes vs Netflix Requirements

### ✅ Already Well Covered
- CAP Theorem → `02-cap-theorem.md`
- Consistent Hashing → `03-consistent-hashing.md`
- Caching → `04-caching.md`
- Load Balancing → `05-load-balancing.md`
- Database Sharding → `06-database-sharding.md`
- Replication → `07-replication.md`
- Bloom Filters → `08-bloom-filters.md`
- Redis → `10-redis.md`
- Message Queues / Kafka → `11-message-queues.md`
- Leader Election + Gossip → `16-leader-election-gossip.md`
- Circuit Breaker → `17-circuit-breaker.md`
- Quorum + Raft → `15-quorum-and-consensus.md`
- Rate Limiting → `14-rate-limiting.md`
- Distributed Transactions / Saga → `20-distributed-transactions.md`

### ⚠️ Gaps — New Files Created
| Missing Topic | File | Why Critical |
|---------------|------|--------------|
| **Distributed Leases** | `22-leases.md` | Explicitly in Netflix prep guide |
| **Stream Processing (Flink/Spark)** | `23-stream-processing.md` | Core of Netflix Data Platform — 20K Flink jobs |
| **Observability & Operations** | `24-observability-operations.md` | Dedicated interview round |
| **Apache Iceberg & Data Lake** | `25-apache-iceberg-data-lake.md` | Netflix's primary table format |
| **Netflix Platform Deep Dive** | `26-netflix-data-platform.md` | Keystone, Maestro, Mantis, EVCache, Atlas |
| **Infrastructure (K8s/Containers)** | `27-infrastructure-k8s.md` | Dedicated infrastructure round |

---

## Netflix-Specific Technology Stack

```
Event Ingestion:    Kafka (2T+ events/day, 1M msgs/sec per topic)
Stream Processing:  Flink (20K+ concurrent jobs), Mantis (ops telemetry)
Batch Processing:   Spark + Maestro orchestration
Storage:            Apache Iceberg (primary table format)
OLAP:               Druid (real-time analytics)
Cache:              EVCache (Memcached-based, 99.9%+ hit rate)
Metrics:            Atlas (time-series)
Tracing:            Inca (message loss detection)
Orchestration:      Maestro (Workflow-as-a-Service, open-source)
Platform:           Keystone (the routing layer: Kafka → Flink → Iceberg/Druid)
Service Discovery:  Eureka
Circuit Breaking:   Hystrix (legacy) → Resilience4j
Chaos:              Chaos Monkey / ChAP
```

---

## Most Likely System Design Questions

### Tier 1 — Almost Certain (Data Platform themed)
1. **Design a streaming data pipeline** (Kafka → Flink → Iceberg/Druid)
   - Handle late-arriving events, exactly-once semantics, backfill
2. **Design a real-time analytics system** — "Design Netflix's Atlas/Druid"
   - Sub-second query latency over billions of events
3. **Design Netflix Keystone** — "Route 1M events/sec from Kafka to multiple sinks"
   - Topic routing, schema registry, fan-out, exactly-once
4. **Design a distributed job scheduler** — "Design Netflix Maestro"
   - DAG execution, retry, backfill, SLOs
5. **Design incremental data processing** — "Process only changed rows from 1PB table"
   - This IS the Iceberg/Maestro IPS pattern Netflix just published

### Tier 2 — Likely
6. **Design a distributed metrics system** (Atlas-like)
7. **Design message loss detection** in a streaming pipeline (Inca)
8. **Design a multi-region active-active data pipeline**
9. **Design EVCache** — multi-region distributed cache with 99.9% hit rate
10. **Design a real-time recommendation system** at 300M user scale

---

## Operations & Reliability Round — Key Topics

This is a **distinct** interview at Netflix. They want to see:

1. **The 3 Pillars of Observability**: Metrics, Logs, Traces
2. **SLIs / SLOs / SLAs** — how you define and track them
3. **Incident Response** — detection → triage → mitigation → postmortem
4. **Chaos Engineering** — Chaos Monkey, ChAP, fault injection patterns
5. **Data pipeline reliability** — late data, message loss, reprocessing
6. **Runbooks and operational maturity**
7. **Capacity planning** — how to size Flink jobs, Kafka partitions

Key question pattern:
> "How do you detect and handle message loss in a distributed stream?"

Answer framework: Inca pattern — sequence numbers on producer side, gap detection on consumer side, alerting, replay from Kafka offset.

---

## Coding Round — What to Expect

Netflix coding rounds are **not LeetCode-style**. They're production-flavored:

- Parsing streaming metrics (JSON log processing)
- Implementing rate limiting in Java
- Multithreaded design: implement a concurrent cache/client library in Java
- Python: process overlapping time intervals (e.g., find non-overlapping view-time tuples)
- SQL: window functions, percentile queries, trending content

**Brush up on:**
- Java: `ConcurrentHashMap`, `AtomicInteger`, `ReentrantLock`, `CompletableFuture`
- Java: Producer-consumer with `BlockingQueue`
- Python: sorting intervals, merge intervals, sliding window
- SQL: `RANK()`, `DENSE_RANK()`, `LAG/LEAD`, `PARTITION BY`

```java
// Pattern Netflix loves: thread-safe cache with expiry
public class BoundedExpiryCache<K, V> {
    private final Map<K, ExpiryEntry<V>> store = new ConcurrentHashMap<>();
    private final int maxSize;
    private final long ttlMs;

    public void put(K key, V value) {
        if (store.size() >= maxSize) evictOldest();
        store.put(key, new ExpiryEntry<>(value, System.currentTimeMillis() + ttlMs));
    }

    public Optional<V> get(K key) {
        ExpiryEntry<V> entry = store.get(key);
        if (entry == null || entry.isExpired()) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }
}
```

---

## Infrastructure Round — Key Areas

1. **Containers and Docker**: images, layers, networking, volumes
2. **Kubernetes**: Pods, Deployments, Services, resource requests/limits
3. **Kubernetes for data workloads**: Flink on K8s, Spark on K8s
4. **Identity and Security**: mTLS, SPIFFE/SPIRE, service accounts, RBAC
5. **Compute for analytics**: spot instances, autoscaling, gang scheduling
6. **Storage**: persistent volumes, object storage (S3), block storage trade-offs

---

## Interview Strategy — What Netflix Evaluates

### The 4 Dimensions (from their prep guide)
1. **Communication** — drive the conversation, explain trade-offs clearly
2. **Scalability** — how does your design handle 10× growth?
3. **High Availability & Resilience** — how does it handle node/network failures?
4. **Correctness & Consistency** — what guarantees does your design provide?

### How to Structure Your Design Answer
```
1. CLARIFY (3 min)
   - "Let me confirm: we're designing X for Y scale, with Z consistency requirement?"
   - Ask: read/write ratio, consistency needs, latency requirements

2. HIGH LEVEL (5 min)
   - Draw the boxes: producers, broker, processors, storage, consumers
   - State your key choices upfront: "I'll use Kafka for durability, Flink for processing,
     Iceberg for storage because..."

3. DEEP DIVE (30 min)
   - Component by component
   - Always mention: fault tolerance, consistency, ordering guarantees
   - Use Netflix vocabulary: "This is similar to how Keystone routes events..."

4. FAULT SCENARIOS (10 min)
   - What if a Flink job crashes mid-processing?
   - What if Kafka broker goes down?
   - What if we get 10× normal traffic?
   - What if a message is processed twice?

5. WRAP UP (5 min)
   - Bottlenecks, limitations, what you'd improve
   - Monitoring: "I'd track consumer lag, processing latency p99, error rate"
```

---

## Critical Vocabulary for Netflix Interview

Say these words — they signal you know the space:

| Term | Context |
|------|---------|
| **Consumer lag** | Kafka: how far behind a consumer group is |
| **Watermark** | Flink: how late can data arrive before we close a window |
| **Exactly-once semantics** | Kafka transactions + Flink checkpoints |
| **Incremental processing** | Only process changed data (Iceberg IPS pattern) |
| **Backfill** | Reprocess historical data through a new pipeline |
| **Snapshot isolation** | Iceberg: read consistent snapshot, not affected by concurrent writes |
| **Manifest file** | Iceberg: catalog of data files in a snapshot |
| **Compaction** | Merge small files into larger ones (Iceberg, Flink) |
| **Checkpoint** | Flink: consistent snapshot of job state for fault tolerance |
| **Savepoint** | Flink: manual checkpoint for upgrades/migrations |
| **Partition pruning** | Only scan relevant partitions (avoids full table scan) |
| **Dead letter queue** | Where failed messages go for later analysis |
| **Schema registry** | Centralized schema store, Avro/Protobuf enforcement |
| **Fan-out** | One event → written to N destinations |
| **Materialized view** | Precomputed query result updated incrementally |

---

## 30-Day Final Prep Plan

### Week 1 — Read Netflix TechBlog (Most Important)
- [ ] Read: Data Mesh platform article
- [ ] Read: Iceberg + Maestro incremental processing
- [ ] Read: Keystone pipeline evolution
- [ ] Read: Mantis stream processing
- [ ] Read: Inca message loss detection
- [ ] Read: Real-time distributed graph (Oct 2025)

### Week 2 — Fill Knowledge Gaps
- [ ] Study: `22-leases.md`
- [ ] Study: `23-stream-processing.md` (Flink deep dive)
- [ ] Study: `24-observability-operations.md`
- [ ] Study: `25-apache-iceberg-data-lake.md`
- [ ] Study: `26-netflix-data-platform.md`
- [ ] Study: `27-infrastructure-k8s.md`

### Week 3 — Practice Design Questions
- [ ] Design: Kafka → Flink → Iceberg pipeline (45 min whiteboard)
- [ ] Design: Distributed metrics collection system
- [ ] Design: Job scheduler with backfill support
- [ ] Design: Multi-region cache with 99.9% hit rate

### Week 4 — Mock Interviews + Behavioral
- [ ] 2 mock system design interviews (use a friend or interviewing.io)
- [ ] Write out 8 STAR stories covering: impact, conflict, failure, cross-team
- [ ] Practice explaining trade-offs out loud

---

## Pre-Interview Checklist

### Tech Setup (Do 24 hours before)
- [ ] Test video/audio/screen share on the meeting platform
- [ ] Test Google Docs or shared whiteboard tool
- [ ] Stable internet — if on WiFi, sit close to router
- [ ] Second internet source ready (phone hotspot)
- [ ] Quiet room, good lighting
- [ ] Dual monitor if possible: interview video on one, whiteboard on other
- [ ] Water, snacks nearby (4-hour interview)
- [ ] IDE open with Java/Python for coding round

### Mental Prep
- [ ] Sleep 8 hours the night before
- [ ] Light exercise morning of
- [ ] Review key vocabulary (table above)
- [ ] Have your STAR stories fresh in mind
- [ ] Read Netflix culture memo (https://jobs.netflix.com/culture)

---

## Netflix Culture — What Behavioral Interviewers Want

Netflix is NOT a typical company. Their culture memo matters. Key phrases to embody:
- **"Judgment"**: Make good decisions with incomplete information
- **"Communication"**: Share information openly, even when uncomfortable
- **"Impact"**: Accomplish amazing things with a small team
- **"Freedom and Responsibility"**: Act in Netflix's best interest without being told

Behavioral question answers should show: you made hard calls, owned failures, influenced without authority, worked across teams.

---

## Resources to Read Before Interview

1. Netflix TechBlog: https://netflixtechblog.com
2. Netflix Maestro (open source): https://github.com/Netflix/maestro
3. Netflix Culture Memo: https://jobs.netflix.com/culture
4. System Design Primer: https://github.com/donnemartin/system-design-primer
5. Designing Data-Intensive Applications (Kleppmann) — read Ch. 8-12
