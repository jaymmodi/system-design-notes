# Distributed Transactions

## The Problem
A single operation spans multiple services or databases. We need all steps to succeed or all to fail — but there's no single ACID transaction across them.

```
Example: Place Order
  1. Deduct inventory (inventory-service + its DB)
  2. Charge payment (payment-service + external gateway)
  3. Create order record (order-service + its DB)
  4. Send confirmation email (email-service)

If step 3 fails after step 2 — customer charged but no order!
```

---

## 2-Phase Commit (2PC) — Strong Consistency

Coordinator manages a 2-phase protocol across all participants.

```
Phase 1 — Prepare:
  Coordinator → all participants: "Can you commit?"
  Each participant: prepare (lock resources), respond YES/NO

Phase 2 — Commit/Abort:
  If all said YES → Coordinator → all: "COMMIT"
  If any said NO  → Coordinator → all: "ROLLBACK"
```

```java
public class TwoPhaseCommitCoordinator {
    private final List<TransactionParticipant> participants;

    public boolean executeTransaction(List<Operation> operations) {
        String txId = UUID.randomUUID().toString();
        List<TransactionParticipant> prepared = new ArrayList<>();

        // Phase 1: Prepare all participants
        for (int i = 0; i < participants.size(); i++) {
            try {
                boolean ready = participants.get(i).prepare(txId, operations.get(i));
                if (!ready) {
                    // Someone said NO — abort all who said YES
                    rollback(prepared, txId);
                    return false;
                }
                prepared.add(participants.get(i));
            } catch (Exception e) {
                rollback(prepared, txId);
                return false;
            }
        }

        // Phase 2: Commit — write decision to durable log FIRST
        log.writeTxDecision(txId, "COMMIT"); // if coordinator crashes here, can recover

        for (TransactionParticipant p : participants) {
            try {
                p.commit(txId);
            } catch (Exception e) {
                // Must keep retrying until participant commits
                // (decision is already logged as COMMIT)
                retryCommit(p, txId);
            }
        }
        return true;
    }

    private void rollback(List<TransactionParticipant> prepared, String txId) {
        log.writeTxDecision(txId, "ABORT");
        prepared.forEach(p -> p.rollback(txId));
    }
}
```

**Problems with 2PC**:
- **Blocking**: if coordinator crashes after Phase 1, participants are locked waiting — **indefinitely**
- **Single point of failure**: coordinator failure = stuck transactions
- **Slow**: 2 round trips, locks held during both phases
- **Not suitable** for microservices across internet

---

## Saga Pattern — The Microservices Solution

Break a distributed transaction into a sequence of local transactions. If one fails, execute **compensating transactions** to undo previous steps.

```
Saga = T1, T2, T3, ... Tn
Compensating = C1, C2, C3, ... Cn-1

Happy path:    T1 → T2 → T3 → T4
Failure at T3: T1 → T2 → T3(fail) → C2 → C1
```

### Choreography-based Saga (Event-driven)

```java
// Each service publishes events, other services react
// No central coordinator — services choreograph themselves

// Step 1: Order Service creates order, publishes event
@Service
public class OrderService {
    @Transactional
    public Order createOrder(CreateOrderRequest req) {
        Order order = new Order(req.getUserId(), req.getItems(), OrderStatus.PENDING);
        Order saved = orderRepository.save(order);

        // Publish event — triggers next saga step
        eventPublisher.publish(new OrderCreatedEvent(saved.getId(), saved.getUserId(),
                                                     saved.getItems(), saved.getTotal()));
        return saved;
    }

    // Compensating transaction: cancel order if downstream fails
    @EventListener
    public void onPaymentFailed(PaymentFailedEvent event) {
        orderRepository.updateStatus(event.getOrderId(), OrderStatus.CANCELLED);
        eventPublisher.publish(new OrderCancelledEvent(event.getOrderId()));
    }
}

// Step 2: Inventory Service reacts to order created
@Service
public class InventoryService {
    @EventListener
    @Transactional
    public void onOrderCreated(OrderCreatedEvent event) {
        try {
            for (OrderItem item : event.getItems()) {
                inventoryRepository.decrementStock(item.getProductId(), item.getQuantity());
            }
            eventPublisher.publish(new InventoryReservedEvent(event.getOrderId()));
        } catch (InsufficientStockException e) {
            // Compensating event
            eventPublisher.publish(new InventoryReservationFailedEvent(event.getOrderId(), e.getMessage()));
        }
    }

    // Compensating transaction: restore stock if order cancelled
    @EventListener
    @Transactional
    public void onOrderCancelled(OrderCancelledEvent event) {
        Order order = orderClient.getOrder(event.getOrderId());
        order.getItems().forEach(item ->
            inventoryRepository.incrementStock(item.getProductId(), item.getQuantity())
        );
    }
}

// Step 3: Payment Service reacts to inventory reserved
@Service
public class PaymentService {
    @EventListener
    @Transactional
    public void onInventoryReserved(InventoryReservedEvent event) {
        try {
            Payment payment = chargeUser(event.getUserId(), event.getAmount());
            eventPublisher.publish(new PaymentSucceededEvent(event.getOrderId(), payment.getId()));
        } catch (PaymentException e) {
            eventPublisher.publish(new PaymentFailedEvent(event.getOrderId(), e.getMessage()));
            // This triggers compensations upstream (inventory → order)
        }
    }
}
```

### Orchestration-based Saga (Central Coordinator)

```java
// One orchestrator drives the saga — easier to reason about, easier to monitor
@Service
public class OrderSagaOrchestrator {

    @Transactional
    public void executeOrderSaga(String orderId) {
        SagaState state = sagaRepository.create(orderId);

        try {
            // Step 1: Reserve inventory
            state.setStep("RESERVE_INVENTORY");
            sagaRepository.save(state);
            inventoryService.reserveItems(orderId, getOrderItems(orderId));

            // Step 2: Charge payment
            state.setStep("CHARGE_PAYMENT");
            sagaRepository.save(state);
            paymentService.chargeForOrder(orderId);

            // Step 3: Confirm order
            state.setStep("CONFIRM_ORDER");
            sagaRepository.save(state);
            orderService.confirmOrder(orderId);

            state.setStatus(SagaStatus.COMPLETED);
            sagaRepository.save(state);

        } catch (InventoryException e) {
            compensate(orderId, state.getStep());
        } catch (PaymentException e) {
            compensate(orderId, state.getStep());
        }
    }

    private void compensate(String orderId, String failedStep) {
        // Execute compensations in reverse order up to failure point
        switch (failedStep) {
            case "CONFIRM_ORDER":
            case "CHARGE_PAYMENT":
                inventoryService.releaseReservation(orderId); // C1: release inventory
                // fall through
            case "RESERVE_INVENTORY":
                orderService.cancelOrder(orderId); // C0: cancel order
        }
        sagaState.setStatus(SagaStatus.COMPENSATED);
    }
}
```

---

## Transactional Outbox Pattern

**Problem**: After DB write, publishing event might fail. Data saved but event never sent → downstream out of sync.

```java
// BAD: Dual write — not atomic
@Transactional
public Order createOrder(Order order) {
    Order saved = orderRepository.save(order);    // DB write
    kafka.publish(new OrderCreatedEvent(saved));   // if this fails → event lost
    return saved;
}

// GOOD: Outbox pattern — write event to DB in SAME transaction
@Transactional
public Order createOrder(Order order) {
    Order saved = orderRepository.save(order);

    // Write event to outbox table IN THE SAME DB TRANSACTION
    OutboxEvent event = new OutboxEvent(
        "OrderCreated",
        saved.getId(),
        serialize(new OrderCreatedEvent(saved))
    );
    outboxRepository.save(event); // same transaction as order save

    // If transaction commits → both order and event are in DB atomically
    return saved;
}

// Separate outbox publisher (runs in background)
@Scheduled(fixedDelay = 500)
public void publishPendingEvents() {
    List<OutboxEvent> pending = outboxRepository.findPending();
    for (OutboxEvent event : pending) {
        try {
            kafka.publish(event.getTopic(), event.getPayload());
            outboxRepository.markPublished(event.getId()); // delete or update status
        } catch (Exception e) {
            log.error("Failed to publish event {}", event.getId(), e);
            // Will retry on next poll
        }
    }
}

// Debezium CDC approach (better than polling):
// MySQL/PG binlog → Debezium reads outbox table changes → publishes to Kafka automatically
// No polling needed, near-real-time
```

---

## Idempotency Keys

```java
// For payment and other non-idempotent operations:
// Client sends unique idempotency key → server deduplicates

@RestController
public class PaymentController {

    @PostMapping("/payments")
    public ResponseEntity<Payment> processPayment(
            @RequestBody PaymentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        // Check if we've already processed this key
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return ResponseEntity.ok(existing.get()); // return previous result
        }

        // First time — process payment
        Payment payment = paymentGateway.charge(request);
        payment.setIdempotencyKey(idempotencyKey);
        paymentRepository.save(payment);

        return ResponseEntity.status(201).body(payment);
    }
}

// Stripe, PayPal, etc. all use this pattern
// Client retries on timeout → same idempotency key → server returns cached result
// No double charge!
```

---

## Distributed Locking for Critical Sections

```java
// When you need coordination but don't need full distributed transaction:
public class InventoryService {
    private final RedissonClient redisson;

    public boolean purchaseItem(String itemId, int quantity) {
        RLock lock = redisson.getLock("inventory:lock:" + itemId);
        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                throw new ConcurrentModificationException("Item is being updated");
            }
            // Only ONE process in cluster runs this at a time
            int stock = inventoryRepository.getStock(itemId);
            if (stock < quantity) return false;
            inventoryRepository.setStock(itemId, stock - quantity);
            return true;
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
```

---

## Choosing the Right Approach

| Scenario | Solution |
|----------|----------|
| Same DB, multiple tables | Database ACID transaction |
| Multiple services, need strong consistency | 2PC (only if unavoidable) |
| Multiple services, eventual consistency ok | Saga (choreography or orchestration) |
| Prevent duplicate events | Transactional Outbox |
| Prevent duplicate API calls | Idempotency Keys |
| Coordinate single resource | Distributed Lock |

---

## When to Avoid Distributed Transactions

- **Design away the need**: Can one service own the transaction? Can you merge services?
- **Use eventual consistency**: For most business operations, it's acceptable if things converge within seconds
- **Don't use 2PC in microservices**: Blocking, complex, doesn't scale

---

## Interview Questions

1. **Why is 2PC problematic in microservices?** Blocking (locks held during protocol), coordinator is SPOF, doesn't work across internet latencies, opposite of microservice autonomy.
2. **What's the difference between Saga choreography and orchestration?** Choreography: services react to events (decentralized). Orchestration: one service directs others (centralized, easier to trace).
3. **What is the outbox pattern?** Write event to a DB table in same transaction as business data. Separate process publishes from outbox to message queue. Ensures atomicity of DB write + event publish.
4. **How do you handle a saga that's partially completed when the orchestrator crashes?** Log saga state to durable storage at each step. On restart, orchestrator reads state and resumes or compensates from where it left off.
