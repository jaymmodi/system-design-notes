# Message Brokers — Redis Streams vs Kafka vs Kinesis vs SQS

> When someone says "use a queue" or "use a stream" — this is the decision.

---

## The Mental Model First

Six tools, three fundamentally different models:

```
Fire-and-forget broadcast          Queue (consume-and-delete)       Log (retain-and-replay)
         │                                    │                              │
Redis pub/sub, SNS                           SQS                  Kafka, Kinesis, Redis Streams
         │                                    │                              │
  "Notification"                        "Task queue"                  "Event log"
  All subscribers get it               One consumer gets it          Many consumers, replay
  No history whatsoever                No history                    History is first-class
  Subscriber must be online            Visibility timeout            Consumers read at own pace
```

**The key questions:**
1. Does missing a message matter? → No = pub/sub, Yes = queue or log
2. Does the message have value after one consumer reads it? → No = SQS, Yes = log
3. Do multiple independent services need the same event? → Yes = SNS fan-out or Kafka
4. Do you need replay? → Yes = Kafka/Kinesis/Redis Streams

---

## Architecture Side by Side

### Redis Pub/Sub
```
Publisher → PUBLISH channel "message"
                 ↓ broadcast simultaneously
         Subscriber A  Subscriber B  Subscriber C
         (all connected clients get it right now)

No persistence. Subscriber offline = message lost.
No history. No consumer groups. Pure broadcast.
```

### SNS
```
Publisher → SNS Topic → push to ALL subscriptions simultaneously
                ├── SQS Queue A  → Email Service (queue buffers, durable)
                ├── SQS Queue B  → Analytics Service
                ├── Lambda fn    → immediate execution
                ├── HTTP endpoint → your webhook
                └── SMS / Email  → direct delivery

No storage in SNS itself. Each subscriber gets their own copy pushed to them.
```

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

## Comprehensive Comparison Table

| | Redis pub/sub | SNS | SQS | Kinesis | Kafka | Redis Streams |
|--|---|---|---|---|---|---|
| **Model** | Broadcast (fire-and-forget) | Push fan-out (fire-and-forget) | Queue (delete on ack) | Partitioned log | Partitioned log | Persistent log |
| **Delivery guarantee** | At-most-once | At-least-once | At-least-once (FIFO: exactly-once) | At-least-once | At-least-once / exactly-once | At-least-once |
| **Persistence** | None | None (SNS) | Yes (up to 14 days) | Yes (24hr–365 days) | Yes (infinite) | Yes (memory-bound) |
| **Replay** | No | No | No | Yes (within retention) | Yes (any offset) | Yes (up to MAXLEN) |
| **Consumer model** | All subscribers get all messages | All subscriptions get pushed | Competing (one gets each msg) | Consumer groups per shard | Independent consumer groups | Consumer groups |
| **Ordering** | Per channel, FIFO | No (FIFO SNS: per group) | No (FIFO SQS: per group) | Per shard | Per partition | Global (single stream) |
| **Max TPS (writes)** | ~1M msg/sec (single node) | Standard: ~unlimited; FIFO: 300/sec (3K batched) | Standard: ~unlimited; FIFO: 300/sec (3K batched) | 1K records/sec per shard (1MB/s) | 1M+ msg/sec (cluster) | ~500K msg/sec |
| **Max TPS (reads)** | Broadcast to all simultaneously | Push (no poll) | ~unlimited (Standard); 300/s (FIFO) | 2MB/s per shard (5 reads/sec polling; enhanced fan-out: 2MB/s per consumer) | Limited by partition count × consumer throughput | ~500K msg/sec |
| **Latency** | <1ms | ~10ms–1s | ~1–10ms | Polling: ~200ms; Enhanced fan-out: ~70ms | ~2–10ms (tunable to ~0.5ms) | <1ms |
| **Max message size** | 512MB (Redis limit; use <1MB) | 256KB | 256KB | 1MB | 1MB default (configurable to ~10MB) | 512MB (Redis limit) |
| **Subscriber offline** | Message lost | Delivered to endpoint (SQS buffers it) | Stays in queue (visibility timeout) | Consumer reads when ready | Consumer reads at own pace | Pending until acked |
| **Fan-out to N services** | Yes (all subscribers) | Yes (push to all subscriptions) | No (one consumer gets it) | Via enhanced fan-out (each consumer: 2MB/s per shard) | Via consumer groups (each group: full copy) | Via consumer groups |
| **Filtering** | Pattern subscriptions (PSUBSCRIBE) | Filter policies per subscription (on message attributes) | No native filtering | No native filtering | No native (use Kafka Streams or consumer-side) | No native |
| **Dead letter** | No | Via SQS DLQ on subscription | Yes (DLQ after N failures) | No built-in | No built-in (handle in consumer) | Via XPENDING + XCLAIM |
| **Scaling** | Vertical (cluster mode for sharding) | Automatic | Automatic | Manual shard add/split or on-demand | Add brokers + partitions | Vertical or Redis Cluster |
| **Managed** | Self-managed or ElastiCache | Fully managed (AWS) | Fully managed (AWS) | Fully managed (AWS) | Self-managed / MSK / Confluent | Self-managed or ElastiCache |
| **Cost model** | Redis instance | Per publish + per delivery | Per request | Per shard-hour + PUT payload | Infra (brokers, storage) | Redis instance |
| **Cross-account/region** | No | Yes (SNS native) | Yes (via SQS policy) | No (same account) | Manual (MirrorMaker) | No |
| **Best for** | Real-time broadcast, presence, live updates | AWS fan-out, trigger multiple services | Task queue, job dispatch | AWS streaming analytics | High-throughput event log, stream processing | Low-latency stream with replay |

---

## Deep Dive: Each Tool

### Redis Pub/Sub — when it shines

**Core insight**: Redis pub/sub is a real-time notification bus. Zero persistence. If no one is subscribed when you publish, the message is gone forever. Use it when dropping a message is acceptable.

```
Use Redis pub/sub when:
  ✅ Real-time broadcast where missing is acceptable — live score updates, chat typing indicators
  ✅ Presence: notify friends when user comes online
  ✅ Cache invalidation: broadcast "key X changed" to all app servers
  ✅ Live commentary, realtime claps — readers expect live, don't need history
  ✅ Sub-millisecond fan-out to all connected servers

Don't use Redis pub/sub when:
  ❌ Any message must not be lost — use Redis Streams or Kafka
  ❌ Consumer offline for even 1 second — messages during that window are lost
  ❌ Need to know how many messages were missed — no offset tracking
  ❌ Need consumer groups (competing consumers) — all subscribers get all messages
  ❌ Need filtering — all subscribers on a channel get everything
```

**Commands:**
```bash
# Publisher
PUBLISH live:match:icc_final '{"ball":42,"runs":4,"commentary":"FOUR!"}'

# Subscriber (blocking, receives all messages on channel)
SUBSCRIBE live:match:icc_final

# Pattern subscription (wildcard)
PSUBSCRIBE live:match:*     ← receives messages on ALL match channels
PSUBSCRIBE cache:invalidate:user:*

# Unsubscribe
UNSUBSCRIBE live:match:icc_final
```

**What happens at scale: fan-out cost**
```
1 Redis PUBLISH to channel with 1000 subscribers:
  Redis loops through all 1000 subscriber connections
  Sends message to each — sequential in Redis's single thread
  1000 × 1KB message = 1MB sent per publish

At 10K subscribers × 100 publishes/sec:
  Redis sending 1GB/sec of data — network saturation risk

Solution: SSE/WebSocket servers as subscribers (not end clients directly)
  Redis → 5 SSE servers (each subscribed)
  Each SSE server → 2K connected end clients
  Redis fan-out: 5 sends (fast)
  SSE servers fan-out: 2K each (parallel)
```

**Java with Jedis:**
```java
// Subscriber (runs in dedicated thread — SUBSCRIBE is blocking)
Jedis jedis = new Jedis("localhost", 6379);
jedis.subscribe(new JedisPubSub() {
    @Override
    public void onMessage(String channel, String message) {
        // broadcast to all SSE/WebSocket clients on this server
        sseClients.forEach(client -> client.send(message));
    }
}, "live:match:icc_final");

// Publisher (separate Jedis connection — subscriber connection is dedicated)
Jedis publisher = new Jedis("localhost", 6379);
publisher.publish("live:match:icc_final", json);
```

**Key limitation: subscriber connection is dedicated**
```
Once you call SUBSCRIBE, that connection can ONLY subscribe/unsubscribe.
You need a SEPARATE connection for publishing.
This is unlike Redis Streams where you can mix XADD and XREAD on same connection.
```

---

### SNS — when it shines

**Core insight**: SNS is the AWS fan-out glue. It doesn't store messages — it pushes them immediately to all subscriptions. The value is connecting multiple AWS services to one event source without coupling them.

```
Use SNS when:
  ✅ Fan-out to multiple AWS services from one publish (SQS + Lambda + HTTP + email)
  ✅ Decouple producers from consumer infrastructure (producer doesn't know who consumes)
  ✅ Cross-account event delivery (SNS supports cross-account subscriptions natively)
  ✅ Simple notification: SMS, email, mobile push (APNs/FCM) directly from SNS
  ✅ Filter messages per subscriber (each SQS subscription sees only matching events)
  ✅ You don't need replay — SNS+SQS gives you the buffer/replay via SQS

Don't use SNS when:
  ❌ You need replay of past messages — SNS has no storage
  ❌ High-throughput ordered stream — use Kinesis/Kafka
  ❌ Consumer needs to process at own pace (pull model) — SNS pushes, use SQS behind it
  ❌ FIFO at > 300 TPS — FIFO SNS is limited, use Kafka
```

**SNS Standard vs FIFO:**
```
Standard SNS:
  - At-least-once delivery (rare duplicate possible)
  - No ordering guarantee
  - Unlimited TPS
  - Subscribers: SQS (Standard or FIFO), Lambda, HTTP, Email, SMS, Mobile Push

FIFO SNS:
  - Exactly-once delivery (5-min dedup window by MessageDeduplicationId)
  - Ordered per MessageGroupId (like Kafka partition key)
  - 300 TPS (3000 with batching)
  - Subscribers: SQS FIFO ONLY (no Lambda, no HTTP directly)
  - Use when: order matters and exactly-once matters (financial events, inventory)
```

**Filter policies — the killer SNS feature:**
```json
// SNS Topic: order-events  (publishes ALL order events)

// Subscription A (Fraud Service SQS) — only high-value orders
{
  "amount": [{"numeric": [">=", 1000]}],
  "status": ["placed"]
}

// Subscription B (Email Service SQS) — all status changes
{
  "status": ["placed", "shipped", "delivered", "cancelled"]
}

// Subscription C (Analytics Lambda) — no filter = gets everything
{}
```

Each SQS queue only receives messages matching its filter. SNS evaluates filters before delivery — non-matching messages never reach that subscription.

**SNS + SQS fan-out (the canonical pattern):**
```
order.placed event
      ↓
  SNS Topic
  ├── SQS: FraudCheckQueue    → Fraud Lambda (processes async, retries via DLQ)
  ├── SQS: InventoryQueue     → Inventory Service (at-least-once, deduped)
  ├── SQS: EmailQueue         → Email Service (retry 3x, DLQ for failures)
  ├── Lambda: RealTimeMetrics → immediate execution, no queue
  └── HTTP: WebhookEndpoint   → partner notification

Why SQS behind SNS (not Lambda directly for all)?
  SQS absorbs burst — if Fraud Lambda is slow, queue buffers
  SQS provides retry + DLQ — SNS delivery failure handling per-subscription
  SQS decouples consumer pace from publish rate
```

**Limits to know:**
```
Message size:    256KB (hard limit — larger payload: store in S3, SNS carries S3 key)
Subscriptions:   12.5M per topic
Topics per account: 100K default (soft limit, requestable)
FIFO TPS:        300/sec (3000 batched) — hard limit, cannot increase
Standard TPS:    ~unlimited (30M+ messages/sec at AWS scale)
Delivery retry:  HTTP endpoints: exponential backoff up to 23 times over 23 hours
                 SQS: handled by SQS (not SNS)
                 Lambda: 2 retries (Lambda async invocation)
```

---

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
Q: What messaging system for X?

Step 1: Can the message be lost if consumer is offline?
  YES → Redis pub/sub (fire-and-forget, sub-ms broadcast)
  NO  → continue

Step 2: Task queue (one consumer, no replay) or event log (many consumers, replay)?
  Task queue  → SQS
  Event log   → continue

Step 3: Need fan-out to multiple independent services without replay?
  YES + AWS + simple → SNS + SQS per service
  YES + need replay  → Kafka consumer groups (each group gets full copy)

Step 4: Need replay + durable log — which one?
  Sub-ms latency, short retention, already have Redis  → Redis Streams
  AWS-native, moderate scale, Lambda/Firehose ecosystem → Kinesis
  High throughput, exactly-once, Flink/Spark ecosystem  → Kafka (or MSK)

Step 5: At what scale does the choice change?
  < 1K msg/sec, AWS-only            → SNS + SQS (zero ops)
  1K–100K msg/sec, AWS-only         → Kinesis (managed, shard math is simple)
  > 100K msg/sec or non-AWS         → Kafka (cheaper, more powerful)
  Broadcast to live connections      → Redis pub/sub (not Kafka)
  Broadcast + must not miss          → Redis Streams or Kafka + SSE server layer
```

**The SNS vs Kafka fan-out distinction:**
```
SNS fan-out: all subscriptions get the message → each SQS queue is independent
  Good when: each service processes the event differently (email + fraud + analytics)
  Bad when:  you need > 300 TPS ordered (FIFO SNS limit), or replay

Kafka fan-out: multiple consumer groups, each reads independently
  Good when: same event, different processing, need replay, high throughput
  Bad when:  simple AWS glue — Kafka is overkill for 5 services at low volume

Rule: SNS+SQS before Kafka. Migrate to Kafka when:
  → You need replay for catch-up (new service needs historical events)
  → You need ordered processing > FIFO SNS limit
  → You need exactly-once end-to-end
  → You need stream processing (Flink consuming same topic)
```

---

## Scale Numbers to Quote in Interviews

| System | Write TPS | Read TPS | Latency | Retention | Max msg size |
|--------|-----------|----------|---------|-----------|--------------|
| Redis pub/sub | ~1M msg/sec | Broadcast (push) | <1ms | None | 512MB (use <1MB) |
| SNS Standard | ~unlimited | Push to subscribers | ~10ms–1s | None | 256KB |
| SNS FIFO | 300/sec (3K batched) | Push (FIFO) | ~10ms–1s | None | 256KB |
| SQS Standard | ~unlimited | ~unlimited | 1–10ms | up to 14 days | 256KB |
| SQS FIFO | 300/sec (3K batched) | 300/sec | 1–10ms | up to 14 days | 256KB |
| Kinesis (per shard) | 1K records/sec, 1MB/s | 5 reads/sec, 2MB/s (shared) or 2MB/s per consumer (enhanced) | Polling: ~200ms; Enhanced: ~70ms | 24hr–365 days | 1MB |
| Kafka (per partition) | ~50MB/s | Consumer-rate | 2–10ms (tunable <1ms) | Infinite | 1MB default |
| Kafka cluster | 1M+ msg/sec | 1M+ msg/sec | 2–10ms | Infinite | Configurable |
| Redis Streams | ~500K msg/sec | ~500K msg/sec | <1ms | Memory-bound (MAXLEN) | 512MB (use <1MB) |

**Kinesis shard math (must know):**
```
Need to handle 50MB/sec ingest → 50 shards (1MB/s each)
5 consumer services each need full 50MB/s → 250MB/s total read
  Shared polling: 50 shards × 2MB/s = 100MB/s → NOT enough for 5 consumers
  Enhanced fan-out: each consumer gets 2MB/s per shard = 100MB/s per consumer → OK
  Cost: enhanced fan-out adds $0.015/shard-hour per consumer

Provisioned vs On-Demand:
  Provisioned: you set shard count, pay per shard-hour
  On-Demand:   auto-scales, pay per GB in/out (2× cost but zero capacity planning)
```

**SNS FIFO hard limit: 300 TPS**
```
Cannot increase this limit (it is hard, not soft).
If you need FIFO ordered delivery > 300 TPS → use Kafka.
If you need exactly-once delivery at high TPS → Kafka transactions.
SNS FIFO + SQS FIFO is the serverless exactly-once fan-out at low volume.
```

---

## Fan-Out Pattern: SNS+SQS vs Kafka vs Kinesis

```
S3 / Producer
      ↓
   SNS Topic
  /    \    \
SQS1  SQS2  SQS3     ← one queue per subscriber — each processes independently
 ↓      ↓     ↓
Svc A  Svc B  Svc C
```

| Capability | SNS+SQS | Kafka | Kinesis (Enhanced Fan-Out) |
|-----------|---------|-------|---------------------------|
| Replay old messages | ❌ once consumed, gone | ✅ replay from any offset | ✅ up to 7 days retention |
| Message ordering | ❌ best-effort (FIFO queue helps but limited) | ✅ per partition | ✅ per shard |
| Retention | max 14 days, consumed = deleted | configurable, consumed ≠ deleted | 24h default, up to 7 days |
| Throughput | SQS standard: nearly unlimited; FIFO: 3000/s | millions/s | 1MB/s write, 2MB/s read per shard |
| Fan-out | ✅ SNS → multiple SQS queues | ✅ multiple consumer groups | ✅ up to 20 enhanced consumers per stream |
| Consumer pace independence | ✅ yes | ✅ yes | ✅ yes |
| Exactly-once | ❌ at-least-once only | ✅ with transactions | ❌ at-least-once only |
| Delivery model | pull (SQS poll) | pull | push via HTTP/2 (~70ms latency) |
| Managed service | ✅ fully managed | ❌ self-managed (or MSK) | ✅ fully managed |
| Checkpointing | SQS visibility timeout | consumer group offsets in Kafka | KCL + DynamoDB table |

> Sources: [SQS quotas](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-quotas.html), [Kinesis enhanced fan-out](https://docs.aws.amazon.com/streams/latest/dev/enhanced-consumers.html)

---

## Kinesis: DynamoDB Checkpointing

KCL (Kinesis Client Library) uses a DynamoDB table to track which shard offset (sequence number) each consumer has processed — one row per shard.

```
DynamoDB table: "my-app-kinesis-checkpoint"
┌─────────────────┬──────────────────────────┬───────────────┐
│ shardId (PK)    │ sequenceNumber           │ leaseOwner    │
├─────────────────┼──────────────────────────┼───────────────┤
│ shardId-000000  │ 49590338271490256608559  │ worker-1      │
│ shardId-000001  │ 49590338271490256608560  │ worker-2      │
│ shardId-000002  │ 49590338271490256608561  │ worker-1      │
└─────────────────┴──────────────────────────┴───────────────┘

On crash + restart:
  KCL reads DynamoDB → finds last sequenceNumber per shard → resumes from there
  Without DynamoDB: KCL doesn't know where it left off → re-reads from TRIM_HORIZON
```

Kafka equivalent: offsets stored in `__consumer_offsets` internal topic — no external DB needed.

---

## Kinesis Enhanced Fan-Out: Push via HTTP/2

### Shared throughput (old, polling model):
```
Consumer 1 ──GET──→ Kinesis shard   ← polling every 200ms
Consumer 2 ──GET──→ Kinesis shard   ← same 2MB/s shared between all consumers
Consumer 3 ──GET──→ Kinesis shard   ← consumers compete for bandwidth
                    └── 2MB/s total, split across all consumers
```

### Enhanced fan-out (HTTP/2 push model):
```
Kinesis shard ──PUSH──→ Consumer 1  (dedicated 2MB/s, ~70ms latency)
              ──PUSH──→ Consumer 2  (dedicated 2MB/s, ~70ms latency)
              ──PUSH──→ Consumer 3  (dedicated 2MB/s, ~70ms latency)
              └── each consumer gets its OWN 2MB/s pipe, no contention
```

How the push works:
```
1. Consumer calls SubscribeToShard API (HTTP/2 long-lived connection)
2. Kinesis holds the connection open
3. New record arrives at shard
4. Kinesis PUSHES record to all subscribed consumers simultaneously
5. No polling loop needed — Kinesis initiates delivery
   ↑ this is why latency drops from 200ms → 70ms (no poll interval wait)
```

HTTP/2 specifically enables this because it supports **multiplexed streams** — one TCP connection carries data for multiple consumers simultaneously without head-of-line blocking.

---

## Scaling: What Happens When You Add Shards/Partitions?

### Kafka — adding partitions

```
Before (2 partitions):
  Partition 0: msg[A, C, E]  → Consumer 1 (owns P0)
  Partition 1: msg[B, D, F]  → Consumer 2 (owns P1)

After adding Partition 2:
  Kafka triggers consumer group REBALANCE
  ┌─────────────────────────────────────────────┐
  │  All consumers PAUSE processing              │
  │  Coordinator reassigns partitions            │
  │  Partition 0 → Consumer 1                   │
  │  Partition 1 → Consumer 2                   │
  │  Partition 2 → Consumer 3 (new)             │
  │  All consumers RESUME                        │
  └─────────────────────────────────────────────┘
  Downtime: seconds (stop-the-world rebalance)
```

**Stateful consumer problem with Kafka repartitioning:**
```
Stateful app example: counting orders per user_id (windowed aggregation)
  Kafka streams user_id to partition via hash(user_id) % numPartitions

Before: hash("user-123") % 2 = partition 1  → Consumer 2 has state for user-123
After:  hash("user-123") % 3 = partition 0  → Consumer 1 now gets user-123 events
                                               Consumer 1 has NO state for user-123 ← problem

Effects:
  - In-memory state (counts, aggregations) is on the wrong consumer
  - Must rebuild state from scratch by replaying partition history
  - During rebuild: stale or incorrect results
  - Kafka Streams handles this via changelog topics (state backed by Kafka)
    but rebuild still takes time proportional to partition history size
```

### Kinesis — resharding (split/merge)

```
Before (2 shards):
  Shard 0: partitionKey hash range [0, 50%]    → Consumer A
  Shard 1: partitionKey hash range [50%, 100%] → Consumer B

Split Shard 0 into Shard 2 + Shard 3:
  Shard 0: CLOSED (still readable for 7 days, but no new writes)
  Shard 2: hash range [0, 25%]   → Consumer A (or new consumer)
  Shard 3: hash range [25%, 50%] → Consumer B (or new consumer)

  KCL handles this automatically:
    1. Detects parent shard (Shard 0) is CLOSED
    2. Finishes processing Shard 0
    3. Starts reading child shards (Shard 2, Shard 3)
    4. Updates DynamoDB checkpoint with new shard IDs
```

**Stateful consumer problem with Kinesis resharding:**
```
Same issue: state was accumulated per shard
  Shard 0 consumer had: {user-123: 50 orders, user-456: 30 orders}
  After split:
    Shard 2 consumer gets user-123 events → no prior state
    Shard 3 consumer gets user-456 events → no prior state
  Must rebuild from closed parent shard or accept state reset
```

### Mitigations for stateful scaling

| Strategy | How | Tradeoff |
|----------|-----|----------|
| External state store | Store state in Redis/DynamoDB keyed by entity ID, not partition | Extra latency on every read/write |
| Kafka Streams changelog | State backed by compacted Kafka topic, restored on reassignment | Rebuild time on rebalance |
| Sticky partitioning | Don't repartition — scale consumers vertically first | Limited scalability |
| Cooperative rebalancing (Kafka) | Only reassign partitions that need to move, others keep running | Kafka 2.4+ only, reduces pause |
| Accept stateless + idempotent | Recompute from scratch, make processing idempotent | Only works for short windows |

**Key interview point:** adding partitions/shards is NOT free for stateful apps. The routing key (partition key / message key) determines which consumer owns which data. Changing the number of partitions changes the routing — state built on old routing is now on the wrong node.

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

---

## Fan-out Deep Dive

Fan-out = one message published → multiple receivers each get a copy.

### Fan-out Comparison

| | Redis pub/sub | SNS | Kafka | Kinesis | SQS | Redis Streams |
|---|---|---|---|---|---|---|
| **Fan-out model** | All connected subscribers simultaneously | Push to all subscriptions | Each consumer group gets full copy independently | Each consumer group reads independently per shard | One consumer gets it (no fan-out) | Each consumer group independently |
| **Receiver offline** | Message lost | SQS buffers it; Lambda/HTTP retried 23x | Consumer reads when back online, no data loss | Consumer reads when back online (within retention) | Stays in queue (visibility timeout) | Pending in stream until acked |
| **Slow consumer** | No effect on others (fire and forget) | No effect (push doesn't wait) | Slow group falls behind, others unaffected | Slow group falls behind, others unaffected | Queue depth grows | Other groups unaffected |
| **N independent services** | All N get it if connected | All N subscriptions pushed | N consumer groups, each with own offset | N consumer groups, each reads same shard | N separate queues needed (via SNS) | N consumer groups |
| **Fan-out cost** | Free (one Redis publish) | Per delivery per subscription | Free (consumers pull) | Enhanced fan-out: $0.015/shard-hr per consumer | N × SQS cost | Free |
| **Max receivers** | Unlimited (network bound) | 12.5M subscriptions per topic | Unlimited consumer groups | Shared: 5 reads/sec per shard; Enhanced: unlimited | Unlimited queues behind SNS | Unlimited consumer groups |
| **Ordering per receiver** | FIFO per channel | No (FIFO SNS: per group) | FIFO per partition per group | FIFO per shard per group | No (FIFO queue: per group) | FIFO (global) |
| **Fan-out to live clients** | Native — built for this | No (pushes to AWS services only) | Via intermediary (Kafka → SSE server) | Via intermediary | No | Via intermediary |

---

### The Three Fan-out Patterns

#### Pattern 1 — Broadcast to live connections (Redis pub/sub)
```
Use when: 1M users watching same live match, realtime claps, presence updates
          Losing a message is OK (client will get next update)

Publisher → Redis PUBLISH match:final <update>
                 ↓ simultaneously
         SSE Server A  SSE Server B  SSE Server C
         (10K clients) (10K clients) (10K clients)

SSE servers are the Redis subscribers — not individual clients directly.
Redis fan-out: 3 sends. Each server fans out to 10K clients.
```

#### Pattern 2 — Fan-out to independent services (SNS + SQS)
```
Use when: order.placed → email + fraud + inventory + analytics
          Each service must process every event, can't miss any

order.placed event
      ↓
  SNS Topic
  ├── SQS: EmailQueue      → Email Service      (buffers, retries)
  ├── SQS: FraudQueue      → Fraud Service
  ├── SQS: InventoryQueue  → Inventory Service
  └── Lambda: Metrics      → immediate, no buffer needed

Each SQS queue is independent — Fraud being slow doesn't affect Email.
Retry + DLQ per service. Zero data loss.
```

#### Pattern 3 — Fan-out with replay (Kafka consumer groups)
```
Use when: > 5 services, new services need to catch up from history,
          stream processing (Flink), high throughput, exactly-once

Topic: user-events  [P0][P1][P2]

Consumer Group "billing":   offset 10,450
Consumer Group "analytics": offset 10,448  ← 2 behind, catching up
Consumer Group "ml-train":  offset 0       ← new service replaying history

New service joins → starts from offset 0, replays all history.
No re-publish needed. SNS can never do this.
```

---

### Decision: Which Fan-out for Your Use Case?

```
Are receivers live TCP connections (WebSocket/SSE clients)?
  YES → Redis pub/sub (only tool built for this)

Are receivers AWS services at volume < 300 TPS ordered?
  YES → SNS (simplest, zero ops, filter policies per subscription)

Are receivers independent services needing guaranteed delivery + replay?
  YES + low volume  → SNS + SQS per service
  YES + high volume → Kafka consumer groups

Does a new service need to replay historical events?
  YES → Kafka (SNS cannot replay, SNS+SQS cannot catch up from the past)

Do consumers process at wildly different speeds?
  YES → Kafka or SNS+SQS (slow consumer falls behind independently, others unaffected)
  NO (fire-and-forget OK) → Redis pub/sub
```

---

### The Three Anti-patterns

```
WRONG: Kafka for fan-out to live WebSocket clients
  Kafka poll interval (5–100ms) + processing = too slow for live UX
  Fix: Kafka → Redis pub/sub → WebSocket servers
       (Kafka for durability, Redis for last-mile delivery)

WRONG: Redis pub/sub for service-to-service fan-out
  Service B restarts 30 seconds → misses 30 seconds of events → silent data loss
  Fix: SNS + SQS (SQS buffers during restart, retries on failure)

WRONG: SNS FIFO for > 300 TPS ordered fan-out
  FIFO SNS hard limit: 300 TPS — cannot increase, ever
  Fix: Kafka partitioned by key
```

### The hybrid at scale

```
Kafka + Redis pub/sub together (most common at scale):

Kafka (durable backbone)
    ↓ consumer reads events
SSE/WebSocket servers
    ↓ PUBLISH to Redis pub/sub
Redis pub/sub
    ↓ fan-out to all connected servers
Live clients (millions)

Kafka guarantees no event is lost and provides replay.
Redis delivers to live connections in <1ms.
Neither alone handles both requirements.
```

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
