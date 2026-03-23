# Skip Lists

## What Problem Skiplist Solves

A sorted linked list has O(n) search — you scan every node. A BST gives O(log n) but requires rebalancing (AVL, Red-Black tree) which is complex, especially for concurrent access.

Skiplist gives **O(log n) search with no rebalancing** — probabilistic instead of deterministic balance.

---

## Structure

Multiple levels of linked lists, each a "fast lane" over the one below:

```
Level 3: [head] ────────────────────────────► [50] ──────────────► [null]
Level 2: [head] ──────────► [20] ───────────► [50] ──► [70] ──────► [null]
Level 1: [head] ──► [10] ──► [20] ──► [30] ──► [50] ──► [70] ──► [90] ──► [null]
Level 0: [head] ──► [10] ──► [20] ──► [30] ──► [50] ──► [70] ──► [90] ──► [null]
```

- **Level 0**: complete sorted list — every node
- **Level 1+**: express lanes — subset of nodes, probabilistically chosen
- Each node has **forward pointers** for each level it participates in

---

## Node Promotion — The Coin Flip

When inserting a node, flip a coin to decide how many levels it participates in:

```
Flip heads → promote to next level → flip again
Flip tails → stop

P(level 1) = 50%
P(level 2) = 25%
P(level 3) = 12.5%
P(level k) = (1/2)^k
```

This random promotion is what makes the skiplist **probabilistically balanced** without rotation or rebalancing.

---

## Search

Find 30:

```
Level 2: [head] ──────────► [20] ──────────────► [50]
Level 1: [head] ──► [10] ──► [20] ──► [30] ──► [50]
Level 0: [head] ──► [10] ──► [20] ──► [30] ──► [50]
```

```
Start at head, highest level (Level 2):
  head → 20: 20 < 30, move forward
  20 → 50:   50 > 30, drop down to Level 1

Level 1:
  20 → 30:   30 == 30, FOUND ✓
```

**Key rule**: go forward if `next < target`, drop down if `next > target`.

---

## Insert

Insert 25:

```
1. Search for 25, tracking last node visited at each level (update[])
2. Flip coin → promotes to Level 1 (2 levels: 0 and 1)
3. Splice into Level 0: 20 → 25 → 30
4. Splice into Level 1: 20 → 25 → 30
```

```
Before:
Level 1: [head] ──► [20] ──► [30]
Level 0: [head] ──► [20] ──► [30]

After:
Level 1: [head] ──► [20] ──► [25] ──► [30]
Level 0: [head] ──► [20] ──► [25] ──► [30]
```

---

## Complexity

| Operation | Average  | Worst Case |
|-----------|----------|------------|
| Search    | O(log n) | O(n)       |
| Insert    | O(log n) | O(n)       |
| Delete    | O(log n) | O(n)       |
| Space     | O(n log n) | O(n log n) |

Worst case is O(n) theoretically but probability decreases exponentially — practically never happens.

---

## Java Implementation

```java
import java.util.Random;

public class SkipList<K extends Comparable<K>, V> {

    private static final int MAX_LEVEL = 16;
    private static final double PROBABILITY = 0.5;

    private final Node<K, V> head = new Node<>(null, null, MAX_LEVEL);
    private int currentLevel = 1;
    private final Random random = new Random();

    // ── Node ──────────────────────────────────────────────────────────
    static class Node<K, V> {
        K key;
        V value;
        Node<K, V>[] forward;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.forward = new Node[level];
        }
    }

    // ── Random level generator ────────────────────────────────────────
    private int randomLevel() {
        int level = 1;
        while (random.nextDouble() < PROBABILITY && level < MAX_LEVEL)
            level++;
        return level;
    }

    // ── Search ────────────────────────────────────────────────────────
    public V get(K key) {
        Node<K, V> curr = head;

        for (int i = currentLevel - 1; i >= 0; i--) {
            while (curr.forward[i] != null &&
                   curr.forward[i].key.compareTo(key) < 0) {
                curr = curr.forward[i];
            }
        }

        curr = curr.forward[0];
        if (curr != null && curr.key.compareTo(key) == 0)
            return curr.value;
        return null;
    }

    // ── Insert ────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public void put(K key, V value) {
        Node<K, V>[] update = new Node[MAX_LEVEL];
        Node<K, V> curr = head;

        for (int i = currentLevel - 1; i >= 0; i--) {
            while (curr.forward[i] != null &&
                   curr.forward[i].key.compareTo(key) < 0) {
                curr = curr.forward[i];
            }
            update[i] = curr;
        }

        curr = curr.forward[0];

        // Update existing key
        if (curr != null && curr.key.compareTo(key) == 0) {
            curr.value = value;
            return;
        }

        // Insert new node
        int newLevel = randomLevel();

        if (newLevel > currentLevel) {
            for (int i = currentLevel; i < newLevel; i++)
                update[i] = head;
            currentLevel = newLevel;
        }

        Node<K, V> newNode = new Node<>(key, value, newLevel);

        for (int i = 0; i < newLevel; i++) {
            newNode.forward[i] = update[i].forward[i];
            update[i].forward[i] = newNode;
        }
    }

    // ── Delete ────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public boolean remove(K key) {
        Node<K, V>[] update = new Node[MAX_LEVEL];
        Node<K, V> curr = head;

        for (int i = currentLevel - 1; i >= 0; i--) {
            while (curr.forward[i] != null &&
                   curr.forward[i].key.compareTo(key) < 0) {
                curr = curr.forward[i];
            }
            update[i] = curr;
        }

        curr = curr.forward[0];

        if (curr == null || curr.key.compareTo(key) != 0)
            return false;

        for (int i = 0; i < currentLevel; i++) {
            if (update[i].forward[i] != curr) break;
            update[i].forward[i] = curr.forward[i];
        }

        while (currentLevel > 1 &&
               head.forward[currentLevel - 1] == null)
            currentLevel--;

        return true;
    }
}
```

---

## ConcurrentSkipList — How Java Implements It

`java.util.concurrent.ConcurrentSkipListMap` is Java's lock-free skiplist.
Uses **CAS (compareAndSet)** instead of locks.

### The Problem With Naive Locking

```
Thread 1: inserting 25 — found update[], about to splice
Thread 2: deleting 20  — modifying same pointers
→ race condition → corrupted pointers
```

Global lock works but serializes all operations — no better than a TreeMap.

---

### Solution: Marker Nodes + CAS

#### Step 1: Logical Deletion (Mark Before Physical Delete)

```java
// Step 1: Logically delete — append marker node after deleted node
node.next = new MarkerNode(node.next);   // CAS operation

// Step 2: Physically unlink — any thread can help
predecessor.next = node.next.next;       // CAS operation
```

```
Before delete 30:
[20] ──► [30] ──► [50]

After logical delete:
[20] ──► [30] ──► [MARKER] ──► [50]

After physical delete:
[20] ──────────────────────► [50]
```

Marker signals "deleted — skip me." Other threads that encounter a marked node help complete the deletion.

#### Step 2: CAS for Insert

```java
// Instead of: update[i].forward[i] = newNode
// Use CAS:
boolean success = UNSAFE.compareAndSwapObject(
    update[i],       // object
    forwardOffset[i],// field offset
    expectedNext,    // expected current value
    newNode          // new value to set
);
if (!success) retry();  // another thread modified it — restart
```

If two threads insert at the same position simultaneously, only one CAS succeeds. The other retries.

---

### Node Structure in Java's Implementation

```java
static final class Node<K, V> {
    final K key;
    volatile Object value;    // volatile — visible across threads
    volatile Node<K, V> next; // volatile — visible across threads

    // Special marker node constructor
    Node(Node<K, V> next) {
        this.key = null;
        this.value = this;    // self-reference signals marker
        this.next = next;
    }

    boolean casNext(Node<K, V> cmp, Node<K, V> val) {
        return UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
    }

    boolean casValue(Object cmp, Object val) {
        return UNSAFE.compareAndSwapObject(this, valueOffset, cmp, val);
    }

    boolean isMarker()  { return value == this; }
    boolean isDeleted() { return value == null; }
}
```

---

### Index Nodes — Separate from Data Nodes

Java separates **data nodes** (level 0) from **index nodes** (upper levels):

```java
static class Index<K, V> {
    final Node<K, V> node;      // points down to data node
    final Index<K, V> down;     // points down to next level
    volatile Index<K, V> right; // points right in same level

    boolean casRight(Index<K, V> cmp, Index<K, V> val) {
        return UNSAFE.compareAndSwapObject(this, rightOffset, cmp, val);
    }
}
```

```
HeadIndex(level=3) ──► Index ──► Index ──► null
      │                  │          │
HeadIndex(level=2) ──► Index ──► Index ──► Index ──► null
      │                  │          │          │
HeadIndex(level=1) ──► Index ──► Index ──► Index ──► null
      │                  │          │          │
    Node ──────────────► Node ──► Node ──► Node ──► null
   (key=10)           (key=20) (key=30) (key=50)
```

Index nodes can be added/removed independently without touching data nodes. Concurrent index updates don't block concurrent data reads.

---

### Simplified Concurrent Insert

```java
private void doPut(K key, V value) {
    outer: for (;;) {   // retry loop
        Node<K, V> b = findPredecessor(key);
        Node<K, V> n = b.next;

        for (;;) {
            if (n != null) {
                Object v = n.value;

                // n is deleted — help complete deletion, retry
                if (v == null) {
                    n.helpDelete(b, n.next);
                    continue outer;
                }

                // key exists — update with CAS
                if (n.key.compareTo(key) == 0) {
                    if (n.casValue(v, value)) return;
                    continue outer;   // CAS failed, retry
                }

                // n.key > key — insert between b and n
                if (n.key.compareTo(key) > 0) break;
            }

            // CAS new node in
            Node<K, V> z = new Node<>(key, value, n);
            if (b.casNext(n, z)) {
                int level = randomLevel();
                addIndex(z, level);
                return;
            }
            // CAS failed — retry
        }
    }
}
```

---

## Why Skiplist Over Balanced BST for Concurrency

| | Red-Black Tree | Skiplist |
|--|----------------|----------|
| Rebalancing | Rotations touch many nodes | No rotations |
| Lock granularity | Must lock subtree during rotation | 2 adjacent nodes max |
| Lock-free possible | Extremely complex | Naturally suited |
| Range queries | O(log n) + O(k) | O(log n) + O(k) |
| Cache locality | Poor (pointer chasing) | Level 0 is sequential |

Rotations in BST require updating 3–7 nodes atomically — very hard to do lock-free. Skiplist inserts/deletes only touch **local predecessor pointers** — perfect for CAS.

---

## Where Redis Uses Skiplist

Redis `ZSET` (sorted set) uses skiplist + hashtable together:

```
HASHTABLE:  member → score       O(1) score lookup   (ZSCORE)
SKIPLIST:   score-ordered nodes  O(log n) range scan  (ZRANGE, ZRANGEBYSCORE)
```

Range queries traverse level 0 sequentially after O(log n) search to find the start — like a sorted array with fast insertion.

---

## Interview One-liner

> "Skiplist achieves O(log n) search/insert/delete through probabilistic multilevel indexing — no rotations needed. For concurrency, Java's ConcurrentSkipListMap uses CAS on volatile next pointers and two-phase deletion: logical delete via marker nodes, then physical unlink — any thread can help complete a deletion. This avoids lock contention of rebalancing trees because skiplist modifications only touch local predecessor pointers, not entire subtrees."
