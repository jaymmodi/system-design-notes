# Scalability Fundamentals

## What is Scalability?
The ability of a system to handle increasing load by adding resources — without redesigning the whole thing.

---

## Vertical vs Horizontal Scaling

### Vertical Scaling (Scale Up)
- Add more CPU/RAM/disk to a single machine
- **Simple** — no code changes needed
- **Hard limit** — biggest machine on AWS is ~24TB RAM
- **Single point of failure**

### Horizontal Scaling (Scale Out)
- Add more machines, distribute load
- **No hard ceiling** — add nodes indefinitely
- **Complex** — requires stateless services, load balancing, distributed coordination
- **Resilient** — no SPOF

```java
// Vertical: just upgrade hardware
// Horizontal: your service must be STATELESS

// BAD — stores state in-memory, breaks horizontal scaling
public class BadSessionService {
    private Map<String, UserSession> sessions = new HashMap<>(); // lives on ONE server

    public void storeSession(String token, UserSession session) {
        sessions.put(token, session);
    }
}

// GOOD — stateless service, sessions in Redis (shared store)
public class GoodSessionService {
    private final RedisClient redis;

    public GoodSessionService(RedisClient redis) {
        this.redis = redis;
    }

    public void storeSession(String token, UserSession session) {
        redis.set(token, serialize(session), Duration.ofHours(1));
    }

    public UserSession getSession(String token) {
        String data = redis.get(token);
        return data == null ? null : deserialize(data);
    }
}
```

---

## Latency vs Throughput

- **Latency**: Time to complete ONE request (ms)
- **Throughput**: Requests handled per second (RPS/QPS)
- Optimizing one often trades off the other

```
Example:
  Batch processing: HIGH throughput, HIGH latency (wait to fill batch)
  Real-time API: LOW latency, moderate throughput
```

---

## Performance Numbers Every Engineer Must Know

| Operation | Latency |
|-----------|---------|
| L1 cache reference | 0.5 ns |
| L2 cache reference | 7 ns |
| RAM access | 100 ns |
| SSD random read | 150 µs |
| HDD seek | 10 ms |
| Network: same DC | 0.5 ms |
| Network: cross-region | 150 ms |

```java
// These numbers matter for back-of-envelope estimates
// 1 million RPS * 100ns per RAM op = 0.1 seconds of CPU time
// 1 million RPS * 10ms per disk read = YOU NEED CACHING
```

---

## Availability & SLAs

```
Availability  |  Downtime/year
99%           |  3.65 days
99.9%         |  8.76 hours
99.99%        |  52 minutes
99.999%       |  5 minutes
```

### How to achieve high availability:
1. **Redundancy** — multiple instances, no SPOF
2. **Failover** — auto-switch on failure
3. **Health checks** — detect failures fast
4. **Graceful degradation** — return stale data vs returning error

```java
// Graceful degradation example
public class ProductService {
    private final DatabaseClient db;
    private final CacheClient cache;

    public Product getProduct(String id) {
        try {
            Product p = db.findById(id);
            cache.set(id, p);
            return p;
        } catch (DatabaseException e) {
            // DB is down — return stale cached data
            Product cached = cache.get(id);
            if (cached != null) {
                log.warn("DB down, returning stale cache for {}", id);
                return cached;
            }
            throw new ServiceUnavailableException("Both DB and cache failed");
        }
    }
}
```

---

## Key Design Principles

### 1. Keep Services Stateless
Every request carries enough context. No server-side session state. Enables free horizontal scaling.

### 2. Design for Failure
Assume every component WILL fail. Use timeouts, retries with backoff, circuit breakers.

```java
// Exponential backoff with jitter
public <T> T retryWithBackoff(Supplier<T> operation, int maxRetries) {
    Random rand = new Random();
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
        try {
            return operation.get();
        } catch (Exception e) {
            if (attempt == maxRetries) throw e;
            long backoff = (long) Math.pow(2, attempt) * 100L; // 100, 200, 400ms...
            long jitter = rand.nextInt(100); // add randomness to avoid thundering herd
            sleep(backoff + jitter);
        }
    }
    throw new RuntimeException("unreachable");
}
```

### 3. Async Over Sync Where Possible
Sync calls chain latency. Async (queues, events) decouple components.

### 4. Push Complexity to the Edges
Keep core data path fast. Do heavy processing offline/async.

---

## Common Bottlenecks & Solutions

| Bottleneck | Symptom | Solution |
|-----------|---------|----------|
| Database | Slow queries, high CPU | Read replicas, caching, indexing |
| Single server | CPU maxed out | Horizontal scaling |
| Network | High latency to users | CDN, edge nodes |
| Hot partition | One node overwhelmed | Better partitioning key |
| Blocking I/O | Threads exhausted | Async I/O, reactive |

---

## Single Point of Failure (SPOF)

A SPOF is any component whose failure brings down the entire system.

```
        Users
          ↓
      [Load Balancer]  ← SPOF — if this dies, nothing works
          ↓
      [App Server]
          ↓
      [Database]       ← SPOF — if this dies, no reads or writes
```

### Common SPOFs and fixes

| Component | SPOF Risk | Fix |
|---|---|---|
| Load balancer | One LB → all traffic dies | Active-active pair, DNS failover |
| Database | One primary → no writes | Primary + replicas, automatic failover |
| Message queue | One broker → no events | Kafka replication (min.insync.replicas) |
| DNS | Single DNS server | Multiple DNS providers |
| Datacenter | One DC → total outage | Multi-region deployment |
| Config service | One node → no deploys | Replicated (ZooKeeper, etcd clusters) |

**Active-active** — both nodes serve traffic simultaneously, capacity doubles
**Active-passive** — standby does nothing until primary fails, simpler but wastes capacity

---

## AWS Multi-AZ: ALB + Route 53

### How ALB Multi-AZ actually works

One ALB spanning multiple AZs — not one ALB per AZ. AWS deploys an ALB node in each AZ you select internally.

```
Route 53
    ↓  (ALIAS record → ALB DNS name)

AWS ALB  (single logical LB, one node per AZ internally)
    ├── ALB node in us-east-1a  ──→  [App] [App]
    ├── ALB node in us-east-1b  ──→  [App] [App]
    └── ALB node in us-east-1c  ──→  [App] [App]
```

The ALB DNS name resolves to multiple IPs — one per AZ node. If one AZ dies, its IP is removed from DNS automatically.

### Route 53's role in multi-AZ

Route 53 just points to the ALB — it does not do per-AZ routing:

```
Route 53:
  api.myapp.com  →  ALIAS → my-alb-123.us-east-1.elb.amazonaws.com

ALB DNS (managed by AWS):
  my-alb-123.us-east-1.elb.amazonaws.com
    → 54.10.1.1   (ALB node in 1a)
    → 54.10.2.1   (ALB node in 1b)
    → 54.10.3.1   (ALB node in 1c)
```

### Target Groups — one spanning all AZs

```
ALB
 └── Listener (443)
      └── Rule → Target Group: my-app-tg
                    ├── i-001 (EC2 in 1a)  ✓
                    ├── i-002 (EC2 in 1a)  ✓
                    ├── i-003 (EC2 in 1b)  ✓
                    ├── i-004 (EC2 in 1b)  ✓
                    ├── i-005 (EC2 in 1c)  ✓
                    └── i-006 (EC2 in 1c)  ✓
```

Cross-zone load balancing (default on ALB) distributes evenly across all targets regardless of which AZ the request entered.

### AZ failure — what happens

```
1a goes down:
  AWS removes 1a IP from ALB DNS automatically
  1a instances marked unhealthy, removed from target group
  Route 53 → ALB (1b, 1c) → 4 remaining targets
  Zero manual intervention needed
```

### Route 53 active routing — multi-region only

```
api.myapp.com
    ├── us-east-1 ALB  (primary — latency-based or weighted)
    └── eu-west-1 ALB  (failover)

If us-east-1 health check fails → Route 53 shifts all traffic to eu-west-1
```

### Summary

| Question | Answer |
|---|---|
| One LB per AZ? | No — one ALB, AWS places nodes per AZ internally |
| Does Route 53 route across AZs? | No — ALB handles AZ distribution |
| One target group or one per AZ? | One target group with targets in all AZs |
| What does Route 53 do here? | ALIAS record pointing to ALB DNS name |
| When does Route 53 route actively? | Multi-region failover or latency-based routing |

---

## Interview Questions

1. **How would you scale a service from 1K to 10M users?**
   - Start: single server → add caching → read replicas → horizontal app servers → sharding
2. **What makes a service hard to scale horizontally?**
   - Statefulness, shared mutable in-memory data, sticky sessions
3. **When would you choose vertical over horizontal scaling?**
   - When the app is inherently stateful, when simplicity matters, for databases that are hard to shard
4. **How do you eliminate SPOFs in a production AWS service?**
   - ALB across 3 AZs, ASG with min 2 instances per AZ, RDS Multi-AZ, Route 53 health checks for multi-region failover
