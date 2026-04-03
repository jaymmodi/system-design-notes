# SQL vs NoSQL + Indexes

---

## SQL (Relational Databases)

### When SQL Wins
- **ACID transactions** across multiple tables
- **Complex queries** with JOINs, aggregations
- **Well-defined schema** that doesn't change often
- **Referential integrity** matters
- Strong **consistency** required

### Key SQL Concepts

```java
// Connection pooling — never create connections per request
@Configuration
public class DataSourceConfig {
    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
        config.setUsername("user");
        config.setPassword("pass");
        config.setMaximumPoolSize(20);     // max connections to DB
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30_000); // 30s to get a connection
        config.setIdleTimeout(600_000);      // close idle connections after 10min
        return new HikariDataSource(config);
    }
}
```

---

## Indexes — The Most Important SQL Optimization

### B-Tree Index (Default)
```sql
-- Without index: full table scan O(N)
SELECT * FROM orders WHERE user_id = 123;  -- scans all rows!

-- With index: O(log N) tree lookup
CREATE INDEX idx_orders_user_id ON orders(user_id);
SELECT * FROM orders WHERE user_id = 123;  -- uses index!

-- Composite index: order matters!
CREATE INDEX idx_orders_user_status ON orders(user_id, status, created_at);
-- This index can serve:
--   WHERE user_id = 123
--   WHERE user_id = 123 AND status = 'pending'
--   WHERE user_id = 123 AND status = 'pending' AND created_at > '2024-01-01'
-- But NOT:
--   WHERE status = 'pending'  -- can't use index starting at middle column
--   WHERE created_at > '2024-01-01'  -- same issue
-- Rule: "leftmost prefix" — index usable only from the leftmost column
```

```java
// How B-Tree index works internally:
// Balanced tree where leaf nodes contain:
//   key value → pointer to table row (heap file)
// Range queries very efficient: tree traversal + sequential scan of leaves
// INSERT/UPDATE/DELETE are slower (maintain tree balance)

// B-Tree supports:
// =, <, >, <=, >=, BETWEEN, IN (sometimes), LIKE 'prefix%'
// NOT useful for: LIKE '%suffix', functions on column: WHERE LOWER(name) = 'alice'

// To support function-based queries:
// CREATE INDEX idx_lower_name ON users(LOWER(name)); -- expression index
```

### Hash Index
```sql
-- Only equality (=), not ranges
CREATE INDEX idx_user_email_hash ON users USING HASH(email);
-- Faster than B-tree for pure equality lookups
-- Cannot do: WHERE email > 'a@example.com'
```

### Partial Index
```sql
-- Index only rows matching a condition — smaller, faster
CREATE INDEX idx_pending_orders ON orders(created_at)
WHERE status = 'pending';
-- Only indexes pending orders — great if most orders are completed
-- SELECT * FROM orders WHERE status = 'pending' ORDER BY created_at uses this

CREATE INDEX idx_active_users ON users(email)
WHERE deleted_at IS NULL;
-- Only live users indexed
```

### Covering Index (Index-Only Scan)
```sql
-- Include all columns needed by query in the index itself
-- Avoids reading the actual table (heap) entirely
CREATE INDEX idx_orders_covering
ON orders(user_id, created_at)
INCLUDE (status, total_amount);  -- PostgreSQL syntax

-- Query served entirely from index (no table access):
SELECT status, total_amount FROM orders
WHERE user_id = 123 ORDER BY created_at DESC LIMIT 10;
```

### Full-Text Index
```sql
-- PostgreSQL full-text search
CREATE INDEX idx_products_search ON products
USING GIN(to_tsvector('english', name || ' ' || description));

SELECT name FROM products
WHERE to_tsvector('english', name || ' ' || description)
      @@ to_tsquery('english', 'laptop & fast');
-- For heavy full-text: use Elasticsearch instead
```

### Java: Analyzing Query Performance

```java
// Always EXPLAIN ANALYZE slow queries
// EXPLAIN ANALYZE SELECT * FROM orders WHERE user_id = 123;
// Look for: "Seq Scan" = full table scan (needs index)
//           "Index Scan" = good
//           "Index Only Scan" = best (covering index)
//           rows= estimate vs actual = statistics stale? Run ANALYZE

// N+1 problem — most common ORM performance bug
// BAD:
List<Order> orders = orderRepo.findByUserId(userId);  // 1 query
orders.forEach(order -> {
    order.setProductName(productRepo.findById(order.getProductId()).getName()); // N queries!
});

// GOOD: JOIN in single query
@Query("SELECT o FROM Order o JOIN FETCH o.product WHERE o.userId = :userId")
List<Order> findByUserIdWithProduct(@Param("userId") String userId); // 1 query with JOIN
```

---

## NoSQL Categories

### 1. Key-Value (Redis, DynamoDB)
```java
// Simplest model: key → value
// O(1) reads/writes, massive scale, no complex queries

// DynamoDB: serverless, single-digit ms, global tables
DynamoDbClient dynamo = DynamoDbClient.create();

// Get item
GetItemResponse response = dynamo.getItem(GetItemRequest.builder()
    .tableName("Users")
    .key(Map.of("userId", AttributeValue.fromS("user123")))
    .build());

// Put item with TTL
dynamo.putItem(PutItemRequest.builder()
    .tableName("Sessions")
    .item(Map.of(
        "sessionId", AttributeValue.fromS(sessionId),
        "userId", AttributeValue.fromS(userId),
        "ttl", AttributeValue.fromN(String.valueOf(expiryEpoch)) // DynamoDB TTL
    ))
    .build());

// Conditional write (optimistic locking)
dynamo.putItem(PutItemRequest.builder()
    .tableName("Inventory")
    .item(Map.of("sku", AttributeValue.fromS(sku),
                 "quantity", AttributeValue.fromN("95"),
                 "version", AttributeValue.fromN("3")))
    .conditionExpression("version = :v")
    .expressionAttributeValues(Map.of(":v", AttributeValue.fromN("2")))
    .build()); // fails if version changed by another writer
```

### 2. Document (MongoDB, Firestore)
```java
// JSON-like documents, flexible schema, rich queries within document
MongoClient mongo = MongoClients.create("mongodb://localhost:27017");
MongoDatabase db = mongo.getDatabase("ecommerce");
MongoCollection<Document> orders = db.getCollection("orders");

// Insert — schema is flexible
orders.insertOne(new Document()
    .append("userId", "user123")
    .append("status", "pending")
    .append("items", List.of(
        new Document("productId", "prod1").append("qty", 2).append("price", 29.99),
        new Document("productId", "prod2").append("qty", 1).append("price", 49.99)
    ))
    .append("total", 109.97)
    .append("createdAt", new Date())
);

// Query
List<Document> pendingOrders = orders.find(
    and(eq("userId", "user123"), eq("status", "pending"))
).sort(Sorts.descending("createdAt")).limit(10).into(new ArrayList<>());

// Aggregation pipeline
List<Document> revenueByDay = orders.aggregate(List.of(
    Aggregates.match(eq("status", "completed")),
    Aggregates.group("$dayOfYear($createdAt)",
        Accumulators.sum("revenue", "$total"),
        Accumulators.sum("count", 1)),
    Aggregates.sort(Sorts.ascending("_id"))
)).into(new ArrayList<>());

// Index in MongoDB
orders.createIndex(Indexes.ascending("userId", "status"));
orders.createIndex(Indexes.descending("createdAt"), new IndexOptions().expireAfter(90L, TimeUnit.DAYS)); // TTL index
```

### 3. Wide-Column (Cassandra, HBase)
```java
// Rows + dynamic columns + optimized for time-series / heavy writes
// Each row can have different columns — flexible but structured

// Cassandra CQL
String createTable = """
    CREATE TABLE sensor_readings (
        sensor_id uuid,
        timestamp timestamp,
        temperature float,
        humidity float,
        PRIMARY KEY (sensor_id, timestamp)  -- partition key + clustering key
    ) WITH CLUSTERING ORDER BY (timestamp DESC)  -- newest first
      AND default_time_to_live = 7776000;        -- 90 day TTL
    """;

// Java with Datastax driver
CqlSession session = CqlSession.builder().build();
PreparedStatement insert = session.prepare(
    "INSERT INTO sensor_readings (sensor_id, timestamp, temperature, humidity) " +
    "VALUES (?, ?, ?, ?) USING TTL 7776000"
);

session.execute(insert.bind(sensorId, Instant.now(), 23.5f, 65.2f));

// Read last 100 readings for sensor (all in same partition — fast)
ResultSet results = session.execute(
    "SELECT * FROM sensor_readings WHERE sensor_id = ? LIMIT 100",
    sensorId
);
```

### 4. Graph (Neo4j) — See `12-graph-databases.md`

---

## Choosing the Right Database

```
Start with this decision tree:

Q1: Do you need ACID transactions across multiple entities?
  YES → SQL (PostgreSQL)
  NO → continue

Q2: Is your data graph-like (relationships are first class)?
  YES → Graph DB (Neo4j)
  NO → continue

Q3: Is it time-series / append-only / sensor data?
  YES → Time-series DB (InfluxDB, Cassandra)
  NO → continue

Q4: Do you need flexible schema and nested documents?
  YES → Document DB (MongoDB)
  NO → continue

Q5: Simple key-value, extreme scale, low latency?
  YES → Key-Value (DynamoDB, Redis)
  NO → default to SQL
```

---

## SQL vs NoSQL Trade-offs

| | SQL (PostgreSQL) | Document (MongoDB) | Wide-Column (Cassandra) | Key-Value (DynamoDB) |
|---|---|---|---|---|
| Schema | Rigid, enforced | Flexible | Semi-structured | Schema-less |
| Transactions | Full ACID | Multi-doc (4.0+) | Lightweight | Conditional writes |
| Queries | Full SQL | Rich (no JOIN) | CQL (limited) | Key-only |
| Scale writes | Vertical + some horizontal | Horizontal | Excellent | Excellent |
| Consistency | Strong | Tunable | Tunable | Tunable |
| Best for | Financials, ERP | Catalogs, CMS | Time-series, logs | Sessions, cache |

---

## Database Normalization vs Denormalization

```java
// NORMALIZED (3NF) — SQL approach: no redundancy, use JOINs
// orders table:    order_id, user_id, status, total
// users table:     user_id, name, email
// order_items:     item_id, order_id, product_id, qty
// products table:  product_id, name, price

// DENORMALIZED — NoSQL approach: embed related data, avoid JOINs
// MongoDB order document:
{
    "_id": "order123",
    "userId": "user456",
    "userName": "Alice",        // denormalized from users
    "userEmail": "alice@...",   // denormalized
    "items": [
        {"productId": "p1", "productName": "Laptop", "price": 999, "qty": 1}
        // productName denormalized — no JOIN needed
    ],
    "total": 999
}
// Tradeoff: fast reads, but if user changes email you must update all their orders
```

---

## Indexes — Common Mistakes

```java
// 1. Too many indexes → slow writes (every write updates all indexes)
// Rule: index columns used in WHERE, ORDER BY, JOIN ON
// Don't blindly index every column

// 2. Low selectivity index — useless if few distinct values
// Bad: index on "is_active" (only true/false) — full scan often faster
// Good: index on "email" (millions of distinct values)

// 3. Not using covering indexes for hot queries
// Hot query: SELECT id, name, price FROM products WHERE category = 'electronics' ORDER BY price
// Without covering: index scan + table fetch for each row
// With covering: CREATE INDEX idx ON products(category, price) INCLUDE (id, name)
// → index-only scan, no table access

// 4. Implicit type conversion kills indexes
// Bad: WHERE user_id = '123'  (user_id is INT, comparing to STRING)
// Index skipped! Always match types

// 5. Functions on indexed columns
// Bad:  WHERE YEAR(created_at) = 2024  -- index on created_at NOT used
// Good: WHERE created_at BETWEEN '2024-01-01' AND '2024-12-31'
```

---

## When to Use What

| Use Case | Database |
|----------|----------|
| Bank accounts, payments | PostgreSQL (ACID) |
| Product catalog (varying attributes) | MongoDB |
| User sessions | Redis |
| IoT sensor data | Cassandra or InfluxDB |
| Social graph | Neo4j |
| Full-text search | Elasticsearch |
| Global low-latency K-V | DynamoDB |
| Analytics / OLAP | Snowflake, BigQuery, ClickHouse |

---

---

## DynamoDB Indexes — Deep Dive

### DynamoDB data model first

Every item needs a partition key (PK). Optionally a sort key (SK).

```
Table: Orders
  PK: userId       ← hashed → determines which partition stores the item
  SK: orderId      ← sorted  → items within a partition are ordered by SK

Physical storage:
  Partition (hash of PK="USER#123"):
    SK="ORDER#001" → { status: "pending",  amount: 25.00, createdAt: "2024-01-01" }
    SK="ORDER#002" → { status: "shipped",  amount: 99.00, createdAt: "2024-01-03" }
    SK="ORDER#003" → { status: "pending",  amount: 10.00, createdAt: "2024-01-05" }
    ← sorted by SK within the partition

  Partition (hash of PK="USER#456"):
    SK="ORDER#010" → { status: "completed", amount: 50.00, createdAt: "2024-01-02" }
    ...
```

You can only query by PK (required, exact match) + optionally filter/range on SK.
Any other access pattern → you need an index.

---

### LSI — Local Secondary Index

**Same partition key, different sort key. Sorted differently within each partition.**

```
Base table:         PK=userId, SK=orderId
LSI (ByAmount):     PK=userId, SK=amount    ← same PK, different SK
LSI (ByCreatedAt):  PK=userId, SK=createdAt ← same PK, different SK
```

Physical storage — within the same partition, an additional sorted list:

```
Partition (PK="USER#123"):

  Base table sorted by orderId:            LSI sorted by amount:
    ORDER#001 → {amount:25, status:pending}   amount:10.00 → ORDER#003
    ORDER#002 → {amount:99, status:shipped}   amount:25.00 → ORDER#001
    ORDER#003 → {amount:10, status:pending}   amount:99.00 → ORDER#002
                                              ↑ same partition, different sort order
```

Query example:
```java
// "Get all orders for user123, sorted by amount ascending"
// Base table can't do this — orderId is the SK, not amount
// LSI can:
QueryRequest.builder()
    .tableName("Orders")
    .indexName("ByAmount")
    .keyConditionExpression("userId = :uid")
    .expressionAttributeValues(Map.of(":uid", AttributeValue.fromS("USER#123")))
    .build();
// Returns: ORDER#003 (10), ORDER#001 (25), ORDER#002 (99) ← sorted by amount
```

LSI rules:
```
✓ Same partition key as base table (required — "local" to the partition)
✓ Different sort key
✓ Strongly consistent reads (data is in the same partition, same nodes)
✓ No extra WCU cost for writes (same partition write)
✗ Must define at TABLE CREATION TIME — cannot add later
✗ Shares partition storage with base table (10GB per partition limit applies)
✗ Max 5 LSIs per table
✗ Only useful if you always filter by the same partition key (userId must be known)
```

---

### GSI — Global Secondary Index

**Different partition key AND/OR different sort key. Completely separate storage. Spans all partitions.**

```
Base table:       PK=userId,  SK=orderId
GSI (ByStatus):   PK=status,  SK=createdAt  ← totally different keys
```

Physical storage — AWS maintains a separate index table, async-replicated:

```
Base table partitions:            GSI partitions (separate storage):
  PK=USER#123:                      PK=pending (hash of "pending"):
    ORDER#001: pending, 2024-01-01    2024-01-01 → {userId:USER#123, orderId:ORDER#001, amount:25}
    ORDER#002: shipped, 2024-01-03    2024-01-05 → {userId:USER#123, orderId:ORDER#003, amount:10}
    ORDER#003: pending, 2024-01-05    2024-01-07 → {userId:USER#789, orderId:ORDER#020, amount:75}
  PK=USER#456:
    ORDER#010: completed, 2024-01-02  PK=shipped (hash of "shipped"):
                                        2024-01-03 → {userId:USER#123, orderId:ORDER#002, amount:99}
                                        ...
                                      ← async replicated from base table writes
```

Query example:
```java
// "Get all pending orders across ALL users, sorted by date"
// Base table can't — you'd need to scan all partitions
// GSI can:
QueryRequest.builder()
    .tableName("Orders")
    .indexName("ByStatus")
    .keyConditionExpression("#s = :status AND createdAt > :since")
    .expressionAttributeNames(Map.of("#s", "status"))
    .expressionAttributeValues(Map.of(
        ":status", AttributeValue.fromS("pending"),
        ":since",  AttributeValue.fromS("2024-01-01")
    ))
    .build();
```

GSI rules:
```
✓ Any attribute as PK (even one that doesn't exist on all items — sparse GSI)
✓ Can add AFTER table creation
✓ No partition storage limit (separate storage from base table)
✓ Max 20 GSIs per table
✗ Eventually consistent only (async replication — slight lag after writes)
✗ Extra WCU cost: write to base table + write to each GSI
✗ GSI PK must be exact match — range only on GSI SK
✗ NOT strongly consistent (unlike LSI)
```

Write cost with GSI:
```
1 write to Orders table                = 1 WCU
+ 1 write to ByStatus GSI             = 1 WCU
+ 1 write to ByCreatedAt GSI          = 1 WCU
─────────────────────────────────────────────
Total for 1 item write with 2 GSIs    = 3 WCU
```

---

### Sparse GSI — powerful pattern

GSI only indexes items that have the GSI partition key attribute.
Items missing the attribute are simply absent from the GSI.

```java
// Only urgent orders have the "urgent" attribute set
// Most orders don't have it — they won't appear in the GSI

// Item 1 (urgent):
{  userId: "USER#123", orderId: "ORDER#001", urgent: "true", amount: 500 }
// ↑ appears in ByUrgent GSI

// Item 2 (normal):
{  userId: "USER#456", orderId: "ORDER#010", amount: 25 }
// ↑ NOT in ByUrgent GSI — no "urgent" attribute

// Result: GSI only contains the small subset of urgent orders
// Very cheap to scan — only urgent items indexed
QueryRequest.builder()
    .tableName("Orders")
    .indexName("ByUrgent")
    .keyConditionExpression("urgent = :u")
    .expressionAttributeValues(Map.of(":u", AttributeValue.fromS("true")))
    .build();
```

---

### LSI vs GSI — side by side

| | LSI | GSI |
|---|---|---|
| Partition key | Same as base table | Any attribute |
| Sort key | Different from base table | Any attribute |
| When to create | Table creation only | Anytime |
| Consistency | Strongly consistent ✓ | Eventually consistent only |
| Write cost | Free (same partition write) | Extra WCU per GSI |
| Storage | Shared with base partition (10GB limit) | Separate (no limit) |
| Use when | Same user/entity, query sorted differently | Cross-entity queries, different access pattern |
| Max per table | 5 | 20 |

---

### How DynamoDB indexes compare to SQL B-tree indexes

```
SQL B-tree index:

  Heap file (table rows, unordered pages):
    Page 1: [row(id=5)][row(id=2)][row(id=8)]  ← inserted order
    Page 2: [row(id=1)][row(id=9)]

  B-tree index on status:
    Root
    ├── "completed" → Leaf: [page1:slot3, page2:slot1, ...]  ← pointers to heap
    ├── "pending"   → Leaf: [page1:slot1, page2:slot2, ...]
    └── "shipped"   → Leaf: [page1:slot2, ...]

  On write:  update heap page + walk B-tree + insert pointer into leaf
  On read:   walk B-tree O(log n) → fetch heap page via pointer
  Consistency: always synchronous — index always matches table
```

```
DynamoDB GSI:

  Base table partitions (distributed across nodes):
    Node A: PK=USER#123 items (sorted by SK=orderId)
    Node B: PK=USER#456 items
    Node C: PK=USER#789 items

  GSI (ByStatus) — separate partition set on separate nodes:
    Node X: GSI_PK=pending  items (sorted by GSI_SK=createdAt)
    Node Y: GSI_PK=shipped  items
    Node Z: GSI_PK=completed items

  On write to base table:
    1. Write to base table partition (Node A) — synchronous, client acked
    2. Async replication stream → GSI partition (Node X) — eventual
       ← client already got success before GSI updated

  On GSI read: goes to GSI partition directly (Node X), may be slightly stale
```

Key differences:

| | SQL B-tree | DynamoDB LSI | DynamoDB GSI |
|---|---|---|---|
| Consistency | Always synchronous | Synchronous (same partition) | Async — eventually consistent |
| Read after write | Always sees latest | Always sees latest | May see stale data |
| Storage model | Pointer to heap row | Within partition storage | Completely separate storage |
| Query flexibility | Range on any indexed column | Range on SK within known PK | Range on SK within known GSI PK |
| Cross-partition query | Full table scan | No (PK must be known) | Yes — GSI PK can be any attribute |
| Write overhead | Update index tree synchronously | None extra | Extra WCU per GSI |
| Add after creation | Yes (with lock / concurrent build) | No | Yes |

The fundamental gap: **SQL indexes are fully consistent and transparent. DynamoDB GSI is eventually consistent and costs extra WCUs.** LSI is the only DynamoDB index that matches SQL consistency guarantees — but only within a single partition.

---

### Single-table design — GSI as the access pattern engine

DynamoDB best practice: one table, multiple entity types, multiple GSIs for each access pattern.

```
Table: AppData
  PK: pk    (overloaded — holds entity type + ID)
  SK: sk    (overloaded — holds relationship + ID)

Items:
  PK=USER#123,    SK=PROFILE#123   → user profile
  PK=USER#123,    SK=ORDER#001     → order belonging to user
  PK=ORDER#001,   SK=ITEM#A        → line item in order
  PK=PRODUCT#999, SK=METADATA      → product

GSI1: ByType
  GSI_PK=entityType (USER / ORDER / PRODUCT)
  GSI_SK=createdAt
  → "get all orders created this week"

GSI2: ByStatus
  GSI_PK=status (pending / shipped / completed)
  GSI_SK=createdAt
  → "get all pending orders" (sparse — only items with status attribute)
```

Design rule: **figure out all access patterns BEFORE designing the table**.
In SQL you add an index later. In DynamoDB, LSI is impossible to add later, and a missing GSI means a full Scan (expensive).

---

## Interview Questions

1. **How do indexes work internally?** B-tree: balanced tree, leaf nodes point to table rows. O(log N) lookup. Writes are slower (must update tree). Hash: O(1) equality, no range queries.
2. **What is index selectivity?** % of rows matching a value. High selectivity (email, UUID) = index is efficient. Low selectivity (boolean, status) = full scan may be faster.
3. **When would you choose MongoDB over PostgreSQL?** Flexible/evolving schema, document-centric data (no complex JOINs), horizontal write scaling needed.
4. **What is a covering index?** All columns needed by a query are IN the index — avoids accessing the table entirely. Huge speedup for hot read queries.
5. **How does DynamoDB handle hot partitions?** Adaptive capacity automatically moves throughput to busy partitions. But fundamentally: choose a high-cardinality partition key to avoid hot partitions.
6. **LSI vs GSI?** LSI: same PK, different SK, strongly consistent, must define at creation, shares partition storage. GSI: any keys, eventually consistent, can add anytime, separate storage + extra WCU cost.
7. **Why can't you add an LSI after table creation?** LSI data lives inside the partition storage alongside base table data. Retrofitting would require rewriting all existing partition data — DynamoDB doesn't support online LSI creation for this reason.
8. **When does a GSI read return stale data?** When a write just happened to the base table and the async replication to the GSI hasn't completed yet (typically milliseconds). Use base table GetItem with strong consistency if you need latest data immediately after a write.
9. **What is a sparse GSI?** A GSI where not all items have the GSI partition key attribute. Only items with that attribute are indexed — makes the GSI much smaller and cheaper to query. Useful for filtering a small subset (e.g. only "urgent" items).
