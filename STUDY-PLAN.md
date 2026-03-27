# Study Plan — Netflix Interview Apr 8–9, 2026

**Interview Schedule** (updated Mar 26):
- Wed Apr 8: Problem Solving (9am PT) ~~Infrastructure-DSI (10:30am PT)~~ ← CANCELLED
- Thu Apr 9: Distributed Systems Design (9am PT) → Operations & Reliability (11am PT)

**Today**: Mar 26, 2026 — 13 days out
**Rounds remaining**: 3 (Problem Solving, Distributed Systems Design, Ops & Reliability)

> Infrastructure-DSI cancelled by Netflix (Belen's email Mar 26).
> Candidacy unaffected — panel refined for next steps.
> Phase 3 time redirected: deeper design practice + Ops reinforcement.

**Active project**: `/Users/jmmodi/IdeaProjects/redis`
**Run**: `java -jar target/redis-deep-dive-1.0.0.jar [1-14|all]`

---

## Phase 1: Concurrency + Redis Internals ✅ DONE (Mar 22–26)

---

## Phase 2: Distributed Systems Design (Mar 27–31) — 5 days

**Focus**: Drive a 45-minute design session confidently. Quantify everything.

| Day | Task | Files |
|-----|------|-------|
| **Mar 27 (Fri)** | Design: Distributed KV Store (DynamoDB/Cassandra-style). Timed 45 min. Cover: consistent hashing, replication, failure handling. Bonus: how would Redis Cluster serve as the KV store? | `design/03-consistent-hashing.md`, `design/07-replication.md` |
| **Mar 28 (Sat)** | Design: Real-time analytics pipeline at Netflix scale (Kafka → Flink → Iceberg → Druid). Late event handling, exactly-once. Bridge: Redis Streams vs Kafka — where does each fit? | `design/23-stream-processing.md`, `design/25-apache-iceberg-data-lake.md` |
| **Mar 29 (Sun)** | Design: Distributed Cache (EVCache-style). Multi-region, cache stampede, write-through vs write-behind. Quote throughput numbers from `Benchmark.java`. | `design/04-caching.md`, `design/26-netflix-data-platform.md`, `redis/Benchmark.java` |
| **Mar 30 (Mon)** | Design: Workflow Orchestrator (Maestro-style). DAG execution, retry, backfill, leader election for HA. | `design/20-distributed-transactions.md`, `design/16-leader-election-gossip.md` |
| **Mar 31 (Tue)** | Review all 4 designs. For each: write "what happens when X fails?" for 3 different X. Practice CAP theorem answer for each. | `design/02-cap-theorem.md`, `design/15-quorum-and-consensus.md` |

---

## Phase 3: Design Depth + Ops Reinforcement (Apr 1–5) — 5 days

**Focus**: Deeper design practice + Ops prep for the two remaining Apr 9 rounds.
Note: `REVERSE-DESIGN-NARRATIVE.md` is still live — GuardDuty story answers
"walk me through your most complex system" in the Design round AND anchors Ops STAR stories.

| Day | Task | Files |
|-----|------|-------|
| **Apr 1 (Wed)** | Read `REVERSE-DESIGN-NARRATIVE.md` end to end. Purpose has shifted: use it as your answer to "walk me through your most complex system" in the Design round (not a dedicated round anymore). Note: async DynamoDB batching (Decision 3) = same pattern as `PipelineAndLuaDemo.java`. Practice opening + architecture walk out loud — target 4 min. | `REVERSE-DESIGN-NARRATIVE.md` §1–2, `redis/PipelineAndLuaDemo.java` |
| **Apr 2 (Thu)** | Say all 5 Design Decisions from `REVERSE-DESIGN-NARRATIVE.md` cold — trade-off framing only ("what I chose, why, what I gave up"). Then: design a new system cold — Notification System or Typeahead (timed 45 min). Use `29-system-design-questions.md` as reference after. | `REVERSE-DESIGN-NARRATIVE.md` §3, `design/29-system-design-questions.md` §7 or §8 |
| **Apr 3 (Fri)** | Ops & Reliability deep dive. Read `24-observability-operations.md` end to end — all 7 STAR stories. Practice 3 incident scenarios out loud: (1) p99 spiked 3× but errors normal, (2) 30% error rate after deploy, (3) memory leak in production stream processor. Framework: detect → isolate → mitigate → fix → prevent. | `design/24-observability-operations.md`, `design/17-circuit-breaker.md` |
| **Apr 4 (Sat)** | STAR stories: say all 8 from `RESUME-ANALYSIS.md` out loud, 2–3 min each. Then quick-pick drill: for each Ops question ("tell me about an incident", "tell me about a cost win", "tell me about cross-team"), which story? Then: LeetCode — LFU Cache (LC 460), Time-Based Key-Value Store (LC 981). | `RESUME-ANALYSIS.md` §Quick-pick, `CONCURRENCY-CODING-PRACTICE.md` |
| **Apr 5 (Sun)** | Run `HyperLogLogDemo.java` + `BloomFilterDemo.java` — know these cold. Review `REVERSE-DESIGN-NARRATIVE.md` §Anticipated Questions — say top 5 Q&As out loud. Review 4 Phase 2 designs — headers + failure scenarios only, no new material. | `redis/HyperLogLogDemo.java`, `redis/BloomFilterDemo.java`, `REVERSE-DESIGN-NARRATIVE.md` §4, `data-structures/08-bloom-filters.md` |

---

## Phase 4: Final Review (Apr 6–7) — 2 days

**Focus**: No new material. Light review, STAR stories, sleep.

| Day | Task | Files |
|-----|------|-------|
| **Apr 6 (Mon)** | Light review: `26-netflix-data-platform.md` (Netflix vocabulary — Flink, Iceberg, EVCache, Maestro, Keystone). Say each STAR story one last time — 2 min each. | `design/26-netflix-data-platform.md`, `RESUME-ANALYSIS.md` |
| **Apr 7 (Tue)** | Light review only: `NETFLIX-JOB-ANALYSIS.md` (questions to ask them, red flags to avoid). Review concurrency cheat sheet — just the concepts table, not full implementations. **Get 8 hours sleep.** | `NETFLIX-JOB-ANALYSIS.md`, `CONCURRENCY-CODING-PRACTICE.md` §Key Concepts |

---

## Apr 8 Morning Protocol (Interview Day 1 — 1 round only)

```
7:00 AM  Wake. No new studying.
7:30 AM  Read ONLY: Key Rules section (bottom of this file)
          + your positioning statement from RESUME-ANALYSIS.md
8:00 AM  Light breakfast. No screens.
8:45 AM  Log into interview setup. Test audio/video. CoderPad ready.
9:00 AM  Problem Solving round (60 min)
          → Lead with Java
          → Think out loud — narrate your approach BEFORE coding
          → If concurrency: write the simplest correct version first, then optimize
          → If stuck: say "let me think through the edge cases" — never go silent

~10:00 AM  Done for the day. Rest. Light walk. No studying.
            Review tomorrow's designs only if it feels natural — not forced.
```

## Apr 9 Morning Protocol (Interview Day 2 — 2 rounds)

```
7:00 AM  Wake. Review: 3 design headers only (KV store, streaming pipeline, caching)
7:30 AM  Re-read STAR stories — just the Netflix angle bullets
8:45 AM  Log in early
9:00 AM  Distributed Systems Design round (45–60 min)
          → Clarify requirements first: scale, latency, consistency requirements
          → State CAP position explicitly: "For this system I'd prioritize AP because..."
          → Always address failure scenarios BEFORE they ask
          → Quantify: "10M requests/sec", "p99 < 100ms", "3 replicas"

~10:15 AM  Short break between rounds. Water. Breathe.

11:00 AM Operations & Reliability round (45–60 min)
          → Frame answers as: detect → isolate → mitigate → fix → prevent
          → Quantify SLOs: "I'd set p99 < 500ms, error rate < 0.1%"
          → Own your incidents: "Here's what I missed and how I fixed the process"
          → Use STAR stories — composite failure mode + async DDB are your strongest
```

---

## Redis Project — Module → Interview Topic Map

| Module | File | Round |
|--------|------|-------|
| Distributed Lock | `DistributedLockDemo.java` | Problem Solving — fencing tokens, Lua atomicity |
| Rate Limiting | `RateLimitDemo.java` | Problem Solving — compare to your Java implementations |
| Cluster + Sharding | `ClusterDemo.java`, `ShardingExplained.java` | Design — hash slots, consistent hashing, EVCache |
| Streams | `StreamDemo.java` | Design — Kafka bridge, consumer groups, ACK, recovery |
| Pipeline + Lua | `PipelineAndLuaDemo.java` | Design — async batching pattern |
| HyperLogLog | `HyperLogLogDemo.java` | Design — probabilistic counting at Netflix scale |
| Bloom Filter | `BloomFilterDemo.java` | Design — SSTable read optimization |
| Benchmark | `Benchmark.java` | Any round — quote real throughput numbers |
| Caching | `CachingDemo.java` | Design — cache-aside, TTL, EVCache pattern |
| Sorted Sets | `LeaderboardDemo.java` | Design — ZSET internals → skiplist |

---

## Key Rules for All Rounds

1. **Quantify everything** — "high traffic" → "55M events/second", "fast" → "sub-100ms p99"
2. **Drive the design** — Don't wait for hints. Netflix culture values self-direction.
3. **"It depends" always followed by the actual answer** — state the trade-off immediately
4. **Always address failure scenarios** — what happens when a node fails, region goes down, consumer falls behind
5. **Bridge to Netflix tech** — Kinesis/Spark/DynamoDB → Kafka/Flink/Iceberg in every applicable answer

---

## Files by Round (Quick Reference)

| Round | Must-Read Files |
|-------|----------------|
| Problem Solving (Apr 8, 9am) | `CONCURRENCY-CODING-PRACTICE.md`, `redis/RateLimitDemo.java`, `redis/DistributedLockDemo.java` |
| Distributed Systems Design (Apr 9, 9am) | `design/02-cap-theorem.md`, `design/03-consistent-hashing.md`, `design/07-replication.md`, `design/23-stream-processing.md`, `design/25-apache-iceberg-data-lake.md`, `design/26-netflix-data-platform.md` |
| Operations & Reliability (Apr 9, 11am) | `design/24-observability-operations.md`, `design/17-circuit-breaker.md`, `RESUME-ANALYSIS.md` |
