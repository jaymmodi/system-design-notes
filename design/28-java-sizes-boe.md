# Java Memory Sizes — Back-of-Envelope Reference

> Use this to quickly estimate how much memory a data structure will use at scale.
> All sizes assume: 64-bit JVM, compressed oops ON (default for heap < 32GB).

---

## Primitives — the baseline

| Type | Size | Range |
|------|------|-------|
| `boolean` | 1 byte (field) / 1 byte (array) | true/false |
| `byte` | 1 byte | -128 to 127 |
| `short` | 2 bytes | -32K to 32K |
| `char` | 2 bytes | 0 to 65535 (Unicode) |
| `int` | 4 bytes | -2B to 2B |
| `float` | 4 bytes | ~7 decimal digits |
| `long` | 8 bytes | -9.2×10¹⁸ to 9.2×10¹⁸ |
| `double` | 8 bytes | ~15 decimal digits |

**Memory tip**: `int` = 4 bytes, `long` = 8 bytes. Everything else follows from there.

---

## JVM Object Overhead — the hidden cost

Every object on the heap has a header before its fields:

```
Object layout (compressed oops, default):
  ┌─────────────────────────────────────┐
  │  Mark word         (8 bytes)        │  GC state, hash code, lock info
  │  Class pointer     (4 bytes)        │  compressed pointer to class metadata
  ├─────────────────────────────────────┤
  │  Fields            (varies)         │
  │  Padding           (0–7 bytes)      │  align to 8-byte boundary
  └─────────────────────────────────────┘

Minimum object size: 16 bytes (even if it has zero fields)
Object reference:     4 bytes (compressed oops) ← what you store in collections
```

**The tax**: even a single `int` field object costs 16 bytes minimum (12 header + 4 int), not 4.

---

## Wrapper Types — cost of boxing

| Type | Size | vs primitive |
|------|------|-------------|
| `Boolean` | 16 bytes | 16× a primitive boolean |
| `Byte` | 16 bytes | 16× |
| `Short` | 16 bytes | 8× |
| `Character` | 16 bytes | 8× |
| `Integer` | 16 bytes | 4× |
| `Float` | 16 bytes | 4× |
| `Long` | 24 bytes | 3× |
| `Double` | 24 bytes | 3× |

```
Integer object:  12 (header) + 4 (int value) = 16 bytes
Long object:     12 (header) + 8 (long value) = 20 → padded to 24 bytes

Integer[] of 1M elements:
  Array header:        16 bytes
  1M references:       4M bytes  (4 bytes each, compressed oops)
  1M Integer objects:  16M bytes (16 bytes each)
  Total:              ~20 MB

int[] of 1M elements:
  Array header:  16 bytes
  1M ints:        4M bytes
  Total:         ~4 MB   ← 5× less than Integer[]
```

**Interview rule**: Always use primitive arrays over boxed arrays at scale.

---

## Arrays

```
Array layout:
  ┌──────────────────────────────────────┐
  │  Object header     (12 bytes)        │
  │  Array length      ( 4 bytes)        │
  ├──────────────────────────────────────┤
  │  Elements          (n × element size)│
  │  Padding           (0–7 bytes)       │
  └──────────────────────────────────────┘

Array header = 16 bytes
```

| Array type | Formula | 1M elements |
|------------|---------|-------------|
| `byte[]` | 16 + n | ~1 MB |
| `int[]` | 16 + 4n | ~4 MB |
| `long[]` | 16 + 8n | ~8 MB |
| `double[]` | 16 + 8n | ~8 MB |
| `Object[]` | 16 + 4n | ~4 MB (refs only, not the objects) |

---

## String

Java 9+ uses **compact strings**: 1 byte/char for ASCII (Latin-1), 2 bytes/char for others.

```
String object:
  Object header:   12 bytes
  byte[] ref:       4 bytes  (pointer to the char data)
  hash (int):       4 bytes
  coder (byte):     1 byte + 3 padding
  Total:           24 bytes  (the String object itself)

byte[] holding the chars:
  Array header:    16 bytes
  n chars ASCII:    n bytes  (1 byte each, Latin-1)
  n chars Unicode: 2n bytes

Total (ASCII string of length n):   ~40 + n bytes
Total (Unicode string of length n): ~40 + 2n bytes
```

| String | Size |
|--------|------|
| `""` (empty) | 40 bytes |
| `"hello"` (5 chars, ASCII) | ~45 bytes |
| UUID string (36 chars) | ~76 bytes |
| URL (100 chars, ASCII) | ~140 bytes |
| 1M strings of avg 50 chars | ~90 MB |

---

## Common Collections

### ArrayList

```
ArrayList object:          ~24 bytes
Internal Object[] array:   16 + 4 × capacity bytes

Default capacity: 10, grows by 1.5× on each resize

For n elements (assume capacity ≈ n):
  Overhead:   ~24 + 16 + 4n bytes = ~40 + 4n bytes
  Plus:       the actual element objects (not counted above)

ArrayList<Integer> of 1M elements:
  ArrayList + array:   ~4 MB   (references)
  Integer objects:     ~16 MB  (16 bytes each)
  Total:              ~20 MB

int[] of 1M:            ~4 MB   ← 5× smaller
```

### LinkedList

```
LinkedList object:    ~32 bytes
Each Node:            32 bytes
  Object header:      16 bytes
  item ref:            4 bytes
  next ref:            4 bytes
  prev ref:            4 bytes
  padding:             4 bytes

LinkedList<Integer> of 1M elements:
  Nodes:         32M bytes  (32 bytes × 1M)
  Integer objs:  16M bytes
  Total:        ~48 MB

vs ArrayList<Integer>: ~20 MB  ← LinkedList is 2.4× larger
```

**Rule**: LinkedList is almost always the wrong choice. ArrayList wins on memory and cache locality.

### HashMap

```
HashMap object:          ~48 bytes
Internal Node[] table:   16 + 4 × capacity bytes
  Default capacity:      16, grows when load factor (0.75) exceeded

Each HashMap.Node:       32 bytes
  Object header:         16 bytes
  hash (int):             4 bytes
  key ref:                4 bytes
  value ref:              4 bytes
  next ref:               4 bytes

HashMap<String, Integer> of 1M entries (capacity ≈ 1.33M):
  table array:            ~5 MB   (4 × 1.33M refs)
  1M Node objects:        32 MB   (32 bytes each)
  1M String keys:         ~90 MB  (assume avg 50 chars)
  1M Integer values:      ~16 MB
  Total:                 ~143 MB

Per entry cost:  ~143 bytes
```

### HashSet

```
HashSet is a HashMap where value = dummy PRESENT object.
Same cost as HashMap per entry.

HashSet<String> of 1M entries ≈ same as HashMap above (~143 MB)
```

### TreeMap / TreeSet

```
Each TreeMap.Entry:  40 bytes
  Object header:     16 bytes
  key ref:            4 bytes
  value ref:          4 bytes
  left ref:           4 bytes
  right ref:          4 bytes
  parent ref:         4 bytes
  color (boolean):    1 byte + 3 padding

TreeMap has ~25% higher per-entry cost than HashMap (40 vs 32 bytes/node)
Plus: no backing array needed (tree structure), so no wasted capacity slots
```

### ConcurrentHashMap

```
Similar per-entry cost to HashMap (each bin is a Node, same 32 bytes)
Extra overhead: volatile reads, striped locks (ForwardingNode during resize)
Practical rule: ~same as HashMap for estimation purposes
```

---

## Quick Estimation Formula

```
For any collection of n objects, ask:

1. What's the per-entry overhead of the collection?  (see above)
2. What's the size of each key object?
3. What's the size of each value object?

Total = n × (collection_overhead + key_size + value_size)

Example: HashMap<String(uuid), Long> with 10M entries
  collection node:   32 bytes
  String(36 chars):  ~76 bytes
  Long object:       24 bytes
  Per entry:         132 bytes
  10M entries:       1.32 GB

  Optimization: use String → long (primitive) in a specialized map like Eclipse Collections
  Per entry: 32 (node) + 76 (String) + 8 (long, inlined) = 116 bytes → 1.16 GB
  Better: encode UUID as two longs → long[] key, eliminate String entirely
```

---

## Real-World Estimates

### User session store
```
1M active sessions
  Session object: userId(long=8) + token(String 36 chars=76) + expiry(long=8) + roles(int=4)
  Session object overhead: 16 (header) + 8 + 76 + 8 + 4 = ~112 bytes per session
  HashMap entry overhead: 32 bytes
  Total per session: ~144 bytes
  1M sessions: ~144 MB
```

### Event log in memory
```
1M events, each: eventId(long) + userId(long) + timestamp(long) + type(int)
  As objects (Event[]):  16 (header) + 8+8+8+4 = ~44 bytes → 1M × 44 = 44 MB
  As parallel long[]s:   3 × (16 + 8M) = ~24 MB + int[] 4M = ~28 MB total
  As single long[]:      pack 3 longs + 1 int into 4 longs per event
                         16 + 4 × 8 × 1M = ~32 MB

Using primitive arrays: 28–44 MB vs 44–100 MB with objects
```

### Redis key overhead
```
Per key in Redis:
  Key string:  ~50 bytes (avg key name)
  Value:       varies
  Dict entry:  ~64 bytes overhead
  Total:       ~90–150 bytes per key before the value

10M Redis keys: ~1–1.5 GB in RAM just for key overhead
```

### Cache sizing
```
Cache of 1M User objects
  User: id(long) + name(String 20 chars) + email(String 30 chars) + createdAt(long)
  Object: 16 + 8 + 60 + 70 + 8 = ~162 bytes
  HashMap overhead: 32 bytes per entry + array
  Total per user: ~200 bytes
  1M users: ~200 MB
  10M users: ~2 GB  ← think about whether this fits in your instance
```

---

## Powers of Two (memorize these)

```
2^10 = 1,024        ≈ 1 thousand   (1 KB)
2^20 = 1,048,576    ≈ 1 million    (1 MB)
2^30 = 1,073,741,824 ≈ 1 billion   (1 GB)

1 byte  = 8 bits
1 KB    = 1,024 bytes      ≈ 10^3
1 MB    = 1,024 KB         ≈ 10^6
1 GB    = 1,024 MB         ≈ 10^9
1 TB    = 1,024 GB         ≈ 10^12

1M int values    = 4 MB
1M long values   = 8 MB
1M String(50ch)  = ~90 MB
1B int values    = 4 GB
```

---

## Cheat Sheet for Interviews

```
Object header:          16 bytes   (always)
Reference:               4 bytes   (compressed oops)
int:                     4 bytes
long:                    8 bytes
Integer (boxed):        16 bytes   = 4× int
Long (boxed):           24 bytes   = 3× long
String (n ASCII chars): 40 + n bytes
Array header:           16 bytes

HashMap entry:          32 bytes   + key + value objects
LinkedList node:        32 bytes   + element object
ArrayList:              ~40 + 4n bytes  + element objects

Rule of thumb for object collections:
  "Each entry costs ~32–50 bytes for collection overhead
   plus the actual key + value object sizes"

Quick scale check:
  1M entries × 100 bytes/entry = 100 MB  → fits in 1 instance
  1B entries × 100 bytes/entry = 100 GB  → need distributed store
```
