# DynamoDB Key Design Best Practices

> Summarized from AWS official documentation:
> - [Partition Key Design](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-partition-key-design.html)
> - [Sort Key Best Practices](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-sort-keys.html)
> - [Uniform Load Distribution](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-partition-key-uniform-load.html)
> - [Write Sharding](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-partition-key-sharding.html)
> - [Secondary Index Guidelines](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-indexes-general.html)
> - [GSI Overloading](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-gsi-overloading.html)
> - [Adjacency Lists](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-adjacency-graphs.html)
> - [Relational Modeling in DynamoDB](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-modeling-nosql-B.html)

---

## 1. Partition Key Fundamentals

### Per-Partition Limits

Every physical partition can deliver at most:

| Operation | Limit |
|---|---|
| Read | 3,000 RCU/sec |
| Write | 1,000 WCU/sec |

**Unit sizes:**
- 1 RCU = 1 strongly consistent read/sec OR 2 eventually consistent reads/sec for items ≤ 4 KB
- 1 WCU = 1 write/sec for items ≤ 1 KB

**Item size matters:** A 20 KB item consumes 5 RCUs per read. That limits you to just **600 consistent reads/sec** on a single hot partition (3,000 / 5 = 600), not 3,000.

### Good vs Bad Partition Keys

| Partition Key | Uniformity | Reason |
|---|---|---|
| User ID (millions of users) | **Good** | High cardinality, even distribution |
| Status code (few values) | **Bad** | Low cardinality, hot partitions |
| Creation date rounded to day | **Bad** | All writes go to today's partition |
| Device ID (similar usage patterns) | **Good** | Many devices, even access |
| Device ID (one device dominates) | **Bad** | That one device is a hot partition |

**Key principle:** The higher the ratio of `accessed PK values / total PK values`, the more evenly spread your workload.

---

## 2. Sort Key Best Practices

### Hierarchical Sort Keys

Use `#` as a delimiter to encode hierarchy. This enables range queries at any level.

```
[country]#[region]#[state]#[county]#[city]#[neighborhood]
```

**Example queries:**
- All items in US: `begins_with("US")`
- All items in US-West: `begins_with("US#West")`
- All items in California: `begins_with("US#West#CA")`
- All items in San Francisco: `begins_with("US#West#CA#SanFrancisco")`

### Version Control Pattern

Keep the latest version always accessible while also maintaining full history.

**Pattern:**
- `v0_<identifier>` → always the **latest** version (overwritten on every update)
- `v1_<identifier>` → first version
- `v2_<identifier>` → second version
- ...

**On update:**
1. Write new item with next version prefix (`v2_`, `v3_`, ...)
2. Overwrite `v0_` item with the updated content

**Query patterns:**
- Latest version: `begins_with("v0_")`
- Full history: query all, filter out `v0_` items

---

## 3. Write Sharding

When a partition key has low cardinality (e.g., date, status code), use sharding to spread writes.

### Strategy 1: Random Suffix

Append a random number (1–200) to the partition key:

```
2024-07-09.1, 2024-07-09.2, ..., 2024-07-09.200
```

- **Pro:** Writes spread evenly across 200 partitions
- **Con:** To read all items for a day, must query all 200 shards and merge in application

### Strategy 2: Calculated Suffix (Deterministic)

Compute a hash from a queryable attribute:

```
suffix = (sum of UTF-8 code points of OrderId) % 200 + 1
pk = "2024-07-09." + suffix
```

- **Pro:** Writes spread evenly; `GetItem` is possible (recalculate suffix from `OrderId`)
- **Con:** To scan all items for a date, still requires 200 queries + merge

### Sharding for OPEN Orders (E-Commerce Example)

For 5,000 WPS on OPEN orders (exceeds 1,000 WCU/partition limit):

```
status + shard → PK for sparse GSI
  OPEN#0, OPEN#1, OPEN#2, OPEN#3, OPEN#4
```

- Each shard handles ≤ 1,000 WCU/sec
- Sparse GSI only indexes OPEN items (~20% of total) → lower storage cost
- Query: parallel queries across all 5 shards + client-side merge

---

## 4. Secondary Index Guidelines

### GSI vs LSI Recap

| Feature | GSI | LSI |
|---|---|---|
| Partition key | Different from base | Same as base |
| Sort key | Different from base | Different from base |
| Scope | Table-wide ("global") | Per-partition ("local") |
| Size limit | None | **10 GB per partition key** |
| Throughput | Separate RCU/WCU settings | Shares table throughput |
| Can be added later | Yes | **No — creation-time only** |
| Consistency | Eventually consistent only | Strongly consistent available |
| Max per table | **20** | **5** |

### Index Projection Cost Rules

| Projection Type | Storage Cost | Write Cost | When to Use |
|---|---|---|---|
| `KEYS_ONLY` | Lowest | Lowest | When you'll rarely read index attributes |
| `INCLUDE` | Medium | Medium | Project only the attributes queries need |
| `ALL` | Doubles base table storage | Doubles writes | Only when queries need full item |

**Rule of thumb:** If projected attributes stay under 1 KB, projecting more attributes is free (all writes round up to 1 WCU anyway).

### LSI 10 GB Item Collection Limit

An **item collection** = all items in a table + LSIs sharing the same partition key value.

- Hard limit: **10 GB per partition key value**
- Exceeding → write failures
- Monitor: use `ReturnItemCollectionMetrics` on writes and alert before hitting 10 GB

---

## 5. GSI Overloading

Use a single GSI to serve multiple query patterns by putting different types of data in the same generic attribute (e.g., `Data`).

### Pattern

```
Table: employees/warehouses/orders (mixed types)
GSI-PK: (table sort key)
GSI-SK: Data attribute
```

**Different item types store different things in `Data`:**

| Item Type | PK | Data attribute |
|---|---|---|
| Employee | `EMPLOYEE` | Employee name |
| Warehouse assignment | `WAREHOUSE_01` | Employee ID |
| HR record | `HR_confidential` | Hire date |

**One GSI enables:**
1. Look up employee by name: `GSI-PK="Employee_Name"`, `GSI-SK="Murphy, John"`
2. All employees in warehouse: `GSI-PK="Warehouse_01"`
3. Recent hires: `GSI-PK="HR_confidential"`, `GSI-SK between "2025-01-01" and "2025-12-31"`

This pattern effectively gives you **many logical indexes** from a single physical GSI.

---

## 6. Adjacency Lists (Many-to-Many)

Model graph relationships where every entity is a partition key and relationships are sort keys.

### Structure

```
PK          SK          Attributes
InvoiceID   InvoiceID   { invoice attributes }   ← invoice header
InvoiceID   BillID_1    { rollup attributes }     ← relationship
InvoiceID   BillID_2    { rollup attributes }     ← relationship
BillID      BillID      { bill attributes }       ← bill header
```

### Query Patterns

- **All bills for an invoice:** `Query(PK="INV_001")` — returns invoice + all bill relationships
- **All invoices containing a bill:** GSI on sort key → `Query(GSI-PK="BILL_001")`

### When to Use Amazon Neptune Instead

Use Neptune when you need:
- Real-time multi-hop queries (second/third-level relationships)
- Complex edge traversals with millisecond latency
- Querying billions of relationships

---

## 7. Full E-Commerce Data Model

A complete example from AWS showing how to model relational data in DynamoDB using **multiple specialized tables**.

### Design Philosophy

1. **Aggregate-oriented:** Group data by access pattern, not entity type
2. **Low correlation → separate tables:** Employee, Customer, Order, Product are separate
3. **Embed when always accessed together:** Order line items in same partition as order header
4. **Denormalize strategically:** Copy `account_rep_id` into Orders to avoid joins
5. **Item Collection Pattern:** Use for one-to-many (e.g., product + inventory per warehouse)

---

### Table 1: Employee Table

```
PK: employee_id
No sort key (one item per employee)
```

#### GSIs

| GSI | PK | SK | Projection | Purpose |
|---|---|---|---|---|
| EmployeeByName | `name` | `employee_id` | ALL employee attrs | Look up by name |
| EmployeeByWarehouse | `warehouse_id` | `employee_id` | name, job_title, hire_date | Find employees in warehouse |
| EmployeeByJobTitle | `job_title` | `employee_id` | ALL | Role-based queries |
| EmployeeByHireDate | `entity_type` (static: `"EMPLOYEE"`) | `hire_date` | employee_id, name, warehouse_id | Date range queries for recent hires |

**Key insight on EmployeeByHireDate:** Using a static partition key (`"EMPLOYEE"`) puts all records in one GSI partition. This is only safe when write throughput is low (<1,000 WCU/sec). For most employee tables this is fine — but would be a hot partition at scale.

---

### Table 2: Customer Table

```
PK: customer_id
No sort key
phone_numbers stored as a List attribute (multiple phones per customer)
account_rep_id denormalized here (avoids join with account rep table)
```

#### GSIs

| GSI | PK | SK | Projection | Purpose |
|---|---|---|---|---|
| CustomerByAccountRep | `account_rep_id` | `customer_id` | name, email | Account rep → customers |

---

### Table 3: Order Table (Vertical Partitioning)

Two item types share the same partition key (`order_id`):

```
Order Header:  PK=order_id, SK=order_id    → order metadata
Order Item:    PK=order_id, SK=product_id  → line item details
```

**Benefits of vertical partitioning:**
- Single `Query(PK=order_id)` returns header + all line items
- `ProductInOrders` GSI can find all orders for a product
- `account_rep_id` denormalized from customer → enables rep-based order queries without joins

#### GSIs

| GSI | PK | SK | Projection | Purpose |
|---|---|---|---|---|
| OrderByCustomerDate | `customer_id` | `order_date` | order summary + items | Customer order history |
| OpenOrdersByDate | `status + shard` (0–4) | `order_date` | order summary | Active orders by date (sharded, sparse) |
| OrderByAccountRep | `account_rep_id` | `order_date` | order summary | Rep workflow |
| ProductInOrders | `product_id` | `order_id` | customer_id, order_date, qty | Product demand analysis |

**OpenOrdersByDate — sparse + sharded GSI:**
- `shard` attribute (0–4) combined with `status` as composite PK
- Only OPEN orders have the `shard` attribute → **sparse index** (CLOSED orders invisible)
- 5 shards × 1,000 WCU = **5,000 WPS** throughput for high-volume open orders
- Query cost: parallel queries to all 5 shards + client-side merge

---

### Table 4: Product Table (Item Collection Pattern)

```
PK: product_id
SK: product_id OR warehouse_id

product_id#product_id → product metadata (name, price, total_inventory)
product_id#warehouse_id → inventory quantity at that warehouse
```

**Benefits:**
- No separate Inventory table needed → ~**50% cost reduction**
- Single `Query(PK=product_id)` returns product metadata + all warehouse inventory
- `total_inventory` is a denormalized aggregation on the metadata item for fast total lookups
- `GetItem(PK=product_id, SK=warehouse_id)` for per-warehouse inventory

---

### Access Pattern → Query Mapping

| Access Pattern | Operation |
|---|---|
| Get employee by ID | `GetItem` on Employee table |
| Get employee by name | `Query` on EmployeeByName GSI |
| Get customer's phone numbers | `GetItem` on Customer table |
| Get orders for customer in date range | `Query` on OrderByCustomerDate GSI with `order_date BETWEEN` |
| Get all OPEN orders in date range | Parallel `Query` on OpenOrdersByDate GSI (5 shards) + merge |
| Get recently hired employees | `Query` on EmployeeByHireDate GSI with `hire_date >= "2025-01-01"` |
| Get all employees in warehouse | `Query` on EmployeeByWarehouse GSI |
| Get all orders containing product | `Query` on ProductInOrders GSI |
| Get inventory for product at all warehouses | `Query` on Product table (returns all SK items) |
| Get inventory at specific warehouse | `GetItem(PK=product_id, SK=warehouse_id)` |
| Get total inventory for product | `GetItem(PK=product_id, SK=product_id)` reads `total_inventory` |
| Get customers by account rep | `Query` on CustomerByAccountRep GSI |
| Get orders by account rep | `Query` on OrderByAccountRep GSI |
| Get employees by job title | `Query` on EmployeeByJobTitle GSI |

---

## 8. Key Design Patterns Cheat Sheet

### Pattern 1: Hierarchical Sort Key
```
SK = "US#West#CA#SF#Mission"
Query: begins_with("US#West#CA")  → all CA items
```

### Pattern 2: Version Control
```
SK = "v0_order_123"  → always latest
SK = "v1_order_123"  → first version
SK = "v2_order_123"  → second version
```

### Pattern 3: Write Sharding
```
# For low-cardinality PK (e.g., status codes)
PK = "OPEN#" + (hash(orderId) % 5)  → 5 shards
Sparse: only set shard attribute on OPEN items
```

### Pattern 4: GSI Overloading
```
# All item types use a generic "Data" attribute
# Single GSI on Data serves multiple query patterns
Employee.Data = employee name
WarehouseAssignment.Data = employee ID
HRRecord.Data = hire date (ISO-8601 for range queries)
```

### Pattern 5: Item Collection (One-to-Many)
```
# No separate child table needed
PK = "product_001"
SK = "product_001"   → metadata item
SK = "warehouse_sea" → inventory at SEA
SK = "warehouse_pdx" → inventory at PDX
Query(PK) returns everything in one request
```

### Pattern 6: Vertical Partitioning
```
# Two item types colocated in same partition
PK = "order_001", SK = "order_001"   → header
PK = "order_001", SK = "prod_100"    → line item
PK = "order_001", SK = "prod_200"    → line item
Query(PK) returns order + all line items
```

### Pattern 7: Adjacency List
```
# Graph edges stored as sort keys
PK = "Invoice_001", SK = "Invoice_001" → invoice node
PK = "Invoice_001", SK = "Bill_001"    → edge
PK = "Invoice_001", SK = "Bill_002"    → edge
PK = "Bill_001",    SK = "Bill_001"    → bill node
```

### Pattern 8: Sparse GSI
```
# Attribute only set on some items
# GSI only indexes items where attribute exists
order.shard = 0-4   (only on OPEN orders)
GSI on shard → only indexes OPEN orders
CLOSED orders have no shard attr → invisible in GSI
```

---

## 9. Anti-Patterns to Avoid

| Anti-Pattern | Problem | Fix |
|---|---|---|
| Date as partition key | All today's writes to one partition | Write sharding with suffix |
| Status code as partition key | Few values, hot partitions | Composite key + sharding |
| Auto-increment PK | Sequential writes → hot partition | Use UUID/ULID |
| One big table, no access pattern analysis | Expensive scans | Design for access patterns first |
| Projecting ALL on every GSI | Doubles storage + write cost | Project only what queries need |
| LSI on high-volume partition | 10 GB limit → write failures | Use GSI instead; monitor item collection size |
| Same static PK in GSI at high write volume | 1,000 WCU/partition limit | Shard the GSI PK |
| Separate Inventory table | Extra GSI + join pattern | Item Collection Pattern |

---

## 10. Numbers to Remember

| Limit | Value |
|---|---|
| Max WCU per partition | 1,000 |
| Max RCU per partition | 3,000 |
| Max GSIs per table | 20 |
| Max LSIs per table | 5 |
| Max item size | 400 KB |
| LSI item collection limit | **10 GB** per partition key |
| 1 WCU = | 1 write/sec ≤ 1 KB |
| 1 RCU = | 1 strongly consistent read/sec ≤ 4 KB |
| Recommended shard count for 5,000 WPS | 5 shards (5 × 1,000) |
