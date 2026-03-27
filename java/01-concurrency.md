# Java Concurrency — Concepts, Tools, and Examples

> Reference for Netflix interview prep and general Java concurrency mastery.
> For **implementations to write cold**, see `CONCURRENCY-CODING-PRACTICE.md`.

---

## Table of Contents

1. [The Memory Model — Visibility and Ordering](#1-the-memory-model)
2. [synchronized vs ReentrantLock vs StampedLock](#2-synchronized-vs-reentrantlock-vs-stampedlock)
3. [CountDownLatch](#3-countdownlatch)
4. [CyclicBarrier](#4-cyclicbarrier)
5. [Semaphore](#5-semaphore)
6. [Phaser](#6-phaser)
7. [ExecutorService and ThreadPoolExecutor](#7-executorservice-and-threadpoolexecutor)
8. [ScheduledExecutorService](#8-scheduledexecutorservice)
9. [ForkJoinPool and Work Stealing](#9-forkjoinpool-and-work-stealing)
10. [CompletableFuture](#10-completablefuture)
11. [Virtual Threads (Project Loom — Java 21)](#11-virtual-threads-java-21)
12. [Concurrent Collections](#12-concurrent-collections)
13. [Atomic Classes and CAS](#13-atomic-classes-and-cas)
14. [ThreadLocal](#14-threadlocal)
15. [Common Patterns and Pitfalls](#15-common-patterns-and-pitfalls)
16. [Quick Comparison Tables](#16-quick-comparison-tables)

---

## 1. The Memory Model

**Why it matters**: Without understanding visibility and ordering, concurrent code that *looks* correct can fail in subtle ways.

### Visibility Problem
```java
// Thread A
boolean running = true;
while (running) { /* work */ }

// Thread B
running = false; // Thread A may NEVER see this — CPU cache
```

### Three Tools for Visibility

| Tool | What it guarantees |
|------|-------------------|
| `volatile` | Every write is immediately visible to all threads. No CPU cache. |
| `synchronized` | On exit, all writes are flushed. On entry, stale cache is invalidated. |
| `AtomicXxx` | Same as volatile + atomic read-modify-write via CAS |

```java
// volatile — visibility only, NOT compound atomicity
volatile boolean running = true;         // ✅ safe for flag
volatile int count = 0; count++;         // ❌ not atomic (read + increment + write)

// synchronized — visibility + atomicity for the block
synchronized (this) { count++; }         // ✅ atomic

// AtomicInteger — lock-free atomicity
AtomicInteger count = new AtomicInteger();
count.incrementAndGet();                  // ✅ lock-free atomic
```

### Happens-Before Rules (abbreviated)
1. Each action in a thread happens-before every subsequent action in that thread.
2. `synchronized` unlock happens-before the next lock on the same monitor.
3. `volatile` write happens-before a subsequent read of the same variable.
4. `Thread.start()` happens-before any action in the started thread.
5. All actions in a thread happen-before any other thread returns from `join()` on that thread.

---

## 2. synchronized vs ReentrantLock vs StampedLock

### synchronized (intrinsic lock)
```java
public class Counter {
    private int count = 0;

    // Acquires lock on 'this'
    public synchronized void increment() {
        count++;
    }

    // Same lock — only one thread in either method at a time
    public synchronized int get() {
        return count;
    }
}
```

**Limitations**: Cannot try without blocking, cannot be interrupted while waiting, no read/write separation.

---

### ReentrantLock (explicit lock)
```java
public class Counter {
    private int count = 0;
    private final ReentrantLock lock = new ReentrantLock();

    public void increment() {
        lock.lock();
        try {
            count++;
        } finally {
            lock.unlock(); // ALWAYS unlock in finally
        }
    }

    // tryLock — non-blocking attempt
    public boolean tryIncrement() {
        if (lock.tryLock()) {
            try {
                count++;
                return true;
            } finally {
                lock.unlock();
            }
        }
        return false; // didn't acquire
    }

    // tryLock with timeout
    public boolean tryIncrementWithTimeout() throws InterruptedException {
        if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
            try {
                count++;
                return true;
            } finally {
                lock.unlock();
            }
        }
        return false;
    }
}
```

**ReentrantReadWriteLock** — multiple readers OR one writer:
```java
ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
Lock readLock  = rwLock.readLock();
Lock writeLock = rwLock.writeLock();

// Multiple threads can hold readLock simultaneously
readLock.lock();
try { return data; } finally { readLock.unlock(); }

// writeLock is exclusive — blocks all readers and writers
writeLock.lock();
try { data = newValue; } finally { writeLock.unlock(); }
```

Use `ReentrantReadWriteLock` when reads >> writes (e.g., configuration cache).

---

### StampedLock (Java 8+) — optimistic reads
```java
StampedLock lock = new StampedLock();

// Write
long stamp = lock.writeLock();
try { data = newValue; } finally { lock.unlockWrite(stamp); }

// Pessimistic read (like ReadLock)
long stamp = lock.readLock();
try { return data; } finally { lock.unlockRead(stamp); }

// OPTIMISTIC read — no actual locking, just validation
long stamp = lock.tryOptimisticRead();  // returns a "version stamp"
int value = data;                        // read without holding lock
if (!lock.validate(stamp)) {            // check if a write happened
    stamp = lock.readLock();            // someone wrote — fall back to real lock
    try { value = data; } finally { lock.unlockRead(stamp); }
}
return value;
```

**When to use StampedLock**: Read-heavy workloads where you want optimistic reads. Cannot be used as a Condition. Non-reentrant.

---

### Decision Guide

```
Is the critical section short and simple?
  → synchronized (easiest, JVM can optimize)

Do you need tryLock / timeout / interruptible acquire?
  → ReentrantLock

Do you have many reads and few writes?
  → ReentrantReadWriteLock (if high contention)
  → StampedLock (if you want optimistic reads and no reentrancy needed)
```

---

## 3. CountDownLatch

**One-shot barrier**: a counter starting at N, decremented by `countDown()`, and `await()` blocks until it reaches 0. Cannot be reset.

```java
// Pattern 1: Main thread waits for N workers to finish
CountDownLatch latch = new CountDownLatch(3);

for (int i = 0; i < 3; i++) {
    int workerId = i;
    new Thread(() -> {
        try {
            doWork(workerId);
        } finally {
            latch.countDown(); // always in finally
        }
    }).start();
}

latch.await();                           // blocks until count == 0
latch.await(5, TimeUnit.SECONDS);       // with timeout (production best practice)
System.out.println("All workers done");
```

```java
// Pattern 2: Starting gun — workers wait for signal to begin simultaneously
CountDownLatch startGun = new CountDownLatch(1);

for (int i = 0; i < 10; i++) {
    new Thread(() -> {
        try {
            startGun.await();   // all threads block here
            doWork();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }).start();
}

Thread.sleep(100); // let all threads reach await()
startGun.countDown(); // fires once → all 10 start simultaneously
```

**Internal implementation**: Uses AQS (AbstractQueuedSynchronizer).
- `countDown()` → CAS decrement; if 0, release all waiting threads
- `await()` → shared acquisition (succeeds only when count == 0)

**vs CyclicBarrier**: CountDownLatch is one-shot, threads don't need to wait for each other — one thread can call `countDown()` many times or different threads can each call it once. CyclicBarrier is reusable and all N threads must arrive.

---

## 4. CyclicBarrier

**Reusable barrier**: N threads each call `await()` and all block until all N have arrived. Then all are released together. Optional `Runnable` action runs once before release.

```java
// 3 threads must all reach the checkpoint before any continues
CyclicBarrier barrier = new CyclicBarrier(3, () -> {
    System.out.println("All threads reached checkpoint — running merge step");
    // This runs once on the barrier thread (last to arrive) before release
});

for (int i = 0; i < 3; i++) {
    int phase = i;
    new Thread(() -> {
        try {
            for (int round = 0; round < 5; round++) {
                doPhaseWork(phase, round);
                barrier.await();    // wait for all 3 threads
                // all 3 resume here together, every round
            }
        } catch (InterruptedException | BrokenBarrierException e) {
            Thread.currentThread().interrupt();
        }
    }).start();
}
```

**BrokenBarrierException**: thrown when:
- Another thread waiting at the barrier was interrupted
- The barrier was reset while threads were waiting
- The barrier action threw an exception

```java
// Reset use case — if a thread fails, reset and retry
try {
    barrier.await();
} catch (BrokenBarrierException e) {
    barrier.reset(); // resets to initial state
}
```

**Parallel matrix multiplication example** (classic use case):
```java
// Each thread computes one row of result matrix
// After computing their row, all wait at barrier
// Barrier action validates partial results
// Threads then compute next row using validated partial results
CyclicBarrier rowBarrier = new CyclicBarrier(numThreads, () -> validatePartialResult());
```

**CyclicBarrier vs CountDownLatch**:

| | CountDownLatch | CyclicBarrier |
|--|---------------|---------------|
| Reusable | No | Yes |
| Who calls action | Multiple callers `countDown()` | All N must call `await()` |
| Barrier action | No | Yes (optional Runnable) |
| Exception on thread failure | No | `BrokenBarrierException` propagates |
| Use case | Wait for N tasks to complete | Sync N threads at checkpoints |

---

## 5. Semaphore

**Controls access to N permits** — like a parking lot with N spaces. `acquire()` takes a permit (blocks if none available), `release()` returns one.

```java
// Limit concurrent DB connections to 10
Semaphore dbPool = new Semaphore(10);

public Result query(String sql) throws InterruptedException {
    dbPool.acquire();       // blocks if 10 connections in use
    try {
        return db.execute(sql);
    } finally {
        dbPool.release();   // always release in finally
    }
}

// Non-blocking attempt
if (dbPool.tryAcquire()) {
    try { return db.execute(sql); }
    finally { dbPool.release(); }
} else {
    return cachedResult(); // fall back
}

// Try with timeout
if (dbPool.tryAcquire(200, TimeUnit.MILLISECONDS)) { ... }
```

**Fair vs unfair semaphore**:
```java
Semaphore fair   = new Semaphore(5, true);  // FIFO — no starvation, lower throughput
Semaphore unfair = new Semaphore(5, false); // default — higher throughput, starvation possible
```

**Acquire multiple permits at once**:
```java
// Acquire 3 permits atomically (e.g., need 3 GPU cores)
semaphore.acquire(3);
try { runGPUJob(); }
finally { semaphore.release(3); }
```

**Semaphore as mutex** (binary semaphore):
```java
Semaphore mutex = new Semaphore(1);
mutex.acquire();
try { criticalSection(); }
finally { mutex.release(); }
// Unlike synchronized, can be released by a DIFFERENT thread — useful for handoff
```

**GuardDuty connection**: Rate-limiting DynamoDB writes from a Spark executor — `Semaphore(maxConcurrentWrites)` prevents throttling more precisely than just batching.

---

## 6. Phaser

**Flexible, reusable barrier** that supports dynamic registration/deregistration of parties. Think of it as a CyclicBarrier you can resize at runtime.

```java
Phaser phaser = new Phaser(1); // register "main" thread

for (int i = 0; i < 3; i++) {
    phaser.register(); // dynamically register each worker
    new Thread(() -> {
        try {
            // Phase 0
            doPhase0Work();
            phaser.arriveAndAwaitAdvance(); // wait for all to finish phase 0

            // Phase 1
            doPhase1Work();
            phaser.arriveAndAwaitAdvance(); // wait for all to finish phase 1

            phaser.arriveAndDeregister(); // done — remove from count
        } catch (Exception e) { Thread.currentThread().interrupt(); }
    }).start();
}

phaser.arriveAndAwaitAdvance(); // main participates in phase 0
phaser.arriveAndAwaitAdvance(); // main participates in phase 1
phaser.arriveAndDeregister();   // main deregisters
```

**When Phaser > CyclicBarrier**:
- Dynamic party count (threads can join or leave mid-execution)
- You need phase numbers (Phaser tracks current phase number)
- You want to override `onAdvance()` to run code between phases or to terminate

```java
Phaser phaser = new Phaser(workers) {
    @Override
    protected boolean onAdvance(int phase, int registeredParties) {
        System.out.println("Phase " + phase + " complete");
        return phase >= 4; // return true to terminate after phase 4
    }
};
```

---

## 7. ExecutorService and ThreadPoolExecutor

### Executors factory methods (quick start)

```java
// Fixed thread pool — good for CPU-bound tasks
ExecutorService fixed = Executors.newFixedThreadPool(4);

// Cached — creates threads as needed, reuses idle ones (60s keep-alive)
// Good for short-lived async tasks. DANGER: unbounded — can create millions of threads
ExecutorService cached = Executors.newCachedThreadPool();

// Single thread — tasks execute sequentially, preserves order
ExecutorService single = Executors.newSingleThreadExecutor();

// Work-stealing pool (uses ForkJoinPool under the hood)
ExecutorService workStealing = Executors.newWorkStealingPool();
```

**ALWAYS shut down executors**:
```java
executor.shutdown();                          // no new tasks, finish existing
executor.awaitTermination(10, TimeUnit.SECONDS);
executor.shutdownNow();                       // interrupt running tasks
```

---

### ThreadPoolExecutor — full control

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    4,                                    // corePoolSize   — always alive
    16,                                   // maximumPoolSize — created under load
    60L, TimeUnit.SECONDS,               // keepAliveTime  — idle extra threads die after
    new LinkedBlockingQueue<>(1000),     // workQueue      — bounded queue
    new ThreadFactory() { ... },         // optional custom thread factory
    new ThreadPoolExecutor.CallerRunsPolicy() // rejection policy
);
```

**Thread lifecycle**:
```
Task submitted
  → If active threads < corePoolSize: create new thread
  → Else if queue not full: add to queue
  → Else if active threads < maximumPoolSize: create new thread
  → Else: RejectedExecutionHandler fires
```

**Rejection policies**:

| Policy | Behavior |
|--------|----------|
| `AbortPolicy` (default) | Throws `RejectedExecutionException` |
| `CallerRunsPolicy` | Caller thread runs the task — natural backpressure |
| `DiscardPolicy` | Silently drops the task |
| `DiscardOldestPolicy` | Drops the oldest queued task, retries |

**Submitting tasks and getting results**:
```java
// submit Callable — returns Future
Future<String> future = executor.submit(() -> {
    return fetchData(); // can throw checked exceptions
});

String result = future.get();                    // blocks
String result = future.get(2, TimeUnit.SECONDS); // with timeout
future.cancel(true);                             // interrupt if running

// submit Runnable
executor.submit(() -> doWork()); // Future<?>  — result is null

// execute Runnable (no return value, no Future)
executor.execute(() -> doWork());

// invokeAll — submit all, wait for all
List<Callable<String>> tasks = List.of(() -> "a", () -> "b", () -> "c");
List<Future<String>> futures = executor.invokeAll(tasks); // blocks until all done

// invokeAny — submit all, return first to finish (cancels rest)
String first = executor.invokeAny(tasks);
```

---

## 8. ScheduledExecutorService

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

// Run once after 5 seconds
scheduler.schedule(() -> runHealthCheck(), 5, TimeUnit.SECONDS);

// Run every 10 seconds, first run after 0 seconds
// FIXED RATE: starts at fixed intervals regardless of task duration
// If task takes 15s and rate is 10s → next run starts immediately after
ScheduledFuture<?> fixed = scheduler.scheduleAtFixedRate(
    () -> sendHeartbeat(), 0, 10, TimeUnit.SECONDS);

// FIXED DELAY: waits 10s AFTER each task completes before starting next
// If task takes 15s and delay is 10s → total cycle is 25s
ScheduledFuture<?> delayed = scheduler.scheduleWithFixedDelay(
    () -> pollQueue(), 0, 10, TimeUnit.SECONDS);

// Cancel a scheduled task
fixed.cancel(false); // false = don't interrupt if running, true = interrupt
```

**When to use which**:
- `scheduleAtFixedRate`: heartbeats, metrics emission, polling at exact intervals
- `scheduleWithFixedDelay`: tasks that should rest between runs (avoid overlapping)

---

## 9. ForkJoinPool and Work Stealing

**Problem**: Recursive divide-and-conquer tasks (mergesort, tree traversal). A fixed thread pool wastes threads waiting for subtasks.

**Work stealing**: Each thread has its own deque of tasks. When a thread is idle, it "steals" from the tail of another thread's deque. Fork adds to the front; steal takes from the back → minimal contention.

```java
// RecursiveTask returns a value; RecursiveAction returns void
class SumTask extends RecursiveTask<Long> {
    private final long[] array;
    private final int lo, hi;
    static final int THRESHOLD = 1000;

    SumTask(long[] array, int lo, int hi) {
        this.array = array; this.lo = lo; this.hi = hi;
    }

    @Override
    protected Long compute() {
        if (hi - lo <= THRESHOLD) {
            // Base case: compute directly
            long sum = 0;
            for (int i = lo; i < hi; i++) sum += array[i];
            return sum;
        }

        int mid = (lo + hi) / 2;
        SumTask left  = new SumTask(array, lo, mid);
        SumTask right = new SumTask(array, mid, hi);

        left.fork();             // async: push left to this thread's deque
        long rightResult = right.compute(); // compute right in current thread
        long leftResult  = left.join();     // wait for left (may steal from others)

        return leftResult + rightResult;
    }
}

ForkJoinPool pool = new ForkJoinPool(); // default: Runtime.availableProcessors()
long total = pool.invoke(new SumTask(array, 0, array.length));
```

**`commonPool()`**: Shared ForkJoinPool used by parallel streams and CompletableFuture by default.
```java
ForkJoinPool.commonPool().getParallelism(); // CPU cores - 1

// Use custom pool for blocking tasks (don't pollute commonPool)
ForkJoinPool customPool = new ForkJoinPool(4);
customPool.submit(() -> {
    return Arrays.stream(largeArray).parallel().sum();
}).get();
```

---

## 10. CompletableFuture

**Async composition**: chain non-blocking async operations, handle errors, combine multiple futures.

### Basic async operations
```java
// supplyAsync runs in ForkJoinPool.commonPool() by default
CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> fetchUser(id));

// Provide custom executor for blocking I/O (don't use commonPool for blocking)
ExecutorService ioPool = Executors.newCachedThreadPool();
CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> fetchUser(id), ioPool);

// runAsync — no return value
CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> sendEmail());
```

### Chaining (each step runs on the thread that completed the previous)
```java
CompletableFuture<String> result = CompletableFuture
    .supplyAsync(() -> fetchUserId())            // async: returns long
    .thenApply(id -> "user-" + id)               // sync transform (like map)
    .thenApplyAsync(name -> enrich(name), pool)  // async transform on pool
    .thenCompose(name -> fetchProfile(name))     // flatMap — returns CF<Profile>
    .thenApply(profile -> profile.getDisplayName());

// thenApply   = map  (sync, T -> U)
// thenCompose = flatMap (async, T -> CompletableFuture<U>)
```

### Combining futures
```java
// allOf — wait for ALL to complete (returns CF<Void>)
CompletableFuture<Void> all = CompletableFuture.allOf(cf1, cf2, cf3);
all.join(); // block
// To collect results:
List<String> results = Stream.of(cf1, cf2, cf3)
    .map(CompletableFuture::join)
    .collect(Collectors.toList());

// anyOf — return value of FIRST to complete
CompletableFuture<Object> first = CompletableFuture.anyOf(cf1, cf2, cf3);

// thenCombine — combine TWO futures when both complete
cf1.thenCombine(cf2, (result1, result2) -> result1 + result2);
```

### Error handling
```java
CompletableFuture<String> robust = CompletableFuture
    .supplyAsync(() -> callExternalService())
    .exceptionally(ex -> {                    // like catch — returns fallback
        log.error("Call failed", ex);
        return "fallback-value";
    })
    .handle((result, ex) -> {                 // like try-catch-finally — always runs
        if (ex != null) return "error";
        return result.toUpperCase();
    })
    .whenComplete((result, ex) -> {           // side effect — doesn't change value
        log.info("Completed: result={}, error={}", result, ex);
    });
```

### GuardDuty connection: async DynamoDB pattern
```java
// 10× latency reduction — batch writes without blocking Spark executor threads
List<CompletableFuture<UpdateItemResponse>> futures = events.stream()
    .map(event -> CompletableFuture.supplyAsync(
        () -> dynamoDB.updateItem(buildRequest(event)), ddbExecutor))
    .collect(Collectors.toList());

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
// All 1000 DDB writes in-flight simultaneously → latency = max(individual), not sum
```

---

## 11. Virtual Threads (Java 21)

### The problem with platform threads
```
Platform thread = OS thread
  1 GB stack on most OSes
  Context switch = expensive kernel operation (~10μs)
  10,000 threads → 10GB RAM + massive scheduler overhead
  Blocking I/O → thread blocked = wasted OS thread
```

### Virtual threads — Project Loom
```java
// Old way: thread per request (doesn't scale for blocking I/O)
ExecutorService threadPerRequest = Executors.newFixedThreadPool(200);

// New way: virtual thread per request (scales to millions)
ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();

// Or directly:
Thread vt = Thread.ofVirtual().start(() -> handleRequest(req));

// Or with builder:
Thread.Builder builder = Thread.ofVirtual().name("worker-", 0);
Thread t = builder.start(() -> doWork());
```

**How they work**:
```
Virtual thread = lightweight user-space thread (~1KB stack, heap-allocated)
Carrier thread = platform thread that runs virtual threads (small pool, 1 per CPU)

When virtual thread blocks on I/O:
  → Virtual thread is "unmounted" from carrier thread (state saved to heap)
  → Carrier thread is free to run another virtual thread
  → When I/O completes, virtual thread is "remounted" on any available carrier

Result: 1 carrier thread can serve 10,000+ concurrent blocking operations
```

```java
// Before Java 21 — reactive/callback hell to avoid blocking
CompletableFuture.supplyAsync(() -> db.query(sql))
    .thenCompose(result -> CompletableFuture.supplyAsync(() -> cache.set(result)))
    .thenAccept(r -> respond(r));

// With virtual threads — write blocking code, get non-blocking behavior
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> {
        var result = db.query(sql);      // blocks virtual thread, not OS thread
        cache.set(result);               // blocks virtual thread, not OS thread
        respond(result);                 // sequential, readable, correct
    });
}
```

### Structured Concurrency (Java 21 preview)
```java
// Ensures child threads don't outlive their scope — prevents thread leaks
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Future<String> user    = scope.fork(() -> fetchUser(id));
    Future<String> account = scope.fork(() -> fetchAccount(id));

    scope.join();           // wait for both
    scope.throwIfFailed();  // propagate failure

    return new Response(user.resultNow(), account.resultNow());
}
// Scope closes → any still-running forks are cancelled automatically
```

### When to use virtual threads vs platform threads

| Scenario | Use |
|----------|-----|
| Blocking I/O (DB calls, HTTP, file) | Virtual threads ✅ |
| CPU-intensive computation | Platform threads (ForkJoinPool) |
| High concurrency web servers | Virtual threads ✅ |
| Small fixed thread count | Platform threads fine |
| Need thread-local caching (e.g., ThreadLocal pools) | Platform threads (ThreadLocal not recommended with VTs) |

### Virtual thread gotchas
```java
// ❌ Don't pin virtual threads — they can't unmount while pinned
synchronized (lock) {
    db.query(sql); // blocking inside synchronized pins the carrier thread!
}
// ✅ Use ReentrantLock instead
lock.lock();
try { db.query(sql); } finally { lock.unlock(); }

// ❌ Don't pool virtual threads — they're cheap, just create new ones
Executors.newFixedThreadPool(100); // ← defeats the purpose for I/O work

// ❌ ThreadLocal works but can cause memory leaks with millions of VTs
// Use ScopedValue (Java 21) instead for passing context
ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();
ScopedValue.where(CURRENT_USER, user).run(() -> handleRequest());
```

---

## 12. Concurrent Collections

### ConcurrentHashMap
```java
ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();

// Thread-safe operations (no external sync needed)
map.put("key", 1);
map.get("key");
map.putIfAbsent("key", 1);         // atomic: only puts if key absent
map.replace("key", 1, 2);         // atomic: only replaces if current value is 1
map.computeIfAbsent("key", k -> expensiveCompute(k)); // atomic + lazy init

// Atomic increment (common pattern)
map.merge("key", 1, Integer::sum); // atomic: add 1 to existing, or put 1 if absent
map.compute("key", (k, v) -> v == null ? 1 : v + 1); // same

// Bulk operations (Java 8+) — run in parallel using ForkJoinPool
map.forEach(1, (k, v) -> process(k, v));     // 1 = parallelism threshold
map.reduce(1, (k,v) -> v, Integer::sum);     // parallel reduce
```

**How it works**: 16 segments in Java 7 → per-bucket CAS + synchronized in Java 8. Reads are lock-free (volatile). Writes use CAS on empty buckets or synchronized on bucket head for collisions. Full-table operations (size, isEmpty) scan all segments.

### BlockingQueue implementations

```java
// LinkedBlockingQueue — optionally bounded, separate head/tail locks
BlockingQueue<Task> queue = new LinkedBlockingQueue<>(1000);
queue.put(task);       // blocks if full
queue.take();          // blocks if empty
queue.offer(task, 100, TimeUnit.MILLISECONDS); // with timeout
queue.poll(100, TimeUnit.MILLISECONDS);

// ArrayBlockingQueue — bounded, single lock, more predictable latency
BlockingQueue<Task> bounded = new ArrayBlockingQueue<>(100);

// PriorityBlockingQueue — unbounded, sorted by natural order or Comparator
BlockingQueue<Task> priority = new PriorityBlockingQueue<>(11, comparator);

// DelayQueue — elements become available only after their delay expires
BlockingQueue<DelayedTask> delayed = new DelayQueue<>();
// Element must implement Delayed interface with getDelay() method

// SynchronousQueue — zero capacity, handoff only (put blocks until take, vice versa)
// Used inside Executors.newCachedThreadPool()
BlockingQueue<Runnable> handoff = new SynchronousQueue<>();
```

### CopyOnWriteArrayList
```java
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
// Reads: lock-free, iterate over snapshot
// Writes: copy entire array → update copy → swap reference (expensive!)
list.add("item");     // O(n) — copies the entire array
list.get(0);          // O(1) — no lock, reads current snapshot
// Use when: reads >> writes, iteration without ConcurrentModificationException
// Don't use when: large list with frequent writes
```

### ConcurrentLinkedQueue vs LinkedBlockingQueue
```java
ConcurrentLinkedQueue<Task> nonBlocking = new ConcurrentLinkedQueue<>();
// Lock-free CAS, poll() returns null if empty (non-blocking)
// Use for: high-throughput work queues where caller handles empty queue

LinkedBlockingQueue<Task> blocking = new LinkedBlockingQueue<>();
// Blocking, separate head/tail locks
// Use for: producer-consumer where blocking is acceptable
```

---

## 13. Atomic Classes and CAS

### AtomicInteger / AtomicLong / AtomicReference

```java
AtomicInteger counter = new AtomicInteger(0);

counter.get();                    // read
counter.set(10);                  // write
counter.getAndIncrement();        // read then increment (returns old)
counter.incrementAndGet();        // increment then read (returns new)
counter.addAndGet(5);             // add 5, return new
counter.compareAndSet(5, 10);     // CAS: if current==5, set to 10, return true/false

// CAS retry loop (standard pattern)
int current, next;
do {
    current = counter.get();
    next = transform(current);
} while (!counter.compareAndSet(current, next));
```

**AtomicReference** — for arbitrary object references:
```java
AtomicReference<Node> head = new AtomicReference<>(null);

// Lock-free stack push
Node newNode = new Node(value);
do {
    newNode.next = head.get();
} while (!head.compareAndSet(newNode.next, newNode));
```

### LongAdder / LongAccumulator (Java 8+)

```java
// AtomicLong: all threads CAS the same memory location → high contention under load
AtomicLong counter = new AtomicLong(); // bottleneck at high thread count

// LongAdder: each thread has its own cell, sum on read → low contention
LongAdder adder = new LongAdder();
adder.increment();
adder.add(5);
long total = adder.sum();   // adds all cells — not perfectly consistent
adder.reset();              // sets to 0 (not atomic with sum!)

// Use LongAdder over AtomicLong when: many threads update, fewer threads read
```

---

## 14. ThreadLocal

**Per-thread storage**: each thread gets its own copy of the variable. No synchronization needed.

```java
// Typical use: database connections, SimpleDateFormat (not thread-safe), user context
ThreadLocal<SimpleDateFormat> dateFormat = ThreadLocal.withInitial(
    () -> new SimpleDateFormat("yyyy-MM-dd")
);

// In any thread:
String formatted = dateFormat.get().format(new Date()); // each thread gets its own instance

// CRITICAL: always remove in finally for thread pool threads
// Thread pool reuses threads — without remove(), old value leaks to next task
try {
    dateFormat.set(customFormat);
    process();
} finally {
    dateFormat.remove(); // ← mandatory in thread pools
}
```

**InheritableThreadLocal** — child threads inherit parent's value:
```java
InheritableThreadLocal<String> requestId = new InheritableThreadLocal<>();
requestId.set("req-123");
new Thread(() -> {
    System.out.println(requestId.get()); // "req-123" — inherited from parent
}).start();
```

**With virtual threads (Java 21)**: ThreadLocal still works but millions of VTs each with their own TL value = memory pressure. Use `ScopedValue` instead.

---

## 15. Common Patterns and Pitfalls

### Double-Checked Locking (Singleton)
```java
// BROKEN — without volatile, second thread may see partially-constructed object
public class Singleton {
    private static Singleton instance;
    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) {
                    instance = new Singleton(); // not atomic: alloc → init → assign
                }
            }
        }
        return instance;
    }
}

// CORRECT — volatile prevents reordering of alloc/init/assign
public class Singleton {
    private static volatile Singleton instance; // volatile is required
    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) {
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}

// SIMPLER — use initialization-on-demand holder (no volatile needed)
public class Singleton {
    private static class Holder {
        static final Singleton INSTANCE = new Singleton(); // JVM guarantees safe init
    }
    public static Singleton getInstance() { return Holder.INSTANCE; }
}
```

### Deadlock
```java
// Classic deadlock: two threads, lock A and lock B, opposite order
// Thread 1: lock(A) → lock(B)
// Thread 2: lock(B) → lock(A)

// Prevention: always acquire locks in the same order
// Or: use tryLock with timeout and back off on failure
if (lock1.tryLock(100, MILLISECONDS)) {
    try {
        if (lock2.tryLock(100, MILLISECONDS)) {
            try { doWork(); }
            finally { lock2.unlock(); }
        }
    } finally { lock1.unlock(); }
}
```

### Spurious Wakeup — always use `while`, never `if`
```java
// BROKEN
synchronized (lock) {
    if (queue.isEmpty()) lock.wait(); // spurious wakeup → proceeds with empty queue!
    process(queue.poll());
}

// CORRECT
synchronized (lock) {
    while (queue.isEmpty()) lock.wait(); // re-check condition after wakeup
    process(queue.poll());
}
```

### Thread interruption — propagate or restore
```java
// Option 1: propagate (preferred in library code)
public void doWork() throws InterruptedException {
    someBlockingCall(); // throws InterruptedException — propagate it
}

// Option 2: restore the flag (when you can't propagate)
try {
    someBlockingCall();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // restore interrupted status
    // then handle gracefully
}

// Option 3: check interrupted status in loops
while (!Thread.currentThread().isInterrupted()) {
    doWork();
}
```

### Executor shutdown pattern
```java
executor.shutdown();
try {
    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            log.error("Executor did not terminate");
        }
    }
} catch (InterruptedException e) {
    executor.shutdownNow();
    Thread.currentThread().interrupt();
}
```

---

## 16. Quick Comparison Tables

### Synchronization primitives

| Tool | Permits | Reusable | Timeout | Use Case |
|------|---------|----------|---------|----------|
| `synchronized` | 1 | Yes | No | Simple mutual exclusion |
| `ReentrantLock` | 1 | Yes | Yes | When you need tryLock/timeout |
| `Semaphore` | N | Yes | Yes | Resource pool, rate limiting |
| `CountDownLatch` | — | No | Yes | Wait for N tasks to complete |
| `CyclicBarrier` | — | Yes | Yes | N threads sync at checkpoints |
| `Phaser` | Dynamic | Yes | Yes | Multi-phase with dynamic parties |

### Executor types

| Factory | Threads | Queue | Best For |
|---------|---------|-------|----------|
| `newFixedThreadPool(n)` | Fixed n | Unbounded LinkedBQ | CPU-bound work |
| `newCachedThreadPool()` | Unbounded | SynchronousQueue | Short async tasks |
| `newSingleThreadExecutor()` | 1 | Unbounded LinkedBQ | Sequential tasks |
| `newWorkStealingPool()` | CPU cores | Per-thread deque | Recursive tasks |
| `newVirtualThreadPerTaskExecutor()` | Virtual | None | Blocking I/O |

### When to use what

```
High contention counter                → LongAdder
Low-to-medium contention counter       → AtomicInteger
Simple mutual exclusion                → synchronized
Need tryLock / timeout                 → ReentrantLock
Many readers, few writers              → ReentrantReadWriteLock
Optimistic reads, rare writes          → StampedLock
Wait for N tasks once                  → CountDownLatch
N threads sync repeatedly              → CyclicBarrier
Limit concurrent resource access       → Semaphore
Multi-phase with dynamic registration  → Phaser
Async I/O composition                  → CompletableFuture
Blocking I/O at high concurrency       → Virtual Threads
CPU parallel computation               → ForkJoinPool / parallel streams
Per-thread state (thread pool)         → ThreadLocal (+ remove in finally)
Per-thread state (virtual threads)     → ScopedValue
Fan-out + collect results + fail-fast  → StructuredTaskScope
Fire-and-forget / long-lived pool      → ExecutorService
Producer-consumer with backpressure    → ArrayBlockingQueue + Virtual Threads
```

---

## Producer-Consumer with Virtual Threads

### ArrayBlockingQueue vs LinkedBlockingQueue

| | ArrayBlockingQueue | LinkedBlockingQueue |
|---|---|---|
| **Backing store** | Single array, pre-allocated | Node per element, heap-allocated |
| **Lock** | One lock (shared put/take) | Two locks (separate put/take) |
| **Capacity** | Always bounded (required) | Optional bound (default `Integer.MAX_VALUE`) |
| **Memory** | Fixed, predictable | Grows with queue size + GC pressure |
| **Throughput** | Lower under high contention | Higher when producers/consumers rarely collide |
| **Latency** | More predictable | Varies (GC pauses from node allocation) |

**Default choice: `ArrayBlockingQueue` with an explicit bound.**
Switch to `LinkedBlockingQueue` only after profiling shows single-lock contention is a real bottleneck.

### The Bound

The bound is the maximum number of items the queue can hold. When full, `put()` blocks the producer — this is backpressure.

```
Rule of thumb: bound = consumer throughput × acceptable latency

Consumer processes 1,000 items/sec, OK with 2 sec of queued work
→ bound = 1,000 × 2 = 2,000
```

Too small → producer blocks too often, CPU idle
Too large → too much work queued, high latency, more memory

### Full Example — Poison Pill Shutdown

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ProducerConsumer {

    record Task(int id, String data) {}

    static final int QUEUE_BOUND        = 100;
    static final int NUM_PRODUCERS      = 2;
    static final int NUM_CONSUMERS      = 4;
    static final int TASKS_PER_PRODUCER = 10;

    // Poison pill — signals consumers to stop
    static final Task POISON = new Task(-1, "STOP");

    public static void main(String[] args) throws InterruptedException {

        BlockingQueue<Task> queue = new ArrayBlockingQueue<>(QUEUE_BOUND);
        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);
        CountDownLatch producersDone = new CountDownLatch(NUM_PRODUCERS);

        // --- Producers ---
        for (int p = 0; p < NUM_PRODUCERS; p++) {
            int producerId = p;
            Thread.ofVirtual().name("producer-" + p).start(() -> {
                try {
                    for (int i = 0; i < TASKS_PER_PRODUCER; i++) {
                        Task task = new Task(produced.incrementAndGet(), "data-from-producer-" + producerId);
                        queue.put(task);   // blocks if queue is full
                        System.out.printf("[%s] produced task %d%n",
                                Thread.currentThread().getName(), task.id());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            });
        }

        // --- Poison pill dispatcher ---
        // Sends one POISON per consumer after all producers finish
        Thread.ofVirtual().name("dispatcher").start(() -> {
            try {
                producersDone.await();   // wait for all producers to finish
                for (int i = 0; i < NUM_CONSUMERS; i++) {
                    queue.put(POISON);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // --- Consumers ---
        CountDownLatch allDone = new CountDownLatch(NUM_CONSUMERS);

        for (int c = 0; c < NUM_CONSUMERS; c++) {
            Thread.ofVirtual().name("consumer-" + c).start(() -> {
                try {
                    while (true) {
                        Task task = queue.take();  // blocks if queue is empty

                        if (task == POISON) {      // reference equality — intentional
                            System.out.printf("[%s] received poison pill, stopping%n",
                                    Thread.currentThread().getName());
                            break;
                        }

                        process(task);
                        consumed.incrementAndGet();
                        System.out.printf("[%s] consumed task %d%n",
                                Thread.currentThread().getName(), task.id());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }

        allDone.await();  // main thread waits for all consumers to finish
        System.out.printf("%nDone. Produced: %d  Consumed: %d%n",
                produced.get(), consumed.get());
    }

    static void process(Task task) throws InterruptedException {
        Thread.sleep(10); // simulate work
    }
}
```

### Key Design Decisions

**One POISON pill per consumer** — each consumer needs its own pill; once taken it's gone:
```
queue: [POISON][POISON][POISON][POISON]  ← one per consumer
```

**`==` not `.equals()` for poison check** — reference equality avoids accidentally matching a real task.

**`put()`/`take()` are virtual-thread friendly** — they block the virtual thread, not the carrier thread, so no OS threads are wasted waiting.

**`CountDownLatch` for producer coordination** — use `producersDone.countDown()` in each producer's `finally` block so the dispatcher sends poison pills exactly when all producers are done.
