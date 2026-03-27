# Concurrency Coding Practice — Netflix Problem Solving Round

**Goal**: Write each of these cold in Java in ≤20 minutes. Practice daily until Apr 7.

---

## 1. ReadWrite Lock (DIRECTLY REPORTED from Netflix)

**What it tests**: `synchronized`, `wait/notifyAll`, starvation handling

```java
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

**Follow-up they may ask**: "How would you prevent writer starvation?"
```java
// Add: private int waitingWriters = 0;
// lockRead: while (writing || waitingWriters > 0) wait();
// lockWrite: waitingWriters++; while (writing || readers > 0) wait(); waitingWriters--;
```

**Variant**: Use `ReentrantReadWriteLock` from Java stdlib — know when to use which:
- Hand-rolled: interview setting, learning purpose
- `java.util.concurrent.locks.ReentrantReadWriteLock`: production — has fairness policy, upgrades, timeouts

---

## 2. Bounded Blocking Queue (Producer-Consumer)

**What it tests**: Classic concurrency pattern, `wait/notifyAll`, bounded buffer

```java
public class BoundedBlockingQueue<T> {
    private final Queue<T> queue = new LinkedList<>();
    private final int capacity;

    public BoundedBlockingQueue(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void put(T item) throws InterruptedException {
        while (queue.size() == capacity) wait();
        queue.offer(item);
        notifyAll();
    }

    public synchronized T take() throws InterruptedException {
        while (queue.isEmpty()) wait();
        T item = queue.poll();
        notifyAll();
        return item;
    }

    public synchronized int size() {
        return queue.size();
    }
}
```

**Follow-up**: "Why `notifyAll` instead of `notify`?"
> `notify` wakes one random thread — could wake a producer when a consumer is needed. `notifyAll` is safer but less efficient. For two separate conditions (full vs empty), use `ReentrantLock` with two `Condition` objects for finer control.

**Variant with ReentrantLock + Condition (more idiomatic Java)**:
```java
public class BoundedBlockingQueueV2<T> {
    private final Queue<T> queue = new LinkedList<>();
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public BoundedBlockingQueueV2(int capacity) { this.capacity = capacity; }

    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (queue.size() == capacity) notFull.await();
            queue.offer(item);
            notEmpty.signal();
        } finally { lock.unlock(); }
    }

    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty()) notEmpty.await();
            T item = queue.poll();
            notFull.signal();
            return item;
        } finally { lock.unlock(); }
    }
}
```

---

## 3. Thread-Safe LRU Cache

**What it tests**: HashMap + doubly linked list, synchronized access, O(1) get/put

```java
public class LRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private final Node<K, V> head = new Node<>(null, null); // dummy
    private final Node<K, V> tail = new Node<>(null, null); // dummy

    public LRUCache(int capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public synchronized V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) return null;
        moveToFront(node);
        return node.value;
    }

    public synchronized void put(K key, V value) {
        Node<K, V> node = map.get(key);
        if (node != null) {
            node.value = value;
            moveToFront(node);
        } else {
            node = new Node<>(key, value);
            map.put(key, node);
            addToFront(node);
            if (map.size() > capacity) {
                Node<K, V> lru = tail.prev;
                remove(lru);
                map.remove(lru.key);
            }
        }
    }

    private void moveToFront(Node<K, V> node) {
        remove(node);
        addToFront(node);
    }

    private void addToFront(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void remove(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private static class Node<K, V> {
        K key; V value;
        Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
    }
}
```

**Follow-up**: "How would you make this concurrent for high throughput?"
> Use `ConcurrentHashMap` + striped locking, or segment the cache into N shards each with its own lock. Java's `LinkedHashMap` with `accessOrder=true` + `removeEldestEntry` gives you LRU in a single thread.

---

## 4. Thread Pool Executor from Scratch

**What it tests**: Worker threads, task queue, lifecycle management

```java
public class ThreadPool {
    private final BoundedBlockingQueue<Runnable> taskQueue;
    private final List<Worker> workers;
    private volatile boolean shutdown = false;

    public ThreadPool(int numThreads, int queueCapacity) {
        taskQueue = new BoundedBlockingQueue<>(queueCapacity);
        workers = new ArrayList<>(numThreads);
        for (int i = 0; i < numThreads; i++) {
            Worker w = new Worker();
            workers.add(w);
            w.start();
        }
    }

    public void submit(Runnable task) throws InterruptedException {
        if (shutdown) throw new IllegalStateException("ThreadPool is shut down");
        taskQueue.put(task);
    }

    public void shutdown() {
        shutdown = true;
        for (Worker w : workers) w.interrupt();
    }

    private class Worker extends Thread {
        @Override
        public void run() {
            while (!shutdown || taskQueue.size() > 0) {
                try {
                    Runnable task = taskQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}

// Uses BoundedBlockingQueue from above
```

**Follow-up**: "What does `java.util.concurrent.ThreadPoolExecutor` add on top of this?"
> Core vs max thread count, keepAlive time for idle threads, `RejectedExecutionHandler` policies (CallerRuns, Abort, Discard), graceful `awaitTermination`.

---

## 5. Thread-Safe Rate Limiter (Sliding Window)

**Reported directly for distributed systems roles at Netflix**

```java
public class RateLimiter {
    private final int maxRequests;
    private final long windowMs;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public RateLimiter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

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

**Token bucket variant** (better for burst tolerance):
```java
public class TokenBucketRateLimiter {
    private final int capacity;
    private final double refillRatePerMs;
    private double tokens;
    private long lastRefill;

    public TokenBucketRateLimiter(int capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerMs = refillRatePerSecond / 1000.0;
        this.tokens = capacity;
        this.lastRefill = System.currentTimeMillis();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens--;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        double newTokens = (now - lastRefill) * refillRatePerMs;
        tokens = Math.min(capacity, tokens + newTokens);
        lastRefill = now;
    }
}
```

---

## 6. Non-Blocking Counter with CAS

**What it tests**: `AtomicInteger`, compare-and-set, lock-free patterns

```java
public class NonBlockingCounter {
    private final AtomicInteger count = new AtomicInteger(0);

    public int increment() {
        return count.incrementAndGet();
    }

    public int decrement() {
        return count.decrementAndGet();
    }

    // CAS example — conditional update
    public boolean incrementIfLessThan(int limit) {
        while (true) {
            int current = count.get();
            if (current >= limit) return false;
            if (count.compareAndSet(current, current + 1)) return true;
            // Lost the race — retry
        }
    }
}
```

**When to use CAS vs synchronized**:
- CAS: low contention, simple operations — no blocking, higher throughput
- synchronized: complex multi-step operations that need atomicity across multiple fields

---

## 7. Semaphore from Scratch

```java
public class Semaphore {
    private int permits;

    public Semaphore(int permits) {
        this.permits = permits;
    }

    public synchronized void acquire() throws InterruptedException {
        while (permits == 0) wait();
        permits--;
    }

    public synchronized void release() {
        permits++;
        notify();
    }
}
```

Use case at Netflix scale: limiting concurrent DynamoDB connections from a Spark executor.

---

## Key Java Concurrency Concepts — Must Know Cold

| Concept | One-liner |
|---------|-----------|
| `synchronized` | Acquires intrinsic lock on object; blocks other threads |
| `volatile` | Guarantees visibility across threads, NOT atomicity |
| `wait()` | Releases lock and sleeps until `notifyAll()` wakes it |
| `notifyAll()` | Wakes all waiting threads; they re-compete for the lock |
| `AtomicInteger` | CAS-based, lock-free integer — preferred for counters |
| `ReentrantLock` | Explicit lock with tryLock, timed lock, interruptible acquire |
| `StampedLock` | Optimistic reads — no lock acquisition if no concurrent writes |
| `CountDownLatch` | One-time barrier: wait for N operations to complete |
| `CyclicBarrier` | Reusable barrier: N threads wait for each other |
| `CompletableFuture` | Async composition — your async DynamoDB calls pattern |

---

## Practice Schedule

| Day | Task |
|-----|------|
| Day 1 | Write ReadWrite Lock from memory. Add writer-starvation fix. |
| Day 2 | Write BoundedBlockingQueue (synchronized version). Then ReentrantLock version. |
| Day 3 | Write LRU Cache. Explain the dummy head/tail trick. |
| Day 4 | Write ThreadPool using your BoundedBlockingQueue. |
| Day 5 | Write RateLimiter (sliding window + token bucket). |
| Day 6 | All 5 from memory, timed. Target: each in under 15 minutes. |
| Day 7+ | Do LeetCode: Meeting Rooms II, Merge Intervals, Top K Frequent |
