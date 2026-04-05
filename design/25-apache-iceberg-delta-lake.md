# Apache Iceberg & Data Lake Design

## Why Iceberg? (Netflix's Primary Table Format)
Traditional data lakes (Hive) had serious problems:
- No atomic commits → readers saw partial writes
- No schema evolution → adding a column broke existing readers
- No time travel → couldn't query yesterday's snapshot
- Slow listing of S3 files → full table scan just to enumerate files

Iceberg solves all of these. Netflix moved their entire data platform to Iceberg.

---

## Iceberg Architecture

```
Iceberg Table = Catalog Entry → Metadata File → Manifest List → Manifest Files → Data Files

Object Storage (S3/GCS):
  /warehouse/orders/
    metadata/
      v1.metadata.json         ← current metadata file (schema, partition spec, snapshots)
      v2.metadata.json         ← after update
      snap-001.avro            ← manifest list (list of manifest files)
      snap-002.avro
    data/
      year=2024/month=01/
        part-00000-abc123.parquet
        part-00001-def456.parquet
      year=2024/month=02/
        ...
```

### Metadata Hierarchy

```
Metadata File (.json)
  ├── Schema history
  ├── Partition spec
  └── Snapshots list
        └── Snapshot (each represents a consistent table state)
              └── Manifest List (.avro)
                    └── Manifest File 1 (.avro)
                    │     └── Data File 1 (.parquet) — {path, size, record_count, column stats}
                    │     └── Data File 2 (.parquet)
                    └── Manifest File 2 (.avro)
                          └── Data File 3 (.parquet)
```

---

## Key Features

### 1. Snapshot Isolation (MVCC)

```java
// Each write creates a new snapshot — readers and writers never block each other

// Writer: creates new snapshot atomically
Table table = catalog.loadTable(TableIdentifier.of("orders"));
table.newAppend()
    .appendFile(dataFile1)
    .appendFile(dataFile2)
    .commit(); // Atomic: either both files committed, or neither

// Reader: reads a consistent snapshot
// Even if writer commits during a read, reader sees the snapshot it started with
TableScan scan = table.newScan()
    .asOfTime(System.currentTimeMillis() - 3600_000) // time travel: 1 hour ago
    .select("order_id", "user_id", "amount");

try (CloseableIterable<FileScanTask> tasks = scan.planFiles()) {
    for (FileScanTask task : tasks) {
        // Read only files in THAT snapshot — no inconsistency
    }
}
```

### 2. Schema Evolution

```java
// Add, rename, reorder columns WITHOUT rewriting data
UpdateSchema update = table.updateSchema();
update.addColumn("discount_amount", Types.FloatType.get()); // new column
update.renameColumn("total", "total_amount");               // rename
update.commit();

// Old Parquet files: Iceberg reads them, returns NULL for missing "discount_amount"
// No data migration needed!

// What Iceberg allows:
// ✅ Add column
// ✅ Rename column (using column IDs, not names — survives rename)
// ✅ Widen type (int → long, float → double)
// ✅ Reorder columns
// ✅ Make column optional
// ❌ Narrow type (long → int) — would truncate data
// ❌ Delete column (must use rename first to avoid confusion)
```

### 3. Partition Evolution

```java
// Change how table is partitioned WITHOUT rewriting old data
// Old data: partitioned by date
// New data: partitioned by date + region
// Iceberg handles both partition schemes simultaneously

UpdatePartitionSpec update = table.updateSpec();
update.addField(Expressions.bucket("user_id", 16)); // add bucketing
update.commit();

// Queries on new data use new partitions (faster)
// Queries on old data use old partitions
// Transparent to query engines!
```

### 4. Time Travel

```java
// Query any historical snapshot
TableScan scan = table.newScan()
    .asOfTime(Instant.parse("2024-01-15T10:00:00Z").toEpochMilli());

// Roll back table to previous state
table.manageSnapshots()
    .rollbackTo(snapshotId)
    .commit();

// Audit history
for (Snapshot snapshot : table.snapshots()) {
    System.out.println("Snapshot " + snapshot.snapshotId() +
        " at " + snapshot.timestampMillis() +
        " by " + snapshot.summary().get("spark.app.id") +
        " added " + snapshot.summary().get("added-records") + " records");
}
```

### 5. Partition Pruning (Fast Queries)

```java
// Iceberg stores min/max stats for each column in every data file
// Query planner skips files that can't contain matching rows

// Query: WHERE order_date = '2024-01-15' AND user_id = 'user123'
// Iceberg:
//   1. Partition pruning: only look at partitions for date=2024-01-15
//   2. Data file pruning: skip files where min(user_id) > 'user123' OR max(user_id) < 'user123'
//   3. Read only remaining files
// Result: read 0.01% of data instead of 100%

TableScan scan = table.newScan()
    .filter(Expressions.and(
        Expressions.equal("order_date", "2024-01-15"),
        Expressions.equal("user_id", "user123")
    ));
// Files with no matching data → automatically skipped (file-level stats)
```

---

## Netflix's Incremental Processing Solution (IPS)

**The Problem**: Processing 1 PB table to extract only records changed in last hour.
Traditional approach: scan entire table → find new/changed rows → expensive!

**Iceberg IPS Solution**: Use Iceberg metadata to identify exactly which DATA FILES changed, then process only those files — **zero data copy**.

```java
// Netflix Maestro + Iceberg IPS pattern

public class IncrementalProcessor {
    private final Table table;
    private final long lastProcessedSnapshotId;

    public List<DataFile> getNewFilesOnly() {
        // Find current and previous snapshot
        Snapshot current = table.currentSnapshot();
        Snapshot previous = table.snapshot(lastProcessedSnapshotId);

        // Get ONLY the files added between snapshots
        // Iceberg stores "added files" in each snapshot manifest
        Set<Long> previousSnapshotIds = getAncestorIds(previous);

        List<DataFile> newFiles = new ArrayList<>();
        for (ManifestFile manifest : current.dataManifests(table.io())) {
            if (manifest.snapshotId() != null && !previousSnapshotIds.contains(manifest.snapshotId())) {
                // This manifest was added after our last checkpoint
                try (ManifestReader<DataFile> reader = ManifestFiles.read(manifest, table.io())) {
                    reader.forEach(newFiles::add);
                }
            }
        }
        return newFiles; // ONLY newly added data files — not entire table!
    }

    public void processIncremental() {
        List<DataFile> newFiles = getNewFilesOnly();
        System.out.println("Processing " + newFiles.size() + " new files " +
            "(instead of " + table.currentSnapshot().allManifests(table.io()).size() + " total)");

        // Process only new files with Spark/Flink
        Dataset<Row> newData = spark.read().parquet(
            newFiles.stream().map(DataFile::path).toArray(String[]::new)
        );
        processData(newData);

        // Save checkpoint: snapshotId we processed up to
        saveCheckpoint(table.currentSnapshot().snapshotId());
    }
}

// This is EXACTLY what Netflix's Maestro + Iceberg IPS does:
// Maestro orchestrates jobs, tracks which snapshot was last processed
// Next run: "what files were added since snapshot X?"
// Process only those files → massive savings (1% of data instead of 100%)
```

---

## Data Lakehouse Architecture

```
Traditional Data Warehouse:
  → Structured data only
  → Expensive scaling
  → Vendor lock-in
  → No raw data

Traditional Data Lake (S3 + Hive):
  → Any format
  → No ACID transactions
  → Schema chaos
  → No performance guarantees

Data Lakehouse (Iceberg / Delta Lake / Hudi):
  → Any data (structured, semi-structured)
  → ACID transactions on object storage
  → Schema enforcement and evolution
  → Performance via file-level statistics
  → Open format (no vendor lock-in)
  → Time travel and audit

Netflix's architecture IS a lakehouse:
  All events → Kafka → Flink → Apache Iceberg tables on S3
  Analysts query via Spark/Trino/Presto
  Real-time queries via Druid (separate path)
```

---

## Compaction — Managing Small Files

```java
// Problem: streaming writes create many small Parquet files (bad for query performance)
// Each Flink checkpoint = many small files
// Solution: periodic compaction job merges small files into large ones

// Iceberg compaction with Spark
SparkActions.get().rewriteDataFiles(table)
    .option("target-file-size-bytes", String.valueOf(512 * 1024 * 1024)) // 512MB target
    .option("partial-progress.enabled", "true") // commit incrementally
    .option("max-concurrent-file-group-rewrites", "5")
    .execute();

// How often to compact? Rule of thumb:
// Streaming writes: compact every hour (many small files)
// Batch writes: compact after each batch run
// Measure: avg file size < 50MB → compact; > 256MB → don't bother

// Compaction does NOT change visible data — it rewrites files atomically
// Readers during compaction see consistent snapshot
```

---

## Comparing Table Formats

| Feature | Apache Iceberg | Delta Lake | Apache Hudi |
|---------|---------------|------------|-------------|
| ACID | Yes | Yes | Yes |
| Time travel | Yes | Yes | Yes |
| Schema evolution | Best | Good | Good |
| Partition evolution | Yes | Limited | Limited |
| Streaming support | Good | Good | Best (upserts) |
| Adopted by | Netflix, Apple | Databricks, Azure | Uber, Hive |
| Open spec | Yes (ASF) | Open source | Open source |
| Row-level updates | V2 (merge-on-read) | Yes | Best (copy-on-write) |

---

## Design: Data Pipeline with Iceberg

```
Design question: "Design an event pipeline that stores 2 trillion events/day
                  with ability to query any historical point and process only new data"

Architecture:
  Producers → Kafka → Flink (streaming) → Iceberg tables on S3
                                         → Schema Registry (Avro)
                ↓
            Maestro (orchestration)
                ↓ runs hourly
            Compaction Job (Spark) → compact small files
                ↓ runs hourly
            Incremental Processing Jobs → read only new files via IPS
                ↓
            Druid (real-time queries) / Trino (ad-hoc SQL)

Key decisions:
  1. Partitioning: by event_date (daily) + event_type (for pruning)
  2. File format: Parquet (columnar, compressed, fast scans)
  3. Target file size: 256MB-512MB (after compaction)
  4. Snapshot retention: 7 days (balance: time travel vs metadata size)
  5. Compaction frequency: hourly (streaming creates many small files)
  6. IPS checkpoint: track last processed snapshotId per job
```

---

## When to Use Iceberg

- Large-scale analytics data that needs schema evolution
- Data that needs time travel or audit capabilities
- Multi-engine environments (Spark, Flink, Trino, Presto all reading same tables)
- Replacing Hive tables in a data lake
- Incremental processing pipelines (IPS pattern)

## When to Avoid

- Small datasets (< 1GB) — overhead not worth it
- When you need row-level ACID with very high update frequency (consider HBase/Cassandra)
- Pure real-time OLAP (use Druid or ClickHouse directly)

---

## Interview Questions

1. **Why does Netflix use Iceberg instead of Hive?** ACID transactions, atomic commits, schema evolution without rewrite, partition evolution, file-level statistics for pruning, time travel.
2. **What is Netflix's IPS pattern?** Use Iceberg snapshot metadata to identify only data files added since last checkpoint. Process those files instead of full table. Zero data copy, massive efficiency gain.
3. **How does Iceberg achieve ACID on S3?** Optimistic concurrency via metadata files. Each commit reads current metadata, writes new metadata file atomically (S3 conditional PUT or catalog CAS). If concurrent write → conflict detected, retry.
4. **What is a manifest file in Iceberg?** Avro file listing a set of data files with their column statistics (min, max, null counts). Used for partition pruning — query planner skips manifests that can't contain matching rows.

---

## Iceberg vs Delta Lake — Deep Comparison

> **Jay's context**: You know Delta Lake from GuardDuty. This section is your bridge. The concepts are nearly identical — the differences are in the details that interviewers probe.

### The 30-Second Summary
Both solve the same problem: ACID transactions and schema evolution on a data lake (S3/GCS/ADLS). Delta Lake is Databricks' solution; Iceberg is the open standard adopted by Netflix, Apple, Snowflake, and the broader community.

---

### Architecture Differences

```
Delta Lake:
  _delta_log/
    00000000000000000000.json   ← transaction log entries (JSON)
    00000000000000000001.json
    00000000000000000010.checkpoint.parquet  ← checkpoint every 10 commits
  data/
    part-00000-abc.parquet

Apache Iceberg:
  metadata/
    v1.metadata.json            ← full table state snapshot
    v2.metadata.json
    snap-001.avro               ← manifest list
  data/
    part-00000-abc.parquet

Key structural difference:
  Delta: append-only transaction LOG (like a journal — replay to get current state)
  Iceberg: immutable SNAPSHOTS (each snapshot is a complete, self-contained view)
```

```java
// Delta Lake: transaction log is a sequential journal
// To find current state: read and replay all log entries (or last checkpoint)
// _delta_log/0001.json: {"add": {"path": "part-001.parquet"}}
// _delta_log/0002.json: {"remove": {"path": "part-001.parquet"}}
// Current state = replay from last checkpoint forward

// Iceberg: each snapshot points directly to its manifest list
// No replaying needed — snapshot IS the state at that point in time
// Faster for time travel and concurrent reads
```

---

### Feature-by-Feature Comparison

| Feature | Apache Iceberg | Delta Lake | Notes |
|---------|---------------|------------|-------|
| **ACID transactions** | ✅ Optimistic CC | ✅ Optimistic CC | Both use optimistic concurrency |
| **Time travel** | ✅ By snapshot ID or timestamp | ✅ By version number or timestamp | Both work well |
| **Schema evolution** | ✅ Best-in-class | ✅ Good | Iceberg uses column IDs, not names — survives renames |
| **Partition evolution** | ✅ Native, transparent | ⚠️ Limited (replace, not add) | Iceberg biggest differentiator |
| **Hidden partitioning** | ✅ Yes | ❌ No | Iceberg can auto-partition without exposing to queries |
| **Multi-engine support** | ✅ Spark, Flink, Trino, Presto, Hive | ⚠️ Best with Databricks/Spark | Iceberg is engine-agnostic by design |
| **Row-level deletes** | ✅ V2 (merge-on-read or copy-on-write) | ✅ Yes | Needed for GDPR deletion |
| **Metadata overhead** | Slightly higher (richer metadata) | Lighter (simpler log) | Iceberg trades storage for query speed |
| **Incremental processing** | ✅ IPS via snapshot diff | ✅ Delta CDF (Change Data Feed) | Similar capability, different API |
| **Concurrent writers** | ✅ Optimistic, catalog-level CAS | ✅ Optimistic, S3 conditional PUT | Both handle concurrent writes |
| **Streaming writes** | ✅ Flink + Iceberg sink | ✅ Spark Structured Streaming | Iceberg has better Flink integration |
| **Open specification** | ✅ Apache Software Foundation | ⚠️ Open source, Databricks-led | Iceberg more truly open |
| **Vendor** | Netflix, Apple, Snowflake, AWS | Databricks, Microsoft Azure | Iceberg won the ecosystem battle |

---

### Partition Evolution — Iceberg's Biggest Win

```java
// DELTA LAKE: changing partitioning requires rewriting the entire table
// Old: PARTITIONED BY (date)
// New: PARTITIONED BY (date, region)
// → Must run INSERT OVERWRITE to rewrite all existing data with new partition scheme

// ICEBERG: partition evolution is seamless
UpdatePartitionSpec update = table.updateSpec();
update.addField(Expressions.identity("region")); // add partition column
update.commit();

// Old data files: still partitioned by (date) only — untouched
// New data files: partitioned by (date, region)
// Query engine handles both transparently — no data rewrite!

// Why this matters at Netflix scale:
// You have 10PB of data partitioned by date
// You realize you also need region partitioning
// Delta Lake: rewrite 10PB — days of compute, massive cost
// Iceberg: zero rewrite — partition evolution applied going forward only
```

---

### Schema Evolution — Column IDs vs Column Names

```java
// DELTA LAKE: tracks columns by NAME
// Rename "user_id" to "userId"
// → Old files: column named "user_id"
// → New files: column named "userId"
// → Queries: must handle both names, potential confusion

// ICEBERG: tracks columns by internal INTEGER ID
// Column "user_id" has internal ID = 3
// Rename to "userId" → same ID (3), just different display name
// → Old and new files: both have column ID 3
// → Query engine resolves name → ID → reads correctly from ALL files
// → Schema evolution is truly transparent

// Example:
UpdateSchema update = table.updateSchema();
update.renameColumn("user_id", "userId");  // ID stays the same
update.addColumn("region", Types.StringType.get()); // new ID assigned
update.commit();
// Existing Parquet files: column ID 3 still there (just called userId now)
// Reader: "give me userId" → resolves to ID 3 → reads from all files correctly
```

---

### Incremental Processing: IPS vs Delta CDF

```java
// ICEBERG: Incremental Processing Solution (IPS) — Netflix's approach
// Read snapshot metadata to find added data files since last checkpoint
List<DataFile> newFiles = getFilesAddedBetweenSnapshots(previousSnapshotId, currentSnapshotId);
// Process ONLY those files — no scanning old data

// DELTA LAKE: Change Data Feed (CDF)
// Enabled per table: ALTER TABLE myTable SET TBLPROPERTIES ('delta.enableChangeDataFeed' = 'true')
// Read changes as a stream:
spark.read.format("delta")
    .option("readChangeFeed", "true")
    .option("startingVersion", lastProcessedVersion)
    .load("/path/to/delta-table");
// Returns: _change_type (insert/update/delete), _commit_version, _commit_timestamp

// Key difference:
// Iceberg IPS: file-level metadata diff (zero data copy — just reads metadata)
// Delta CDF: row-level change records (slightly more overhead, but gives update/delete rows)
// For append-only pipelines: both work, IPS is simpler
// For update/delete tracking: Delta CDF gives row-level change type
```

---

### When to Choose Each

| Situation | Choose |
|-----------|--------|
| Netflix / open ecosystem | **Iceberg** |
| Databricks / Azure Synapse | **Delta Lake** |
| Need partition evolution | **Iceberg** |
| Multi-engine (Flink + Trino + Spark) | **Iceberg** |
| Primarily Spark workloads | Either (Delta slightly simpler) |
| Row-level updates (CDC/upserts) | Delta (mature) or Iceberg V2 |
| GDPR row-level deletion | Both work; Delta CDF gives audit trail |

---

### The Bridge (Jay's Interview Answer)

> "I've used Delta Lake extensively at GuardDuty for stateful detection pipelines. Delta Lake and Iceberg solve the same core problem — ACID semantics on object storage — and share the same key concepts: snapshots, schema evolution, time travel, and optimistic concurrency control. The main advantages Iceberg has that are relevant to Netflix specifically are: partition evolution without data rewrite, stronger multi-engine support (Flink + Trino + Spark all reading the same tables), and the IPS pattern for zero-copy incremental processing. The API is different but the mental model transfers directly."
