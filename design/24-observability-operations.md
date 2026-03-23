# Observability & Operations (The Netflix Ops Interview)

## What Netflix Evaluates in This Round
"You build it, you run it." Netflix expects engineers to own reliability. This round tests:
- How you measure system health
- How you detect and respond to failures
- How you design systems that are observable from the start
- Chaos engineering philosophy

---

## The 3 Pillars of Observability

```
1. METRICS  — What is happening? (aggregated numbers over time)
2. LOGS     — Why did it happen? (detailed event records)
3. TRACES   — Where did it happen? (request flow across services)

All three needed for full visibility:
  Metrics: "Error rate spiked to 5% at 14:32"
  Logs:    "NullPointerException in OrderService.processPayment at 14:32:01"
  Traces:  "Request abc123: Gateway(2ms) → OrderSvc(245ms) → PaymentSvc(TIMEOUT)"
```

---

## 1. Metrics

### SLI / SLO / SLA

```
SLI (Service Level Indicator): what you measure
  - Request success rate
  - p99 latency
  - Error rate
  - Availability (% of time service is up)
  - Throughput (requests/sec)

SLO (Service Level Objective): your internal target
  - "99.9% of requests succeed" (1 failure per 1000)
  - "p99 latency < 500ms"
  - "Availability > 99.95%"

SLA (Service Level Agreement): external contract with customers
  - If SLA is violated → credits, penalties
  - SLA is usually more relaxed than SLO (buffer)

Error budget = 100% - SLO
  99.9% SLO → 0.1% error budget
  0.1% of 30 days = 43.2 minutes per month of allowed failures
  If budget exhausted → stop feature deployments, focus on reliability
```

### Key Metrics to Track for a Data Pipeline

```java
// Netflix Atlas / Prometheus style
public class PipelineMetrics {
    private final MeterRegistry registry;

    // Throughput
    private final Counter eventsConsumed = Counter.builder("pipeline.events.consumed")
        .tag("topic", topicName).register(registry);
    private final Counter eventsProduced = Counter.builder("pipeline.events.produced")
        .tag("sink", sinkName).register(registry);

    // Latency (processing time per event)
    private final Timer processingLatency = Timer.builder("pipeline.processing.latency")
        .publishPercentiles(0.5, 0.95, 0.99, 0.999)
        .register(registry);

    // Errors
    private final Counter processingErrors = Counter.builder("pipeline.errors")
        .tag("type", "processing").register(registry);

    // Consumer lag (most critical for Kafka-based pipelines)
    private final Gauge consumerLag = Gauge.builder("kafka.consumer.lag",
        kafkaConsumer, c -> c.getLag()).register(registry);

    // End-to-end latency (event time to processing time)
    private final Histogram e2eLatency = Histogram.builder("pipeline.e2e.latency")
        .register(registry);

    public void onEventProcessed(Event event, long processingTimeMs, boolean success) {
        eventsConsumed.increment();
        processingLatency.record(processingTimeMs, TimeUnit.MILLISECONDS);

        long e2eMs = System.currentTimeMillis() - event.getEventTimestamp();
        e2eLatency.record(e2eMs, TimeUnit.MILLISECONDS);

        if (!success) processingErrors.increment();
    }
}

// Key alerts to set:
// - Consumer lag > 100K messages for > 5 minutes → page on-call
// - Error rate > 1% → page on-call
// - p99 latency > 2x baseline → warn
// - Events processed = 0 for > 30 seconds → page immediately (dead job?)
```

---

## 2. Structured Logging

```java
// Don't use System.out.println — use structured logs with context
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.logstash.logback.argument.StructuredArguments;

public class OrderProcessor {
    private static final Logger log = LoggerFactory.getLogger(OrderProcessor.class);

    public void processOrder(Order order) {
        log.info("Processing order",
            StructuredArguments.keyValue("orderId", order.getId()),
            StructuredArguments.keyValue("userId", order.getUserId()),
            StructuredArguments.keyValue("amount", order.getAmount()),
            StructuredArguments.keyValue("itemCount", order.getItems().size())
        );

        // Log at ERROR with context — machine parseable
        try {
            processPayment(order);
        } catch (PaymentException e) {
            log.error("Payment failed",
                StructuredArguments.keyValue("orderId", order.getId()),
                StructuredArguments.keyValue("errorCode", e.getCode()),
                StructuredArguments.keyValue("retryable", e.isRetryable()),
                e
            );
        }
    }
}

// Output (JSON):
// {"message":"Processing order","orderId":"ord-123","userId":"usr-456","amount":99.99}
// Easily queryable in Elasticsearch/Splunk/CloudWatch Logs

// Log levels:
// ERROR: unexpected failure requiring human attention
// WARN:  expected failure, handled, but worth knowing
// INFO:  normal business events (order created, payment processed)
// DEBUG: diagnostic info (only enable in dev/staging)
```

### Netflix Mantis: Streaming Log Queries

```java
// Mantis enables SQL-like queries over live log streams without storing everything
// "Show me all errors from OrderService in the last 60 seconds where amount > 1000"

// Query: SELECT orderId, error, amount FROM logs
//        WHERE service='order-service' AND level='ERROR' AND amount > 1000

// Without Mantis: store all logs, query storage
// With Mantis: stream logs through in-memory, apply filter, send to dashboard
// Result: real-time operational visibility without petabyte log storage
```

---

## 3. Distributed Tracing

```java
// Trace = tree of spans representing one request's journey
// Each span = one operation (HTTP call, DB query, Kafka send)

// OpenTelemetry (industry standard)
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.GlobalOpenTelemetry;

public class OrderService {
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("order-service");

    public Order createOrder(CreateOrderRequest request) {
        Span span = tracer.spanBuilder("createOrder")
            .setAttribute("userId", request.getUserId())
            .setAttribute("orderAmount", request.getTotal())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Child spans created automatically if tracer is propagated
            Order order = orderRepository.save(request); // DB span
            paymentService.charge(order); // HTTP span to payment service
            kafkaProducer.send(new OrderCreatedEvent(order)); // Kafka span

            span.setStatus(StatusCode.OK);
            return order;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}

// Trace propagation: W3C TraceContext headers
// X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736
// X-B3-SpanId: 00f067aa0ba902b7
// Every service reads these, creates child span → full distributed trace

// What to trace:
// - All inbound HTTP/gRPC requests
// - All outbound HTTP/gRPC calls
// - All DB queries (with query text, rows returned)
// - All Kafka produce/consume
// - Any operation > 50ms
```

---

## Incident Response

### Detection → Triage → Mitigation → Resolution → Postmortem

```
1. DETECTION (< 5 minutes)
   - Automated alerting (Prometheus AlertManager, PagerDuty)
   - Key signals: error rate spike, latency spike, consumer lag spike
   - Alert on symptoms, not causes (alert on high error rate, not "disk full")

2. TRIAGE (< 15 minutes)
   - Who is affected? (which users, which endpoints)
   - What changed? (recent deployments, config changes)
   - What are the metrics showing?

3. MITIGATION (FAST — before root cause known)
   - Rollback deployment if recent change
   - Scale out service if resource-constrained
   - Redirect traffic away from bad region
   - Feature flag off the problematic feature
   - Rate limit to protect the system

4. RESOLUTION (after mitigation)
   - Find root cause
   - Permanent fix
   - Verify metrics return to normal

5. POSTMORTEM (within 48 hours)
   - Blameless: focus on systems, not people
   - What happened, timeline
   - Root cause
   - Action items to prevent recurrence
   - Do NOT block on postmortem before fixing
```

### Runbook Pattern

```java
// Document how to handle known failure modes
/*
RUNBOOK: High Consumer Lag on orders-topic

Symptoms:
  - Alert: kafka_consumer_lag{topic="orders"} > 100000
  - Dashboard: https://grafana/d/consumer-lag

Immediate actions:
  1. Check if consumer pods are alive: kubectl get pods -l app=order-processor
  2. Check processing error rate: grafana/d/order-processor-errors
  3. If pods crashing: kubectl logs order-processor-abc123 --previous
  4. If processing errors spike: check order-processor DLQ for error patterns

Escalation:
  - < 30 min to resolve: handle yourself
  - > 30 min: page order-team-oncall
  - > 2 hours: page oncall-manager

Recovery check:
  - Lag decreasing? → good, consumer catching up
  - Error rate back to normal? → verify processing working
  - Run smoke test: curl https://api.internal/health/order-processor
*/
```

---

## Chaos Engineering

```java
// Netflix Chaos Monkey: randomly terminates production instances
// Philosophy: if you're afraid of random failures, your system isn't resilient
// Better to find weaknesses in drills than in real incidents

// Levels of chaos (Principles of Chaos Engineering):
// 1. Kill random instances       → Chaos Monkey
// 2. Kill entire availability zone → Chaos Kong
// 3. Inject network latency      → latency injection
// 4. Drop network packets        → network chaos
// 5. Corrupt data                → advanced testing

// ChAP (Chaos Automation Platform): Netflix's automated chaos
// Runs experiments comparing a control group vs chaos group
// If error rates differ significantly → system is fragile, stop experiment

// Java: Fault injection in testing
@Component
@ConditionalOnProperty("chaos.enabled")
public class ChaosMiddleware implements HandlerInterceptor {
    private final Random random = new Random();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        double p = Double.parseDouble(System.getenv().getOrDefault("CHAOS_FAILURE_RATE", "0.01"));
        if (random.nextDouble() < p) {
            res.setStatus(503);
            return false; // 1% of requests return 503
        }
        return true;
    }
}

// Chaos testing questions at Netflix interview:
// Q: "If I kill half your Flink task managers, what happens?"
// A: Flink detects task manager failure → restarts failed tasks on remaining TMs
//    → restores state from latest checkpoint → replays Kafka from checkpoint offset
//    → eventually converges, consumer lag spikes temporarily

// Q: "If Kafka is unavailable for 2 minutes, what happens?"
// A: Flink source retries with backoff → if connection restored, resumes
//    → consumer lag = 2 min × throughput → catches up after resuming
//    → if Kafka down > checkpoint timeout, Flink job fails → restart from checkpoint
```

---

## Detecting Message Loss — Netflix Inca Pattern

This is a specific Netflix question. How do you know if messages are being dropped in a pipeline?

```java
// Inca: Netflix's message tracing and loss detection system

// Producer side: embed sequence numbers
public class TrackedKafkaProducer {
    private final AtomicLong sequenceNumber = new AtomicLong(0);

    public void send(String topic, Event event) {
        long seq = sequenceNumber.getAndIncrement();
        event.setMetadata("_seq", seq);
        event.setMetadata("_producer_id", instanceId);
        kafka.send(new ProducerRecord<>(topic, serialize(event)));
    }
}

// Consumer side: detect gaps in sequence numbers
public class LossDetector {
    private final Map<String, Long> lastSeenSequence = new ConcurrentHashMap<>();

    public void onMessage(Event event) {
        String producerId = event.getMetadata("_producer_id");
        long seq = Long.parseLong(event.getMetadata("_seq"));
        Long lastSeen = lastSeenSequence.get(producerId);

        if (lastSeen != null && seq != lastSeen + 1) {
            long gap = seq - lastSeen - 1;
            metrics.increment("message_loss", gap);
            alert("Message loss detected: producer=" + producerId +
                  " expected=" + (lastSeen+1) + " got=" + seq + " gap=" + gap);
        }
        lastSeenSequence.put(producerId, seq);
    }
}

// Extension: aggregate-based verification
// Producer writes "I produced N events in window W" to a stats topic
// Consumer reads stats topic → compares with actual consumed count
// Difference = lost messages
```

---

## Operations Questions Netflix Asks

**Q: "How would you tune a Flink job when one topic suddenly gets 10× normal throughput?"**
```
1. Check if it's a spike or sustained increase
2. Immediate: increase Flink parallelism for that job (more task slots)
3. Scale Kafka partitions if throughput > what one partition can handle
4. Check if sinks (Iceberg, Druid) can absorb the extra load
5. Watermark lag will increase — acceptable if SLO allows
6. Backpressure: if sinks can't keep up, Flink throttles Kafka consumption (good!)
7. Longer term: auto-scaling based on consumer lag metric
```

**Q: "Your data pipeline is producing wrong results. How do you debug?"**
```
1. Define "wrong" — missing data? Incorrect aggregations? Late data?
2. Check consumer lag — are we behind?
3. Check error rate metrics and DLQ
4. Trace a specific event through the pipeline (end-to-end tracing)
5. Check for late-arriving events outside watermark → dropped
6. Check for duplicate events → missing deduplication
7. Check if recent code/config change correlates with when results became wrong
8. If Flink: compare expected vs actual state checkpoints
9. Reprocess from Kafka offset before the issue started → compare outputs
```

---

## When to Use What

| Observability Need | Tool |
|--------------------|------|
| Aggregate metrics | Prometheus + Grafana / Atlas |
| Log search & analysis | Elasticsearch (ELK) / Splunk |
| Distributed traces | Jaeger / Zipkin / AWS X-Ray |
| Real-time log querying | Mantis (Netflix) |
| Alerting | PagerDuty + AlertManager |
| Error tracking | Sentry |
| Uptime / synthetic monitoring | Pingdom / Datadog Synthetics |

---

## Interview Questions

1. **What are SLIs, SLOs, and SLAs?** SLI: measured indicator. SLO: internal target for that indicator. SLA: external promise (usually weaker than SLO). Error budget = 1 - SLO.
2. **How do you detect message loss in a Kafka pipeline?** Sequence numbers on producer, gap detection on consumer. Or count-based: producer publishes "produced N" stats, consumer verifies.
3. **What's the difference between metrics, logs, and traces?** Metrics: aggregated numbers over time (fast queries, alerting). Logs: detailed event records (debugging). Traces: request flow across services (latency breakdown, finding bottlenecks).
4. **How would you design a health check for a stream processing pipeline?** Measure: consumer lag, events processed/sec, error rate, checkpoint success rate, e2e latency. Alert if any deviate > 2σ from baseline.
5. **What is chaos engineering and why does Netflix do it?** Intentionally inject failures in production to verify system handles them gracefully. Better to find weaknesses in controlled experiments than during a real incident.

---

## YOUR STAR Stories — Operations & Reliability Round

These are drawn directly from your GuardDuty work. Memorize the structure, say each out loud in 2–3 minutes.

---

### Story 1: Production Memory Leak — Heap Dump Diagnosis
**Netflix angle**: "You build it, you run it — I owned the full lifecycle including production diagnosis"

**S** — Stateful Spark processor running reverse shell detection started showing high disk utilization on master + core nodes in production. Oncall was getting paged. The system was processing ~200B events/6 hours and any instability meant detection gaps for customers.

**T** — Root cause the disk/memory issue without taking the system offline. The failure mode wasn't obvious — standard metrics showed disk filling but no clear culprit.

**A** — Did a heap dump analysis of the Spark application running on the master node. Found an unbounded accumulator growth — objects were being held in memory across micro-batches and never cleared. Fixed with explicit clearing at batch boundaries and added memory headroom monitoring. CR: [CR-165755175].

**R** — Disk leak resolved. Added runbook and monitoring alerts. Zero recurrence. Also pushed GC configuration change from ParallelGC to ZGC which improved throughput further, expecting ~10% fleet scale-down.

**Netflix bridge**: "This is exactly the 'you build it, you run it' model — I diagnosed it at the JVM level, fixed it, and instrumented it so oncall would catch it in < 5 minutes next time."

---

### Story 2: Async DynamoDB → 10× Latency + 50% Fleet Reduction
**Netflix angle**: "I identified a systemic bottleneck and fixed it — with measurable impact"

**S** — Lookup Processor (processing 200B+ events/hour) was making synchronous DynamoDB calls to store and update per-container behavioral state. In large regions, this was causing high processing latency and the fleet was over-provisioned to compensate.

**T** — Reduce latency without changing the correctness guarantees of our conditional writes (optimistic concurrency via `UpdateItem`).

**A** — Identified that synchronous I/O was blocking Spark executors during computation. Migrated DynamoDB repository to async client using Java's `CompletableFuture` — pipelining I/O with computation so executors weren't idle waiting for DynamoDB responses. Also added concurrency limits to prevent overwhelming DynamoDB.

**R** — 10× latency reduction in large regions. Fleet scaled down by 50% automatically after rollout. Reduced 2 oncall pages/week from DynamoDB timeout-related issues. DDB async impact tracked in [GD-39086].

**Netflix bridge**: "The same pattern as EVCache's async, batched, non-blocking access — and Flink's async I/O operator which solves this natively. If I were building this today on Flink, the async pattern would be built in."

---

### Story 3: Composite Failure Mode — Silent Data Loss
**Netflix angle**: "Detected a non-obvious failure mode before it became a customer incident"

**S** — During routine operations, I identified a composite failure mode in the LookupProcessor + FindingDecSup pipeline. The failure wasn't showing up on standard error dashboards because individual components were succeeding — but findings were being silently dropped at the boundary between suppression engine and publisher.

**T** — Identify root cause of silent finding drops. No obvious alert was firing because each component individually reported success.

**A** — Built a cross-component event counter: findings emitted by LookupProcessor vs findings received by FindingDecSup vs findings published to customers. The gap pointed to a race condition in the suppression engine's composite rule evaluation — under certain timing conditions a finding matched no rule branch and was dropped rather than passed through. Fixed the logic and added an explicit "unmatched finding" counter with alert.

**R** — Zero silent drops after fix. Added the counter as a standard metric to all processor dashboards. Documented the failure mode and recovery steps in a runbook so oncall could detect and triage in < 5 minutes.

**Netflix bridge**: "This maps directly to Netflix's Inca pattern — you need end-to-end event counters, not just per-component health checks, to detect silent data loss."

---

### Story 4: Custom Autoscaling Policy → 80% Cost Reduction
**Netflix angle**: "Engineers own the cost of their systems — I drove this proactively"

**S** — Lookup Processor was using AWS default CPU-based autoscaling. Default policy was over-provisioned because CPU alone doesn't accurately reflect load on an event-streaming pipeline — Kinesis backlog can fill up before CPU climbs.

**T** — Design a smarter scaling policy that reduces cost without degrading latency SLA.

**A** — Introduced a three-signal autoscaling policy: (1) CPU utilization as baseline, (2) Kinesis backlog depth as leading indicator of incoming load, (3) empty-receive rate as signal of over-provisioning. Backlog depth is the equivalent of Kafka consumer lag — it fires before CPU, giving time to scale ahead of the wave. Validated with shadow deployment and A/B comparison before full rollout. Also defined scale-down gates: don't scale down if backlog is non-zero.

**R** — 80% reduction in vCPU count and compute costs. Policy was copied to EKS Audit Logs processor — same 80% reduction observed there too. Saved hundreds of thousands of dollars annually across regions.

**Netflix bridge**: "This is exactly Kafka consumer lag-based autoscaling — Flink on Kubernetes does this natively with KEDA. I'd apply the same three-signal philosophy: lag-based scale-out, idle-based scale-in, with minimum-lag gates on scale-down."

---

### Story 5: $50K/Month S3 Cost Reduction — Cross-Team Rollout
**Netflix angle**: "Identified a systemic cost issue and drove it to completion across teams"

**S** — GuardDuty Ingestion was writing events to S3 with an account ID partition in the path. This created millions of small files, generating excessive S3 PUT/GET API calls and inflating both the ingestion fleet size and downstream processor overhead.

**T** — Propose and safely roll out a change to remove the account ID partition — which required coordination across Ingestion team, Lookup Processor team, and downstream consumers.

**A** — Wrote a 1-pager with cost analysis showing the savings. Designed a rollout plan: (1) test in one region with monitoring, (2) validate Lookup Processor could handle larger file sizes, (3) staged rollout with rollback criteria defined. Drove the execution across teams.

**R** — Reduced S3 PUT/GET API calls by over 90%. Saved ~$50,000/month in estimated infrastructure costs. Fleet size reduced in both ingestion and downstream processors.

**Netflix bridge**: "Netflix cares deeply about cost at scale — engineers own the cost of their systems. I identified this proactively through routine infrastructure analysis, not because I was asked to."

---

### Story 6: Per-Finding Canary — Continuous Validation in Production
**Netflix angle**: "Operational excellence — built the safety net before we needed it"

**S** — Before Lookup Processor, GuardDuty had no per-finding canary. When detection logic was deployed to production, regressions could go undetected until a customer reported missing or incorrect findings. With 200B+ events/hour and customers relying on findings for security response, silent regressions were unacceptable.

**T** — Design a continuous validation system that runs after every deployment and catches finding-logic regressions before they impact customers.

**A** — Designed and built a per-finding canary that runs on the Hydra environment after every deployment. The system generates synthetic events that should trigger each finding type, submits them to the pipeline, and validates the correct finding is produced within SLA. Integrated into the deployment pipeline as a quality gate — failed canary blocks promotion.

**R** — Caught multiple regressions in pre-prod that would have silently broken detections in production. Reduced mean time to detect finding-logic bugs from "customer report" (days) to "canary run" (minutes). System was extended to cover all finding types in DecSup processor as well.

**Netflix bridge**: "This is the same philosophy as Netflix's ChAP — continuous automated validation in production-like environments. You can't just rely on unit tests for distributed pipelines; you need end-to-end signal validation."

---

### Story 7: Metadata Missing — Cross-Team Root Cause
**Netflix angle**: "Took ownership of a problem that spanned 4 teams — drove it to resolution"

**S** — Lookup Processor was generating 404s from Metadata Service for container IDs, meaning findings were being emitted without container context — degrading finding quality for customers. This was a chronic oncall ticket source. Multiple teams were involved: Agent, Ingestion, Metadata, and Processor.

**T** — Determine whether metadata was missing at the source (Agent never sent it) or delayed in the pipeline. Then propose a durable cross-team fix.

**A** — Did systematic event analysis using notebooks and DuckDB. Concluded that 25–26% of Fargate container events had missing metadata from the Agent. Produced a 1-pager proposing three resolution options with trade-offs. Drove alignment across all 4 teams on a solution — adding a data quality check at agent launch to proactively detect metadata miss rate increases. Also proposed making metadata-missing findings retryable on client side rather than dropping them.

**R** — Cross-team solution agreed and implemented. Metadata miss rate tracked as a first-class SLI. Oncall tickets for metadata issues reduced significantly. Finding quality improved for Fargate customers.

**Netflix bridge**: "Operational problems at scale rarely have a single owner. The skill is knowing when to stop debugging in isolation and when to drive cross-team alignment — which requires both technical credibility and communication."

---

## Quick-Pick Guide — Which Story for Which Question

| Netflix Question | Best Story |
|-----------------|------------|
| "Tell me about a production incident you owned" | Story 1 (heap dump) or Story 3 (silent data loss) |
| "How do you measure and improve reliability?" | Story 6 (per-finding canary) |
| "Tell me about a cost optimization" | Story 4 (autoscaling) or Story 5 (S3 cost) |
| "Tell me about debugging an intermittent failure" | Story 3 (composite failure mode) |
| "Tell me about working across teams" | Story 7 (metadata missing) |
| "What does 'you build it, you run it' mean to you?" | Story 2 (async DDB) + Story 1 (heap dump) |
| "Tell me about a system optimization with measurable impact" | Story 2 (10× latency, 50% fleet) |
