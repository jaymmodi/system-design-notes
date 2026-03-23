# System Design Notes - Complete Index

A quick reference guide to all topics covered in this repository.

## 📑 Design Fundamentals

### Core Concepts
| # | Topic | File |
|---|-------|------|
| 01 | Scalability Fundamentals | `design/01-scalability-fundamentals.md` |
| 02 | CAP Theorem | `design/02-cap-theorem.md` |
| 03 | Consistent Hashing | `design/03-consistent-hashing.md` |

### Performance & Optimization
| # | Topic | File |
|---|-------|------|
| 04 | Caching | `design/04-caching.md` |
| 05 | Load Balancing | `design/05-load-balancing.md` |
| 06 | Database Sharding | `design/06-database-sharding.md` |
| 07 | Replication | `design/07-replication.md` |
| 21 | Back-of-Envelope & CDN | `design/21-back-of-envelope-cdn.md` |

### Data Storage & Retrieval
| # | Topic | File |
|---|-------|------|
| 10 | Redis | `design/10-redis.md` |
| 12 | Graph Databases | `design/12-graph-databases.md` |
| 13 | Search Engines | `design/13-search-engines.md` |
| 19 | SQL vs NoSQL Indexes | `design/19-sql-vs-nosql-indexes.md` |
| 25 | Apache Iceberg Data Lake | `design/25-apache-iceberg-data-lake.md` |

### Distributed Systems
| # | Topic | File |
|---|-------|------|
| 11 | Message Queues | `design/11-message-queues.md` |
| 14 | Rate Limiting | `design/14-rate-limiting.md` |
| 15 | Quorum & Consensus | `design/15-quorum-and-consensus.md` |
| 16 | Leader Election & Gossip | `design/16-leader-election-gossip.md` |
| 20 | Distributed Transactions | `design/20-distributed-transactions.md` |
| 22 | Leases | `design/22-leases.md` |
| 23 | Stream Processing | `design/23-stream-processing.md` |

### Reliability & Resilience
| # | Topic | File |
|---|-------|------|
| 17 | Circuit Breaker | `design/17-circuit-breaker.md` |
| 18 | API Gateway & Service Discovery | `design/18-api-gateway-service-discovery.md` |

### Operations & Infrastructure
| # | Topic | File |
|---|-------|------|
| 24 | Observability & Operations | `design/24-observability-operations.md` |
| 27 | Infrastructure & Kubernetes | `design/27-infrastructure-k8s.md` |

### Real-world Systems
| # | Topic | File |
|---|-------|------|
| 26 | Netflix Data Platform | `design/26-netflix-data-platform.md` |

## 🌳 Data Structures

### Advanced Tree Structures
| # | Topic | File |
|---|-------|------|
| 28 | Skip Lists | `data-structures/28-skip-lists.md` |
| 29 | LSM Trees | `data-structures/29-lsm-trees.md` |
| 30 | B-Trees | `data-structures/30-b-trees.md` |
| 31 | Red-Black Trees | `data-structures/31-red-black-trees.md` |

### Probabilistic Structures
| # | Topic | File |
|---|-------|------|
| 08 | Bloom Filters | `data-structures/08-bloom-filters.md` |
| 09 | Probabilistic Data Structures | `data-structures/09-probabilistic-data-structures.md` |

## 🔍 Quick Search by Topic

### By Use Case

**Building a Cache Layer**
- `design/04-caching.md`
- `design/10-redis.md`
- `design/03-consistent-hashing.md`

**Handling Scale**
- `design/01-scalability-fundamentals.md`
- `design/05-load-balancing.md`
- `design/06-database-sharding.md`

**Ensuring Reliability**
- `design/02-cap-theorem.md`
- `design/07-replication.md`
- `design/15-quorum-and-consensus.md`
- `design/17-circuit-breaker.md`

**Real-time Processing**
- `design/11-message-queues.md`
- `design/23-stream-processing.md`
- `design/13-search-engines.md`

**Data Management**
- `design/19-sql-vs-nosql-indexes.md`
- `design/20-distributed-transactions.md`
- `design/25-apache-iceberg-data-lake.md`
- `design/26-netflix-data-platform.md`

**System Coordination**
- `design/14-rate-limiting.md`
- `design/15-quorum-and-consensus.md`
- `design/16-leader-election-gossip.md`
- `design/22-leases.md`

**Modern Infrastructure**
- `design/18-api-gateway-service-discovery.md`
- `design/24-observability-operations.md`
- `design/27-infrastructure-k8s.md`

### By Data Structure

**For Searching/Lookup**
- `data-structures/08-bloom-filters.md` - Fast membership testing
- `data-structures/30-b-trees.md` - Ordered access
- `data-structures/31-red-black-trees.md` - Balanced binary trees

**For Databases**
- `data-structures/29-lsm-trees.md` - Write-optimized (RocksDB, LevelDB)
- `data-structures/30-b-trees.md` - Read-optimized (traditional databases)
- `data-structures/28-skip-lists.md` - Probabilistic alternative to balanced trees

**For Distributed Systems**
- `data-structures/08-bloom-filters.md` - Reduce network traffic
- `data-structures/09-probabilistic-data-structures.md` - Approximate answers

## 📊 Topics by Difficulty

### Beginner
- `design/01-scalability-fundamentals.md`
- `design/02-cap-theorem.md`
- `design/04-caching.md`
- `design/05-load-balancing.md`

### Intermediate
- `design/03-consistent-hashing.md`
- `design/06-database-sharding.md`
- `design/10-redis.md`
- `design/11-message-queues.md`
- `data-structures/08-bloom-filters.md`
- `data-structures/30-b-trees.md`

### Advanced
- `design/15-quorum-and-consensus.md`
- `design/16-leader-election-gossip.md`
- `design/20-distributed-transactions.md`
- `design/23-stream-processing.md`
- `design/25-apache-iceberg-data-lake.md`
- `data-structures/29-lsm-trees.md`
- `data-structures/31-red-black-trees.md`

## 🔗 Related Topics

### Consistency & Replication
- `design/02-cap-theorem.md`
- `design/07-replication.md`
- `design/15-quorum-and-consensus.md`

### Database Optimization
- `design/04-caching.md`
- `design/06-database-sharding.md`
- `design/19-sql-vs-nosql-indexes.md`
- `data-structures/29-lsm-trees.md`
- `data-structures/30-b-trees.md`

### Data Processing Pipeline
- `design/11-message-queues.md`
- `design/23-stream-processing.md`
- `design/25-apache-iceberg-data-lake.md`
- `design/26-netflix-data-platform.md`

### Distributed Coordination
- `design/14-rate-limiting.md`
- `design/15-quorum-and-consensus.md`
- `design/16-leader-election-gossip.md`
- `design/22-leases.md`

## 💾 Recommended Reading Order

1. Start with fundamentals
   - `design/01-scalability-fundamentals.md`
   - `design/02-cap-theorem.md`

2. Learn key patterns
   - `design/03-consistent-hashing.md`
   - `design/04-caching.md`
   - `design/05-load-balancing.md`

3. Understand data layer
   - `design/06-database-sharding.md`
   - `design/07-replication.md`
   - `design/10-redis.md`

4. Master distributed systems
   - `design/15-quorum-and-consensus.md`
   - `design/16-leader-election-gossip.md`
   - `design/20-distributed-transactions.md`

5. Explore advanced topics
   - `design/23-stream-processing.md`
   - `design/25-apache-iceberg-data-lake.md`
   - `design/26-netflix-data-platform.md`

## 📝 Quick Facts

- **Total Topics**: 33
- **Design Notes**: 27
- **Data Structure Notes**: 6
- **Total Content**: ~500+ KB of comprehensive documentation

---

Last updated: 2026-03-23