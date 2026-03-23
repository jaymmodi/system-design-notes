# CAP Theorem

## What is CAP?
In a **distributed system**, you can only guarantee **2 of 3** properties simultaneously:

- **C**onsistency — Every read gets the most recent write (or an error)
- **A**vailability — Every request gets a response (no error, but possibly stale data)
- **P**artition Tolerance — System works even when network partitions occur

**Key insight**: Network partitions ALWAYS happen in real distributed systems. You cannot avoid P. So the real choice is: **C vs A when a partition occurs**.

---

## The Three Categories

### CP Systems (Consistency + Partition Tolerance)
- Returns error/timeout rather than stale data
- Used when correctness is critical (banking, inventory)
- Examples: **HBase, MongoDB (default), Zookeeper, etcd**

### AP Systems (Availability + Partition Tolerance)
- Returns possibly stale data rather than an error
- Used when availability is critical (social media, DNS)
- Examples: **Cassandra, DynamoDB, CouchDB, Riak**

### CA Systems (Consistency + Availability)
- Not truly distributed — only works on a single node
- Examples: **Traditional RDBMS (single-node PostgreSQL)**
- Once you add distributed replication, you're choosing CP or AP

---

## Visualizing It

```
Network Partition happens...

CP System:                    AP System:
  Request → Node A               Request → Node A
  A can't reach B?               A can't reach B?
  Return ERROR                   Return stale data from A
  (consistent, unavailable)      (available, inconsistent)
```

---

## Java Examples

### CP: Strong Consistency with ZooKeeper-style distributed lock

```java
// CP behavior: refuse to proceed if quorum not available
public class CPInventoryService {
    private final DistributedLock lock;
    private final Database db;

    public boolean purchaseItem(String itemId, int quantity) {
        // Acquire distributed lock — blocks other nodes
        try (LockHandle handle = lock.acquire(itemId, Duration.ofSeconds(5))) {
            int stock = db.getStock(itemId); // reads from leader only
            if (stock < quantity) return false;
            db.decrementStock(itemId, quantity); // consistent write
            return true;
        } catch (LockAcquisitionException e) {
            // Network partition — refuse rather than risk inconsistency
            throw new ServiceUnavailableException("Cannot guarantee consistency: " + e.getMessage());
        }
    }
}
```

### AP: Eventual Consistency with conflict resolution

```java
// AP behavior: respond with best-effort data, resolve conflicts later
public class APShoppingCartService {
    private final List<Node> nodes;

    public ShoppingCart getCart(String userId) {
        List<ShoppingCart> responses = new ArrayList<>();
        for (Node node : nodes) {
            try {
                responses.add(node.getCart(userId));
            } catch (NetworkException e) {
                // Partition — skip this node, use others
                log.warn("Node {} unreachable, continuing", node.getId());
            }
        }
        if (responses.isEmpty()) {
            return ShoppingCart.empty(); // available even with all nodes down
        }
        return resolveConflict(responses); // merge diverged versions
    }

    // Last-Write-Wins conflict resolution
    private ShoppingCart resolveConflict(List<ShoppingCart> carts) {
        return carts.stream()
            .max(Comparator.comparing(ShoppingCart::getLastModified))
            .orElse(ShoppingCart.empty());
    }
}
```

---

## PACELC — Extension of CAP

CAP only talks about behavior *during a partition*. PACELC asks: what's the tradeoff *normally* (no partition)?

```
If Partition → choose A or C
Else → choose Latency or Consistency

PACELC = PAC + ELC
```

| System | Partition → | Else → |
|--------|-------------|--------|
| DynamoDB | AP | EL (low latency) |
| Cassandra | AP | EL |
| HBase | CP | EC (consistent) |
| Zookeeper | CP | EC |
| MySQL (single) | CA | EC |

```java
// PACELC tradeoff in practice: DynamoDB read consistency
public class DynamoDBClient {

    // Eventually consistent read — cheaper, lower latency, may be stale
    public Item eventuallyConsistentRead(String key) {
        return dynamoDB.getItem(GetItemRequest.builder()
            .tableName("Orders")
            .key(Map.of("id", AttributeValue.fromS(key)))
            .consistentRead(false) // EL: faster, lower cost
            .build())
            .item();
    }

    // Strongly consistent read — costs 2x read units, higher latency
    public Item stronglyConsistentRead(String key) {
        return dynamoDB.getItem(GetItemRequest.builder()
            .tableName("Orders")
            .key(Map.of("id", AttributeValue.fromS(key)))
            .consistentRead(true) // EC: guaranteed latest
            .build())
            .item();
    }
}
```

---

## Eventual Consistency Deep Dive

Systems that choose AP guarantee **eventual consistency**: given no new writes, all nodes will converge to the same value.

### Conflict Resolution Strategies

**1. Last Write Wins (LWW)**
```java
// Use timestamps to pick winner — simple but can lose data
public class LWWRegister<T> {
    private T value;
    private long timestamp;

    public synchronized void write(T newValue, long ts) {
        if (ts > this.timestamp) {
            this.value = newValue;
            this.timestamp = ts;
        }
        // silently discard older writes — DATA LOSS RISK
    }
}
```

**2. Vector Clocks**
```java
// Track causality — know which writes happened before which
public class VectorClock {
    private final Map<String, Integer> clock = new HashMap<>();

    public void increment(String nodeId) {
        clock.merge(nodeId, 1, Integer::sum);
    }

    // Returns: -1 = this happened before other, 1 = after, 0 = concurrent (conflict!)
    public int compareTo(VectorClock other) {
        boolean thisAhead = false, otherAhead = false;
        Set<String> allNodes = new HashSet<>();
        allNodes.addAll(clock.keySet());
        allNodes.addAll(other.clock.keySet());

        for (String node : allNodes) {
            int a = clock.getOrDefault(node, 0);
            int b = other.clock.getOrDefault(node, 0);
            if (a > b) thisAhead = true;
            if (b > a) otherAhead = true;
        }

        if (thisAhead && !otherAhead) return 1;
        if (otherAhead && !thisAhead) return -1;
        return 0; // concurrent — need application-level merge
    }
}
```

**3. CRDTs (Conflict-free Replicated Data Types)**
```java
// Grow-only counter — always safe to merge, no conflicts possible
public class GCounter {
    private final String nodeId;
    private final Map<String, Long> counts = new ConcurrentHashMap<>();

    public GCounter(String nodeId) {
        this.nodeId = nodeId;
    }

    public void increment() {
        counts.merge(nodeId, 1L, Long::sum);
    }

    public long value() {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    // Merge is idempotent and commutative — safe to call multiple times
    public GCounter merge(GCounter other) {
        GCounter result = new GCounter(nodeId);
        Set<String> allNodes = new HashSet<>();
        allNodes.addAll(counts.keySet());
        allNodes.addAll(other.counts.keySet());

        for (String node : allNodes) {
            long max = Math.max(
                counts.getOrDefault(node, 0L),
                other.counts.getOrDefault(node, 0L)
            );
            result.counts.put(node, max);
        }
        return result;
    }
}
```

---

## When to Use What

| Use Case | Choice | Why |
|----------|--------|-----|
| Bank account balance | CP | Never show stale balance |
| Shopping cart | AP | Slightly stale cart is fine |
| Social media likes | AP | 1000 vs 1001 likes doesn't matter |
| DNS | AP | Always available, stale TTL ok |
| Config management (ZooKeeper) | CP | All nodes must agree on config |
| Seat reservation | CP | Can't double-book |

---

## When to Avoid

- **Don't blindly pick CP** if your service doesn't actually need strict consistency — you're trading availability for no reason
- **Don't blindly pick AP** for financial or inventory data where losing a write is unacceptable

---

## Interview Questions

1. **Is Cassandra CP or AP?** AP — it prefers availability, uses eventual consistency with tunable read/write quorum
2. **Can a system be CA in a distributed setting?** No — partitions will happen; you must choose C or A when they do
3. **How does DynamoDB handle CAP?** AP by default, but offers strongly consistent reads as a tunable option
4. **What is BASE and how does it relate to CAP?** BASE (Basically Available, Soft state, Eventually consistent) describes AP systems — the opposite of ACID
