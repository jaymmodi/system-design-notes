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

## Interview Questions

1. **How do indexes work internally?** B-tree: balanced tree, leaf nodes point to table rows. O(log N) lookup. Writes are slower (must update tree). Hash: O(1) equality, no range queries.
2. **What is index selectivity?** % of rows matching a value. High selectivity (email, UUID) = index is efficient. Low selectivity (boolean, status) = full scan may be faster.
3. **When would you choose MongoDB over PostgreSQL?** Flexible/evolving schema, document-centric data (no complex JOINs), horizontal write scaling needed.
4. **What is a covering index?** All columns needed by a query are IN the index — avoids accessing the table entirely. Huge speedup for hot read queries.
5. **How does DynamoDB handle hot partitions?** Adaptive capacity automatically moves throughput to busy partitions. But fundamentally: choose a high-cardinality partition key to avoid hot partitions.
