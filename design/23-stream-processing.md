# Stream Processing (Flink, Kafka Streams, Spark Streaming)

## What & Why
Process data as it arrives rather than storing then processing (batch). Enables real-time analytics, fraud detection, monitoring, and event-driven architectures.

**Netflix context**: 20,000+ concurrent Flink jobs processing 2 trillion events/day through Keystone.

---

## Batch vs Stream vs Micro-batch

```
Batch (Spark classic):
  Data accumulates → job runs every hour → results available 1h later
  Throughput: very high | Latency: high (minutes to hours)

Micro-batch (Spark Structured Streaming):
  Trigger every N seconds → small batch processed → results in seconds
  Throughput: high | Latency: seconds

True Streaming (Flink, Kafka Streams):
  Each event processed as it arrives
  Throughput: very high | Latency: milliseconds
```

---

## Lambda Architecture (Old Way)

```
                      ┌─ Batch Layer (Spark) ──────────────┐
Data → [Message Bus] ─┤                                    ├→ [Serving Layer] → Query
                      └─ Speed Layer (Storm/Flink) ─────── ┘

Batch: accurate but slow (runs on historical data)
Speed: fast but approximate (recent data)
Serving: merges both

Problem: TWO codebases doing same logic; complex; must merge outputs
```

## Kappa Architecture (Modern Way — Netflix uses this)

```
Data → [Kafka] → [Flink / Stream Processor] → [Iceberg / Druid / Kafka]

Single code path. For "batch" reprocessing: replay from Kafka beginning.
Everything is streaming. Much simpler.

Netflix Keystone IS the Kappa architecture:
  All events → Kafka → Flink (per-topic job) → Iceberg / Druid / Kafka
```

---

## Apache Flink — Deep Dive

### Core Concepts

```java
// Flink DataStream API
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

// Source: read from Kafka
KafkaSource<String> source = KafkaSource.<String>builder()
    .setBootstrapServers("kafka:9092")
    .setTopics("user-events")
    .setGroupId("flink-consumer-group")
    .setStartingOffsets(OffsetsInitializer.latest())
    .setValueOnlyDeserializer(new SimpleStringSchema())
    .build();

DataStream<String> rawEvents = env.fromSource(source,
    WatermarkStrategy.forBoundedOutOfOrderness(Duration.ofSeconds(5)), "Kafka Source");

// Parse, filter, transform
DataStream<UserEvent> events = rawEvents
    .map(json -> objectMapper.readValue(json, UserEvent.class))
    .filter(e -> e.getType().equals("view"))
    .assignTimestampsAndWatermarks(
        WatermarkStrategy.<UserEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
            .withTimestampAssigner((e, ts) -> e.getEventTimestamp())
    );

// Window: count views per user per 5-minute tumbling window
DataStream<ViewCount> counts = events
    .keyBy(UserEvent::getUserId)
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .aggregate(new ViewCountAggregator());

// Sink: write to Iceberg
IcebergSink<ViewCount> sink = IcebergSink.<ViewCount>forRow(table, flinkSchema)
    .tableLoader(tableLoader)
    .build();
counts.sinkTo(sink);

env.execute("User View Count Job");
```

---

## Time in Stream Processing — Critical Concept

### Event Time vs Processing Time vs Ingestion Time

```
Event Time:    when event ACTUALLY happened (in the device/app)
               → most accurate, but events arrive out of order
               → requires watermarks

Processing Time: when event is PROCESSED by Flink
               → non-deterministic (depends on system speed)
               → fast, but results differ on reprocessing

Ingestion Time: when event entered Kafka
               → middle ground: monotonic, but not true event time

Netflix uses EVENT TIME for all analytics — accuracy over simplicity.
```

```java
// Event time example
DataStream<UserEvent> withEventTime = events.assignTimestampsAndWatermarks(
    WatermarkStrategy
        .<UserEvent>forBoundedOutOfOrderness(Duration.ofSeconds(10)) // max 10s late
        .withTimestampAssigner((event, ts) -> event.getEventTimestampMs())
);
// Watermark = max_event_time_seen - 10s
// Window closes when watermark passes window end
// Events arriving more than 10s late are dropped (or go to side output)
```

### Watermarks — Handling Late Arriving Data

```
Events arrive: [t=100, t=95, t=98, t=110, t=90, t=105, ...]
                                            ^-- late! (90 arrived after 110)

Watermark = "I've seen events up to time T, nothing earlier will arrive"

With max lateness = 10s:
  When we see t=110 → watermark = 100
  Window [0,100) closes when watermark = 100
  Event t=90 arrives → watermark=100 > 90 → it's LATE

Handling late events:
  1. Drop (simplest, default)
  2. Side output: route to a late-events stream for separate handling
  3. Allowed lateness: keep window open extra time for late events
```

```java
// Late events: route to side output instead of dropping
final OutputTag<UserEvent> lateOutputTag = new OutputTag<>("late-events"){};

SingleOutputStreamOperator<ViewCount> result = events
    .keyBy(UserEvent::getUserId)
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .allowedLateness(Time.minutes(2)) // keep window open extra 2 min
    .sideOutputLateData(lateOutputTag)  // route remaining late to side
    .aggregate(new ViewCountAggregator());

// Process late events separately (e.g., update/correct previous output)
DataStream<UserEvent> lateEvents = result.getSideOutput(lateOutputTag);
lateEvents.addSink(new LateEventCorrectionSink());
```

---

## Window Types

```java
// 1. Tumbling Window — fixed size, no overlap
//    |--5min--|--5min--|--5min--|
events.window(TumblingEventTimeWindows.of(Time.minutes(5)))

// 2. Sliding Window — fixed size, with overlap
//    |--10min--|
//         |--10min--|   (slide every 5min)
//              |--10min--|
events.window(SlidingEventTimeWindows.of(Time.minutes(10), Time.minutes(5)))

// 3. Session Window — gap-based; closes after inactivity
//    |--session1--|--30min gap--|--session2--|
//    Session has NO fixed length — grows until no event arrives for 30min
//    withGap() = inactivity timeout, NOT session max length
events.window(EventTimeSessionWindows.withGap(Time.minutes(30)))
// Use case: "user session" analytics — any activity within 30min = same session
// To cap session length: use ProcessWindowFunction to check window.getEnd() - window.getStart()

// 4. Global Window with Trigger
//    Accumulate until custom trigger fires
events.window(GlobalWindows.create())
    .trigger(CountTrigger.of(1000)) // trigger every 1000 events
```

---

## Exactly-Once Semantics — Critical for Data Correctness

```java
// At-least-once: events may be processed more than once (duplicates)
// At-most-once: events may be dropped (data loss)
// Exactly-once: each event processed exactly once (hardest, most important)

// Flink + Kafka: exactly-once via checkpointing + Kafka transactions

// Enable checkpointing (Flink's fault tolerance mechanism)
env.enableCheckpointing(60_000); // checkpoint every 60 seconds
env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
env.getCheckpointConfig().setMinPauseBetweenCheckpoints(500);
env.getCheckpointConfig().setCheckpointTimeout(60_000);
env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);

// Checkpoint = consistent snapshot of ALL operator state across all parallel instances
// If Flink crashes → restore from latest checkpoint → replay Kafka from checkpointed offset
// With exactly-once sink (Kafka, Iceberg): writes are committed atomically with checkpoint
// Result: exactly-once end-to-end

// Flink checkpoint flow:
// 1. JobManager sends CheckpointBarrier into all source streams
// 2. Each operator receives barrier → snapshots its state → passes barrier downstream
// 3. When all sinks receive barrier → checkpoint complete → JobManager persists
// 4. On failure → restore all operators to snapshot state → replay from Kafka offset
```

---

## Stateful Stream Processing

```java
// Flink maintains keyed state — persisted across events, checkpointed

public class FraudDetectionFunction extends KeyedProcessFunction<String, Transaction, Alert> {
    // State: flag if large transaction seen in last hour
    private ValueState<Boolean> flagState;
    // State: timer to clear flag after 1 hour
    private ValueState<Long> timerState;

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Boolean> flagDesc = new ValueStateDescriptor<>("flag", Boolean.class);
        flagState = getRuntimeContext().getState(flagDesc);
        ValueStateDescriptor<Long> timerDesc = new ValueStateDescriptor<>("timer", Long.class);
        timerState = getRuntimeContext().getState(timerDesc);
    }

    @Override
    public void processElement(Transaction txn, Context ctx, Collector<Alert> out) throws Exception {
        Boolean flag = flagState.value();

        if (flag != null && flag) {
            if (txn.getAmount() < 1.00) {
                // Small transaction after large one = fraud pattern
                out.collect(new Alert(txn.getAccountId(), "Suspicious pattern detected"));
            }
        }

        if (txn.getAmount() > 1000) {
            flagState.update(true);
            long timer = ctx.timerService().currentProcessingTime() + 3_600_000; // 1h
            ctx.timerService().registerProcessingTimeTimer(timer);
            timerState.update(timer);
        }
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<Alert> out) throws Exception {
        // Timer fires after 1 hour — clear flag
        flagState.clear();
        timerState.clear();
    }
}

// State backends: HashMapStateBackend (memory), RocksDBStateBackend (disk, for large state)
env.setStateBackend(new EmbeddedRocksDBStateBackend(true)); // true = incremental checkpoints
env.getCheckpointConfig().setCheckpointStorage("s3://bucket/checkpoints");
```

---

## RocksDB State Backend — Deep Dive

### How Checkpointing to S3 Works

RocksDB state lives on the **local disk** of each TaskManager. S3 is where checkpoints are durably persisted — it is NOT where reads/writes happen during normal processing.

```
Normal operation:
  Event arrives → Flink operator → reads/writes RocksDB on LOCAL DISK (fast, ~µs)
                                    (S3 is NOT involved per-event)

Checkpoint triggered (every 60s by default):
  Flink injects checkpoint barrier into stream
  Each operator: RocksDB flushes memtable → creates SSTable snapshot
  With incremental=true: only NEW/CHANGED SSTables uploaded to S3
  (unchanged SSTables are referenced from previous checkpoint, not re-uploaded)

On failure + restart:
  TaskManagers download checkpoint from S3 → restore RocksDB locally → resume
```

```
Local disk (TaskManager):          S3 (durable storage):
┌──────────────────────┐          ┌──────────────────────────────┐
│ RocksDB instance     │ ──────→  │ checkpoint-1/                │
│  memtable (memory)   │ checkpoint│   sst-001.sst (full)        │
│  SSTable files       │          │ checkpoint-2/                │
│  (local SSD/HDD)     │          │   sst-002.sst (new only) ←  │
└──────────────────────┘          │   ref: sst-001.sst          │
  reads/writes here               └──────────────────────────────┘
  during processing                 uploaded here during checkpoint
```

**Key implication:** if the TaskManager dies between checkpoints, events processed since the last checkpoint are replayed from Kafka. State changes since last checkpoint are lost and recomputed. This is why checkpoint interval matters — shorter = less replay on failure, more S3 writes.

---

### State Size Limitations

```
HashMapStateBackend (in-memory):
  ❌ Limited to TaskManager JVM heap
  ❌ State for ALL keys on a task slot must fit in heap
  ✅ Fastest (pure memory access)
  Use when: small state per key, low cardinality keyspace

RocksDB (EmbeddedRocksDBStateBackend):
  ✅ Bounded only by LOCAL DISK size (not heap)
  ✅ RocksDB manages its own memory (block cache, write buffer) separately from JVM heap
  ✅ Compaction keeps disk usage bounded (old SSTables merged and cleaned)
  ❌ ~10x slower than HashMapStateBackend (disk I/O vs memory)
  ❌ JVM heap still needed for Flink runtime, network buffers, non-state objects

Practical limits:
  State per TaskManager: up to disk size (TBs possible with NVMe)
  Keys per TaskManager: millions — RocksDB handles this natively
  Value size per key: up to GBs (but you'd need very wide rows — unusual)
  Total state across cluster: disk size × number of TaskManagers
```

```java
// Tune RocksDB memory to prevent OOM in JVM:
RocksDBStateBackend backend = new RocksDBStateBackend(checkpointPath, true);

// Bound total RocksDB memory usage (shared across all state on this TaskManager)
RocksDBOptionsFactory optionsFactory = new DefaultConfigurableOptionsFactory();
// In flink-conf.yaml:
// state.backend.rocksdb.memory.managed: true          ← let Flink manage RocksDB memory
// state.backend.rocksdb.memory.fixed-per-slot: 256mb  ← cap per task slot
// state.backend.rocksdb.block.cache-size: 64mb
// state.backend.rocksdb.writebuffer.size: 64mb
// state.backend.rocksdb.writebuffer.count: 2
```

---

### Schema Evolution — What Happens When You Change State?

This is one of the trickiest operational challenges in stateful streaming.

**Flink serializes state using its own `TypeSerializer`.** If you change the class structure, the deserializer may fail when reading old checkpoints.

```
Scenario: your state class is BehaviorProfile

v1: class BehaviorProfile { long eventCount; }
v2: class BehaviorProfile { long eventCount; String region; }  ← added field

What happens on upgrade:
  Old checkpoint has bytes for: [eventCount=42]
  New deserializer expects:     [eventCount, region]
  Result: CompatibilityResult.INCOMPATIBLE → job refuses to start from checkpoint
  You are forced to start from scratch (TRIM_HORIZON in Kafka) and rebuild state
```

**Safe vs unsafe changes:**

| Change | Safe? | Notes |
|--------|-------|-------|
| Add field with default value | ✅ with care | Use Avro/Kryo serializer, not POJO |
| Remove field | ❌ | Breaks existing checkpoints |
| Rename field | ❌ | Treated as remove + add |
| Change field type | ❌ | Binary incompatible |
| Add new state variable | ✅ | New variable starts empty; old variable reads fine |
| Remove state variable | ✅ | Old bytes ignored |

**How to handle schema evolution safely:**

```java
// Option 1: Use Avro serialization for state (schema evolution built-in)
// Register Avro schema with Schema Registry — forward/backward compatible changes are safe

// Option 2: Version your state explicitly
public class BehaviorProfile {
    private int schemaVersion = 2;  // bump on every change
    private long eventCount;
    private String region;  // added in v2

    // Custom TypeSerializer reads schemaVersion first, handles old format
}

// Option 3: State migration via savepoint
// 1. Take savepoint (manual checkpoint): flink savepoint <jobId> s3://savepoints/
// 2. Stop job
// 3. Write migration job: reads old state format → transforms → writes new state format
// 4. Restart from savepoint with new schema
```

**Savepoint = your migration tool:**
```
Savepoint vs Checkpoint:
  Checkpoint: automatic, managed by Flink, deleted when superseded
  Savepoint:  manual, user-managed, never auto-deleted, survives job upgrades

Workflow for schema migration:
  1. flink savepoint <jobId>          ← snapshot state at a point in time
  2. Stop job
  3. Deploy new code (new schema)
  4. flink run --fromSavepoint s3://savepoints/sp-123 my-job.jar
     ↑ Flink checks TypeSerializer compatibility on start
     ↑ If compatible → restore and continue
     ↑ If not → fails fast, savepoint untouched (safe to retry)
```

---

### Flink SQL for Stateful Streaming

Yes — Flink SQL can express stateful streaming queries, including windowed aggregations, joins, and deduplication. The SQL compiles down to the same DataStream operators internally.

```sql
-- Flink SQL: tumbling window aggregation (stateful)
SELECT
    user_id,
    TUMBLE_START(event_time, INTERVAL '5' MINUTE) AS window_start,
    COUNT(*) AS view_count
FROM user_events
GROUP BY
    user_id,
    TUMBLE(event_time, INTERVAL '5' MINUTE);

-- Session window (same withGap concept, SQL syntax)
SELECT
    user_id,
    SESSION_START(event_time, INTERVAL '30' MINUTE) AS session_start,
    SESSION_END(event_time, INTERVAL '30' MINUTE)   AS session_end,
    COUNT(*) AS events_in_session
FROM user_events
GROUP BY
    user_id,
    SESSION(event_time, INTERVAL '30' MINUTE);

-- Deduplication (stateful — Flink keeps last-seen per key)
SELECT *
FROM (
    SELECT *,
        ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY proctime DESC) AS rn
    FROM orders
)
WHERE rn = 1;

-- Stream-stream join (stateful — Flink buffers both sides in state)
SELECT o.order_id, o.amount, p.payment_method
FROM orders o
JOIN payments p
  ON o.order_id = p.order_id
  AND p.payment_time BETWEEN o.order_time - INTERVAL '1' MINUTE
                         AND o.order_time + INTERVAL '1' MINUTE;
```

**What SQL cannot express (must use DataStream API):**

```
SQL limitations:
  ❌ Custom timer logic (e.g., "fire after 1hr of inactivity AND emit only if count > 5")
  ❌ Complex fraud patterns (CEP — use Flink CEP library instead)
  ❌ Side outputs (routing late events to a separate stream)
  ❌ Arbitrary state shapes (SQL state is always window-scoped or join-buffered)
  ❌ Custom watermark strategies per-partition

Use DataStream API when:
  → Business logic can't be expressed as GROUP BY / JOIN / ROW_NUMBER
  → You need KeyedProcessFunction with multiple state variables
  → You need timers, side outputs, or CEP patterns
```

---

### Rescaling — How Flink Rebalances State

This is where Flink fundamentally beats Spark.

**How Flink keys state:**
```
Key: userId → hash → key group (0..maxParallelism-1)
maxParallelism is set at job creation (default: 128)
key groups are then assigned to task slots

parallelism=4, maxParallelism=128:
  Task 0: key groups [0..31]    (32 groups)
  Task 1: key groups [32..63]   (32 groups)
  Task 2: key groups [64..95]   (32 groups)
  Task 3: key groups [96..127]  (32 groups)
```

**Rescaling up (parallelism 4 → 8):**
```
Before rescale:           After rescale (from savepoint):
  Task 0: groups [0..31]   Task 0: groups [0..15]
  Task 1: groups [32..63]  Task 1: groups [16..31]
  Task 2: groups [64..95]  Task 2: groups [32..47]
  Task 3: groups [96..127] Task 3: groups [48..63]
                           Task 4: groups [64..79]
                           Task 5: groups [80..95]
                           Task 6: groups [96..111]
                           Task 7: groups [112..127]

Flink splits the key groups and ships the corresponding RocksDB state
to the new task slots. State is NOT recomputed — just redistributed.
```

```java
// Rescaling workflow:
// 1. Take savepoint
// 2. flink run --fromSavepoint s3://sp/... -p 8 my-job.jar
//    ↑ -p 8 means new parallelism = 8
//    Flink reads savepoint, redistributes key groups → job starts with 8 tasks

// Critical: maxParallelism constrains maximum rescaling target
// If maxParallelism=128, you can never scale beyond 128 parallelism
// Set it conservatively high at job creation:
env.setMaxParallelism(1024); // never set lower than your expected peak parallelism
```

**The Spark problem you called out:**
```
Spark Structured Streaming — why you CANNOT change parallelism for stateful queries:

spark.sql.shuffle.partitions = 200  ← determines state partitioning

State is stored per partition. Changing this value means:
  - State for key "user-123" was in partition hash("user-123") % 200 = partition 47
  - After change to 400 partitions: hash("user-123") % 400 = partition 247
  - Partition 247 has no state for "user-123" → state is LOST

Spark has no key group abstraction. State is tied directly to shuffle partition count.
Changing shuffle.partitions = dropping all existing state = full recompute from beginning.

Workarounds (all painful):
  1. Choose partition count conservatively high from day 1 and never change it
  2. Write a batch migration job to re-partition state manually
  3. Stop job, reprocess from beginning with new partition count
     (only feasible if Kafka retention covers full history)

Flink's maxParallelism solves this with key groups — the indirection layer
between key→group and group→task means tasks can change without remapping keys.
```

---

## Kafka Streams (Lighter Weight)

```java
// When you don't need Flink's complexity: Kafka Streams runs as a library in your app
StreamsBuilder builder = new StreamsBuilder();

KStream<String, String> orders = builder.stream("orders");

// Real-time revenue aggregation per product category
KTable<String, Double> revenueByCategory = orders
    .mapValues(v -> objectMapper.readValue(v, Order.class))
    .groupBy((k, order) -> order.getCategory())
    .aggregate(
        () -> 0.0,
        (category, order, total) -> total + order.getAmount(),
        Materialized.<String, Double, KeyValueStore<Bytes, byte[]>>as("revenue-store")
            .withValueSerde(Serdes.Double())
    );

// Output back to Kafka
revenueByCategory.toStream().to("revenue-by-category");

KafkaStreams streams = new KafkaStreams(builder.build(), config);
streams.start();
// Automatically partitioned by key — same category goes to same instance
// State stored locally (RocksDB) + replicated to Kafka for fault tolerance
```

---

## Netflix Keystone Architecture (The Real System)

```
Input:        2T+ events/day across thousands of Kafka topics

Original Design (monolith):
  One massive Flink job consuming ALL topics → routing logic → multiple sinks
  Problem: single job failure affects everything; can't tune per-topic

Current Design (1:1 Flink-per-topic):
  Each Kafka topic → dedicated Flink job
    ↓
  Flink job does: schema validation, enrichment, routing
    ↓
  Configurable sinks:
    → Another Kafka topic (fan-out)
    → Apache Iceberg table (data lake storage)
    → Apache Druid (real-time OLAP)

Benefits:
  - Independent scaling and failure domains
  - Per-topic configuration and SLAs
  - Simpler operational model
  - 20K+ concurrent jobs
```

```java
// Design question: "Design a system like Keystone"
// Key decisions to articulate:

// 1. Routing/fan-out: how does one event go to multiple sinks?
public class KeystoneRouter {
    public void routeEvent(Event event) {
        List<Sink> sinks = routingConfig.getSinks(event.getTopic()); // from config service
        sinks.parallelStream().forEach(sink -> {
            try {
                sink.write(event);
            } catch (Exception e) {
                deadLetterQueue.send(event, sink, e); // don't drop, don't block
            }
        });
    }
}

// 2. Schema enforcement: Avro + Schema Registry
// 3. Exactly-once: Flink checkpoints + transactional sinks
// 4. Backpressure: Flink automatically slows source when sinks are slow
// 5. Observability: consumer lag per topic, events/sec, error rate
```

---

## Stream Processing Design Patterns

### 1. Enrichment (join stream with lookup table)
```java
// Enrich click events with user profile data
DataStream<ClickEvent> clicks = ...;
MapStateDescriptor<String, UserProfile> profileDesc =
    new MapStateDescriptor<>("profiles", String.class, UserProfile.class);

BroadcastStream<UserProfile> profiles = profileStream.broadcast(profileDesc);

DataStream<EnrichedClick> enriched = clicks.connect(profiles).process(
    new BroadcastProcessFunction<ClickEvent, UserProfile, EnrichedClick>() {
        @Override
        public void processElement(ClickEvent click, ReadOnlyContext ctx, Collector<EnrichedClick> out) {
            ReadOnlyBroadcastState<String, UserProfile> state = ctx.getBroadcastState(profileDesc);
            UserProfile profile = state.get(click.getUserId());
            out.collect(new EnrichedClick(click, profile));
        }
        @Override
        public void processBroadcastElement(UserProfile profile, Context ctx, Collector<EnrichedClick> out) {
            ctx.getBroadcastState(profileDesc).put(profile.getUserId(), profile);
        }
    }
);
```

### 2. Stream-Stream Join
```java
// Join orders with payments within 5-minute window
DataStream<Order> orders = ...;
DataStream<Payment> payments = ...;

DataStream<OrderWithPayment> result = orders
    .keyBy(Order::getOrderId)
    .intervalJoin(payments.keyBy(Payment::getOrderId))
    .between(Time.seconds(-60), Time.seconds(60)) // payment arrives within 60s of order
    .process((order, payment, ctx, out) ->
        out.collect(new OrderWithPayment(order, payment))
    );
```

### 3. Deduplication
```java
// Exactly-once delivery may still have application-level duplicates
public class DeduplicateFunction extends KeyedProcessFunction<String, Event, Event> {
    private ValueState<Long> lastSeenTimestamp;

    @Override
    public void processElement(Event event, Context ctx, Collector<Event> out) throws Exception {
        Long lastSeen = lastSeenTimestamp.value();
        if (lastSeen == null || event.getEventId().hashCode() > lastSeen) {
            out.collect(event);
            lastSeenTimestamp.update((long) event.getEventId().hashCode());
        }
        // Better: use event ID in a ValueState<Set<String>> with TTL
    }
}
```

---

## When to Use What

| Use Case | Tool | Why |
|----------|------|-----|
| Real-time analytics at scale | Flink | Exactly-once, stateful, event time |
| Simple per-event transforms | Kafka Streams | Lightweight, library not cluster |
| Batch backfill / reprocessing | Spark | Mature ecosystem, better for batch |
| Operational telemetry | Mantis (Netflix) / Flink | Low latency, in-memory |
| Complex event correlation | Flink CEP | Pattern matching across events |

---

## Interview Questions

1. **What is a watermark?** A signal telling the stream processor "all events with timestamp < T have arrived." When the watermark passes the end of a window, that window can be closed and results emitted.
2. **Exactly-once vs at-least-once?** Exactly-once: each event affects output exactly once, via checkpointing + transactional sinks. At-least-once: faster, may produce duplicates, make consumer idempotent.
3. **How does Flink recover from failure?** Restores all operator states from latest checkpoint, then replays Kafka from the offset saved in that checkpoint. All un-checkpointed processing is redone.
4. **Lambda vs Kappa architecture?** Lambda: separate batch + speed layers (complex). Kappa: everything is streaming, batch = replay from beginning (simpler, Netflix's approach).
5. **How would you handle a spike in input volume (10× normal)?** Flink applies backpressure automatically (slows Kafka consumption). Scale out by adding parallelism/task managers. Pre-provision extra capacity for peak.

---

## Flink vs Spark — Deep Comparison

> **Jay's context**: You know Spark Streaming deeply from GuardDuty. This section is your bridge. You've already lived the pain points that motivated Flink's design.

### The 30-Second Summary
Spark was built for batch first, streaming was added later (micro-batch model). Flink was built for streaming first — events are processed one at a time as they arrive. This fundamental difference drives every trade-off below.

---

### Processing Model

```
SPARK STRUCTURED STREAMING (micro-batch):
  Kafka → [collect N seconds of events] → [process as small batch] → output
  Trigger interval: 100ms to minutes
  Latency: typically 1-30 seconds
  Mental model: batch job that runs on a tight schedule

SPARK CONTINUOUS PROCESSING (experimental, limited adoption):
  Attempts true streaming — but unstable, rarely used in production

FLINK (true streaming):
  Kafka → [process each event as it arrives] → output
  Latency: milliseconds
  Mental model: a long-running stateful function called per event
```

```java
// SPARK: trigger-based micro-batch
Dataset<Row> stream = spark.readStream().format("kafka")
    .option("kafka.bootstrap.servers", "kafka:9092")
    .option("subscribe", "events").load();

stream.writeStream()
    .trigger(Trigger.ProcessingTime("5 seconds")) // batch every 5 seconds
    .outputMode("append")
    .format("iceberg")
    .start();
// Latency: at minimum 5 seconds (plus processing time)

// FLINK: process each event immediately
KafkaSource<String> source = KafkaSource.<String>builder()
    .setBootstrapServers("kafka:9092")
    .setTopics("events").build();

env.fromSource(source, WatermarkStrategy.noWatermarks(), "Kafka")
   .map(this::parseEvent)
   .sinkTo(icebergSink);
// Latency: milliseconds
```

---

### State Management — The Core Difference

This is where you have lived experience: you replaced Spark's broken checkpointing with DynamoDB. Flink's native state is what you were trying to build.

```
SPARK STREAMING STATE:
  updateStateByKey() / mapGroupsWithState() — stored in Spark executor memory
  Checkpointed to HDFS/S3 periodically
  Problems you experienced at GuardDuty:
    - All state for a key must fit in one executor's memory
    - Checkpoint is all-or-nothing: if one executor fails mid-checkpoint → data loss
    - State tied to Spark's internal RDD/partition model — hard to scale independently
    - Rebalancing/rescaling requires state migration (painful)
  Your fix: externalize state to DynamoDB — independent scalability, durability

FLINK STATE:
  Keyed state lives in state backend (HashMapStateBackend or RocksDBStateBackend)
  Checkpointed incrementally to S3 — each operator snapshots its own state
  What you WOULD have built if you had Flink:
    - RocksDB backend: state larger than memory, spills to disk automatically
    - Incremental checkpointing: only changed state blocks sent to S3
    - Rescaling: state automatically redistributed across new parallelism
    - No external DynamoDB needed for hot state
```

```java
// SPARK: external state in DynamoDB (what you built)
public class GuardDutyStatefulDetection {
    public void process(Event event) {
        // Must read/write external store on every event
        BehaviorProfile profile = dynamoDB.getItem(event.getContainerId()); // network I/O
        boolean anomaly = detect(event, profile);
        dynamoDB.updateItem(event.getContainerId(), updatedProfile); // network I/O
        if (anomaly) emit(event);
    }
    // Latency cost: 2x DynamoDB round trips per event
    // Your optimization: async DynamoDB + batching → 10x improvement
}

// FLINK: native keyed state — no external store needed
public class FlinkStatefulDetection extends KeyedProcessFunction<String, Event, Alert> {
    private MapState<String, BehaviorProfile> profiles; // lives in RocksDB, local

    @Override
    public void processElement(Event event, Context ctx, Collector<Alert> out) {
        BehaviorProfile profile = profiles.get(event.getContainerId()); // local disk read
        if (profile == null) profile = new BehaviorProfile();
        boolean anomaly = detect(event, profile);
        profiles.put(event.getContainerId(), profile); // local disk write
        if (anomaly) out.collect(new Alert(event));
    }
    // No network I/O for state — orders of magnitude faster
    // State checkpointed to S3 automatically by Flink runtime
}
```

---

### Checkpointing

```
SPARK CHECKPOINTING:
  Full checkpoint of all RDD lineage + state to HDFS/S3
  All-or-nothing: entire job checkpoints together
  Recovery: replay ALL events since last checkpoint (slow if interval is long)
  Known issue: partial checkpoint failure can cause data loss (you fixed this with DynamoDB!)
  Frequency: typically every few minutes (large state = slow checkpoint)

FLINK CHECKPOINTING (Chandy-Lamport algorithm):
  Coordinator injects a "barrier" into the event stream
  Each operator checkpoints its own state when it receives the barrier
  Operators continue processing WHILE checkpoint is in progress (non-blocking)
  Incremental: only changed RocksDB SSTables sent to S3 (not full state)
  Recovery: restore per-operator state from checkpoint, replay Kafka from saved offset
  Frequency: every 30-60 seconds is typical (incremental = fast even for large state)
```

```java
// FLINK: checkpoint configuration (compare to what you did with DynamoDB workaround)
env.enableCheckpointing(60_000); // checkpoint every 60 seconds
env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);

// RocksDB: handles state larger than memory (critical for large profiles)
env.setStateBackend(new EmbeddedRocksDBStateBackend(true)); // true = incremental
env.getCheckpointConfig().setCheckpointStorage("s3://checkpoints/flink/");

// Result: state durably checkpointed to S3 without the data loss issues
// you saw in Spark — and without needing an external DynamoDB
```

---

### Exactly-Once Semantics

```
SPARK STRUCTURED STREAMING:
  Exactly-once for some sinks (idempotent writes or transactional)
  Requires: source has replayable offsets (Kafka ✅) + sink is idempotent
  Implementation: write-ahead log + idempotent sink writes
  Limitation: not truly end-to-end for all sink types

FLINK:
  True end-to-end exactly-once via:
    1. Kafka source: save offset in checkpoint
    2. Flink state: checkpointed atomically (Chandy-Lamport)
    3. Kafka/Iceberg sink: two-phase commit tied to checkpoint
  When checkpoint completes → all three are committed atomically
  If crash before checkpoint: restore state + replay Kafka from last checkpoint offset
  Result: guaranteed exactly-once even across source → processing → sink
```

```java
// FLINK exactly-once: two-phase commit sink
// Flink's KafkaSink uses 2PC: pre-commit on each event, commit only on checkpoint
KafkaSink<String> sink = KafkaSink.<String>builder()
    .setBootstrapServers("kafka:9092")
    .setRecordSerializer(KafkaRecordSerializationSchema.builder()
        .setTopic("output-topic")
        .setValueSerializationSchema(new SimpleStringSchema())
        .build())
    .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE) // requires Kafka transactions
    .setTransactionalIdPrefix("flink-producer-")
    .build();
// Kafka transactions ensure: if Flink job crashes, uncommitted Kafka messages are aborted
```

---

### Event Time and Watermarks

```
SPARK STRUCTURED STREAMING:
  withWatermark() — similar concept to Flink watermarks
  Event time windows: supported
  Late data: can be dropped or aggregated separately
  Limitation: watermark logic is simpler, less flexible than Flink

FLINK:
  First-class event time support with pluggable WatermarkStrategy
  Per-partition watermarks: each Kafka partition tracks its own watermark
  → More accurate watermarks when partitions have different lag
  Idle source handling: if one partition is silent, watermark still advances
  Custom watermark generators: full control over lateness logic
```

```java
// SPARK: watermark
Dataset<Row> events = spark.readStream()...;
events.withWatermark("event_time", "10 seconds") // drop events > 10s late
      .groupBy(window(col("event_time"), "5 minutes"), col("user_id"))
      .count();

// FLINK: more granular control
WatermarkStrategy<Event> strategy = WatermarkStrategy
    .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(10))
    .withTimestampAssigner((e, ts) -> e.getEventTimestampMs())
    .withIdleness(Duration.ofMinutes(1)); // handle idle Kafka partitions

DataStream<Event> stream = env.fromSource(kafkaSource, strategy, "Kafka");
// Flink tracks watermark per-partition → more accurate for skewed partitions
```

---

### Backpressure

```
SPARK:
  Micro-batch model naturally limits throughput (process what arrived in last N seconds)
  PID-based rate controller (spark.streaming.backpressure.enabled)
  But: if one micro-batch is slow, next micro-batch queues up → latency grows

FLINK:
  Credit-based backpressure: operators signal downstream capacity via "credits"
  If sink is slow → upstream operators slow down automatically
  Source (Kafka consumer) slows down → natural backpressure to the broker
  No separate configuration needed — built into the runtime
  Result: system self-regulates under load without manual tuning
```

---

### Operational Comparison

| Dimension | Spark Streaming | Flink |
|-----------|----------------|-------|
| **Processing model** | Micro-batch (trigger-based) | True streaming (event-driven) |
| **Latency** | Seconds (trigger interval) | Milliseconds |
| **State storage** | External (DynamoDB) or in-memory | Native (RocksDB), locally managed |
| **Checkpointing** | All-or-nothing, slow for large state | Incremental, non-blocking, fast |
| **Exactly-once** | Possible, requires care | Native, end-to-end via 2PC |
| **Watermarks** | Supported, simpler | First-class, per-partition |
| **Backpressure** | Manual tuning needed | Automatic credit-based |
| **Scaling** | Restart job to change parallelism | Dynamic scaling (Flink 1.17+) |
| **Batch + stream** | Unified API (SparkSession) | Unified API (DataStream/Table) |
| **Maturity** | Very mature, large ecosystem | Mature, growing fast |
| **Learning curve** | Lower (if you know Spark) | Higher, but more powerful |
| **Netflix use** | Batch jobs, backfill | All streaming (20K+ jobs) |
| **Your experience** | Expert (GuardDuty) | Study needed |

---

### When Netflix Uses Spark vs Flink

```
Netflix uses BOTH — they serve different purposes:

FLINK (Keystone pipeline, ~20K concurrent jobs):
  → Real-time event routing (Kafka → Iceberg/Druid)
  → Millisecond-latency processing
  → Long-running stateful jobs (fraud detection, session tracking)
  → Streaming SQL processors (Data Mesh)

SPARK (Maestro-orchestrated batch jobs):
  → Nightly/hourly batch aggregations
  → Iceberg table compaction (merging small streaming files)
  → Large-scale backfills and reprocessing
  → ML feature engineering (scheduled, not real-time)
  → Complex multi-table joins (better than Flink for batch)

Rule of thumb: if latency requirement < 10 seconds → Flink. Otherwise → Spark.
```

---

---

## Spark 4.0 Structured Streaming — What Changed

> Spark 4.0 (released 2025) is the most significant Structured Streaming release since Spark 2.0 added the API. The headline: Spark now has a real stateful streaming API (`TransformWithState`) that competes directly with Flink's `KeyedProcessFunction`. Here's what changed and how it shifts the Spark vs Flink decision.

---

### 1. TransformWithState — Spark's Answer to Flink's KeyedProcessFunction

The old API (`flatMapGroupsWithState`) was limited: one state variable per group, no timers, no multiple state types. Spark 4.0 replaces it with `TransformWithState`, which introduces **arbitrary state** — multiple state variables, multiple column families, timers, TTL, and list/map state.

```python
# Spark 4.0: TransformWithState (Python)
# Before: flatMapGroupsWithState — one state variable, rigid
# Now: full stateful processor with multiple state variables + timers

class FraudDetectionProcessor(StatefulProcessor):
    def init(self, handle: StatefulProcessorHandle) -> None:
        # Multiple named state variables per key (like Flink's ValueState/MapState)
        self.flag_state = handle.getValueState("flag", schema)
        self.profile_state = handle.getMapState("profile", key_schema, value_schema)
        self.timer_state = handle.getListState("timers", schema)

    def handleInputRows(self, key, rows, timer_values) -> Iterator[pd.DataFrame]:
        flag = self.flag_state.get()
        for row in rows:
            if row["amount"] > 1000:
                self.flag_state.update(True)
                # Register timer — fires after 1 hour (like Flink's timerService)
                self.handle.registerTimer(currentMs + 3_600_000)
            if flag and row["amount"] < 1.0:
                yield pd.DataFrame({"alert": ["Suspicious pattern"]})

    def handleExpiredTimer(self, key, timer_values) -> Iterator[pd.DataFrame]:
        # Called when timer fires — clear state (identical pattern to Flink)
        self.flag_state.clear()
        return iter([])

df.groupBy("account_id").transformWithState(
    FraudDetectionProcessor(),
    outputStructType,
    TimeMode.ProcessingTime()
)
```

**How this compares to Flink:**
```
Flink KeyedProcessFunction          Spark 4.0 TransformWithState
─────────────────────────────────   ────────────────────────────────
ValueState<T>                   →   handle.getValueState("name", schema)
MapState<K, V>                  →   handle.getMapState("name", k_schema, v_schema)
ListState<T>                    →   handle.getListState("name", schema)
timerService().registerTimer()  →   handle.registerTimer(timestamp)
onTimer()                       →   handleExpiredTimer()
TTL via StateTtlConfig          →   TTL per state variable (ListState TTL: SPARK-49744)
```

The API is almost isomorphic. The key difference: Flink's state lives in RocksDB natively since Flink 1.x; Spark's RocksDB state backend was added in Spark 3.2 and is now the default for `TransformWithState`.

---

### 2. State Data Source — Spark's Unique Advantage

Flink has **no built-in way to query state at rest**. To debug Flink state you need custom tooling or read checkpoints directly. Spark 4.0 adds a first-class **State Data Source** — you can read any streaming operator's state as a DataFrame:

```python
# Read state as a batch DataFrame — unique to Spark, Flink has no equivalent
state_df = spark.read.format("statestore") \
    .option("path", "/checkpoint/stateful-query") \
    .option("batchId", 42) \
    .option("operatorId", 0) \
    .load()

state_df.show()
# +──────────────+──────────────────+──────────────+
# | account_id   | flag             | last_updated |
# +──────────────+──────────────────+──────────────+
# | acc-123      | true             | 1712345678   |

# Change Feed Reader — see what changed between batches (like CDC for state)
changes_df = spark.read.format("statestore") \
    .option("readChangeFeed", "true") \
    .option("startBatchId", 40) \
    .load()
# Shows: which keys were updated/deleted between batch 40 and now
```

**Why this matters:** production debugging of stateful streaming jobs is notoriously hard. State Data Source lets you answer "what is the state for key X right now?" without stopping the job. This is a genuine operational advantage over Flink.

---

### 3. maxBytesPerTrigger — Better Backpressure Control

```python
# Spark 4.0: cap how much data is processed per micro-batch
df = spark.readStream.format("kafka") \
    .option("maxBytesPerTrigger", "100mb") \  # NEW in 4.0
    .option("maxOffsetsPerTrigger", 10000) \   # existed before
    .load()
```

Previously you could only limit by record count. Byte-based limits are more predictable for variable-size messages (e.g., a batch of 10K 1MB messages is very different from 10K 1KB messages).

---

### 4. RocksDB Improvements

```
Spark 4.0 RocksDB state backend:
  - Configurable compression (SPARK-45503) — LZ4, Snappy, Zstd
  - fallocate() can be disabled (SPARK-45415) — important for cloud storage
  - Virtual Column Families (SPARK-48742) — separate RocksDB column family per state variable
    → avoids key collisions between state variables; matches how Flink organizes RocksDB
  - Schema evolution for state (SPARK-50714) — add/remove fields from state schema without full replay
```

---

### 5. Schema Evolution for State

Previously: changing your state schema required wiping state and replaying from scratch. Spark 4.0 adds `StateSchemaV3` and Avro encoding for `TransformWithState` — you can evolve the state schema across versions.

Flink handles this via `TypeInformation` compatibility checks + manual migration code. Spark 4.0's approach (Avro-based schema registry integration) is arguably cleaner.

---

### Updated Comparison: Spark 4.0 vs Flink

| Dimension | Spark 4.0 Structured Streaming | Flink |
|-----------|-------------------------------|-------|
| **Processing model** | Micro-batch (default); Continuous mode (experimental) | True streaming (event-driven) |
| **Latency** | ~100ms–seconds (trigger interval) | Milliseconds |
| **Stateful API** | `TransformWithState` — multiple state vars, timers, TTL | `KeyedProcessFunction` — same capabilities, mature |
| **State backend** | RocksDB (Spark 3.2+, improved in 4.0) | RocksDB (first-class since Flink 1.x) |
| **State debugging** | State Data Source — query state as DataFrame ✅ | No native state query — external tooling needed ❌ |
| **State schema evolution** | Avro-based, built-in (Spark 4.0) ✅ | Manual `TypeInformation` compat + migration code |
| **Checkpointing** | All-or-nothing (full checkpoint) | Incremental Chandy-Lamport (only changed blocks) |
| **Exactly-once** | Yes (idempotent writes + WAL) | Yes (2PC, end-to-end, more sinks supported) |
| **Watermarks** | `withWatermark()` — simpler | First-class, per-partition, idle source handling |
| **Backpressure** | `maxBytesPerTrigger` + PID controller | Automatic credit-based, no config needed |
| **Batch + stream unification** | Unified SparkSession — same code runs batch or stream | Table API unifies batch/stream; less mature |
| **Python support** | First-class PySpark, `TransformWithStateInPandas` | PyFlink — improving but still behind PySpark |
| **SQL support** | Spark SQL — best-in-class | Flink SQL — very good, but Spark SQL is richer |
| **Ecosystem** | Delta Lake, Iceberg, Hudi, MLlib, pandas | Iceberg, Kafka, fewer native connectors |
| **Operational tooling** | State Data Source, Spark UI, history server | Flink Web UI, checkpoint browser |
| **Scaling** | Restart required for parallelism change | Rescaling with state redistribution (Flink 1.17+) |
| **Netflix use** | Batch, backfill, Iceberg compaction | Real-time streaming (20K+ jobs) |

---

### Where Flink Still Wins

```
1. Latency: Flink is milliseconds; Spark 4.0 micro-batch is still seconds minimum
   → If you need sub-second latency, Flink is the only answer

2. Checkpointing: Flink's incremental Chandy-Lamport is faster and more reliable
   for large state. Spark's full checkpoint is still the bottleneck for big state jobs.

3. True streaming semantics: event-time windowing, per-partition watermarks,
   idle source detection — Flink has had these since 2016, Spark's are still simpler.

4. Rescaling without restart: Flink 1.17+ can redistribute keyed state to a new
   parallelism without stopping the job. Spark requires a restart.

5. Exactly-once breadth: Flink's 2PC works with more sink types out of the box.
```

### Where Spark 4.0 Closes the Gap (or Wins)

```
1. State debugging: State Data Source is a genuine advantage — query live state
   as a DataFrame, read change feeds, inspect specific batch snapshots.
   Flink has nothing comparable built-in.

2. Python: TransformWithStateInPandas brings the full stateful API to Python.
   PyFlink is still catching up.

3. SQL richness: Spark SQL is more mature for complex analytics on streaming data.

4. Batch/stream unification: Spark's unified model is more production-proven.
   Same code, same API, same optimizations whether batch or stream.

5. Ecosystem: Delta Lake, MLlib, pandas UDFs — if you're in the Spark ecosystem,
   4.0 makes it much harder to justify switching to Flink for stateful jobs.

6. State schema evolution: Avro-based schema evolution in 4.0 is cleaner than
   Flink's manual migration approach.
```

### When to Choose Each (Updated for 2025)

```
Choose Flink when:
  → Latency < 1 second is required (Flink is the only real option)
  → Very large stateful jobs where incremental checkpointing matters
  → You need fine-grained watermark control (per-partition, idle handling)
  → You're already on the Flink ecosystem (Kafka-native)

Choose Spark 4.0 Structured Streaming when:
  → You're already on Spark / Databricks / EMR
  → Python is your primary language (PySpark >> PyFlink)
  → Latency of seconds is acceptable (most analytics use cases)
  → You need production state debugging (State Data Source)
  → You need Spark SQL richness on streaming data
  → Batch + stream unified codebase is important
  → Delta Lake / Iceberg writes with ACID guarantees

The honest answer for most data platform teams in 2025:
  Spark 4.0 has closed the stateful streaming gap enough that the choice
  is now primarily driven by ecosystem (already using Spark?) and latency
  requirements (< 1s → Flink, seconds → Spark). The "Flink for everything
  streaming" default no longer holds.
```

---

### The Bridge (Jay's Interview Answer)

> "I ran Spark Streaming at 200 billion events per hour at GuardDuty. I hit Spark's fundamental limitations directly — specifically the checkpointing model for stateful processing. When a checkpoint partially failed, we had data loss. My solution was to externalize state to DynamoDB with conditional writes, which gave us independent durability and scalability — but at the cost of network round trips per event.
>
> Flink's design solves this more elegantly. Its native keyed state with RocksDB backend is essentially what I was trying to build on top of Spark — durable, incrementally checkpointed state that lives close to the processing logic. The Chandy-Lamport barrier-based checkpointing gives exactly-once guarantees without the all-or-nothing failure mode I experienced with Spark. If I were building the GuardDuty pipeline today, I'd use Flink for the stateful detection layer and Spark for the batch aggregation and backfill jobs — which is exactly how Netflix splits the two."
