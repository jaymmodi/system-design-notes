# Message Brokers — Redis Streams vs Kafka vs Kinesis vs SQS

> When someone says "use a queue" or "use a stream" — this is the decision.

---

## The Mental Model First

These four tools solve different versions of the same problem: **decouple producers from consumers**.

But they differ on one critical axis:

```
Queue (consume-and-delete)          Log (retain-and-replay)
        │                                    │
       SQS                          Kafka / Kinesis / Redis Streams
        │
  "Task queue"                      "Event log"
  Message gone after consumed       Message stays, many consumers can replay
  No history                        History = first-class feature
```

**The key question**: Does the message have value after one consumer reads it?
- No → SQS (task queue, job dispatch)
- Yes → Kafka/Kinesis/Redis Streams (event log, analytics, audit trail)

---

## Architecture Side by Side

### SQS
```
Producer → [Queue] → Consumer (message deleted on ack)

Standard Queue:   at-least-once, no ordering guarantee
FIFO Queue:       exactly-once, ordered within group, 300 msg/s (3000 with batching)

Retention: 1 min – 14 days (default 4 days)
Visibility timeout: message hidden while being processed (not deleted yet)
Dead Letter Queue: messages that fail N times go here
```

### Kinesis
```
Producer → [Shard 1] [Shard 2] [Shard 3] → Consumer 1
                                          → Consumer 2 (enhanced fan-out)

Each shard: 1 MB/s write, 2 MB/s read, ordered within shard
Retention: 24hr (default) → 7 days (extended) → 365 days (long-term)
Scaling: add/remove shards manually (or on-demand mode)
```

### Kafka
```
Producer → Topic [Partition 0] [Partition 1] [Partition 2]
                        ↓               ↓               ↓
           Consumer Group A: one consumer per partition, ordered per partition
           Consumer Group B: independent offset, full replay

Retention: time-based (default 7 days) or size-based or infinite (compacted topics)
Replication: each partition has 1 leader + N replicas across brokers
```

### Redis Streams
```
Producer → XADD mystream * field value
                  ↓
         [0-0][1-0][1-1][2-0]...  (global append-only log, IDs = timestamp-seq)
                  ↓
Consumer Group A: each message acked per consumer (like Kafka consumer group)
Consumer Group B: independent, can read from beginning

Retention: MAXLEN (trim by count) or MINID (trim by timestamp)
Persistence: RDB snapshot + AOF (configurable, not as durable as Kafka)
```

---

## Feature Comparison Table

| Feature | SQS | Kinesis | Kafka | Redis Streams |
|---------|-----|---------|-------|---------------|
| **Model** | Queue (delete on ack) | Log | Log | Log |
| **Ordering** | None (FIFO: per group) | Per shard | Per partition | Global (single stream) |
| **Retention** | Up to 14 days | 24hr–365 days | Configurable / infinite | By count or memory |
| **Replay** | No | Yes | Yes | Yes (up to MAXLEN) |
| **Multiple consumers** | Competing (one gets it) | Fan-out (2MB/s shared or enhanced) | Consumer groups (independent) | Consumer groups |
| **Throughput** | ~3K msg/s (FIFO), unlimited (Standard) | Per shard: 1MB/s in, 2MB/s out | 1M+ msg/s (horizontally) | ~100K–500K msg/s |
| **Latency** | ~1–10ms | ~70ms | ~2–10ms | ~0.1–1ms |
| **Exactly-once** | FIFO only | No (at-least-once) | Yes (transactions) | No (at-least-once) |
| **Scaling** | Automatic | Manual shard split/merge | Add partitions/brokers | Vertical or cluster |
| **Managed** | Fully (AWS) | Fully (AWS) | Self-managed or MSK/Confluent | Self-managed or ElastiCache |
| **Cost model** | Per request | Per shard-hour + data | Infra cost | Redis instance cost |
| **Ecosystem** | AWS-native | AWS-native | Kafka Connect, Flink, Spark | Redis ecosystem |

---

## Deep Dive: Each Tool

### SQS — when it shines

**Core insight**: SQS is a work distribution queue. Think of it like a to-do list that gets crossed off.

```
Use SQS when:
  ✅ Each message should be processed by exactly ONE consumer
  ✅ You don't need replay (processed = done)
  ✅ You want zero infrastructure management
  ✅ Tasks: send email, resize image, process payment, run a job

Don't use SQS when:
  ❌ Multiple services need to consume the same event independently
  ❌ You need event replay or audit trail
  ❌ You need ordering across many messages
```

**Visibility timeout** — the key SQS concept:
```
Message received by Consumer A → message becomes "invisible" for N seconds
If A acks (deletes) → message gone forever
If A crashes / doesn't ack within timeout → message becomes visible again → Consumer B picks it up
This is how at-least-once delivery works in SQS

// Java — SQS delete after processing
ReceiveMessageResponse resp = sqs.receiveMessage(req);
for (Message msg : resp.messages()) {
    try {
        process(msg);
        sqs.deleteMessage(DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(msg.receiptHandle())
            .build());
    } catch (Exception e) {
        // Don't delete → visibility timeout expires → requeued
    }
}
```

**SQS + SNS fan-out pattern**:
```
SNS Topic (pub/sub)
    ├── SQS Queue A → Email Service
    ├── SQS Queue B → Analytics Service
    └── SQS Queue C → Audit Service

This gives you independent consumer queues off a single event publish.
Before Kafka is justified, SNS+SQS is often enough for fan-out.
```

---

### Kinesis — when it shines

**Core insight**: Kinesis is AWS-managed Kafka. Same partitioned-log model, but you pay per shard-hour instead of managing brokers.

```
Use Kinesis when:
  ✅ Deep AWS integration (Lambda, Firehose to S3, Glue, Analytics)
  ✅ Real-time streaming analytics on AWS
  ✅ Don't want to manage Kafka infra
  ✅ Moderate scale (shards are easy to provision)

Don't use Kinesis when:
  ❌ You need very high throughput (Kafka is cheaper and faster at scale)
  ❌ Non-AWS ecosystem or portability matters
  ❌ You need long retention cheaply (Kinesis extended retention is expensive)
  ❌ Exactly-once semantics (Kinesis has at-least-once only)
```

**Shard math** — essential for interviews:
```
Each shard:  1 MB/s write,  2 MB/s read
             1000 records/s write

Your pipeline: 10 MB/s inbound
  → need at least 10 shards (write bound)

If 5 consumers each read all data:
  → each consumer needs 10 MB/s read
  → 5 consumers × 10 MB/s = 50 MB/s total read
  → each shard provides 2 MB/s read → would need 25 shards
  → OR use Enhanced Fan-Out: each consumer gets dedicated 2MB/s per shard
     (costs more, eliminates read throttling between consumers)
```

**KCL (Kinesis Client Library)** — lease-based consumption:
```
KCL maintains a DynamoDB table with one row per shard
Each row = "lease" (which worker owns this shard + current checkpoint)

Worker A starts → claims lease on Shard 0, 1
Worker B starts → KCL rebalances → Worker B gets Shard 2, 3

If Worker A crashes → lease expires → Worker B steals Shard 0, 1
This is exactly like Kafka consumer group coordinator, but using DynamoDB for state
```

**Your GuardDuty connection**: You used Kinesis at 200B+ events/hr. That's ~55M/s → ~55,000 shards if uniform, but in practice events are batched. This scale is where Kafka starts being cheaper than Kinesis.

---

### Kafka — when it shines

**Core insight**: Kafka is the event streaming backbone. If you need the full power of a distributed log — unlimited retention, highest throughput, exactly-once, rich ecosystem (Flink, Spark, Connect, Schema Registry) — Kafka is the answer.

```
Use Kafka when:
  ✅ Multiple independent consumer groups (each gets full copy of events)
  ✅ Replay is critical (re-process last 7 days / indefinitely with compaction)
  ✅ Very high throughput (millions of events/sec)
  ✅ Exactly-once end-to-end (producer transactions + consumer offset commit)
  ✅ Rich ecosystem: Kafka Connect (100s of connectors), Flink, Spark Streaming, ksqlDB
  ✅ Event sourcing: topic is the source of truth

Don't use Kafka when:
  ❌ You want fully managed with zero ops (use MSK but still more ops than Kinesis/SQS)
  ❌ Simple task queue (SQS is simpler and cheaper)
  ❌ You need sub-millisecond latency (Redis Streams or direct RPC)
  ❌ Small scale — Kafka cluster has fixed infra cost (~$300+/mo minimum on MSK)
```

**Consumer groups** — the key Kafka abstraction:
```
Topic: user-events  [P0][P1][P2][P3]

Consumer Group "analytics":      Consumer Group "billing":
  ConsumerA → P0, P1               ConsumerX → P0, P1, P2, P3
  ConsumerB → P2, P3

Each group has its OWN offset per partition.
analytics and billing read independently — no coordination between them.
Adding a new consumer group = new replay from any offset, doesn't affect others.
```

**Exactly-once in Kafka**:
```java
// Producer side: idempotent + transactional
Properties props = new Properties();
props.put("enable.idempotence", "true");        // dedup retries
props.put("transactional.id", "my-producer-1"); // enables transactions

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
producer.initTransactions();

producer.beginTransaction();
try {
    producer.send(new ProducerRecord<>("output-topic", key, value));
    consumer.commitSync(offsets);           // commit input offset in SAME transaction
    producer.commitTransaction();
} catch (Exception e) {
    producer.abortTransaction();
}
// If broker crashes mid-transaction → transaction is atomically aborted on recovery
```

**Log compaction** — Kafka as a key-value store:
```
Normal topic: retain all events for N days, then delete
Compacted topic: retain LATEST value per key forever

Use case: "current state" topics — each key's latest event = current state
  user-profiles topic: always readable for current profile state
  device-registry: current device config per device ID

Reading from offset 0 on a compacted topic = full snapshot of current state
This is how Kafka is used as a database (event sourcing pattern)
```

**Kafka vs Kinesis translation** (from your RESUME-ANALYSIS.md):
```
Kinesis               Kafka
--------              -----
Shard                 Partition
Sequence number       Offset
Enhanced fan-out      Consumer group (each gets independent stream)
KCL lease table       Consumer group coordinator (in Kafka brokers)
Provisioned shards    Partition count (set at topic creation)
Kinesis Firehose      Kafka Connect sink (to S3, Elasticsearch, etc.)
```

---

### Redis Streams — when it shines

**Core insight**: Redis Streams is Kafka-lite in memory. Same consumer group model, but with sub-millisecond latency and Redis's simplicity. Not for high-durability or high-throughput at scale.

```
Use Redis Streams when:
  ✅ Sub-millisecond latency event processing
  ✅ You already have Redis in your stack
  ✅ Moderate volume, short retention (IoT, activity feeds, real-time dashboards)
  ✅ Need Kafka-like consumer groups without Kafka infrastructure
  ✅ Simple pub/sub with replay (vs Redis Pub/Sub which has no persistence)

Don't use Redis Streams when:
  ❌ High durability required (Redis AOF is good but not Kafka-level)
  ❌ High volume with long retention (all in memory = expensive)
  ❌ Multiple independent consumer groups at huge scale
  ❌ Kafka ecosystem integration (Flink, Spark, Connect) needed
```

**Redis Streams vs Redis Pub/Sub**:
```
Pub/Sub:   fire-and-forget, subscriber must be connected at publish time
           → no history, no replay, no consumer groups
           → use for: real-time notifications where missing is OK

Streams:   persistent log, XADD appends, XREAD with blocking, consumer groups
           → history, replay (up to MAXLEN), pending entries for unacked messages
           → use for: anything where you need guaranteed delivery + history
```

**Key Redis Streams commands**:
```
XADD mystream * sensor temperature 72    → append, auto-generate ID
XADD mystream MAXLEN ~ 10000 * ...       → append, trim to ~10K entries

XREAD COUNT 10 BLOCK 0 STREAMS mystream 0        → read 10 from beginning, block if empty
XREAD COUNT 10 BLOCK 5000 STREAMS mystream $     → read only NEW entries ($ = latest)

# Consumer groups (like Kafka consumer groups)
XGROUP CREATE mystream grp1 0             → create group, start from beginning
XGROUP CREATE mystream grp1 $             → create group, start from now

XREADGROUP GROUP grp1 consumer1 COUNT 10 STREAMS mystream >   → read undelivered
                                                               # > means "not yet delivered to this group"
XACK mystream grp1 <message-id>           → acknowledge processed

XPENDING mystream grp1 - + 10            → list pending (delivered, not acked)
XCLAIM mystream grp1 consumer2 3600000 <id>  → steal message idle > 1hr (dead consumer recovery)
```

**Pending Entries List (PEL)** — how Redis Streams guarantees delivery:
```
When consumer reads a message → added to PEL (pending list for this consumer)
When consumer ACKs → removed from PEL
If consumer crashes → message stays in PEL
XPENDING shows stuck messages → XCLAIM reassigns to healthy consumer

This is equivalent to Kafka's consumer group offset tracking but per-message
```

---

## Decision Framework (The Real Interview Answer)

```
Question: What messaging system would you use for X?

Step 1: Queue or Log?
  Task execution (one consumer processes, done)  → SQS
  Event processing (multiple consumers, replay)  → Kafka / Kinesis / Redis Streams

Step 2: If Log — which one?

  Max throughput + long retention + exactly-once + ecosystem?
    → Kafka (or MSK on AWS)

  AWS-managed + moderate scale + AWS ecosystem (Lambda, Firehose)?
    → Kinesis

  Sub-millisecond latency + already have Redis + short retention?
    → Redis Streams

  Pure fan-out on AWS (SNS → multiple SQS queues)?
    → SNS + SQS (simpler than Kafka for basic fan-out)

Step 3: At Netflix scale?
  Kafka. Netflix uses Kafka (Keystone) as their central event bus.
  200B+ events/day, multiple consumer groups, Flink jobs consuming same stream.
  Kinesis would be 10× more expensive at that scale.
```

---

## Scale Numbers to Quote in Interviews

| System | Throughput | Latency | Retention |
|--------|-----------|---------|-----------|
| SQS Standard | ~unlimited (distributed) | 1–10ms | up to 14 days |
| SQS FIFO | 3,000 msg/s (batched) | 1–10ms | up to 14 days |
| Kinesis (per shard) | 1 MB/s write, 2 MB/s read | ~70ms | 24hr–1yr |
| Kafka (per partition) | ~10–50 MB/s | 2–10ms | configurable/infinite |
| Kafka cluster | 1M+ msg/s | 2–10ms | infinite (compacted) |
| Redis Streams | 100K–500K msg/s | <1ms | memory-bound |

---

## Netflix Angle (Keystone Pipeline)

Netflix's event streaming architecture:
```
Device events (play, pause, search, error)
    → Kafka (Keystone) — central bus, 1.3T events/day
        ├── Consumer Group: Flink jobs (real-time analytics)
        ├── Consumer Group: Iceberg sink (cold storage via Kafka Connect)
        ├── Consumer Group: Druid (interactive dashboards)
        ├── Consumer Group: GuardDuty-equivalent (security/anomaly detection)
        └── Consumer Group: A/B testing platform

Why Kafka over Kinesis at Netflix scale:
  - Cost: $0.015/shard-hour for Kinesis × 50K shards = ~$540K/month
    vs Kafka cluster on self-managed hardware: ~$50K/month
  - Retention: Kinesis max 365 days costs extra; Kafka retention is just disk
  - Exactly-once: critical for billing and analytics (Kafka has it, Kinesis doesn't)
  - Ecosystem: Flink, Spark, Iceberg all have native Kafka connectors
```

Your Kinesis → Kafka bridge answer:
> "I ran GuardDuty's pipeline on Kinesis at 200B events/hour. The mental model is identical to Kafka — shards are partitions, sequence numbers are offsets, KCL leases are consumer group assignments. If I were redesigning it today at Netflix, I'd use Kafka for three reasons: exactly-once semantics for finding deduplication, cheaper long-term retention for the behavioral profiles, and native Flink integration which eliminates the DynamoDB state layer entirely."

---

## Common Interview Questions

**Q: How does Kafka guarantee ordering?**
> Ordering is guaranteed within a partition only. To order globally, all related events must go to the same partition (use a key that routes them there — e.g., `userId` or `deviceId`). Cross-partition ordering is not guaranteed and requires a separate sorting step (e.g., Flink watermark-based event time processing).

**Q: What happens if a Kafka consumer falls behind?**
> Messages are retained regardless — the consumer just has a larger lag. Monitor consumer lag (difference between latest offset and committed offset). If lag grows unboundedly: add more consumers (up to partition count), increase batch size, or optimize processing. If consumer group dies for longer than retention period → data is lost for that group.

**Q: How do you handle duplicate messages in Kafka?**
> Three layers: (1) Idempotent producers (enable.idempotence=true) dedup retries at broker. (2) Transactional producers atomically commit output + input offset. (3) Consumer-side idempotency: process `eventId` with upsert logic so replaying same message is a no-op. All three together = end-to-end exactly-once.

**Q: SQS vs Kafka for microservice communication?**
> SQS: simpler, no infrastructure, natural for task queues, message is consumed once. Kafka: when multiple services need to react to the same event independently, when you need replay for debugging/new service catchup, when events are the source of truth (event sourcing). Rule of thumb: start with SQS, migrate to Kafka when you need fan-out or replay.

**Q: When would Redis Streams beat Kafka?**
> Latency-critical path where you can't afford 2–10ms Kafka overhead — e.g., real-time fraud scoring in <1ms, live game state updates, IoT sensor ingestion into Redis for immediate processing. Also when you already have Redis and the volume fits in memory — no extra infrastructure. The moment you need >1TB retention or 3+ nines durability, Kafka wins.
