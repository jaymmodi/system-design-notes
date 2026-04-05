# Message Queues & Event Streaming

## What & Why
Async communication between services via messages. Decouples producers from consumers, buffers load spikes, enables event-driven architectures.

**Sync**: `ServiceA calls ServiceB directly` → A waits, A fails if B is down
**Async**: `ServiceA → Queue → ServiceB` → A continues, B processes when ready

---

## Core Concepts

```
Producer → [  Queue / Topic  ] → Consumer
              Broker stores
              messages until
              consumed
```

- **Queue** (point-to-point): each message consumed by exactly ONE consumer (RabbitMQ, SQS)
- **Topic** (pub/sub): each message delivered to ALL subscribers (Kafka, SNS)
- **Partition**: unit of parallelism in Kafka — one consumer per partition at a time
- **Consumer Group**: multiple consumers sharing work on a topic

---

## Kafka Deep Dive

### Architecture
```
                     Topic: orders (3 partitions)
Producer →  Broker 1: [Partition 0: msg1, msg4, msg7...]
            Broker 2: [Partition 1: msg2, msg5, msg8...]
            Broker 3: [Partition 2: msg3, msg6, msg9...]

Consumer Group "order-service":
  consumer-1 → reads Partition 0
  consumer-2 → reads Partition 1
  consumer-3 → reads Partition 2
```

### Java Producer

```java
import org.apache.kafka.clients.producer.*;

public class OrderEventProducer {
    private final KafkaProducer<String, String> producer;

    public OrderEventProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka1:9092,kafka2:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Durability settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");          // wait for all replicas
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // exactly-once

        // Performance settings
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);         // batch messages for 5ms
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);    // 16KB batches
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        this.producer = new KafkaProducer<>(props);
    }

    public CompletableFuture<RecordMetadata> sendOrderEvent(Order order) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
            "orders",           // topic
            order.getUserId(),  // key → determines partition (same user → same partition → ordered)
            serialize(order)    // value
        );

        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                future.completeExceptionally(exception);
            } else {
                System.out.printf("Sent to partition %d, offset %d%n",
                    metadata.partition(), metadata.offset());
                future.complete(metadata);
            }
        });
        return future;
    }
}
```

### Java Consumer

```java
public class OrderEventConsumer {
    private final KafkaConsumer<String, String> consumer;
    private volatile boolean running = true;

    public OrderEventConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka1:9092,kafka2:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // start from beginning if new group
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // manual commit for at-least-once

        this.consumer = new KafkaConsumer<>(props);
    }

    public void start() {
        consumer.subscribe(List.of("orders"));

        while (running) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

            for (ConsumerRecord<String, String> record : records) {
                try {
                    processOrder(deserialize(record.value()));
                    // Only commit after successful processing (at-least-once delivery)
                } catch (Exception e) {
                    log.error("Failed to process offset {}", record.offset(), e);
                    // Send to dead-letter queue for manual review
                    deadLetterQueue.send(record);
                }
            }

            // Commit offsets after processing batch
            consumer.commitSync();
        }
    }
}
```

---

## Delivery Guarantees

### At-Most-Once (fire and forget)
```java
// Producer: no retries
props.put(ProducerConfig.ACKS_CONFIG, "0"); // don't wait for ack
props.put(ProducerConfig.RETRIES_CONFIG, 0);
// Consumer: commit before processing
consumer.commitSync(); // commit first
processMessage(record); // if this fails, message is LOST — never retried
// Use case: metrics, logs where occasional loss is ok
```

### At-Least-Once (default)
```java
// Producer: retries enabled (messages may be sent multiple times)
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
// Consumer: commit after processing
processMessage(record);
consumer.commitSync(); // if crash here, message replayed on restart
// Use case: most cases — make consumer idempotent to handle duplicates
```

### Exactly-Once (Kafka 0.11+)
```java
// Consumer side — required for exactly-once
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); // skip aborted transaction messages
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);         // offsets committed via sendOffsetsToTransaction, not auto

// Producer idempotency + transactions
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "order-producer-1");

producer.initTransactions();
try {
    producer.beginTransaction();
    producer.send(new ProducerRecord<>("orders", key, value));
    producer.send(new ProducerRecord<>("order-events", key, event));
    // Send offsets to transaction (read-process-write atomically)
    producer.sendOffsetsToTransaction(currentOffsets, consumer.groupMetadata());
    producer.commitTransaction();
} catch (Exception e) {
    producer.abortTransaction();
    throw e;
}
// Use case: financial systems, inventory deduction
```

---

## Idempotency — Handle Duplicates Gracefully

```java
// At-least-once delivery means duplicates WILL happen
// Make consumers idempotent: processing same message N times = same result as once

public class IdempotentOrderProcessor {
    private final Set<String> processedMessageIds; // stored in Redis or DB

    public void processOrder(ConsumerRecord<String, String> record) {
        String messageId = record.topic() + ":" + record.partition() + ":" + record.offset();

        // Check if already processed (idempotency key)
        if (processedMessageIds.contains(messageId)) {
            log.info("Duplicate message {}, skipping", messageId);
            return;
        }

        Order order = deserialize(record.value());
        // Process order
        orderRepository.save(order);

        // Mark as processed (with TTL to prevent unbounded growth)
        processedMessageIds.add(messageId); // Redis: SETEX with 24h TTL
    }
}

// Alternative: use DB UPSERT with natural idempotency key
// INSERT INTO orders (id, ...) ON CONFLICT (id) DO NOTHING
// The orderId itself is the idempotency key
```

---

## Backpressure & Consumer Lag

```java
// Consumer lag: how far behind is consumer vs producer?
// Monitor this! Growing lag = consumer can't keep up

public class KafkaLagMonitor {
    public Map<TopicPartition, Long> getLag(String groupId, String topic) {
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(
            consumer.partitionsFor(topic).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition()))
                .collect(toList())
        );

        Map<TopicPartition, OffsetAndMetadata> committedOffsets =
            adminClient.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get();

        Map<TopicPartition, Long> lag = new HashMap<>();
        endOffsets.forEach((tp, endOffset) -> {
            long committed = committedOffsets.getOrDefault(tp, new OffsetAndMetadata(0)).offset();
            lag.put(tp, endOffset - committed);
        });
        return lag;
    }
}

// If lag grows: scale out consumers (up to partition count), optimize processing, or add more partitions
```

---

## Dead Letter Queue (DLQ)

```java
// Messages that fail processing repeatedly → DLQ for investigation/replay
public class ResilientConsumer {
    private static final int MAX_RETRIES = 3;
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();

    public void processWithRetry(ConsumerRecord<String, String> record) {
        String key = record.topic() + ":" + record.partition() + ":" + record.offset();
        int retries = retryCounts.getOrDefault(key, 0);

        try {
            processMessage(record);
            retryCounts.remove(key);
        } catch (RetryableException e) {
            if (retries < MAX_RETRIES) {
                retryCounts.put(key, retries + 1);
                // Exponential backoff: pause consumer for 2^retries seconds
                Thread.sleep((long) Math.pow(2, retries) * 1000);
                throw e; // re-throw to prevent offset commit
            } else {
                // Send to DLQ
                producer.send(new ProducerRecord<>(
                    record.topic() + ".dlq",
                    record.key(),
                    record.value()
                ));
                retryCounts.remove(key);
                // Commit to avoid blocking on this message forever
            }
        } catch (PoisonPillException e) {
            // Non-retryable — send to DLQ immediately
            producer.send(new ProducerRecord<>(record.topic() + ".dlq", record.key(), record.value()));
        }
    }
}
```

---

## Kafka vs RabbitMQ vs SQS

| Feature | Kafka | RabbitMQ | AWS SQS |
|---------|-------|----------|---------|
| Model | Log (replayable) | Queue (consume & delete) | Queue |
| Message retention | Days/weeks | Until consumed | 4-14 days |
| Throughput | Very high (millions/sec) | High (100K/sec) | High (managed) |
| Replay | Yes (from any offset) | No | No (DLQ only) |
| Ordering | Per-partition | Per-queue | FIFO queue option |
| Complexity | High | Medium | Low (managed) |
| Use case | Event streaming, audit log | Task queues, routing | Simple queuing, AWS native |

---

## Message Queue Patterns

### Fan-Out
```
Order created → [Topic: order.created]
                    ↓           ↓           ↓
              inventory    email-svc    analytics
              (decrement)  (confirm)   (track)
```

### Work Queue (Task Distribution)
```
[Tasks] → [Queue] → Worker1
                  → Worker2   (auto load balanced)
                  → Worker3
```

### Event Sourcing
```java
// Store ALL events, derive current state from event log
public class OrderEventStore {
    private final KafkaProducer<String, OrderEvent> producer;

    public void recordEvent(OrderEvent event) {
        producer.send(new ProducerRecord<>("order-events", event.getOrderId(), event));
    }

    public Order replayOrder(String orderId) {
        // Read all events for this order from Kafka
        consumer.seekToBeginning(partitions);
        Order order = new Order();
        while (true) {
            ConsumerRecords<String, OrderEvent> records = consumer.poll(Duration.ofMillis(100));
            for (ConsumerRecord<String, OrderEvent> r : records) {
                if (r.key().equals(orderId)) {
                    order.apply(r.value()); // apply event to state machine
                }
            }
            if (records.isEmpty()) break;
        }
        return order;
    }
}
```

---

## When to Use Message Queues
- Async processing (email, notifications, image resizing)
- Decouple services (reduce direct dependencies)
- Buffer traffic spikes (queue absorbs burst, workers drain at steady rate)
- Fan-out to multiple consumers
- Audit log (Kafka's immutable log)
- Stream processing

## When to Avoid
- Real-time, low-latency requirements (< 1ms — use direct calls or Redis pub/sub)
- Simple in-process communication
- When synchronous response is truly needed (user waits for result)
- Small apps where complexity of a broker isn't justified (use in-memory queue first)

---

## Interview Questions

1. **Kafka vs RabbitMQ?** Kafka: high-throughput log, replayable, event streaming. RabbitMQ: traditional message broker with routing, task queues.
2. **How do you ensure no message loss in Kafka?** acks=all, min.insync.replicas=2, producer retries with idempotence, consumer commit after processing.
3. **What is a partition key and why does it matter?** Determines which partition a message goes to. Same key → same partition → guaranteed ordering for that key.
4. **How do you handle poison pill messages?** Messages that always fail → DLQ after max retries, alert on-call, fix and replay from DLQ.
5. **What is consumer lag?** Gap between latest message produced and latest message consumed. Growing lag = consumer is falling behind. Alert at > X messages lag.
