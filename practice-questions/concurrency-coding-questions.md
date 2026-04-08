# Concurrency Coding Questions — Problem Solving Round

Context: Phone screen covered time series DB design + Java locks (CAS, ReentrantLock).
These questions are framed as an interviewer would ask them.

---

## Q1. Thread-Safe Time-Based Key-Value Store

> Store key-value pairs with a timestamp. `set(key, value, timestamp)` stores it. `get(key, timestamp)` returns the value with the largest timestamp ≤ the given timestamp.

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.TreeMap;

public class TimeBasedKVStore {
    private final ConcurrentHashMap<String, TreeMap<Integer, String>> store = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public void set(String key, String value, int timestamp) {
        lock.writeLock().lock();
        try {
            store.computeIfAbsent(key, k -> new TreeMap<>()).put(timestamp, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String get(String key, int timestamp) {
        lock.readLock().lock();
        try {
            TreeMap<Integer, String> times = store.get(key);
            if (times == null) return "";
            Integer floor = times.floorKey(timestamp);
            return floor == null ? "" : times.get(floor);
        } finally {
            lock.readLock().unlock();
        }
    }
}
```

**Why ReentrantReadWriteLock here?** Multiple concurrent readers are fine — only writes need exclusion. At Netflix scale with heavy read traffic, this matters.

**Follow-up: "What if you have high write contention?"** → Stripe by key: one lock per key bucket instead of one global lock.

---

## Q2. Sliding Window Rate Limiter

> Allow at most `maxRequests` per `windowMs` milliseconds per key (e.g., per user).

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayDeque;
import java.util.Deque;

public class SlidingWindowRateLimiter {
    private final int maxRequests;
    private final long windowMs;
    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = windows.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (timestamps) {                          // per-key lock — no global bottleneck
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs)
                timestamps.pollFirst();
            if (timestamps.size() < maxRequests) {
                timestamps.addLast(now);
                return true;
            }
            return false;
        }
    }
}
```

**Follow-up: "Why not one big `synchronized` on `this`?"** → Every user would block every other user. Per-key locking scopes contention to just that key's window.

**Follow-up: "Token bucket vs sliding window — when to use which?"**
- Sliding window: strict enforcement, no burst beyond limit
- Token bucket: allows short bursts up to capacity, better UX for bursty clients

---

## Q3. ReadWrite Lock from Scratch

> Multiple readers can hold the lock simultaneously. A writer needs exclusive access.

```java
public class ReadWriteLock {
    private int readers = 0;
    private boolean writing = false;
    private int waitingWriters = 0;        // prevent writer starvation

    public synchronized void lockRead() throws InterruptedException {
        while (writing || waitingWriters > 0) wait();
        readers++;
    }

    public synchronized void unlockRead() {
        readers--;
        if (readers == 0) notifyAll();
    }

    public synchronized void lockWrite() throws InterruptedException {
        waitingWriters++;
        while (writing || readers > 0) wait();
        waitingWriters--;
        writing = true;
    }

    public synchronized void unlockWrite() {
        writing = false;
        notifyAll();
    }
}
```

**Follow-up: "Why `notifyAll` not `notify`?"** → `notify` wakes one random thread. If a writer is waiting and a reader gets woken, the writer starves. `notifyAll` lets all threads re-check the condition.

**Follow-up: "How does `ReentrantReadWriteLock` differ from this?"** → Supports lock downgrade (write → read), fairness policy, `tryLock` with timeout, reentrant acquisition by the same thread.

---

## Q4. Thread-Safe LRU Cache

> O(1) get and put. Evicts least recently used when over capacity.

```java
import java.util.HashMap;
import java.util.Map;

public class LRUCache {
    private final int capacity;
    private final Map<Integer, Node> map = new HashMap<>();
    private final Node head = new Node(0, 0);   // dummy
    private final Node tail = new Node(0, 0);   // dummy

    public LRUCache(int capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public synchronized int get(int key) {
        Node node = map.get(key);
        if (node == null) return -1;
        moveToFront(node);
        return node.val;
    }

    public synchronized void put(int key, int val) {
        Node node = map.get(key);
        if (node != null) {
            node.val = val;
            moveToFront(node);
        } else {
            node = new Node(key, val);
            map.put(key, node);
            addToFront(node);
            if (map.size() > capacity) {
                Node lru = tail.prev;
                remove(lru);
                map.remove(lru.key);
            }
        }
    }

    private void moveToFront(Node n) { remove(n); addToFront(n); }

    private void addToFront(Node n) {
        n.next = head.next; n.prev = head;
        head.next.prev = n; head.next = n;
    }

    private void remove(Node n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    private static class Node {
        int key, val;
        Node prev, next;
        Node(int key, int val) { this.key = key; this.val = val; }
    }
}
```

**Follow-up: "Why dummy head and tail?"** → Eliminates null checks on every insert/remove. Every real node always has a prev and next.

**Follow-up: "How would you scale this to handle 1M concurrent requests?"** → Shard into N independent caches by `key % N`, each with its own lock. Reduces contention by factor of N.

---

## Q5. CAS-Based Lock-Free Counter

> Implement a counter using CAS. Explain when you'd use this over `synchronized`.

```java
import java.util.concurrent.atomic.AtomicInteger;

public class LockFreeCounter {
    private final AtomicInteger count = new AtomicInteger(0);

    public int increment() {
        return count.incrementAndGet();
    }

    // CAS: only increment if below limit
    public boolean incrementIfBelow(int limit) {
        while (true) {
            int current = count.get();
            if (current >= limit) return false;
            if (count.compareAndSet(current, current + 1)) return true;
            // another thread changed it — retry
        }
    }
}
```

**CAS vs `synchronized` — the answer they want:**

| | CAS / Atomic | `synchronized` |
|---|---|---|
| Blocking | No — spin retry | Yes — thread sleeps |
| Best for | Low contention, simple ops | Complex multi-field atomicity |
| Downside | CPU spin under high contention | Context switch overhead |
| Netflix use | Atlas metrics counters, Hystrix stats | Cache eviction logic |

---

## Practice Order for Apr 7

1. Q1 — Time-Based KV Store (ties directly to phone screen)
2. Q2 — Sliding Window Rate Limiter
3. Q3 — ReadWrite Lock
4. Q4 — LRU Cache
5. Q5 — CAS Counter (verbal explanation is enough)

Target: each in under 15 minutes, written cold without notes.
