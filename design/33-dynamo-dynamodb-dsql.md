# Dynamo vs DynamoDB vs Aurora DSQL

> Summarized from:
> - https://brooker.co.za/blog/2025/08/15/dynamo-dynamodb-dsql.html
> - https://brooker.co.za/blog/2024/12/04/inside-dsql.html
> - https://brooker.co.za/blog/2025/11/02/thinking-dsql.html
>
> Author: Marc Brooker — AWS engineer who worked on these systems.

---

## The Three Systems — Evolution

```
2007: Dynamo (internal Amazon KV store — never public)
         │
         └─► inspired DynamoDB (2012, public)
                    │
                    └─► led to Aurora DSQL (2024, newest)
```

Each is a fundamentally different architecture, not just an upgrade.

---

## 1. Durability — How Data Survives Failures

### Dynamo (2007)
Consistent hashing ring. Each key replicated to N clockwise neighbor nodes.
```
Key → hash → Node A → also stored on Node B, Node C (N=3)
```
- Data appears **N times** in the system
- Problem: during scaling (adding nodes), durability temporarily drops — keys mid-migration are under-replicated

### DynamoDB
One node per key in hash ring, but that "node" is a **Paxos replica group** (multiple servers across AZs).
```
Key → hash → Replica Group (Leader + 2 replicas across 3 AZs)
              └─► Leader writes WAL → sends to replicas → Paxos quorum
```
- Data appears **once logically** but fault-tolerant via Paxos
- Scaling done by splitting/merging replica groups — no durability gap during scaling
- Much easier to detect and fix under-replicated keys

### DSQL
Paxos-variant with a **separate Journal component** independent from storage nodes.
```
Write → Journal (independent, Paxos-replicated log)
              │
              └─► Storage nodes apply from Journal
```
- Journal and storage scale **independently**
- Range-based sharding (not hash-based) → enables cross-shard atomic commits

---

## 2. Consistency — The Big Evolution

### Dynamo
**Eventual consistency only.**
- Configurable N, R, W: if R + W > N → quorum-like system
- Vector clocks to track conflicting versions
- Client must resolve conflicts ("last writer wins" or custom merge)
- Updates propagate to all replicas asynchronously

### DynamoDB
**Strong writes, choice on reads.**

Strong write = three guarantees per Marc:
1. Writes apply in **per-key total order** (no post-commit merging)
2. Writes applied **atomically** with precondition evaluation (`ConditionExpression`)
3. **Fully durable** — synchronous replication before ack

```
Write → Leader only (always strongly consistent)
Read  → Leader (strongly consistent) OR any replica (eventually consistent)
```

No vector clocks needed — because writes are always strong, there's never a conflict to resolve.

### DSQL
**Strong consistency everywhere — reads, writes, multi-region.**

Uses **MVCC + physical time (EC2 precision time)**:
```
Transaction starts → acquires τ_start (timestamp from EC2 precision time)
All reads happen "as of" τ_start → point-in-time snapshot
Commit → optimistic check: did anything I read change? → abort if yes
```

Key insight: **reads go to nearest replica without coordination** — no leader needed for reads because MVCC gives a consistent snapshot as of τ_start. Any replica can serve it.

---

## 3. Architecture — How They're Built Inside

### Dynamo
```
Client → consistent hash → Node (peer-to-peer replication)
```
Simple, peer-to-peer. Each node is equal. No separation of concerns.

### DynamoDB
```
Client → hash → Replica Group Leader
                    │
                    ├─► WAL → replicas (Paxos)
                    └─► Storage (coupled with compute)
```
Paxos groups. Hash-based. Compute and storage somewhat coupled.

### DSQL — Fully Disaggregated
Every component is a **separate, independently scalable service**:

```
Client
  │
  ▼
Query Processor (QP) — customized PostgreSQL in Firecracker MicroVM
  │   - PostgreSQL SQL engine, planner, optimizer, wire protocol
  │   - NOT PostgreSQL storage layer
  │   - One MicroVM per transaction, placed near client
  │   - QPs never talk to each other
  │
  ▼
Journal (Paxos-replicated write-ahead log)
  │   - Independent of storage
  │   - Write throughput scales independently
  │
  ▼
Storage Nodes (MVCC, multi-version row storage)
      - Read throughput scales independently
      - Pushdown: filtering, aggregation, index scans run ON storage
      - Reads "as of" τ_start — no QP cache needed
```

**Why Firecracker MicroVMs for QP?**
Borrowed from Lambda — lightweight, fast startup, multi-tenant isolation. Each transaction gets its own PostgreSQL process without full VM overhead.

**Why pushdown to storage?**
"Latency lags bandwidth" — network bandwidth improves faster than latency. Sending less data across the network (filter on storage side) beats sending raw pages to QP. Index scans, filtering, aggregation happen at the storage node.

---

## 4. Programming Model

| | Dynamo | DynamoDB | DSQL |
|--|--------|----------|------|
| Data model | Key-value | Key-value + document | Full relational SQL |
| Transactions | Single key only | Single-shot ACID (all reads+writes in one call) | Interactive ACID (BEGIN → queries → COMMIT) |
| Isolation | None | Serializable (per transaction) | Strong snapshot isolation globally |
| Joins | No | No | Yes |
| SQL | No | No | PostgreSQL compatible |

**DynamoDB transactions = single-shot:** declare all reads and writes upfront in one `TransactWriteItems` call. No back-and-forth.

**DSQL transactions = interactive:** `BEGIN → SELECT → compute in app → UPDATE → COMMIT`. Normal SQL flow. Works across regions.

---

## 5. Marc's Core Argument — Dynamo's Assumptions Are Outdated

The Dynamo paper (2007) claimed:
> "Data stores that provide ACID guarantees tend to have poor availability"
> "Strong consistency and high availability cannot coexist during network failures"

Marc argues these **have not stood the test of time**:

| Old assumption | Modern reality |
|----------------|---------------|
| Strong consistency = poor availability | DynamoDB and DSQL are highly available AND strongly consistent |
| Network partitions make strong consistency impractical | Modern data center networks are far more reliable than 2007 |
| HDDs made synchronous replication expensive | SSDs + fast networks make Paxos practical and fast |
| Consistency vs latency trade-offs are unavoidable | DSQL achieves lower latency than Dynamo with stronger consistency |

> "Time has marched on, we've learned a ton, we've got better hardware and better ideas."

---

## 6. DSQL Intentional Limitations

DSQL deliberately trades flexibility for simplicity:

| Limitation | Why deliberate |
|------------|---------------|
| Max 10 MiB or 3,000 rows per transaction | Prevents head-of-line blocking — large txns delay tail latency for everyone |
| Max 5 min transaction duration | Avoids PostgreSQL VACUUM overhead for MVCC cleanup |
| No config knobs (replica count, sharding, consistency level) | Self-tunes — complexity is a choice |
| No foreign key enforcement | Performance — customers avoid them anyway |
| No stored procedures | Modern teams prefer app-layer logic in CI/CD |
| Single isolation level (strong snapshot) | Simplifies app logic — no eventual consistency routing needed |

---

## 7. Multi-Region — The Key Differentiator

```
Dynamo:   eventual consistency, no multi-region built-in
DynamoDB: last-writer-wins multi-region (MREC) OR 3-region strong (MRSC)
DSQL:     active-active, any region, globally serializable, no leader
```

DSQL has **no leader** — writes go to nearest AZ/region. Conflicts detected at COMMIT via optimistic concurrency (OCC at global database scale):

```
User India  → write → ap-south-1 QP → Journal → commit check
User US     → write → us-east-1 QP → Journal → commit check
                                                      │
                                               conflict? → one aborts + retry
                                               no conflict? → both commit ✅
```

---

## Full Comparison

| | Dynamo (2007) | DynamoDB | Aurora DSQL |
|--|---------------|----------|-------------|
| Sharding | Hash ring | Hash ring (Paxos groups) | Range-based |
| Replication | Peer-to-peer N copies | Paxos replica groups | Paxos Journal + storage |
| Consistency | Eventual only | Strong writes, choice on reads | Strong everywhere |
| Multi-region writes | No | Last-writer-wins / 3-region strong | Active-active, globally serializable |
| Transactions | Single key | Single-shot ACID | Interactive ACID |
| SQL | No | No | PostgreSQL compatible |
| Compute | N/A | Coupled with storage | Firecracker MicroVM per txn |
| Read scaling | N replicas | Read replicas | Any replica (MVCC, no coordination) |
| Serverless | N/A | On-demand mode | Always serverless |
| Operational burden | High (internal) | None | None |

---

## One-Line Summary

- **Dynamo:** Simple, available, eventual — the foundation, but the trade-offs are no longer necessary
- **DynamoDB:** Strong writes, scalable, serverless NoSQL — the right choice for key-value at any scale
- **DSQL:** Everything DynamoDB is, plus SQL, interactive transactions, and globally serializable consistency — the future for relational workloads at global scale
