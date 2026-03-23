# Red-Black Trees

## What Problem Red-Black Tree Solves

A plain BST degenerates on sorted input:

```
Insert 1, 2, 3, 4, 5 into BST:
1
 \
  2
   \
    3
     \
      4
       \
        5
← this is just a linked list. Search is O(n), not O(log n)
```

You need a **self-balancing BST** that guarantees O(log n) height regardless of insertion order. Red-black tree does this with minimal rebalancing overhead — O(1) rotations amortized per insert/delete.

AVL trees are more strictly balanced (better read performance) but do more rotations on write. Red-black trees are **write-optimized** among balanced BSTs — preferred when writes are frequent.

---

## The Five Rules (Invariants)

Every valid red-black tree obeys these:

```
1. Every node is RED or BLACK
2. Root is BLACK
3. Every leaf (NIL sentinel) is BLACK
4. RED node's children must both be BLACK
   (no two consecutive red nodes on any path)
5. Every path from a node to its descendant NIL leaves
   has the same number of BLACK nodes (black-height)
```

Rule 4 + Rule 5 together guarantee: **longest path ≤ 2× shortest path** → height ≤ 2 log₂(n+1) → O(log n) guaranteed.

```
Shortest path: all BLACK nodes
  B → B → B → NIL   (black-height = 3)

Longest path: alternating RED-BLACK
  B → R → B → R → B → NIL   (still black-height = 3)

Longest = 2× shortest → height bounded
```

---

## Structure

```
        [B:30]
       /       \
   [R:15]      [R:50]
   /    \      /    \
[B:10][B:20][B:40][B:60]
```

- Each node: key, value, color (1 bit), left, right, parent
- NIL leaves: sentinel node (BLACK, shared across tree)

---

## Rotations — The Primitive Operation

Rotations restructure the tree while preserving BST ordering. Two types:

### Left Rotation (rotate x left)

```
    x                y
   / \              / \
  A   y    →      x   C
     / \         / \
    B   C       A   B

BST property preserved: A < x < B < y < C  (before and after)
```

### Right Rotation (rotate y right)

```
      y            x
     / \          / \
    x   C   →   A   y
   / \              / \
  A   B            B   C
```

Rotations are **O(1)** — just pointer updates, no data movement.

---

## Insert

Insert always adds a **RED** node (adding BLACK would immediately violate Rule 5 — changes black-height). Then fix violations.

### Step 1: Standard BST insert, color new node RED

```
Insert 25 into:
        [B:30]
       /       \
   [R:15]      [R:50]

→ Insert as RED leaf:
        [B:30]
       /       \
   [R:15]      [R:50]
       \
      [R:25]    ← RED node, RED parent = VIOLATION (Rule 4)
```

### Step 2: Fix violations — 3 cases based on uncle's color

Let `z` = newly inserted node, `P` = parent, `G` = grandparent, `U` = uncle (P's sibling).

---

#### Case 1: Uncle is RED → Recolor

```
        [B:G]                  [R:G]  ← G becomes RED
       /     \                /     \
   [R:P]    [R:U]    →    [B:P]    [B:U]  ← P,U become BLACK
   /                      /
[R:z]                  [R:z]       ← z stays RED
```

Push the "problem" up to G — G is now RED, may violate with its parent. Recurse upward.

---

#### Case 2: Uncle is BLACK, z is inner child → Rotate to make outer child

```
z is right child of P, P is left child of G:

        [B:G]                    [B:G]
       /     \                  /     \
   [R:P]    [B:U]    →      [R:z]    [B:U]
       \                    /
      [R:z]              [R:P]

Left-rotate P → transforms into Case 3
```

---

#### Case 3: Uncle is BLACK, z is outer child → Rotate + recolor

```
z is left child of P, P is left child of G:

        [B:G]                    [B:P]
       /     \                  /     \
   [R:P]    [B:U]    →      [R:z]    [R:G]
   /                                 /   \
[R:z]                            [B:?]  [B:U]

Right-rotate G, swap colors of P and G
```

At most **2 rotations** per insert to fix all violations.

---

## Delete

More complex than insert — 4 cases. Core idea: if you delete a BLACK node, you've violated Rule 5 (black-height). Fix by "pushing" the deficit up the tree.

### Step 1: Standard BST delete

- Leaf: remove directly
- One child: replace with child
- Two children: replace with in-order successor, delete successor

### Step 2: If deleted node was BLACK → fix double-black

Call the replacement node `x` (the node that moved into the deleted position).

#### Case 1: x's sibling S is RED → rotate to make S BLACK

```
    [B:P]                  [B:S]
   /     \                /     \
[DB:x]  [R:S]    →    [R:P]    [B:SR]
        /    \        /    \
      [B:SL][B:SR] [DB:x] [B:SL]

Left-rotate P, swap colors P↔S
→ transforms into Case 2/3/4
```

#### Case 2: S is BLACK, S's children both BLACK → recolor S

```
    [?:P]                  [?:P]  ← P absorbs double-black
   /     \                /     \
[DB:x]  [B:S]    →    [B:x]   [R:S]
        /    \                 /    \
      [B:SL][B:SR]           [B:SL][B:SR]

S becomes RED (gives its "black" to P)
If P was RED → P becomes BLACK, done
If P was BLACK → P is now double-black, recurse up
```

#### Case 3: S is BLACK, S's far child BLACK, near child RED → rotate

```
Rotate to move RED child to far side → transforms into Case 4
```

#### Case 4: S is BLACK, S's far child RED → rotate + recolor

```
    [?:P]                   [?:S]
   /     \                 /     \
[DB:x]  [B:S]    →     [B:P]    [B:SR]
        /    \          /    \
      [B:SL][R:SR]  [B:x]  [B:SL]

Left-rotate P, copy P's color to S, make P and SR BLACK
→ double-black resolved
```

At most **3 rotations** per delete.

---

## Java Implementation

```java
public class RedBlackTree<K extends Comparable<K>, V> {

    private static final boolean RED   = true;
    private static final boolean BLACK = false;

    private Node<K, V> root;
    private final Node<K, V> NIL;  // sentinel

    public RedBlackTree() {
        NIL = new Node<>(null, null);
        NIL.color = BLACK;
        root = NIL;
    }

    // ── Node ──────────────────────────────────────────────────────────
    static class Node<K, V> {
        K key;
        V value;
        boolean color;
        Node<K, V> left, right, parent;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.color = RED;
        }
    }

    // ── Search ────────────────────────────────────────────────────────
    public V get(K key) {
        Node<K, V> node = root;
        while (node != NIL) {
            int cmp = key.compareTo(node.key);
            if      (cmp < 0) node = node.left;
            else if (cmp > 0) node = node.right;
            else              return node.value;
        }
        return null;
    }

    // ── Rotations ─────────────────────────────────────────────────────
    private void rotateLeft(Node<K, V> x) {
        Node<K, V> y = x.right;
        x.right = y.left;
        if (y.left != NIL) y.left.parent = x;
        y.parent = x.parent;
        if      (x.parent == null) root = y;
        else if (x == x.parent.left) x.parent.left = y;
        else                          x.parent.right = y;
        y.left = x;
        x.parent = y;
    }

    private void rotateRight(Node<K, V> y) {
        Node<K, V> x = y.left;
        y.left = x.right;
        if (x.right != NIL) x.right.parent = y;
        x.parent = y.parent;
        if      (y.parent == null) root = x;
        else if (y == y.parent.left) y.parent.left = x;
        else                          y.parent.right = x;
        x.right = y;
        y.parent = x;
    }

    // ── Insert ────────────────────────────────────────────────────────
    public void put(K key, V value) {
        Node<K, V> z = new Node<>(key, value);
        z.left = z.right = z.parent = NIL;

        // Standard BST insert
        Node<K, V> y = null;
        Node<K, V> x = root;
        while (x != NIL) {
            y = x;
            int cmp = key.compareTo(x.key);
            if      (cmp < 0) x = x.left;
            else if (cmp > 0) x = x.right;
            else { x.value = value; return; }  // update existing
        }
        z.parent = y;
        if      (y == null)                   root = z;
        else if (key.compareTo(y.key) < 0)    y.left = z;
        else                                   y.right = z;

        z.color = RED;
        fixInsert(z);
    }

    private void fixInsert(Node<K, V> z) {
        while (z.parent != NIL && z.parent.color == RED) {
            if (z.parent == z.parent.parent.left) {
                Node<K, V> uncle = z.parent.parent.right;

                if (uncle.color == RED) {                    // Case 1
                    z.parent.color        = BLACK;
                    uncle.color           = BLACK;
                    z.parent.parent.color = RED;
                    z = z.parent.parent;                     // recurse up
                } else {
                    if (z == z.parent.right) {               // Case 2
                        z = z.parent;
                        rotateLeft(z);
                    }
                    z.parent.color        = BLACK;           // Case 3
                    z.parent.parent.color = RED;
                    rotateRight(z.parent.parent);
                }
            } else {
                // Mirror: parent is right child
                Node<K, V> uncle = z.parent.parent.left;

                if (uncle.color == RED) {                    // Case 1
                    z.parent.color        = BLACK;
                    uncle.color           = BLACK;
                    z.parent.parent.color = RED;
                    z = z.parent.parent;
                } else {
                    if (z == z.parent.left) {                // Case 2
                        z = z.parent;
                        rotateRight(z);
                    }
                    z.parent.color        = BLACK;           // Case 3
                    z.parent.parent.color = RED;
                    rotateLeft(z.parent.parent);
                }
            }
        }
        root.color = BLACK;  // Rule 2: root always BLACK
    }

    // ── Delete ────────────────────────────────────────────────────────
    public boolean remove(K key) {
        Node<K, V> z = root;
        while (z != NIL) {
            int cmp = key.compareTo(z.key);
            if      (cmp < 0) z = z.left;
            else if (cmp > 0) z = z.right;
            else break;
        }
        if (z == NIL) return false;

        Node<K, V> y = z;
        boolean yOriginalColor = y.color;
        Node<K, V> x;

        if (z.left == NIL) {
            x = z.right;
            transplant(z, z.right);
        } else if (z.right == NIL) {
            x = z.left;
            transplant(z, z.left);
        } else {
            y = minimum(z.right);            // in-order successor
            yOriginalColor = y.color;
            x = y.right;
            if (y.parent == z) {
                x.parent = y;
            } else {
                transplant(y, y.right);
                y.right = z.right;
                y.right.parent = y;
            }
            transplant(z, y);
            y.left = z.left;
            y.left.parent = y;
            y.color = z.color;
        }

        if (yOriginalColor == BLACK)
            fixDelete(x);                    // fix only if BLACK removed

        return true;
    }

    private void transplant(Node<K, V> u, Node<K, V> v) {
        if      (u.parent == null)         root = v;
        else if (u == u.parent.left)  u.parent.left = v;
        else                           u.parent.right = v;
        v.parent = u.parent;
    }

    private void fixDelete(Node<K, V> x) {
        while (x != root && x.color == BLACK) {
            if (x == x.parent.left) {
                Node<K, V> w = x.parent.right;  // sibling

                if (w.color == RED) {                        // Case 1
                    w.color = BLACK;
                    x.parent.color = RED;
                    rotateLeft(x.parent);
                    w = x.parent.right;
                }
                if (w.left.color == BLACK && w.right.color == BLACK) {
                    w.color = RED;                           // Case 2
                    x = x.parent;
                } else {
                    if (w.right.color == BLACK) {            // Case 3
                        w.left.color = BLACK;
                        w.color = RED;
                        rotateRight(w);
                        w = x.parent.right;
                    }
                    w.color = x.parent.color;                // Case 4
                    x.parent.color = BLACK;
                    w.right.color = BLACK;
                    rotateLeft(x.parent);
                    x = root;
                }
            } else {
                // Mirror cases
                Node<K, V> w = x.parent.left;

                if (w.color == RED) {
                    w.color = BLACK;
                    x.parent.color = RED;
                    rotateRight(x.parent);
                    w = x.parent.left;
                }
                if (w.right.color == BLACK && w.left.color == BLACK) {
                    w.color = RED;
                    x = x.parent;
                } else {
                    if (w.left.color == BLACK) {
                        w.right.color = BLACK;
                        w.color = RED;
                        rotateLeft(w);
                        w = x.parent.left;
                    }
                    w.color = x.parent.color;
                    x.parent.color = BLACK;
                    w.left.color = BLACK;
                    rotateRight(x.parent);
                    x = root;
                }
            }
        }
        x.color = BLACK;
    }

    private Node<K, V> minimum(Node<K, V> node) {
        while (node.left != NIL) node = node.left;
        return node;
    }
}
```

---

## Complexity

| Operation | Time     | Rotations (worst) |
|-----------|----------|-------------------|
| Search    | O(log n) | 0                 |
| Insert    | O(log n) | 2                 |
| Delete    | O(log n) | 3                 |
| Space     | O(n)     | —                 |

Height guaranteed ≤ **2 log₂(n+1)** by the invariants.

---

## Red-Black Tree vs AVL Tree

Both are self-balancing BSTs. The difference is how strictly they enforce balance.

| | Red-Black Tree | AVL Tree |
|--|----------------|----------|
| Balance condition | Black-height equal on all paths | Height difference ≤ 1 between subtrees |
| Height guarantee | ≤ 2 log₂(n+1) | ≤ 1.44 log₂(n+1) |
| Search | Slightly slower (taller) | Slightly faster (shorter) |
| Insert rotations | ≤ 2 | ≤ 2 |
| Delete rotations | ≤ 3 | O(log n) — can cascade |
| Write performance | ✅ Better | ❌ More rotations on delete |
| Read performance | ❌ Slightly worse | ✅ Better |
| Use case | Write-heavy maps/sets | Read-heavy, rarely modified |

**Rule of thumb**:
- Lots of inserts/deletes → Red-Black tree
- Mostly reads, rare writes → AVL tree

---

## Where Red-Black Trees Are Used

| System | Usage |
|--------|-------|
| Java `TreeMap`, `TreeSet` | Red-black tree internally |
| Java `ConcurrentSkipListMap` | Skiplist (not RB — easier for lock-free) |
| Linux kernel `CFS scheduler` | Red-black tree for task scheduling |
| Linux kernel `epoll` | Red-black tree for file descriptor tracking |
| C++ `std::map`, `std::set` | Red-black tree |
| Nginx | Red-black tree for timer management |
| MongoDB indexes (older) | B-tree (not RB) |

---

## Red-Black Tree vs Skiplist

This is the key comparison for your interview — Java gives you both:

| | Red-Black Tree (`TreeMap`) | Skiplist (`ConcurrentSkipListMap`) |
|--|---------------------------|-------------------------------------|
| Ordering | ✅ Sorted | ✅ Sorted |
| Search | O(log n) | O(log n) |
| Insert | O(log n) | O(log n) |
| Concurrency | ❌ Needs external lock | ✅ Lock-free (CAS) |
| Rotations | O(1) amortized | None |
| Memory | Lower (no level pointers) | Higher (multiple forward pointers) |
| Cache locality | Poor (pointer chasing) | Level 0 is sequential |
| Range queries | O(log n + k) in-order traversal | O(log n + k) level-0 scan |

**When to use TreeMap (red-black)**: single-threaded or externally synchronized sorted map.
**When to use ConcurrentSkipListMap**: concurrent sorted map — lock-free reads and writes.

**Why Java didn't make ConcurrentTreeMap**: making rotations lock-free requires updating 3–7 nodes atomically — no practical CAS-based solution. Skiplist only touches 2 adjacent pointers per insert → CAS works naturally.

---

## Red-Black Tree vs B+ Tree

| | Red-Black Tree | B+ Tree |
|--|----------------|---------|
| Lives in | RAM | Disk (pages) |
| Node size | One key per node | Hundreds of keys per node |
| Disk I/O | Not designed for disk | Minimizes disk reads |
| Height | ~2 log₂(n) | ~log₅₀₀(n) ≈ 3–4 levels |
| Use case | In-memory sorted map | Database index on disk |

Red-black tree in RAM → B+ tree on disk. Same sorted-map semantics, different physical constraints.

---

## Connection to LSM / MemTable

Some LSM implementations use red-black tree instead of skiplist for the MemTable:

```
MemTable options:
  Skiplist   → ConcurrentSkipListMap — lock-free concurrent writes
  Red-black  → TreeMap + lock — simpler, slightly less concurrent

RocksDB default: skiplist (better concurrent write throughput)
Early LevelDB:   skiplist
Some embedded DBs: red-black (simpler to implement correctly)
```

The reason skiplist wins for MemTable: concurrent writes with CAS vs locking a red-black tree during rotations.

---

## Interview One-liner

> "Red-black trees are self-balancing BSTs that maintain O(log n) height by enforcing five color invariants — most importantly, equal black-height on all root-to-leaf paths and no consecutive red nodes. This bounds height at 2 log₂(n). Insertions add a red node and fix violations with at most 2 rotations; deletions fix black-height deficits with at most 3 rotations. Java's TreeMap uses red-black trees for sorted maps in single-threaded contexts, but ConcurrentSkipListMap is preferred for concurrent access because making rotations lock-free is impractical — skiplist inserts only touch 2 local pointers, which CAS handles naturally."
