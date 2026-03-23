# Netflix Data Platform — Keystone, Maestro, Mantis, EVCache, Atlas

## Why Study These?
Netflix interviewers explicitly expect you to reference their internal systems. Naming "Keystone" or "Maestro" when relevant signals you've done your homework and understand the domain.

---

## Keystone — The Routing Pipeline

**What**: Routes 2 trillion+ events/day from Kafka to multiple sinks (Iceberg, Druid, Kafka)
**Scale**: 20,000+ concurrent Flink jobs

### Evolution (Tell This Story)

```
Phase 1 (2016): One monolithic Flink job
  All topics → [giant Flink job with routing logic] → all sinks
  Problem: one failure or bottleneck affects everything
  Problem: can't tune per-topic
  Problem: deployment risk (one change affects all pipelines)

Phase 2 (current): 1:1 Kafka-topic-to-Flink-job
  Each Kafka topic → [dedicated Flink job] → sinks
  Benefits:
    - Independent failure domains
    - Per-topic SLAs and tuning
    - Simpler, smaller jobs
    - Zero-blast-radius deployments
```

### Design Components

```java
// How to "Design Keystone" in an interview

// 1. Schema Registry (enforce contract between producers and consumers)
public class KeystoneSchemaRegistry {
    // Producers register schema before publishing
    public int registerSchema(String topic, Schema schema) {
        int schemaId = nextId.getAndIncrement();
        schemas.put(schemaId, schema);
        topicSchemas.put(topic, schemaId);
        return schemaId;
    }

    // Message format: [4-byte magic] [4-byte schema-id] [avro payload]
    public byte[] encode(Object event, int schemaId) {
        Schema schema = schemas.get(schemaId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC_BYTE);
        out.write(ByteBuffer.allocate(4).putInt(schemaId).array());
        // Avro encode event using schema
        DatumWriter<Object> writer = new GenericDatumWriter<>(schema);
        new DataFileWriter<>(writer).create(schema, out).append(event).flush();
        return out.toByteArray();
    }
}

// 2. Routing Configuration (which topic → which sinks)
// Stored in a config service (ZooKeeper / etcd / internal service)
{
  "topic": "user-events",
  "sinks": [
    {"type": "ICEBERG", "table": "datalake.user_events", "format": "PARQUET"},
    {"type": "DRUID", "datasource": "user_events_rt", "dimensions": ["event_type","country"]},
    {"type": "KAFKA", "topic": "user-events-enriched", "enrichWith": "user-profile-service"}
  ],
  "parallelism": 20,
  "checkpointInterval": "60s"
}

// 3. Per-topic Flink job lifecycle (Kubernetes-based)
public class KeystoneJobManager {
    public void deployJobForTopic(String topicName, RoutingConfig config) {
        // Each job is a Flink application on Kubernetes
        FlinkDeployment deployment = new FlinkDeployment();
        deployment.setJobConfig(Map.of(
            "kafka.topic", topicName,
            "routing.config", serialize(config),
            "parallelism", config.getParallelism()
        ));
        k8sClient.create(deployment);
    }

    // Auto-scaling: watch consumer lag, scale parallelism
    @Scheduled(fixedDelay = 30000)
    public void autoScale() {
        for (String topic : monitoredTopics) {
            long lag = kafkaAdmin.getConsumerLag(topic, "keystone-consumer");
            int currentParallelism = getCurrentParallelism(topic);

            if (lag > HIGH_LAG_THRESHOLD) {
                scaleUp(topic, currentParallelism * 2);
            } else if (lag < LOW_LAG_THRESHOLD && currentParallelism > MIN_PARALLELISM) {
                scaleDown(topic, currentParallelism / 2);
            }
        }
    }
}
```

---

## Maestro — Workflow Orchestrator

**What**: Schedules hundreds of thousands of workflows and millions of jobs daily
**Comparison**: Like Airflow, but built for Netflix's scale and SLOs
**Open source**: https://github.com/Netflix/maestro

### Architecture

```java
// Maestro key concepts:
// Workflow = DAG of steps, can be parameterized, versioned
// Step = individual task (Spark job, Flink job, SQL query, custom code)
// Instance = one execution of a workflow
// Backfill = re-run workflow for historical dates

// Workflow definition (YAML):
/*
id: daily-revenue-pipeline
schedule: "0 2 * * *"  # 2am daily
steps:
  - id: extract
    type: SPARK
    params:
      class: com.netflix.ExtractJob
      date: "${workflow.start_date}"
  - id: transform
    type: SPARK
    depends_on: [extract]
    params:
      class: com.netflix.TransformJob
  - id: load-iceberg
    type: SPARK
    depends_on: [transform]
    params:
      class: com.netflix.LoadIcebergJob
  - id: compact
    type: ICEBERG_COMPACTION
    depends_on: [load-iceberg]
    params:
      table: "datalake.daily_revenue"
*/

// Backfill: process 90 days of historical data
public class BackfillExecution {
    public void runBackfill(String workflowId, LocalDate startDate, LocalDate endDate) {
        List<String> instances = new ArrayList<>();

        // Create one instance per day
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            WorkflowInstance instance = maestroClient.createInstance(workflowId,
                Map.of("workflow.start_date", date.toString()));
            instances.add(instance.getId());
        }

        // Execute with max concurrency = 10 (don't overwhelm resources)
        orchestrateBackfill(instances, 10);
    }
}

// What makes Maestro different from Airflow:
// 1. Scale: Airflow struggles at 10K+ workflows; Maestro handles 500K+
// 2. Built-in backfill: first-class concept, not an afterthought
// 3. IPS integration: knows about Iceberg snapshots for incremental processing
// 4. Parameterized: same workflow runs for any date/region
// 5. SLO tracking: workflows have defined SLAs, Maestro alerts on violations
```

---

## Mantis — Operational Telemetry

**What**: Stream processing for operational/observability data at Netflix
**Scale**: 8 million events/second, hundreds of concurrent jobs
**Key difference from Keystone**: Mantis = ops telemetry; Keystone = business/analytics data

### Architecture

```
Netflix Services → [Mantis Push Gateway] → [Mantis Job DAG] → Dashboards/Alerts

Key innovation: in-memory processing
  Traditional: store ALL logs to S3 → run query → wait for results (minutes)
  Mantis: stream logs through in-memory → apply SQL filter → results in milliseconds

Use case examples:
  "Show me all 5xx errors from checkout-service in last 60 seconds"
  "Alert when p99 latency for payment-service > 500ms"
  "Find all users who got an error in the last 5 minutes"
```

```java
// Mantis job: SQL-like processing on live telemetry stream
/*
SELECT
    clientIp,
    count(*) as requestCount,
    avg(responseTime) as avgResponseTime,
    sum(case when statusCode >= 500 then 1 else 0 end) as errorCount
FROM LogStream
WHERE serviceName = 'checkout-service'
    AND timestamp > now() - interval '60' second
GROUP BY clientIp
HAVING errorCount > 5
ORDER BY errorCount DESC
*/

// This query runs continuously over live telemetry
// Result: real-time list of client IPs generating errors
// No data needs to be stored first!

// When you're answering ops/reliability questions:
// "I'd use a system like Mantis — stream the metrics/logs through an in-memory
//  SQL processor to get real-time operational visibility without storage overhead"
```

---

## EVCache — Netflix's Distributed Cache

**What**: Memcached-based, multi-region distributed cache
**Scale**: 99.9%+ hit rate, serves Netflix personalization and session data
**Why not Redis?**: Netflix already had Memcached expertise; simpler key-value is sufficient

### Architecture

```java
// EVCache = Enhanced Volatile Cache
// Multi-region: writes go to ALL regions; reads go to local region

// EVCache client (conceptual)
public class EVCacheClient {
    private final Map<String, List<MemcachedClient>> regionClients;
    private final String localRegion;

    public void set(String key, Object value, int ttlSeconds) {
        // Write to all regions (async for non-local, sync for local)
        for (String region : regionClients.keySet()) {
            List<MemcachedClient> pool = regionClients.get(region);
            MemcachedClient client = pool.get(Math.abs(key.hashCode()) % pool.size());

            if (region.equals(localRegion)) {
                client.set(key, ttlSeconds, value).get(); // sync for local
            } else {
                client.set(key, ttlSeconds, value); // async for remote
            }
        }
    }

    public Object get(String key) {
        // Read from local region only (latency)
        List<MemcachedClient> pool = regionClients.get(localRegion);
        MemcachedClient client = pool.get(Math.abs(key.hashCode()) % pool.size());
        Object result = client.get(key);

        if (result == null) {
            // Cache miss → go to origin (database/service)
            // Could also try another region as fallback
        }
        return result;
    }
}

// Consistent hashing: which server holds this key?
// Each key → consistent hash → same server (even when servers added/removed)
// Adding server: ~1/N keys rehashed (see consistent-hashing.md)

// Key insight for interview: EVCache maintains 99.9%+ hit rate because:
// 1. Netflix data access is heavily skewed (80/20 rule — popular content hit constantly)
// 2. TTL tuned per use case (session: 24h, recommendation: 1h, static: 7 days)
// 3. Multi-replica: typically 3 replicas per key across different hosts
// 4. Warm startup: cache preloaded from backup on server restart
```

---

## Atlas — Metrics and Monitoring

**What**: Netflix's internal time-series metrics system
**Innovation**: Multi-dimensional tagging + percentile approximation at scale

```java
// Atlas stores metrics with multiple tags (dimensions)
// Query: "p99 latency for checkout-service in EU region on mobile devices"

// Equivalent of Prometheus but built before Prometheus existed
// Netflix open-sourced the client libraries

// Instrument your code:
import com.netflix.spectator.api.*;

Registry registry = Spectator.globalRegistry();

// Counter
Counter requestCounter = registry.counter("requests.count",
    "service", "checkout",
    "region", "us-east",
    "status", "success"
);
requestCounter.increment();

// Timer
Timer requestTimer = registry.timer("requests.latency",
    "service", "checkout",
    "endpoint", "/checkout"
);
requestTimer.record(Duration.ofMillis(245));

// Gauge (current value)
Gauge queueSize = registry.gauge("queue.size",
    "service", "checkout",
    "queue", "payment-queue"
);
queueSize.set(1234);

// Atlas can answer:
// "avg(requests.latency{service=checkout,region=us-east})"
// "count(requests.count{status=error}) / count(requests.count) > 0.01"
// Multi-dimensional makes it easy to slice by region, device, version, etc.
```

---

## Putting It All Together: A Day in the Life at Netflix

```
User clicks "Play" on Netflix:

1. Request hits API gateway → authenticated → routed to Streaming Service
2. EVCache: "What content is this user allowed to stream?" → 99.9% cache hit
3. Playback event generated → sent to Kafka (via Keystone producer)
4. Keystone routes event:
   → Iceberg table (datalake.playback_events) for analytics
   → Druid (real-time dashboard: "users currently watching")
   → Kafka topic for recommendation service
5. Mantis: monitors error rates, p99 latency in real-time
   If error rate spikes → Atlas alert → PagerDuty → on-call engineer
6. Maestro: runs nightly ETL:
   → Reads new Iceberg files (via IPS, only today's additions)
   → Aggregates viewership stats
   → Writes back to Iceberg (recommendation input tables)
7. Chaos Monkey: randomly terminates one of these services
   → System self-heals, on-call verifies no user impact

This entire flow processes 2 trillion events/day.
```

---

## Interview Talking Points

When asked about data platform design, work in these patterns:

1. **"For routing events to multiple sinks, I'd design something like Netflix's Keystone — one Flink job per topic, enabling independent SLAs and failure domains"**

2. **"For incremental processing of a large table, I'd use Apache Iceberg's snapshot metadata to identify only new data files — that's the pattern Netflix calls IPS and described at re:Invent 2024"**

3. **"For operational monitoring, rather than storing and querying logs, I'd stream them through an in-memory processor like Netflix's Mantis — get real-time visibility without storage overhead"**

4. **"For caching personalization data across regions, the key design decision is write-to-all, read-from-local — similar to how Netflix's EVCache achieves 99.9%+ hit rates"**

5. **"For workflow orchestration at scale, I'd design something like Netflix's Maestro — parameterized DAGs with built-in backfill, SLO tracking, and IPS integration"**

---

## Interview Questions

1. **What is Keystone and how did Netflix evolve it?** Started as monolithic Flink job → evolved to 1:1 Kafka-topic-to-Flink-job for independent failure domains and per-topic tuning.
2. **How does Netflix handle backfill without copying petabytes?** Iceberg IPS: read snapshot metadata to identify only new data files since last checkpoint. Maestro orchestrates, processes only delta.
3. **How does EVCache handle multi-region consistency?** Write to all regions (async for remote, sync for local). Read from local region only. Eventual consistency — acceptable for personalization/session data.
4. **Why does Netflix run 20K+ concurrent Flink jobs instead of fewer large ones?** Isolation: one topic's spike doesn't affect others. Independent deployment: update one job without risk to others. Per-topic SLAs.
