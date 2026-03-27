# Netflix Popular Interview Questions (Sourced from Glassdoor, Blind, LeetCode Discuss)

---

## System Design Questions

### Video / Streaming (Core Domain)
1. Design Netflix's video streaming platform end-to-end
   - Expected: Open Connect CDN, adaptive bitrate (HLS/DASH), API gateway (Zuul), EVCache, Kafka, Cassandra, multi-region failover
   - Follow-ups: "How does your CDN invalidate stale content?" / "What happens when a region goes down during peak?"

2. Design a scalable, fault-tolerant CDN with geo-replication and content invalidation
   - Follow-ups: "Open Connect vs third-party CDN trade-offs?" / "How do you handle cold-start for a new show?"

3. Design a low-latency streaming service with sub-500ms startup delay
   - Focus: Edge proximity, chunked progressive delivery, ABR switching stability

4. Design the adaptive bitrate streaming system (ABR)
   - Expected: Segment manifests, bitrate ladder, bandwidth estimation, buffer management

### Recommendations & Personalization
5. Design Netflix's personalized recommendation system at 300M user scale
   - Key probe: "Hover behavior as implicit signal — how do you weight it vs explicit ratings?"
   - Expected: Offline (collaborative filtering) + online serving, Kafka pipelines, cold-start handling, A/B testing

6. Design the Netflix homepage row system (Trending, New Releases, etc.)
   - Expected: Ranking algorithms, cache with TTL, Elasticsearch, freshness vs compute trade-off

7. Design an A/B testing framework
   - Expected: Random bucketing, experiment service, Atlas metrics, statistical significance, feature flags

### Data Platform (Most Likely for Your Role)
8. Design a real-time analytics pipeline at Netflix scale (500B events/day, 1.3 PB/day)
   - Expected components: Kafka → Flink → Iceberg → Druid / Spark
   - Follow-ups: "How do you handle late-arriving events?" / "Exactly-once semantics?"

9. Design a petabyte-scale data lake pipeline (S3 → Spark → warehouse)
   - Expected: Partitioning strategies, Iceberg schema evolution, WAP pattern, Maestro scheduling, GDPR deletion

10. Design a distributed database syncing across 3 regions / 3 zones with eventual consistency
    - CAP probe: Netflix prioritizes AP

11. Design a distributed counter (intentionally vague)
    - Interviewer WILL ask: "Does it need to be consistent or eventually consistent?" / "Available during partition?"
    - This is a CAP theorem probe — state assumptions explicitly

12. Design an event stream ingestion system (Kafka-style)
    - Expected: Partitioning, consumer groups, at-least-once vs exactly-once, retention, backpressure

13. Design a distributed caching layer
    - Follow-ups: Eviction policy? Cache stampede? Write-through vs write-behind?

14. Design a monitoring and distributed tracing system for microservices
    - Expected: Atlas (metrics), Zipkin/OpenTelemetry (traces), SLO dashboards, alerting pipeline

15. Design a multi-region active-active architecture
    - Follow-ups: "How do you route traffic when a region fails?" / "How do you replicate data globally?"

16. Design a chaos engineering system (Chaos Monkey-style)
    - Expected: Fault injection types, blast radius controls, automated rollback

17. Design an API rate limiter
    - Expected: Token bucket vs sliding window, Redis-backed, distributed, per-user + per-endpoint

### Reverse System Design (Your Own Past Work)
- "Walk me through the largest scale system you've built. What would you change today?"
- "Where did it break? How did you detect and fix it?"
- "What were the hardest trade-offs you made?"

---

## Distributed Systems Technical Questions

### Consensus & Coordination
- Explain Raft vs Paxos — when would you use each?
- How does ZooKeeper use ZAB for leader election?
- How does etcd guarantee consistency in Kubernetes?
- How does Kafka use ZooKeeper vs KRaft for controller election?

### CAP Theorem & Consistency
- Walk me through CAP. Where does Cassandra sit? DynamoDB?
- What is eventual consistency? Give a concrete Netflix example.
- Difference between read-your-writes vs causal consistency?
- What is a CRDT and when would you use one vs a distributed lock?
- **Difference between linearizability and serializability?**

### Replication & Partitioning
- How does Cassandra's consistent hashing and vnodes work?
- Difference between leader-based and leaderless replication?
- Cassandra cross-DC replication: LOCAL_QUORUM vs EACH_QUORUM?
- What is split-brain and how do you prevent it?
- How does Kafka partition replication work? What is ISR (In-Sync Replicas)?

### Failure Handling & Resilience
- What is a circuit breaker? How does Hystrix implement it?
- What is bulkhead isolation? How does it differ from circuit breaker?
- Explain exponential backoff with jitter — why is jitter important?
- How does Chaos Monkey work and what's the philosophy?
- Difference between health check, readiness probe, and liveness probe?
- How do you design graceful degradation when downstream is slow vs down?

### Concurrency & Multithreading (DEDICATED ROUND)
- **Implement a ReadWrite lock** (directly reported — write the code)
- **Implement a thread pool executor from scratch** (write the code)
- **Build a concurrent client library** (Blind-reported for L4 distributed systems)
- Explain producer-consumer pattern. Implement a bounded blocking queue.
- Optimistic vs pessimistic locking in a distributed context?
- How do you detect and resolve deadlocks in Java?
- Difference between `synchronized`, `ReentrantLock`, and `StampedLock`?

```java
// Practice this — ReadWrite Lock from scratch
public class ReadWriteLock {
    private int readers = 0;
    private boolean writing = false;

    public synchronized void lockRead() throws InterruptedException {
        while (writing) wait();
        readers++;
    }
    public synchronized void unlockRead() {
        readers--;
        if (readers == 0) notifyAll();
    }
    public synchronized void lockWrite() throws InterruptedException {
        while (writing || readers > 0) wait();
        writing = true;
    }
    public synchronized void unlockWrite() {
        writing = false;
        notifyAll();
    }
}
```

### Messaging & Streaming
- How does Kafka guarantee ordering within a partition? Global ordering?
- At-least-once vs at-most-once vs exactly-once in Kafka?
- How do you handle consumer lag in a Kafka pipeline?
- What is a Kafka compacted topic and when do you use it?
- How does Flink handle stateful processing and checkpointing?
- Event time vs processing time in Flink — why does it matter?
- How does Netflix's 1:1 Kafka-topic-to-Flink-job mapping work and why?

---

## Coding / Algorithm Questions

### High Frequency (Reported Multiple Times)
| Problem | Pattern | Difficulty |
|---------|---------|------------|
| Merge Intervals | Sort + merge | Medium |
| Meeting Rooms II | Min-heap / sweep line | Medium |
| LRU Cache | HashMap + doubly linked list | Medium |
| Top K Frequent Elements | Heap | Medium |
| Reverse Linked List | Two pointers | Easy |
| Number of Islands | DFS/BFS | Medium |
| Valid Parentheses | Stack | Easy |

### Reported at Netflix Specifically
```python
# DIRECTLY REPORTED: Given user view times as (start, end) tuples,
# find all unique non-overlapping time ranges viewed
# Input: [(0,30), (15,60), (90,120)]
# Output: [(0,60), (90,120)]

def merge_view_times(intervals):
    if not intervals: return []
    intervals.sort()
    merged = [intervals[0]]
    for start, end in intervals[1:]:
        if start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged
```

```java
// Implement a thread-safe rate limiter (reported for distributed systems roles)
public class RateLimiter {
    private final int maxRequests;
    private final long windowMs;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs)
            timestamps.pollFirst();
        if (timestamps.size() < maxRequests) {
            timestamps.addLast(now);
            return true;
        }
        return false;
    }
}
```

### Trees & Linked Lists
- Recover BST where two nodes are swapped
- Deserialize a binary tree using BFS with a queue
- Lowest Common Ancestor
- Detect and remove loop in linked list (Floyd's cycle)
- Intersection of two linked lists

### Strings & Arrays
- Longest palindromic substring (expand around center)
- First missing positive — O(n) time, O(1) space
- Next Greater Element (monotonic stack)
- Median of two sorted arrays (binary search)

### Design / Simulation
- Implement an in-memory file system (`mkdir`, `ls`, `addContentToFile`)
- Implement LRU Cache
- Logger Rate Limiter
- Reservoir sampling from an unbounded stream

### SQL (Data Platform Roles)
```sql
-- All reported as asked at Netflix
-- 1. Second-highest salary by department
SELECT department, MAX(salary) as salary
FROM employees
WHERE salary < (SELECT MAX(salary) FROM employees)
GROUP BY department;

-- Better with window functions:
SELECT DISTINCT department, salary
FROM (SELECT department, salary,
      DENSE_RANK() OVER (PARTITION BY department ORDER BY salary DESC) as rk
      FROM employees) t
WHERE rk = 2;

-- 2. Top 3 salaries per department
SELECT department, employee, salary FROM (
  SELECT department, employee, salary,
         DENSE_RANK() OVER (PARTITION BY department ORDER BY salary DESC) rk
  FROM employees
) WHERE rk <= 3;

-- 3. First-touch attribution (first touchpoint in user funnel)
SELECT user_id, MIN(timestamp) as first_touch, channel
FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY timestamp) rn
      FROM touchpoints) t
WHERE rn = 1
GROUP BY user_id, channel;

-- 4. Last transaction per calendar day per user
SELECT user_id, date, amount FROM (
  SELECT user_id, DATE(timestamp) as date, amount,
         ROW_NUMBER() OVER (PARTITION BY user_id, DATE(timestamp) ORDER BY timestamp DESC) rn
  FROM transactions
) WHERE rn = 1;

-- 5. Top 5 paired products purchased together
SELECT p1.product_id, p2.product_id, COUNT(*) as pair_count
FROM order_items p1 JOIN order_items p2
  ON p1.order_id = p2.order_id AND p1.product_id < p2.product_id
GROUP BY p1.product_id, p2.product_id
ORDER BY pair_count DESC LIMIT 5;
```

---

## Behavioral / Culture Questions

### Judgment & Decision-Making
- "Tell me about a time you made a difficult decision with limited data."
- "Describe a situation with significant ambiguity — what was your process?"
- "Tell me about a time you influenced a decision without formal authority."
- "How do you balance creative freedom with accountability?"

### Feedback & Communication
- "How do you give and receive feedback? Give a specific example."
- "Tell me about a time you gave difficult feedback to a coworker."
- "Tell me about a time you disagreed with a teammate or leader. How did you resolve it?"
- "What positive AND negative feedback have you received? Be specific."

### Ownership & Accountability
- "Tell me about a time you made a mistake. How did you handle it?"
- "Describe a time you owned a production incident — from detection through postmortem."
- "Tell me about a time you identified and fixed a problem outside your direct scope."

### Courage & High Performance
- "Describe a time you demonstrated courage in your work."
- "Tell me about a time you pushed back on leadership or stakeholders."
- "How do you handle working in an 'uncomfortably exciting' environment?"

### Freedom & Responsibility
- "Tell me about a time you operated with significant autonomy."
- "If your manager was unavailable and a production decision needed to be made, what would you do?"
- "What does 'context, not control' mean to you in practice?"

### Director-Level / Dream Team Round
- "Tell me about your most impressive accomplishment and why it was significant at scale."
- "Why Netflix specifically — not just any tech company?"
- "What concerns you about Netflix's culture memo? Be honest."
- "What are you genuinely bad at? What has a manager told you to work on?"

### Culture Memo (Asked at Recruiter Screen)
- "What do you think about Netflix's culture memo?"
- "What resonates with you?"
- "What aspects concern you?"

> **Prepare 8-10 STAR stories covering**: impact at scale, conflict/disagreement, failure/mistake, pushing back, cross-team influence, operating autonomously, giving tough feedback.

---

## Operations & Reliability Questions

- "Walk me through debugging an intermittent production failure affecting 5% of requests."
- "A deployment just caused 30% error rates in one region — what do you do in the first 5 minutes?"
- "Your p99 latency spiked 3x but error rates are normal. What could cause this?"
- "How do you conduct an effective postmortem? What makes it actionable vs performative?"
- "How do you define SLOs and SLIs for a streaming service? What is your error budget?"
- "Walk me through your on-call setup — how do you structure alerts to avoid alert fatigue?"
- "How do you implement graceful degradation? Give an example from your experience."
- "Walk me through a rolling vs blue-green vs canary deployment."
- "What is your rollback strategy when a canary shows degraded metrics?"

---

## Data Pipeline Questions (Your Role Specific)

- "How would you design a data pipeline for analyzing user behavior at Netflix scale?"
- "Explain the WAP (Write-Audit-Publish) pattern in Apache Iceberg."
- "What is schema evolution and how does Iceberg handle it differently from Hive?"
- "Batch vs stream processing — when would you choose each?"
- "How do you handle data quality issues in a streaming environment?"
- "How do you handle late-arriving events in Flink/Spark Streaming?"
- "How would you design a GDPR-compliant deletion system for a petabyte-scale data lake?"
- "Describe a data pipeline optimization you did — what was the bottleneck?"
- "How do Netflix Keystone / Maestro / Iceberg IPS work together?"

---

## Key Patterns From All Reports

1. **CAP probe is everywhere** — every design question eventually asks consistency vs availability
2. **Your own past work is fair game** — reverse system design of your resume projects
3. **Netflix tech vocabulary signals readiness** — say Kafka, Flink, Iceberg, EVCache, Hystrix, Atlas
4. **Concurrency is a dedicated coding round** — implement locks and thread pools, not just describe them
5. **Behavioral is a hard gate** — technical excellence does NOT override culture misalignment
6. **Culture memo is discussed from recruiter screen** — read it before step 1
7. **System design > LeetCode** — design round is make-or-break, coding is least weighted
8. **Quantify everything** — "I scaled X to Y RPS", "reduced latency from A to B ms"
