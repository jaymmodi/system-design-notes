# LSM Trees

## What Problem LSM Solves

Traditional B-tree storage engines do **random writes** — updating a value means seeking to the exact page on disk and modifying it in place.

```
B-tree write "user:123 → Alice":
  1. Find the leaf page on disk (random seek — slow on HDD, expensive on SSD)
  2. Modify the page in place
  3. Write back to disk (random write)
```

Random writes on disk are slow. HDDs have mechanical seek time (~10ms). SSDs handle random writes but wear out faster with them.

**LSM (Log-Structured Merge) tree** converts random writes into **sequential writes** — always append, never modify in place. Sequential I/O is 10–100× faster.

---

## Core Idea: Write to Memory First, Flush Sequentially

```
All writes → in-memory buffer (MemTable)
                    │
              buffer full?
                    │
                    ▼
         flush to disk as immutable
         sorted file (SSTable)
                    │
              files accumulate?
                    │
                    ▼
         background compaction:
         merge SSTables, remove
         old versions
```

---

## The Three Components

### 1. MemTable — In-Memory Write Buffer

A sorted in-memory data structure (usually a skiplist or red-black tree):

```
MemTable (in RAM):
  "apple"  → "red"
  "banana" → "yellow"
  "cherry" → "red"
  ← sorted by key →
```

Every write goes here first — **pure RAM, no disk I/O**. This is why LSM writes are fast.

Why sorted? Because when flushed to disk, you want a sorted file — sorting in memory is cheap.

**Why skiplist for MemTable specifically?**
- O(log n) insert in sorted order
- Sequential scan for flush (level 0 traversal)
- Concurrent writes with ConcurrentSkipListMap
- RocksDB, Cassandra, HBase all use skiplist for MemTable

### 2. SSTable — Sorted String Table (Immutable on Disk)

When MemTable fills up (typically 64MB–256MB), it's flushed to disk as an **SSTable**:

```
SSTable file on disk (immutable, sorted):
┌─────────────────────────────────┐
│ "apple"  → "red"                │
│ "banana" → "yellow"             │
│ "cherry" → "red"                │
│ ...sorted...                    │
├─────────────────────────────────┤
│ Index block (sparse index)      │
│ "apple" → offset 0              │
│ "cherry" → offset 128           │
├─────────────────────────────────┤
│ Bloom filter                    │
│ (probabilistic membership)      │
└─────────────────────────────────┘
```

Key properties:
- **Immutable** — never modified after written
- **Sorted** — binary search within file
- **Bloom filter** — O(1) "definitely not here" check before reading
- **Sparse index** — jump to approximate position, then scan

### 3. Compaction — Background Merge

SSTables accumulate over time. Without compaction:
- Many files to check per read
- Old versions of keys waste space
- Deleted keys (tombstones) not reclaimed

Compaction merges multiple SSTables into one, keeping only the latest version:

```
SSTable 1 (older):    SSTable 2 (newer):
  "apple" → "red"       "apple" → "green"   ← newer wins
  "banana" → "yellow"   "cherry" → "dark"
  "cherry" → "red"

After compaction:
  "apple" → "green"
  "banana" → "yellow"
  "cherry" → "dark"
```

---

## Write Path

```
Write("banana", "yellow")
        │
        ▼
1. Write to WAL (Write-Ahead Log) on disk
   → sequential append, for crash recovery only
   → if process crashes before MemTable flush, WAL replays

        │
        ▼
2. Write to MemTable (skiplist in RAM)
   → O(log n) insert
   → return success to client ✓

        │
   MemTable full (e.g., 64MB)?
        │
        ▼
3. Freeze MemTable → immutable
   New writes go to fresh MemTable

        │
        ▼
4. Flush immutable MemTable to disk as SSTable
   → sequential write (fast)
   → build bloom filter + sparse index

        │
   Too many SSTables?
        │
        ▼
5. Background compaction: merge SSTables
   → sequential read + sequential write
   → discard old versions + tombstones
```

---

## Read Path

```
Read("banana")
        │
        ▼
1. Check MemTable (most recent writes)
   → O(log n) skiplist lookup
   → FOUND? return immediately ✓

        │ not found
        ▼
2. Check immutable MemTable (being flushed)

        │ not found
        ▼
3. Check SSTables newest → oldest
   For each SSTable:
     a. Check bloom filter → "definitely not here"? skip entire file
     b. Check sparse index → find approximate offset
     c. Binary search within file
   → FOUND? return ✓

        │ not found in any SSTable
        ▼
4. Key does not exist
```

**Read amplification** — worst case checks every SSTable. Bloom filters eliminate most unnecessary file reads (99%+ of "not found" cases skip the file entirely).

---

## Bloom Filter — Why It's Critical for LSM Reads

Without bloom filters, a read for a nonexistent key scans every SSTable file:

```
Read("zebra") — key doesn't exist
Without bloom filter:
  Check SSTable 1... not found
  Check SSTable 2... not found
  Check SSTable N... not found  → O(N files) disk reads

With bloom filter:
  SSTable 1 bloom: "zebra? definitely not here" → skip
  SSTable 2 bloom: "zebra? definitely not here" → skip
  SSTable N bloom: "zebra? definitely not here" → skip
  → 0 disk reads
```

Bloom filter has no false negatives — if it says "not here," the key is definitely absent.
False positives are possible (~1%) — says "maybe here" when key isn't → one unnecessary disk read.

---

## Deletions — Tombstones

LSM never modifies in place. Deletes are written as tombstones:

```
Delete("banana")
→ Write tombstone: "banana" → [DELETED]
→ Stored like any other write (MemTable → SSTable)
```

During reads, tombstone means "key is deleted — return not found."
During compaction, tombstone + older versions are discarded together.

**Ghost read problem**: tombstone must outlive all older versions of the key. If compaction removes the tombstone before removing the old value in a deeper level, the deleted key resurfaces.

---

## Compaction Strategies

### Level Compaction (LevelDB, RocksDB)

SSTables organized into levels. Each level is 10× larger than the previous:

```
Level 0 (L0): 4 SSTables    — freshly flushed from MemTable
Level 1 (L1): ~10 SSTables  — 10MB total
Level 2 (L2): ~100 SSTables — 100MB total
Level 3 (L3): ~1TB total
```

Compaction: L0 → L1 when L0 has ≥4 files. L1 → L2 when L1 exceeds size limit.

- Read amplification: **low** (few levels to check)
- Write amplification: **higher** (data rewritten as it moves down levels)
- Space amplification: **low** (old versions removed quickly)

### Size-Tiered Compaction (Cassandra default)

Group SSTables of similar size, merge when you have N of them:

```
Tier 1 (small):  [1MB][1MB][1MB][1MB] → merge → [4MB]
Tier 2 (medium): [4MB][4MB][4MB][4MB] → merge → [16MB]
Tier 3 (large):  [16MB][16MB]...
```

- Read amplification: **higher** (more files per tier)
- Write amplification: **lower** (fewer compaction passes)
- Space amplification: **higher** (duplicate data during compaction)

Good for write-heavy workloads where reads are less frequent.

---

## The Three Amplification Trade-offs

Every LSM design trades between these three — you cannot minimize all simultaneously:

| | Level Compaction | Size-Tiered |
|--|-----------------|-------------|
| **Write amplification** | Higher | Lower |
| **Read amplification** | Lower | Higher |
| **Space amplification** | Lower | Higher |

---

## LSM vs B-tree

| | LSM Tree | B-tree |
|--|----------|--------|
| Write performance | ✅ Sequential writes, fast | ❌ Random writes, slow |
| Read performance | ❌ Read amplification | ✅ Single lookup, fast |
| Space efficiency | ❌ Space amplification during compaction | ✅ In-place, no duplication |
| Write amplification | ❌ Data rewritten during compaction | ✅ Written once |
| Crash recovery | WAL replay | WAL + page recovery |
| Best for | Write-heavy (events, logs, time-series) | Read-heavy (OLTP, user lookups) |

**Uses LSM**: Cassandra, RocksDB, HBase, LevelDB, InfluxDB, DynamoDB (partially)
**Uses B-tree**: PostgreSQL, MySQL InnoDB, SQLite, traditional RDBMS

---

## Connection to Skiplist

MemTable (the in-memory write buffer) is almost always implemented as a **skiplist**:

```
Write arrives → insert into skiplist MemTable (O(log n), sorted)
MemTable full → sequential scan of skiplist level 0 → write sorted SSTable
```

The skiplist's two properties that make it perfect for MemTable:
1. **O(log n) sorted insert** — maintains sort order on every write
2. **O(n) sequential scan** — level 0 traversal produces sorted output for SSTable flush

This is why skiplist and LSM trees appear together in every storage engine (RocksDB, Cassandra, HBase).

---

## Connection to Your Work

```
DynamoDB under the hood:
  - Write-heavy per-container behavioral profiles
  - Uses LSM-inspired storage backend
  - Heavy writes (every event updates state) suit LSM well

Delta Lake / Apache Iceberg:
  - Parquet files on S3 are immutable (like SSTables)
  - Compaction = small file compaction in Spark
  - New data always appended, never modified in place
  → Delta/Iceberg IS an LSM-inspired design at the file level

Your async DynamoDB optimization:
  - Batching UpdateItem calls = reducing write amplification
  - Same motivation as LSM's MemTable batching writes before flush
```

---

## Prefix Bloom Filters — Range Scan Optimization

### The problem — regular bloom filters don't help prefix scans

Regular bloom filters answer one question: **"is this exact key in this SSTable?"**

```
Bloom filter check: "is 'user:123:profile' in SSTable 4?" → yes/no

But for: "are there ANY keys starting with 'user:123:' in SSTable 4?"
  → Can't ask this — would need to enumerate every possible suffix
  → user:123:profile, user:123:settings, user:123:history ... impossible

So for prefix/range queries, bloom filters are useless → must open every SSTable:

Query: scan all keys for user:123

  Open SSTable 1 → scan → no keys for user:123  (wasted)
  Open SSTable 2 → scan → no keys for user:123  (wasted)
  Open SSTable 3 → scan → found 2 keys
  Open SSTable 4 → scan → no keys for user:123  (wasted)
  Open SSTable 5 → scan → found 1 key
  → 3 of 5 SSTables were unnecessary disk reads
```

### The fix — bloom on the prefix, not the full key

```
Building SSTable — keys written:
  user:123:profile
  user:123:settings
  user:456:profile
  user:789:history

Regular bloom:  {user:123:profile, user:123:settings, user:456:profile, user:789:history}

Prefix bloom (prefix = partition key, everything before last ':'):
  PFADD "user:123"  (from user:123:profile)
  PFADD "user:123"  (from user:123:settings — already set, no-op)
  PFADD "user:456"  (from user:456:profile)
  PFADD "user:789"  (from user:789:history)
  → {user:123, user:456, user:789}

Query: scan all keys for user:999

  SSTable 1 prefix bloom: "user:999? definitely not here" → SKIP ✓
  SSTable 2 prefix bloom: "user:999? definitely not here" → SKIP ✓
  SSTable 3 prefix bloom: "user:999? maybe here"          → open and scan
  SSTable 4 prefix bloom: "user:999? definitely not here" → SKIP ✓
  → opened 1 SSTable instead of 4
```

### How RocksDB and Cassandra implement it

**RocksDB — prefix extractor:**
```java
Options options = new Options();
options.useFixedLengthPrefixExtractor(8); // first 8 bytes = prefix
// RocksDB builds bloom filter on extracted prefixes, not full keys

RocksIterator iter = db.newIterator();
iter.seek("user:123");
while (iter.isValid() && iter.key().startsWith("user:123")) {
    // only SSTables that might contain "user:123" prefix were opened
    iter.next();
}
```

**Cassandra — partition key is the prefix:**
```
Key structure:  partition_key : clustering_key : column
                "user:123"    : "2024-01-01"   : "profile"
                "user:123"    : "2024-01-02"   : "settings"

Prefix bloom = bloom on partition key only ("user:123")

SELECT * FROM events WHERE user_id = 'user:123'
  → extract partition key → check prefix bloom on each SSTable
  → SSTables with no "user:123" partition → skipped entirely
```

### Regular vs prefix bloom — when to use which

```
Regular bloom:   exact key lookups    GET user:123:profile
Prefix bloom:    prefix/range scans   SCAN user:123:*

Trade-off of prefix bloom:
  Fewer distinct values → bits more densely set → higher false positive rate
  "user:123" prefix shared across many keys → one bit set serves all
  but other prefixes collide more easily

RocksDB uses both:
  Exact lookup → check regular bloom first
  Prefix scan  → check prefix bloom first
```

---

## Interview One-liner

> "LSM trees convert random writes into sequential writes by buffering in a sorted in-memory skiplist (MemTable), flushing immutable sorted files (SSTables) to disk, and periodically merging them in the background (compaction). Reads pay read amplification — checking multiple SSTables — mitigated by bloom filters that eliminate 99%+ of unnecessary file reads. The fundamental trade-off is write amplification vs read amplification vs space amplification, tuned by compaction strategy. This makes LSM ideal for write-heavy workloads like event streams, audit logs, and time-series data — exactly the profile of a Kafka/Flink data platform."
