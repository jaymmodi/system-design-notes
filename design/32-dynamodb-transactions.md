# DynamoDB Transactions — Deep Dive

> Summarized from [AWS DynamoDB Transactions API](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html)

---

## What Are DynamoDB Transactions?

Group multiple read or write actions into a single **all-or-nothing** operation — ACID guarantees within a single AWS region.

```
TransactWriteItems([
  Put(order#123, status=PLACED),
  Update(inventory#item-A, qty -= 1),
  ConditionCheck(user#456, balance >= price)
]) → all commit or all abort
```

---

## Two APIs

### TransactWriteItems
- Up to **100 write actions** per transaction
- Up to **4 MB** aggregate size
- Action types: `Put`, `Update`, `Delete`, `ConditionCheck`
- **Synchronous + idempotent** (via `ClientRequestToken`)
- Cannot target the same item twice in one transaction

### TransactGetItems
- Up to **100 Get actions** per transaction
- Up to **4 MB** aggregate size
- Returns an **atomic snapshot** — all items read as of the same point in time
- Use this instead of `BatchGetItem` when you need serializable reads

---

## Isolation Levels

This is where DynamoDB gets nuanced. It does NOT use one isolation level for everything.

### Serializability

> The result of concurrent operations is equivalent to some **serial (one-at-a-time) execution**.
> No interleaving of operations from different transactions is visible.

DynamoDB guarantees **serializable** isolation between:

| Operation A | Operation B |
|---|---|
| `TransactWriteItems` | `TransactWriteItems` |
| `TransactWriteItems` | `TransactGetItems` |
| `TransactWriteItems` | `GetItem` (single item) |
| `TransactWriteItems` | `PutItem` / `UpdateItem` / `DeleteItem` |
| Individual item in `BatchWriteItem` | Any transactional op |

**What this means in practice:** A `GetItem` on a key that's part of a concurrent `TransactWriteItems` will see either the full before-state or the full after-state — never a partial write.

---

### Read-Committed

> Reads only see **committed** data — never an in-flight or aborted transaction's partial writes.
> But two reads within the same operation can see different committed states.

DynamoDB guarantees **read-committed** (not serializable) isolation for:

| Operation | Why not serializable |
|---|---|
| `BatchGetItem` | Multi-item read — items may be read at different points in time |
| `Query` | Scans multiple items — a write can commit mid-scan |
| `Scan` | Same as Query |

**Example of the gap:**
```
t=0: TransactWriteItems begins — updates item A and item B
t=1: Query reads item A → sees OLD value (transaction not yet committed)
t=2: Transaction commits
t=3: Query reads item B → sees NEW value (committed)
→ Query sees inconsistent state across A and B
```

Use `TransactGetItems` to avoid this — it gives you a serializable snapshot of all items.

---

### Linearizability

> Every operation appears to take effect **instantaneously** at some point between its start and end.
> Stronger than serializability — also constrains the real-time order of operations.

DynamoDB **does not explicitly advertise linearizability** as a named guarantee, but:
- Strongly consistent reads (`ConsistentRead: true`) on a single item are linearizable — you always see the latest committed value
- Transactions within a region commit at a single point — subsequent reads with `ConsistentRead: true` reflect the committed state

**Linearizability vs Serializability:**

| | Serializability | Linearizability |
|---|---|---|
| **Scope** | Multi-item transactions | Single-object operations |
| **Real-time order** | Not required | Required — respects wall clock |
| **Example** | T1 and T2 execute serially in some order | If T1 completes before T2 starts, T2 must see T1's writes |
| **DynamoDB** | Transactions + single-item ops | Strongly consistent single-item reads |

---

### Full Isolation Level Matrix

| Operation | Isolation with `TransactWriteItems` |
|---|---|
| `GetItem` | Serializable |
| `PutItem` / `UpdateItem` / `DeleteItem` | Serializable |
| `TransactGetItems` | Serializable |
| `BatchGetItem` | Read-committed |
| `BatchWriteItem` (per item) | Serializable |
| `Query` | Read-committed |
| `Scan` | Read-committed |

---

## Conflict Handling

### When conflicts occur:
1. Two `TransactWriteItems` target the same item simultaneously
2. A `TransactGetItems` conflicts with an ongoing `TransactWriteItems` on the same item
3. A standard write (`PutItem`) conflicts with an ongoing `TransactWriteItems`

### Error types:

| Error | Triggered by | Behavior |
|---|---|---|
| `TransactionCanceledException` | Conflict inside a `TransactWriteItems` or `TransactGetItems` | Entire transaction fails — no partial writes |
| `TransactionConflictException` | Standard write (`PutItem`) conflicts with ongoing transaction | That single request fails |
| `TransactionInProgressException` | Duplicate `TransactWriteItems` with same token still in-flight | SDK retries automatically |
| `IdempotentParameterMismatch` | Same `ClientRequestToken` reused with different parameters | Fails immediately |

---

## Idempotency

```java
TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
    .clientRequestToken("order-123-attempt-1")   // idempotency key
    .transactItems(/* ... */)
    .build();

dynamoDB.transactWriteItems(request);
// Safe to retry — same token = same result, no duplicate write
```

- Token valid for **10 minutes** after first completion
- If retried within window with same parameters → returns success, no re-execution
- If retried with **different** parameters → `IdempotentParameterMismatch`

---

## Capacity Cost — 2× Everything

DynamoDB performs **two underlying operations per item** (prepare + commit):

| Scenario | Cost |
|---|---|
| `TransactWriteItems`, 3 × 500B items | 3 items × 2 WCUs = **6 WCUs** |
| `TransactGetItems`, 3 × 500B items | 3 items × 2 RCUs = **6 RCUs** |
| `TransactWriteItems` via DAX | 6 WCUs (write) + 6 RCUs (DAX cache population) = **12 total** |

Plan capacity for retries — `TransactionInProgressException` retries also consume WCUs.

---

## Streams Caveat

```
TransactWriteItems([Put A, Update B, Delete C])
  → Stream record for A
  → Stream record for B    ← may appear at DIFFERENT times in the stream
  → Stream record for C
```

Stream consumers **cannot assume** they'll see all records from a transaction together. If you need to read the post-transaction state atomically, use `TransactGetItems`.

---

## Global Tables Limitation

Transactions work **within a single region only**:
- ACID guarantees do not extend across regions
- A transaction committed in us-east-1 replicates to eu-west-1 **eventually**
- A partially replicated transaction state IS observable in replica regions mid-replication

For cross-region consistency you need application-level sagas or a distributed coordination layer.

---

## Java Code Pattern

```java
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

// TransactWriteItems — place order + deduct inventory atomically
TransactWriteItemsRequest writeRequest = TransactWriteItemsRequest.builder()
    .clientRequestToken("order-" + orderId + "-v1")
    .transactItems(
        TransactWriteItem.builder()
            .put(Put.builder()
                .tableName("Orders")
                .item(Map.of(
                    "pk", AttributeValue.fromS("order#" + orderId),
                    "status", AttributeValue.fromS("PLACED")
                ))
                .conditionExpression("attribute_not_exists(pk)")  // no duplicate orders
                .build())
            .build(),
        TransactWriteItem.builder()
            .update(Update.builder()
                .tableName("Inventory")
                .key(Map.of("pk", AttributeValue.fromS("item#" + itemId)))
                .updateExpression("SET qty = qty - :one")
                .conditionExpression("qty > :zero")               // don't go negative
                .expressionAttributeValues(Map.of(
                    ":one",  AttributeValue.fromN("1"),
                    ":zero", AttributeValue.fromN("0")
                ))
                .build())
            .build()
    )
    .build();

try {
    client.transactWriteItems(writeRequest);
} catch (TransactionCanceledException e) {
    // inspect cancellation reasons per item
    e.cancellationReasons().forEach(r ->
        System.out.println(r.code() + ": " + r.message())
    );
}


// TransactGetItems — read order + inventory atomically
TransactGetItemsRequest getRequest = TransactGetItemsRequest.builder()
    .transactItems(
        TransactGetItem.builder()
            .get(Get.builder()
                .tableName("Orders")
                .key(Map.of("pk", AttributeValue.fromS("order#" + orderId)))
                .build())
            .build(),
        TransactGetItem.builder()
            .get(Get.builder()
                .tableName("Inventory")
                .key(Map.of("pk", AttributeValue.fromS("item#" + itemId)))
                .build())
            .build()
    )
    .build();

TransactGetItemsResponse response = client.transactGetItems(getRequest);
// response.responses() — both items from the same committed point in time
```

---

## Atomicity of Individual Operations vs Locking

**Individual write operations (`UpdateItem`, `PutItem`, `DeleteItem`) are always atomic** — they operate on the most recent version of the item regardless of concurrency. No locking needed for single-item writes.

**Locking is only needed for read-modify-write cycles:**

```
// SAFE — atomic, no locking needed
updateItem(PK, "SET counter = counter + 1")   // ADD expression, atomic

// UNSAFE — race condition without locking
value = getItem(PK)          // another process can modify here
value.counter += 1           //
putItem(PK, value)           // may overwrite the other process's write
```

| Operation | Atomic? | Locking needed? |
|-----------|---------|-----------------|
| `UpdateItem` (expression-based) | Yes — always latest version | No |
| `PutItem` / `DeleteItem` | Yes | No |
| Read → modify → write (in app code) | No — gap between read and write | Yes → use `ConditionExpression` |
| Counter increment | Yes — use `ADD` expression | No |

---

## Concurrency Control Strategies

### 1. Optimistic Locking (Optimistic Concurrency Control)

**Assumption:** Conflicts are rare — let concurrent access happen, detect conflict at write time.

**Mechanism:** Store a `version` attribute on the item. Increment it on every write. Guard each write with a `ConditionExpression` that checks the version you read is still current.

```java
// Read: version = 4
updateItem(
  conditionExpression: "version = :readVersion",   // fails if someone else already wrote
  updateExpression:    "SET #data = :newData, version = :newVersion",
  expressionAttributeValues: {
    ":readVersion": 4,
    ":newVersion":  5,
    ":newData":     "..."
  }
)
// ConditionalCheckFailedException → retry from read
```

**Best for:**
- Low contention (conflicts are rare)
- Single-item updates
- Cheap retries (short operations)

---

### 2. Pessimistic Locking — Transactions (`TransactWriteItems`)

**Assumption:** Conflicts matter — use DynamoDB's native transactions for all-or-nothing semantics.

**Mechanism:** Wrap reads and writes in `TransactWriteItems` / `TransactGetItems`. DynamoDB ensures atomicity across multiple items/tables.

**Best for:**
- Multi-item atomic updates (e.g. deduct inventory + create order)
- All-or-nothing semantics
- Moderate contention

**Cost:** 2× WCU per item (prepare + commit phase).

---

### 3. Pessimistic Locking — Lock Client (Dedicated Lock Table)

**Assumption:** Conflicts are frequent or retries are expensive — acquire exclusive access before modifying.

**Mechanism:** Maintain a separate DynamoDB lock table. Process acquires a lease (with TTL) before doing work. Other processes see the lock and wait. Heartbeat keeps the lock alive. Lock auto-expires if process crashes.

```
Lock table item:
  PK: "lock:resource123"
  owner: "process-A"
  ttl: <expiry timestamp>    ← auto-released if process crashes

Process B tries to acquire:
  PutItem with condition: attribute_not_exists(PK)
  → fails if Process A holds it → wait and retry
```

**Best for:**
- Long-running workflows
- Coordinating access to external resources
- Expensive retries (you don't want to redo work)
- Distributed coordination across services

---

### Strategy Comparison

| Strategy | Assumption | Best For | Cost |
|----------|------------|----------|------|
| **Optimistic locking** | Conflicts rare | Single-item, cheap retries, low contention | Normal WCU |
| **Transactions** | Need atomicity | Multi-item all-or-nothing, moderate contention | 2× WCU |
| **Lock client** | Conflicts likely / retries expensive | Long workflows, external resources, distributed coordination | Extra lock table WCU |

---

### ⚠️ Global Tables Warning

Optimistic locking with version numbers **does not work across DynamoDB global tables**. Global tables use **last writer wins** reconciliation — a write in us-east-1 can silently overwrite a concurrent write in eu-west-1 without version checks. Handle conflicts at the application level when using global tables.

---

## When to Use Transactions vs Alternatives

| Scenario | Use |
|---|---|
| Atomic multi-item write (order + inventory) | `TransactWriteItems` |
| Atomic multi-item read (consistent snapshot) | `TransactGetItems` |
| Single-item conditional write | `PutItem` / `UpdateItem` with `ConditionExpression` — cheaper, no 2× cost |
| Bulk load (correctness per item, not group) | `BatchWriteItem` — no 2× cost |
| Cross-region atomic write | Not possible in DynamoDB — use application-level saga |
| High-throughput counter | Atomic `UpdateItem` with ADD — never use transactions for counters |

---

## Key Numbers to Quote in Interviews

| Limit | Value |
|---|---|
| Max items per transaction | 100 |
| Max aggregate size | 4 MB |
| Capacity multiplier | 2× per item (prepare + commit) |
| Idempotency token window | 10 minutes |
| Cross-region support | None |
