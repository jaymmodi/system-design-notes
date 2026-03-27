# Resume Analysis — Jay Modi vs Netflix L5 Data Platform

---

## Verdict: Strong Fit, Needs Reframing

Your background is genuinely strong for this role. The scale (200B events/hour), the stateful stream processing, the DynamoDB expertise, and the cost-optimization track record all speak directly to what Netflix Data Platform does. But your resume tells a **security story** — you need to retell it as a **data infrastructure story**.

---

## Your Strengths (Directly Map to Netflix)

| Your Experience | Netflix Equivalent | Where It Shows Up |
|----------------|-------------------|-------------------|
| 200B+ events/hour, Spark Streaming | Flink jobs, Keystone pipeline | Same problem, different tools |
| DynamoDB stateful processing | Online DataStores — KV team | Direct domain match |
| Kinesis event streaming | Kafka | Same concept, different vendor |
| Delta Lake | Apache Iceberg | Virtually identical (ACID table format) |
| Cell-based modular processor design | Keystone's 1:1 topic-to-Flink-job | Same isolation philosophy |
| Custom autoscaling (CPU, backlog, empty receives) | Kafka consumer lag-based autoscaling | Exact same concept |
| Eliminated Spark checkpointing data loss | Flink exactly-once semantics | Same problem solved |
| 10× latency reduction via async DynamoDB | EVCache / low-latency KV stores | Same optimization mindset |
| $250K/year storage removal | Netflix cares deeply about cost at scale | Maps directly |
| ECS/Fargate at scale | K8s (same container orchestration family) | Gap: need to study K8s |

**The 200B events/hour number is your ace card.** Netflix processes ~83B events/hour (2T/day). Your system processed MORE. Lead with this.

---

## Gaps to Address Before the Interview

### 1. Kinesis → Kafka (Easy Bridge)
You know Kinesis deeply. Kafka is essentially the same mental model.

```
Kinesis               Kafka equivalent
---------             ----------------
Shard                 Partition
Sequence number       Offset
Consumer library      Consumer group
KCL (Kinesis Client   Consumer group coordinator
  Library) lease      + partition assignment
Shard iterator        Offset seek
Enhanced fan-out      Multiple consumer groups
Provisioned shards    Partition count
```

**What to say in interview**: "I've worked extensively with Kinesis at scale — Kafka operates on the same partitioned-log model. Key differences I'd need to adapt to: Kafka's consumer group rebalancing protocol vs KCL's lease-based assignment, and Kafka's stronger exactly-once story via transactions."

### 2. Spark Streaming → Flink (Easy Bridge)
You replaced Spark + Kinesis state with DynamoDB — that's exactly the problem Flink's built-in state solves.

```
Spark Streaming           Flink equivalent
---------------           ----------------
DStream                   DataStream
Micro-batch                True streaming
Checkpoint + Kinesis       Checkpoint + Kafka offset (same concept)
Accumulators              Keyed State (ValueState, MapState)
Structured Streaming       Flink SQL
Delta Lake table           Iceberg table
```

**What to say**: "I ran Spark Streaming at 200B events/hour and hit Spark's checkpointing limitations — which I solved by externalizing state to DynamoDB. Flink's built-in keyed state management solves this problem natively, which is why it's a better fit for stateful pipelines at Netflix's scale."

### 3. Delta Lake → Apache Iceberg (Very Easy Bridge)
These are virtually identical in purpose. You know one, you know the other.

```
Delta Lake                Apache Iceberg
----------                --------------
Transaction log           Snapshot + manifest files
ACID commits              ACID commits
Schema evolution          Schema evolution (more flexible)
Time travel               Time travel
Z-ordering                Sort orders + partition evolution
MERGE INTO                Row-level deletes (Iceberg V2)
```

**What to say**: "I've used Delta Lake for stateful detection pipelines — it solves the same problem as Iceberg: ACID semantics on object storage, schema evolution, time travel. Iceberg's partition evolution and the Netflix IPS pattern for incremental processing are things I'd learn quickly given this foundation."

### 4. ECS/Fargate → Kubernetes (Moderate Gap)
You run containers on ECS. K8s is more complex but the mental model transfers.

```
ECS                       Kubernetes
---                       ----------
Task Definition           Pod spec
Service                   Deployment
Task                      Pod
Service auto-scaling      HPA
ECS Cluster               Node pool
CloudFormation            Helm / Kustomize
IAM roles for tasks       Service Account + RBAC
```

Study: `27-infrastructure-k8s.md` — focus on HPA, resource requests/limits, readiness probes, and Flink/Spark on K8s.

### 5. Security Framing → Data Infrastructure Framing
Your resume talks about "threat detection" and "findings." Netflix doesn't care about threat detection. They care about data infrastructure. Reframe every bullet:

| Your Resume Says | Say Instead in Interview |
|-----------------|--------------------------|
| "Container runtime monitoring" | "Built event ingestion pipeline for container-level telemetry at 200B events/hour" |
| "Stateful threat detection" | "Built stateful stream processing using Spark + DynamoDB for real-time signal correlation" |
| "Finding suppression engine" | "Built a rate-limiting and deduplication layer for a high-volume streaming pipeline" |
| "LLM-assisted rule generation" | "Built an AI-assisted pipeline to auto-generate SQL detection rules from natural language" |
| "False positive reduction" | "Improved signal quality and reduced processing costs by migrating Java DSL to SQL-based engines" |

---

## Your Reverse System Design Story — Prepare This Cold

**Netflix WILL ask**: "Walk me through the most complex distributed system you've built."

Here's how to frame your Lookup Processor story:

```
The System: GuardDuty Runtime Detection Pipeline

Scale:
  "200B+ events per hour, sustained, zero data loss, multi-year traffic growth"
  (Netflix's entire platform is ~83B/hour — your system is bigger)

Architecture:
  Event source (container runtime agents on EKS/ECS)
    → Kinesis (partitioned event stream, ~same as Kafka)
    → Spark Streaming (stateful detection layer)
    → DynamoDB (profile state: per-container behavioral baseline)
    → Finding emission → downstream consumers

Key Design Decisions to Highlight:
  1. Cell-based processor design
     "Each detection type is an isolated cell — independent deployment,
      independent scaling, independent failure domain.
      This is the same philosophy as Netflix Keystone's 1:1 topic-to-Flink-job model."

  2. Replaced Spark checkpointing with DynamoDB state
     "Spark's checkpoint-based state had known data loss scenarios on failure.
      I externalized state to DynamoDB using UpdateItem with conditional writes —
      now state is durable, independently scalable, and failure-resilient."

  3. Autoscaling: CPU + backlog + empty receives
     "Standard CPU-based scaling reacts too slowly and too coarsely for event streams.
      I added backlog depth (like Kafka consumer lag) and empty-receive rate as
      custom signals. Result: 80% cost reduction while maintaining SLA."

  4. 10× latency reduction via async DynamoDB
     "Switched from synchronous DynamoDB calls to async/batched API with
      UpdateItem semantics. This let us pipeline I/O with computation — 10× latency
      improvement and ~50% fleet size reduction."

What You'd Do Differently (Netflix loves this question):
  "If I were building this today at Netflix, I'd:
   1. Use Flink instead of Spark Streaming — native keyed state eliminates the
      DynamoDB dependency for hot state
   2. Use Kafka instead of Kinesis — stronger exactly-once story, better
      consumer group flexibility
   3. Use Apache Iceberg instead of Delta Lake for the historical data layer —
      Iceberg's partition evolution and the IPS pattern would dramatically reduce
      backfill cost
   4. Use Kubernetes instead of ECS — better resource utilization across mixed
      Spark/Flink workloads"
```

---

## STAR Stories to Prepare

**8 stories covering all Netflix behavioral dimensions.** Each has a Netflix angle — say it at the end of every story.

---

### Story 1: "Judgment / Bold Architectural Decision"
**Question fit**: Hard decision with incomplete data, pushing back on status quo, architectural trade-off
**Event**: Replacing Spark Streaming checkpoint state with DynamoDB-backed state

- **S**: Lookup Processor running 200B+ events/hour had a known data loss bug in Spark's KCL integration — shard checkpoints advanced as soon as events were read, before processing completed. Any crash between read and checkpoint write caused data loss. This was a known Spark JIRA (SPARK-20462) with no upstream fix.
- **T**: Fix the data loss without a full Spark rewrite and without taking down a production system handling live customer traffic.
- **A**: Designed a novel approach — externalize all stateful profile data to DynamoDB using `UpdateItem` with conditional writes (optimistic concurrency). State is now durable and independently scalable. The Spark job becomes stateless — if it crashes and restarts, it re-reads from Kinesis and the DynamoDB state is intact. Convinced the team to migrate incrementally: one finding type at a time, with A/B validation.
- **R**: Eliminated data loss entirely. 900M DynamoDB updates/6 hours with 0 throttling and 0 failed updates. Delivered 4 state-based findings in EKS Audit Logs processor and 3 in Patrol Processor — all on schedule.
- **Netflix angle**: **"I made a bold architectural call on a production system at 200B events/hour — externalizing state to DynamoDB was unconventional but it solved the correctness problem permanently and gave us independent scalability."**

---

### Story 2: "Impact at Scale — Async I/O Optimization"
**Question fit**: Performance optimization, measurable impact, systemic bottleneck
**Event**: Async DynamoDB → 10× latency reduction + 50% fleet scale-down

- **S**: After launching DynamoDB-backed state, Lookup Processor was making synchronous I/O calls to DynamoDB for every event. In large AWS regions, this caused high processing latency — executors were idle waiting for DynamoDB responses while queued events piled up.
- **T**: Reduce latency without changing correctness guarantees of our conditional writes.
- **A**: Migrated DynamoDB repository layer to async client using Java `CompletableFuture` — pipelining I/O with computation so Spark executors processed the next batch while waiting for DynamoDB. Also added concurrency limits to the async client to prevent thundering herd on DynamoDB. Also introduced upstream data locality guarantees (Patrol Partitioning) so that events for the same container arrived at the same executor — enabling in-memory aggregation before DynamoDB writes.
- **R**: 10× latency reduction in large regions. Fleet scaled down by 50% automatically after rollout. Reduced 2 oncall pages/week from DynamoDB timeout-related issues.
- **Netflix angle**: **"This is the same pattern as EVCache's async non-blocking access and Flink's async I/O operator. If I built this on Flink today, it would be native — but the optimization mindset is identical."**

---

### Story 3: "Courage / Pushing Back on Status Quo"
**Question fit**: Courage, disagreeing with direction, pushing back with data
**Event**: Migrating detection logic from Java DSL to SQL + $250K/year storage removal

- **S**: All detection rules in Lookup Processor were written in a hand-crafted Java DSL — opaque, engineer-only, impossible for security analysts to read or modify. Every false positive required a full code change, review, and deploy cycle (days to weeks). The team was comfortable with the existing system despite its costs.
- **T**: Make detection logic transparent and analyst-accessible, reduce false positives and operational overhead.
- **A**: Pushed for a SQL-based migration despite resistance — rewrite risk, team familiarity with the DSL, and concern about regression. Built a proof-of-concept showing SQL rules were equivalent and testable in isolation. Designed an AI-assisted migration prompt (Cedric prompt) to convert existing Java DSL rules to SQL systematically. Led migration of all TI-based and non-TI-based findings with Alex and Sudarshan.
- **R**: Reduced false positives measurably. Removed DynamoDB dependency from Lookup Processor — saving $250K/year annually across all regions. Rule change cycle reduced from days to hours. Template used by the entire team to migrate all remaining findings.
- **Netflix angle**: **"I pushed back on a system everyone was comfortable with, because I had data showing the cost — both in dollars and in engineering velocity. That's the kind of call Netflix expects engineers to make."**

---

### Story 4: "Cross-Team Influence — Lookup Processor Launch"
**Question fit**: Leading without authority, cross-functional delivery, ambiguous scope
**Event**: Container runtime monitoring across EKS, ECS-Fargate, ECS-EC2, EC2

- **S**: Before re:Invent 2022, GuardDuty could only map security findings to EC2 instances — not to containers. Customers running workloads on EKS, ECS-Fargate, and ECS-EC2 had no container-level visibility. GuardDuty wanted to launch agent-based runtime monitoring at re:Invent 2022 (EKS preview) and full GA across all environments in 2023.
- **T**: Design and deliver the core processing architecture (Lookup Processor) on a hard deadline with no historical data on customer traffic patterns — meaning unknown scale, unknown workload distribution.
- **A**: Designed a cell-based architecture where each environment (EKS, ECS-Fargate, ECS-EC2, EC2) is an independent deployable cell with isolated Kinesis streams, DynamoDB state, and ECS Fargate fleet. Designed shared schema/API contracts across Ingestion, Metadata, and Processing teams. Chose ECS Fargate as compute to eliminate EC2 patching overhead for FedRamp compliance. Built per-finding canary for continuous validation. Led weekly cross-team syncs to unblock dependencies.
- **R**: Delivered EKS preview at re:Invent 2022 on time. Rolled out full GA across all environments in 2023. EKS-EC2 cell traffic grew 8× over 12 months (10B → 80B events/6 hours) with zero operational incidents. New processor cells now deployable in weeks vs months.
- **Netflix angle**: **"I led a cross-functional initiative across 4 teams from design through production delivery — on a hard deadline, at unknown scale, without direct authority over any of the teams."**

---

### Story 5: "Operational Excellence — Production Memory Leak"
**Question fit**: Incident ownership, "you build it you run it", production debugging
**Event**: Heap dump analysis — disk leak in Stateful Spark Processor

- **S**: Stateful Spark processor running reverse shell detection started showing high disk utilization on master and core nodes in production. Oncall was getting paged. The system was processing 200B+ events/6 hours — any instability meant detection gaps for customers. Standard metrics showed disk filling but no obvious error in application logs.
- **T**: Root cause the failure without taking the system offline, and fix it durably so it doesn't recur.
- **A**: Did a heap dump analysis of the Spark application running on the master node. Found unbounded accumulator growth — Java objects were being accumulated across micro-batches and never cleared, eventually spilling to disk. Fixed with explicit clearing at batch boundaries. Added memory headroom monitoring and disk utilization alerts. Also identified and fixed a GC inefficiency — migrated from ParallelGC to ZGC, improving throughput and reducing GC pause times.
- **R**: Disk leak resolved, zero recurrence. Added runbook so oncall could detect and triage in < 5 minutes. ZGC change expected to produce ~10% additional fleet scale-down.
- **Netflix angle**: **"You build it, you run it — I diagnosed this at the JVM level, fixed it, instrumented it, and documented the runbook. The oncall rotation shouldn't have to reverse-engineer a heap dump to fix a known failure mode."**

---

### Story 6: "Silent Failure Detection — Composite Failure Mode"
**Question fit**: Detecting non-obvious failures, end-to-end ownership, observability design
**Event**: Silent finding drops in LookupProcessor + FindingDecSup pipeline

- **S**: During routine operations, findings were being silently dropped at the boundary between the suppression engine and the publisher. No alert was firing because each component individually reported success — the failure was invisible to per-component health checks.
- **T**: Identify root cause of silent drops with no obvious alert signal, and fix it durably.
- **A**: Built cross-component event counters: findings emitted by LookupProcessor vs findings received by FindingDecSup vs findings published to customers. The gap in counts pointed to a race condition in the suppression engine's composite rule evaluation — under certain timing, a finding matched no rule branch and was silently dropped rather than passed through. Fixed the logic and added an explicit "unmatched finding" counter with a PagerDuty alert threshold.
- **R**: Zero silent drops after fix. Counter added as standard metric to all processor dashboards. Documented the failure mode and recovery steps in a runbook. Finding quality for customers restored.
- **Netflix angle**: **"This is the Netflix Inca pattern — you need end-to-end event counters, not just per-component health checks. A system that looks healthy but drops data is worse than one that fails loudly."**

---

### Story 7: "Cost Optimization — $50K/Month S3 Reduction"
**Question fit**: Proactive cost ownership, cross-team rollout, infrastructure analysis
**Event**: Removing account ID partition from Ingestion S3 bucket

- **S**: GuardDuty Ingestion was writing events to S3 with an account ID in the path — creating millions of tiny files and generating excessive S3 PUT/GET API calls. This was inflating both Ingestion fleet size and downstream processor overhead. No one had flagged this as a cost issue.
- **T**: Propose and safely roll out a structural change across Ingestion and Processor teams — a change that touches the data path of a live 200B events/hour system.
- **A**: Did cost analysis showing the impact. Wrote a 1-pager. Designed a staged rollout plan: validate in one region, verify Lookup Processor handles larger file sizes, define rollback criteria, then region-by-region promotion. Drove execution across both teams.
- **R**: Reduced S3 PUT/GET API calls by over 90%. Saved ~$50,000/month in estimated infrastructure costs. Fleet size reduced in Ingestion and downstream processors.
- **Netflix angle**: **"I identified this proactively through routine infrastructure analysis — not because I was asked to. Netflix expects engineers to own the cost of their systems, not wait for someone else to notice."**

---

### Story 8: "Continuous Validation — Per-Finding Canary"
**Question fit**: Reliability engineering, proactive quality, operational design
**Event**: Designing the per-finding canary system for Lookup Processor

- **S**: Before Lookup Processor, GuardDuty had no per-finding canary. Regressions in detection logic could go undetected until a customer reported missing findings — days or weeks after a bad deploy. With 200B+ events/hour and customers relying on findings for security response, silent regressions were unacceptable.
- **T**: Build a continuous validation system that catches finding-logic regressions before they reach customers.
- **A**: Designed and built a per-finding canary running on a dedicated Hydra environment. The system generates synthetic events that should trigger each finding type, submits them through the full pipeline, and validates the correct finding is produced within SLA. Integrated as a quality gate in the deployment pipeline — a failed canary blocks promotion to production. Extended to cover all finding types in DecSup processor as well.
- **R**: Caught multiple pre-production regressions that would have silently broken customer detections. Mean time to detect finding-logic bugs dropped from "customer report" (days) to "canary run" (minutes). Full E2E coverage extended to 95% of runtime findings, running every 30 minutes, gating all pipeline promotions.
- **Netflix angle**: **"This is the same philosophy as Netflix's ChAP — you can't rely on unit tests alone for distributed pipelines. You need end-to-end signal validation in production-like environments, automated, on every deploy."**

---

## Quick-Pick Guide — Which Story for Which Question

| Netflix Question | Best Story |
|-----------------|------------|
| "Tell me about a hard architectural decision" | Story 1 (DynamoDB state) |
| "Tell me about a systemic performance optimization" | Story 2 (async DDB, 10× latency) |
| "Tell me about a time you pushed back" | Story 3 (SQL migration) |
| "Tell me about leading without authority" | Story 4 (Lookup Processor launch) |
| "Tell me about a production incident you owned" | Story 5 (heap dump) |
| "Tell me about a non-obvious failure you detected" | Story 6 (silent drops) |
| "Tell me about a cost optimization" | Story 7 ($50K S3) or Story 2 (80% autoscaling) |
| "Tell me about operational reliability design" | Story 8 (canary) |
| "What does 'you build it, you run it' mean to you?" | Story 5 + Story 6 |
| "Tell me about cross-team influence" | Story 4 (launch) or Story 7 (S3 cost) |
| "Tell me about impact at scale" | Story 1 (200B events, DDB state) |
| "What are you most proud of?" | Story 4 (Lookup Processor) or Story 1 (DDB architecture) |

---

## Skills Gap Summary

| Skill | Your Level | What to Do |
|-------|-----------|------------|
| Java | Expert ✅ | Lead with this |
| Spark Streaming | Expert ✅ | Bridge to Flink |
| DynamoDB / KV stores | Expert ✅ | Direct match, go deep |
| Kinesis | Expert ✅ | Bridge to Kafka |
| Delta Lake | Proficient ✅ | Bridge to Iceberg |
| Stateful stream processing | Expert ✅ | Your strongest card |
| Autoscaling / cost optimization | Expert ✅ | Strong differentiator |
| Redis internals | Hands-on ✅ | `IdeaProjects/redis` — cluster, streams, locks, rate limiting |
| Kafka | Conceptual only ⚠️ | `design/11-message-queues.md` — consumer groups, exactly-once, partitioning |
| Apache Flink | Conceptual only ⚠️ | `design/23-stream-processing.md` — checkpoints, keyed state, watermarks |
| Apache Iceberg | Conceptual only ⚠️ | `design/25-apache-iceberg-data-lake.md` — WAP, IPS, schema evolution |
| Kubernetes | ECS familiarity ⚠️ | `design/27-infrastructure-k8s.md` — HPA, Flink on K8s, resource limits |
| Netflix internal stack | None ⚠️ | `design/26-netflix-data-platform.md` — EVCache, Keystone, Maestro, Iceberg |
| Consistent hashing | Conceptual ⚠️ | `design/03-consistent-hashing.md` — virtual nodes, hash slots |
| Concurrency (coding) | Strong Java ✅ | `CONCURRENCY-CODING-PRACTICE.md` — implement locks/pools cold |
| LSM / B-tree internals | Solid ✅ | `data-structures/29-lsm-trees.md`, `data-structures/30-b-trees.md` |
| Skiplist / Red-black tree | Solid ✅ | `data-structures/28-skip-lists.md`, `data-structures/31-red-black-trees.md` |

---

## Your Positioning Statement (Memorize This)

Use this to open the interview when asked "tell me about yourself":

> "I'm a senior distributed systems engineer with 7 years at AWS GuardDuty, where I built and scaled real-time event processing pipelines handling 200 billion events per hour — using Spark Streaming, DynamoDB, and Kinesis on AWS. I've led architecture across stateful detection systems, replaced broken checkpointing models with fault-tolerant DynamoDB-backed state, and driven 80% compute cost reductions through multi-signal autoscaling. The problems I've been solving — stateful stream processing at scale, exactly-once semantics, cost-efficient autoscaling, cross-team pipeline ownership — are exactly what Netflix's Data Platform works on. The main thing I'm excited to adapt to is Flink and Iceberg, which solve the same problems I've been solving with Spark and Delta Lake."

---

## Most Likely Interview Questions FOR YOU Specifically

Given your resume, expect these personalized probes:

1. **"You replaced Spark checkpointing with DynamoDB. Why not just fix the checkpointing? What trade-offs did you make?"**
   → Talk about: operational complexity of Spark state, latency of checkpoint restore, DynamoDB's independent scalability and strong consistency guarantees

2. **"How did your cell-based design handle a cell failure? What was the blast radius?"**
   → Talk about: independent Kinesis shards per cell, DynamoDB conditional writes for idempotency, dead-letter queues, no shared state between cells

3. **"200B events/hour is a big number. Walk me through the math — how many Kinesis shards, Spark executors, DynamoDB capacity units?"**
   → Know your system's numbers cold: shards, throughput per shard, executor count, DynamoDB WCU/RCU

4. **"How would you migrate this pipeline from AWS (Kinesis/Spark/DynamoDB) to Netflix's stack (Kafka/Flink/Iceberg)?"**
   → This is the bridge question — your answer in the section above is your prep

5. **"Your autoscaling reduced costs 80%. How did you validate the policy wasn't causing latency SLA violations?"**
   → Talk about: shadow deployment, A/B comparison, consumer lag thresholds as gates before scale-down

6. **"You mention 'zero data loss' — how did you prove it?"**
   → Talk about: sequence numbers + gap detection (Inca pattern), end-to-end event counters, DLQ monitoring
