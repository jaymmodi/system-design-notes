> Summarized from https://docs.aws.amazon.com/sns/latest/dg/welcome.html, https://docs.aws.amazon.com/sns/latest/dg/fifo-message-archiving-replay.html, https://docs.aws.amazon.com/sns/latest/dg/fifo-message-delivery.html, https://aws.amazon.com/about-aws/whats-new/2025/01/high-throughput-mode-amazon-sns-fifo-topics/

# Amazon SNS Deep Dive

## Fan-out Architecture

```
Publisher → SNS Topic → push to ALL subscriptions simultaneously
              ├── SQS Queue A    → downstream service (buffered, durable)
              ├── SQS Queue B    → analytics service
              ├── Lambda fn      → direct trigger (standard topics only)
              ├── HTTP/S endpoint → webhook (standard topics only)
              ├── Firehose        → S3 / Redshift archiving
              └── SMS / Email    → direct delivery (standard topics only)
```

SNS itself has **no storage** for standard topics — pure push, fire-and-forget.

---

## Standard vs FIFO Topics

| Feature | Standard Topic | FIFO Topic |
|---|---|---|
| Ordering | Best-effort | Strict (per message group ID) |
| Deduplication | No | Yes (5-min dedup window) |
| Message storage / archive | No | Yes — up to 365 days |
| Replay to subscribers | No (DIY via Firehose → S3) | Yes — built-in `ReplayPolicy` |
| Subscribers supported | SQS, Lambda, HTTP/S, SMS, Email, Firehose, Mobile push | **SQS only** (FIFO or standard queues) |
| Lambda | Direct | Must go through SQS → Lambda trigger |
| HTTP/S, SMS, Email | Yes | **No** |
| Default TPS | Soft quota (region-dependent, can request increase) | 3,000 MPS per topic |
| High-throughput mode | N/A | Up to 30,000 MPS (us-east-1) |

---

## FIFO Topic — The Catches

### 1. Subscriber type restrictions (biggest catch)
FIFO topics **cannot** deliver to HTTP/S endpoints, SMS, email, or mobile push notifications — these cannot guarantee strict ordering. Only SQS queues are supported as direct subscribers.

```
SNS FIFO Topic
  ├── SQS FIFO Queue  ✅  → Lambda trigger (indirect)
  ├── SQS Standard Queue  ✅
  ├── HTTP endpoint  ❌  (error at subscription time)
  ├── SMS / Email    ❌
  └── Lambda (direct) ❌  → must go through SQS first
```

### 2. Cannot convert Standard → FIFO
Topic type is **immutable** — set at creation time. To migrate, you must:
1. Create a new FIFO topic
2. Re-subscribe all consumers
3. Migrate publishers to the new topic ARN

### 3. Message group ID required
Publishers must include a `MessageGroupId` — messages within the same group are delivered in order. Throughput scales with number of distinct group IDs.

### 4. 256 KB message size limit
Same as standard topics. Use SNS Extended Client Library for larger payloads (stores body in S3).

---

## TPS / Throughput Limits

| Mode | Limit | How to enable |
|---|---|---|
| Standard topics | Soft quota (varies by region) | Request increase via support |
| FIFO default | **3,000 MPS** per topic | Default (raised 10x in Nov 2023) |
| FIFO high-throughput (us-east-1) | **30,000 MPS** | `FifoThroughputScope=MessageGroup` |
| FIFO high-throughput (us-west-2, eu-west-1) | **9,000 MPS** | `FifoThroughputScope=MessageGroup` |

High-throughput mode trades topic-level deduplication for message-group-level deduplication. Must also enable high-throughput mode on subscribed SQS FIFO queues.

---

## Pricing Differences

| Component | Standard Topic | FIFO Topic |
|---|---|---|
| Publishes | $0.50 / 1M requests | $0.50 / 1M requests |
| Deliveries | Varies by endpoint type | Same |
| Message archive storage | N/A | **Additional charge** (per GB stored) |
| Replay | N/A | Charged as deliveries |

Archive storage cost is separate from SQS/S3 storage — SNS charges for the copy it holds internally.

---

## Message Archiving & Replay (FIFO only)

```
Topic owner enables ArchivePolicy (retention: 1–365 days)
         │
         ▼
SNS stores a copy of every message internally
         │
Subscriber creates ReplayPolicy:
  - StartingPoint: timestamp
  - EndingPoint: timestamp (optional)
         │
         ▼
SNS replays matching messages → subscriber endpoint
  - Same MessageId and Timestamp as original
  - Adds Replayed=true attribute
  - FilterPolicy applies during replay
```

**Use cases:** disaster recovery, onboarding new consumers to historical data, reprocessing after a bug fix.

---

## Key Interview Points

- SNS standard = stateless pub/sub; SNS FIFO = ordered, durable, replayable — but **SQS-only subscribers**
- "Can SNS store messages?" → Yes, but **only FIFO topics**, up to 365 days, with extra cost
- Lambda from FIFO: FIFO → SQS FIFO → Lambda trigger (two hops)
- Cannot convert topic type; migration requires new topic + re-subscription
- High-throughput FIFO (30K MPS) requires distributing across many message group IDs
