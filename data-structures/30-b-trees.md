# B-Trees

## What Problem B-Tree Solves

Binary search trees work well in memory but fail on disk. A BST with 1 million nodes needs up to 20 levels (log₂ 1M ≈ 20) — meaning 20 disk reads to find one key. Each disk read is a random I/O (~10ms on HDD).

```
BST on disk — 1 million keys:
  20 levels × 10ms per disk read = 200ms per lookup ← unacceptable
```

B-tree solves this by making each node **fat** — storing hundreds of keys per node instead of one. More keys per node = fewer levels = fewer disk reads.

```
B-tree on disk — 1 million keys, order 500:
  ~3 levels × 10ms per disk read = 30ms per lookup ✓
```

---

## Core Idea: Fat Nodes That Map to Disk Pages

A disk read fetches a full **page** (typically 4KB or 16KB) regardless of how much data you actually need. B-tree nodes are sized to exactly fill one page — so each disk read fetches one full node, maximally using the I/O.

```
Disk page = 16KB
B-tree node = 16KB
→ one disk read = one full node with hundreds of keys
```

---

## Structure

A B-tree of order `t` (minimum degree):
- Every node except root has **at least t-1 keys**
- Every node has **at most 2t-1 keys**
- A node with `k` keys has **k+1 children**
- All **leaves are at the same depth**

```
                    [30 | 70]
                   /    |    \
          [10|20]    [40|50|60]    [80|90]
          / | \      / | | | \    /  |  \
        ...        ...         ...
```

Each internal node is a **router** — it holds separator keys that tell you which subtree to go into. Actual data lives in leaves (in B+ trees — most real implementations).

---

## B-Tree vs B+ Tree

Almost every real database uses **B+ tree**, not plain B-tree:

| | B-Tree | B+ Tree |
|--|--------|---------|
| Data stored | Every node | Leaves only |
| Internal nodes | Keys + data | Keys only (routers) |
| Leaf nodes | Linked | Linked list ✅ |
| Range queries | Complex (data scattered) | ✅ scan leaf linked list |
| Internal node capacity | Lower (data takes space) | Higher (keys only, more per page) |

```
B+ Tree structure:
                    [30 | 70]           ← internal: keys only
                   /    |    \
          [10|20]    [40|50|60]    [80|90]   ← leaves: keys + data
             │            │            │
             └────────────────────────→    ← linked list for range scans
```

All leaves are connected in a linked list — range queries (`WHERE age BETWEEN 20 AND 50`) just find the start leaf and scan forward. No backtracking.

---

## Search

Find key 50 in this B+ tree (order 3):

```
                [30 | 70]
               /    |    \
      [10|20]    [40|50|60]    [80|90]
```

```
1. Start at root: [30 | 70]
   50 > 30 and 50 < 70 → go to middle child

2. Node: [40|50|60]
   50 == 50 → FOUND ✓
```

At each node: **binary search** the keys, follow the correct child pointer.

**Disk reads**: one per level. For a database with 1 billion rows, B+ tree height is ~4–5. That's 4–5 disk reads maximum for any lookup.

---

## Insert

Insert key 45 into a leaf that has room:

```
Before: [40|50|60]
After:  [40|45|50|60]  ← simple insert, sorted
```

Insert into a **full leaf** (node overflow) — must split:

```
Insert 45 into full leaf [40|50|60|70] (max 4 keys):

1. Split leaf into two:
   Left:  [40|45]
   Right: [50|60|70]

2. Push middle key (50) up to parent:
   Parent absorbs 50 as separator

3. If parent is also full → split parent recursively
   → splits propagate upward
   → only way tree grows taller: root splits
```

```
Before split:
        [30 | 70]
       /    |    \
  [10|20] [40|50|60|70] [80|90]
             ↑ full, inserting 45

After split:
        [30 | 50 | 70]          ← 50 pushed up
       /   |    |    \
  [10|20] [40|45] [60|70] [80|90]
```

---

## Delete

Three cases:

### Case 1: Delete from leaf with enough keys — simple removal
```
Delete 45 from [40|45|60]:
→ [40|60]  ✓  (still has ≥ t-1 keys)
```

### Case 2: Leaf underflows — borrow from sibling
```
Delete 40 from [40] (only key, underflow):
Right sibling [60|70|80] has extra keys

→ Rotate: borrow 60 from right sibling
→ Leaf becomes [60], right sibling becomes [70|80]
→ Update parent separator key
```

### Case 3: Leaf underflows, sibling can't lend — merge
```
Delete 40 from [40] (only key):
Right sibling [60] also has minimum keys — can't lend

→ Merge: [40] + [60] → [60]
→ Pull separator key down from parent
→ Parent loses a key → may cause parent to underflow → recurse up
→ Only way tree shrinks: root becomes empty → height decreases by 1
```

---

## Java Implementation

```java
public class BTree<K extends Comparable<K>, V> {

    private final int t; // minimum degree
    private Node<K, V> root;

    public BTree(int t) {
        this.t = t;
        this.root = new Node<>(true);
    }

    // ── Node ──────────────────────────────────────────────────────────
    static class Node<K, V> {
        List<K> keys = new ArrayList<>();
        List<V> values = new ArrayList<>();       // only used in leaves
        List<Node<K, V>> children = new ArrayList<>();
        boolean isLeaf;

        Node(boolean isLeaf) { this.isLeaf = isLeaf; }

        int keyCount() { return keys.size(); }
    }

    // ── Search ────────────────────────────────────────────────────────
    public V get(K key) {
        return search(root, key);
    }

    private V search(Node<K, V> node, K key) {
        int i = 0;
        // Find first key >= target
        while (i < node.keyCount() && key.compareTo(node.keys.get(i)) > 0)
            i++;

        // Exact match in this node
        if (i < node.keyCount() && key.compareTo(node.keys.get(i)) == 0)
            return node.isLeaf ? node.values.get(i) : search(node.children.get(i + 1), key);

        // Not found in leaf
        if (node.isLeaf) return null;

        // Recurse into correct child
        return search(node.children.get(i), key);
    }

    // ── Insert ────────────────────────────────────────────────────────
    public void put(K key, V value) {
        Node<K, V> r = root;

        // Root is full — split it first
        if (r.keyCount() == 2 * t - 1) {
            Node<K, V> newRoot = new Node<>(false);
            newRoot.children.add(r);
            splitChild(newRoot, 0);
            root = newRoot;
        }
        insertNonFull(root, key, value);
    }

    private void insertNonFull(Node<K, V> node, K key, V value) {
        int i = node.keyCount() - 1;

        if (node.isLeaf) {
            // Insert in sorted position
            node.keys.add(null);
            node.values.add(null);
            while (i >= 0 && key.compareTo(node.keys.get(i)) < 0) {
                node.keys.set(i + 1, node.keys.get(i));
                node.values.set(i + 1, node.values.get(i));
                i--;
            }
            node.keys.set(i + 1, key);
            node.values.set(i + 1, value);
        } else {
            // Find correct child
            while (i >= 0 && key.compareTo(node.keys.get(i)) < 0) i--;
            i++;
            // Split child if full
            if (node.children.get(i).keyCount() == 2 * t - 1) {
                splitChild(node, i);
                if (key.compareTo(node.keys.get(i)) > 0) i++;
            }
            insertNonFull(node.children.get(i), key, value);
        }
    }

    private void splitChild(Node<K, V> parent, int i) {
        Node<K, V> full = parent.children.get(i);
        Node<K, V> sibling = new Node<>(full.isLeaf);

        // Middle key moves up to parent
        K midKey = full.keys.get(t - 1);
        V midVal = full.isLeaf ? full.values.get(t - 1) : null;

        // Right half goes to sibling
        sibling.keys.addAll(full.keys.subList(t, full.keyCount()));
        full.keys.subList(t - 1, full.keyCount()).clear(); // remove mid + right from full

        if (full.isLeaf) {
            sibling.values.addAll(full.values.subList(t, full.values.size()));
            full.values.subList(t - 1, full.values.size()).clear();
        } else {
            sibling.children.addAll(full.children.subList(t, full.children.size()));
            full.children.subList(t, full.children.size()).clear();
        }

        // Insert mid key + new sibling into parent
        parent.keys.add(i, midKey);
        if (midVal != null) parent.values.add(i, midVal);
        parent.children.add(i + 1, sibling);
    }
}
```

---

## Complexity

| Operation | Time     | Disk reads |
|-----------|----------|------------|
| Search    | O(t log_t n) | O(log_t n) |
| Insert    | O(t log_t n) | O(log_t n) |
| Delete    | O(t log_t n) | O(log_t n) |
| Range scan (B+ tree) | O(log_t n + k) | O(log_t n + k/t) |

With t=500 and n=1 billion: height ≈ 4. Every operation touches ≤ 4 disk pages.

---

## How Real Databases Use B+ Trees

### PostgreSQL

```
Table heap file:   actual rows stored in pages (unordered)
Index file:        B+ tree — leaf nodes store (key, heap pointer)

SELECT * FROM users WHERE id = 123:
  1. B+ tree search → find heap pointer for id=123   (3-4 disk reads)
  2. Fetch heap page → return row                    (1 disk read)
  Total: 4-5 disk reads regardless of table size
```

### MySQL InnoDB (Clustered Index)

```
Primary key index:  B+ tree — leaf nodes store FULL ROW (not pointer)
Secondary indexes:  B+ tree — leaf nodes store primary key value

SELECT * FROM users WHERE id = 123:
  1. Primary B+ tree search → leaf has full row   (3-4 disk reads)
  Total: 3-4 disk reads

SELECT * FROM users WHERE email = 'jay@example.com':
  1. Email secondary index → find primary key     (3-4 disk reads)
  2. Primary index lookup → fetch full row        (3-4 disk reads)
  Total: 6-8 disk reads  ← "double lookup" or "index dive"
```

---

## Page Splits in Production — Why They're Expensive

When a B+ tree leaf splits during insert:
1. Allocate new page
2. Move half the keys to new page
3. Update parent to add new separator key
4. Write two dirty pages to disk
5. If parent also splits → cascade

On a busy OLTP database inserting sequential IDs, this causes **right-side page splits** constantly — every new ID goes into the rightmost leaf. This is actually fine (predictable). Random UUIDs as primary keys cause **page splits everywhere** — high fragmentation, poor cache utilization.

**Netflix relevance**: This is why Cassandra (LSM) is preferred over MySQL for high-write workloads — no page splits, pure sequential appends.

---

## B-Tree vs LSM Tree — Full Comparison

| | B+ Tree | LSM Tree |
|--|---------|----------|
| Write path | Random I/O (find page, modify in place) | Sequential I/O (append to MemTable → SSTable) |
| Read path | O(log n), few disk reads | O(log n) + read amplification across SSTables |
| Write amplification | Low (write once to final location) | High (data rewritten during compaction) |
| Read amplification | Low (one path down tree) | Higher (multiple SSTables to check) |
| Space amplification | Low (in-place updates) | Higher (old versions until compaction) |
| Range queries | ✅ leaf linked list scan | ✅ level 0 sequential scan |
| Concurrent writes | Complex (page locks during splits) | Simpler (MemTable + immutable SSTables) |
| Best for | Read-heavy OLTP | Write-heavy (logs, events, time-series) |
| Used by | PostgreSQL, MySQL, SQLite, Oracle | Cassandra, RocksDB, HBase, LevelDB |

---

## Connection to Skiplist and LSM

```
Write-optimized stack (LSM):
  MemTable  = skiplist         (sorted writes in memory)
  SSTable   = sorted file      (flushed from skiplist level-0 scan)
  Compaction = merge SSTables  (like merging sorted arrays)

Read-optimized stack (B+ tree):
  Every node = disk page       (fat nodes minimize disk reads)
  Tree height = log_t(n)       (t=500 → height 4 for 1B rows)
  Leaf linked list = range scan (same as skiplist level-0)
```

The leaf linked list in a B+ tree and level-0 traversal in a skiplist solve the **same problem**: efficient sorted sequential scan after O(log n) point lookup.

---

## Interview One-liner

> "B+ trees minimize disk I/O by making nodes fat — sized to one disk page, holding hundreds of keys — so tree height stays at 3–5 levels even for billions of rows. Internal nodes are pure routers (keys only), leaves hold data and are linked for range scans. The cost is random writes — every insert must find the exact leaf page and potentially cascade splits upward. This makes B+ trees ideal for read-heavy OLTP workloads (PostgreSQL, MySQL), while LSM trees win for write-heavy workloads by converting random writes to sequential appends."
