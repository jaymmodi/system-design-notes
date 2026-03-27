# Reverse Design Narrative — Infrastructure-DSI Round

**Round**: Infrastructure-DSI, Wednesday Apr 8, 10:30 AM PT
**What they'll ask**: "Walk me through the most complex distributed system you've built."
**Target length**: 8–10 minutes talking, then field questions for 40+ minutes

---

## The Opening (Memorize This — Say It in 60 Seconds)

> "The most complex system I built is the GuardDuty Runtime Detection Pipeline at AWS. At its peak it processed **200 billion events per hour** — more than Netflix's entire data platform. It's a stateful, real-time stream processing system that ingests container-level telemetry from customer workloads running on EKS, ECS-Fargate, ECS-EC2, and bare EC2, builds per-container behavioral profiles in DynamoDB, and emits security signals downstream.
>
> I designed and owned the core processing architecture — what we called the **Lookup Processor** — and led the work that got us from a fragile prototype to a production system handling customers at AWS scale. Let me walk you through the architecture, the key design decisions I made, and what I'd do differently at Netflix's stack."

---

## The Architecture Walk (3–4 Minutes)

Draw or describe this flow. Be specific with numbers.

```
Container runtime agents (EKS nodes, ECS tasks, EC2)
  │
  ▼
Kinesis Data Streams
  • ~N shards per processing cell
  • 1MB/s per shard ingest capacity
  • 7-day retention for replay
  │
  ▼
Spark Streaming (Lookup Processor)
  • Micro-batch: 1–5 second windows
  • Cell-based architecture: one Spark app per detection type
  • Stateful join: incoming event ↔ DynamoDB profile
  • ECS/Fargate deployment — one task per Kinesis shard
  │
  ├──► DynamoDB (Profile State)
  │     • Per-container behavioral baseline
  │     • UpdateItem with conditional writes (optimistic concurrency)
  │     • Async batched I/O — 10× latency reduction
  │
  └──► Finding Emission
        → Downstream consumers (SNS, SQS, customer delivery)
```

**Key numbers to say out loud**:
- 200B+ events/hour (sustained, not peak)
- Sub-second detection latency end-to-end
- 10× latency reduction after async DynamoDB migration
- 80% compute cost reduction from custom autoscaling
- $250K/year storage savings from Java DSL → SQL migration

---

## The 5 Design Decisions (Each 1–2 Minutes)

### Decision 1: Cell-Based Processor Architecture

**What I did**: Each detection type runs as an independent Spark app with its own Kinesis stream, its own DynamoDB state namespace, and its own ECS task fleet.

**Why**: "When one detection type has a bug or a traffic spike, it fails in isolation. Other detectors keep running. This is identical to the philosophy behind Netflix Keystone's 1:1 Kafka-topic-to-Flink-job model."

**Trade-off I made**: "More operational overhead — N deployments instead of one. But at 200B events/hour, shared failure domains are unacceptable. The blast radius of a single bad deploy needed to be bounded."

---

### Decision 2: Replaced Spark Checkpointing with DynamoDB-Backed State

**What I did**: Instead of using Spark's built-in checkpoint state, I externalized all stateful data to DynamoDB using `UpdateItem` with conditional writes.

**Why**: "Spark's checkpoint-based state had a known failure mode: if a checkpoint write and the downstream Kinesis commit happened in separate operations, a crash between them caused data loss or double-processing. DynamoDB gave us durable, independently scalable state with strong consistency guarantees."

**The mechanism**: "Each Spark executor calls DynamoDB `UpdateItem` with a `ConditionExpression` on a version attribute — this is optimistic concurrency control. If two executors race on the same container ID, one succeeds and the other retries. No locks needed."

**Trade-off**: "We added a network hop to DynamoDB for every event. Mitigated this with async batched I/O — below."

---

### Decision 3: Async Batched DynamoDB I/O → 10× Latency Reduction

**What I did**: Migrated from synchronous `getItem`/`updateItem` per event to an async pipeline: buffer events, issue batched `batchWriteItem`, process results asynchronously using Java's `CompletableFuture`.

**Why**: "Synchronous DynamoDB calls meant the Spark executor was blocked on I/O during computation. With async batching, we pipeline I/O and CPU — executor processes the next batch while waiting for DynamoDB response."

**Result**: 10× end-to-end latency reduction. Fleet size cut by ~50% — fewer executors needed to maintain the same throughput.

**The pattern**: "This is identical to what Netflix does with EVCache — async, batched, non-blocking KV access patterns."

---

### Decision 4: Custom Autoscaling (CPU + Backlog + Empty Receives)

**What I did**: Replaced AWS default CPU-based autoscaling with a three-signal policy:
1. **CPU utilization** — baseline signal
2. **Kinesis backlog depth** — equivalent to Kafka consumer lag
3. **Empty receive rate** — how often Kinesis polls return 0 records (signals under-load)

**Why**: "CPU alone reacts too slowly and scales too coarsely for event streams. A sudden burst fills the Kinesis backlog before CPU climbs — by the time CPU triggers scale-out, we're already behind. Backlog depth is a leading indicator."

**Result**: 80% compute cost reduction while maintaining SLA. The empty-receive signal prevents over-provisioning during off-peak.

**Netflix angle**: "This maps directly to Kafka consumer lag-based autoscaling, which Flink on K8s does natively."

---

### Decision 5: Java DSL → SQL-Based Detection (Finding Suppression + Deduplication)

**What I did**: Migrated detection rules from hand-written Java DSL code (opaque, engineer-only) to SQL-like declarative rules that analysts could read and modify.

**Why**: "The Java DSL was a black box — every false positive required a full code change, review, and deploy cycle. SQL rules are auditable, testable in isolation, and can be modified by security analysts without engineering involvement."

**Result**: Reduced false positives measurably, eliminated $250K/year of storage costs from redundant finding storage, and reduced deployment cycle from days to hours.

---

## What I'd Do Differently at Netflix (The "Bridge" Answer)

This is the most important part — say it confidently and specifically.

> "If I were building this today for Netflix's stack, I'd make four changes:
>
> **1. Flink instead of Spark Streaming** — Flink's native keyed state eliminates the DynamoDB dependency for hot operational state. Instead of async DynamoDB calls per event, state lives in Flink's managed RocksDB backend, which is local and fast. DynamoDB stays for cold/long-term profile storage only.
>
> **2. Kafka instead of Kinesis** — Kafka's consumer group protocol is more flexible than KCL's lease-based assignment. More importantly, Kafka transactions give true exactly-once semantics without needing conditional writes for idempotency — the pipeline becomes simpler.
>
> **3. Apache Iceberg instead of Delta Lake** — For the historical detection signal store, Iceberg's partition evolution and the Incremental Processing Strategy (IPS) pattern would dramatically reduce backfill cost. When we update a detection rule, we only reprocess changed data, not the full history.
>
> **4. Kubernetes instead of ECS/Fargate** — Better bin-packing across mixed Spark and Flink workloads. The Flink Kubernetes Operator handles job lifecycle management that we were doing manually with ECS task definitions."

---

## Anticipated Deep-Dive Questions + Answers

### "Why not just fix Spark's checkpointing instead of DynamoDB?"

> "Spark checkpointing has two phases: write state to S3, then commit the Kinesis offset. If the process crashes between them, you either replay events with stale state (correctness issue) or lose the state update (data loss). The only fix within Spark is two-phase commit across two external systems — which is complex and slow. Externalizing state to DynamoDB with conditional writes moved the atomicity boundary entirely inside DynamoDB, which handles it natively."

### "Walk me through the math — how many shards, how many executors?"

> "At 200B events/hour: that's ~55M events/second. Each Kinesis shard handles 1MB/s or 1,000 records/sec. Assuming average event size of ~1KB, we needed at minimum 55,000 shard-equivalents of capacity. We used a cell-based model so each detection type had its own shard allocation sized to its event volume — some cells had hundreds of shards, others tens. On the Spark side, one executor per shard was the baseline, with our custom autoscaler adjusting from 0.5× to 2× depending on backlog."

### "How did you validate the autoscaling policy wasn't causing SLA violations?"

> "Shadow deployment first — ran the new policy in read-only mode alongside the existing policy, comparing scale decisions. Then A/B tested on a low-traffic cell with strict SLA monitoring: consumer lag threshold as a hard gate (scale-down blocked if lag > N seconds). Only promoted to full fleet after 2 weeks of clean metrics."

### "How did you prove zero data loss?"

> "Two mechanisms: First, Kinesis sequence numbers are monotonically increasing within a shard — we tracked the highest processed sequence number per shard in DynamoDB and ran a gap detection job that alerted on any skipped ranges. Second, end-to-end event counters: agent emits a count metric, pipeline emits a processed count metric — any sustained divergence triggered a PagerDuty alert."

### "What was the blast radius of a cell failure?"

> "Zero blast radius to other cells — completely isolated. Within a failed cell: events accumulated in Kinesis (7-day retention), processing paused, but no data was lost. Recovery was automatic — Spark job restarted from the last committed Kinesis offset in DynamoDB. Worst case: detection latency for that cell increased by the restart time (~30 seconds), not data loss."

### "How did you handle hot partitions in DynamoDB?"

> "Container IDs are UUIDs — naturally distributed. We avoided user-ID-based keys which would have hot spots. For write-heavy containers (high-frequency syscalls), we used a write sharding pattern: suffix the key with `containerID#0` through `containerID#N`, then read with parallel gets and merge."

---

## Closing the Narrative

End with this when they say "what questions do you have for us?":

> "One thing I'm most curious about — in the Infrastructure-DSI team specifically, what does the boundary look like between your team and the Flink/Kafka platform teams? I ask because the hardest part of building at this scale isn't the computation layer, it's the operational handshake between storage, compute, and orchestration. I want to understand where your team owns that boundary."

This signals systems thinking and cross-team awareness — both Netflix L5 expectations.
