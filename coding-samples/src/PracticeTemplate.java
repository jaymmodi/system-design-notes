import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

// ============================================================
// PRACTICE TEMPLATE — Concurrency Coding Questions
// Compile: javac PracticeTemplate.java && java PracticeTemplate
// ============================================================

public class PracticeTemplate {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Q1: Time-Based KV Store ===");
        TimeBasedKVStore kvStore = new TimeBasedKVStore();
        kvStore.set("foo", "bar", 1);
        kvStore.set("foo", "bar2", 4);
        System.out.println(kvStore.get("foo", 3));   // expected: bar
        System.out.println(kvStore.get("foo", 5));   // expected: bar2
        System.out.println(kvStore.get("foo", 0));   // expected: (empty)

        System.out.println("\n=== Q2: Sliding Window Rate Limiter ===");
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, 1000);
        System.out.println(limiter.tryAcquire("user1")); // true
        System.out.println(limiter.tryAcquire("user1")); // true
        System.out.println(limiter.tryAcquire("user1")); // true
        System.out.println(limiter.tryAcquire("user1")); // false — limit hit

        System.out.println("\n=== Q3: ReadWrite Lock ===");
        ReadWriteLock rwLock = new ReadWriteLock();
        rwLock.lockRead();
        System.out.println("Read lock acquired");
        rwLock.unlockRead();
        rwLock.lockWrite();
        System.out.println("Write lock acquired");
        rwLock.unlockWrite();

        System.out.println("\n=== Q4: LRU Cache ===");
        LRUCache cache = new LRUCache(2);
        cache.put(1, 10);
        cache.put(2, 20);
        System.out.println(cache.get(1));   // expected: 10
        cache.put(3, 30);                   // evicts key 2
        System.out.println(cache.get(2));   // expected: -1
        System.out.println(cache.get(3));   // expected: 30

        System.out.println("\n=== Q5: Lock-Free Counter ===");
        LockFreeCounter counter = new LockFreeCounter();
        System.out.println(counter.increment());            // 1
        System.out.println(counter.incrementIfBelow(3));    // true
        System.out.println(counter.incrementIfBelow(2));    // false — already at 2
    }

    // ----------------------------------------------------------
    // Q1: Thread-Safe Time-Based Key-Value Store
    // set(key, value, timestamp) — stores value at timestamp
    // get(key, timestamp) — returns value with largest ts <= timestamp
    // ----------------------------------------------------------
    static class TimeBasedKVStore {
        private final ConcurrentHashMap<String, TreeMap<Integer, String>> store = new ConcurrentHashMap<>();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        public void set(String key, String value, int timestamp) {
            lock.writeLock().lock();
            try {
                store.computeIfAbsent(key, _ -> new TreeMap<>()).put(timestamp, value);
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

    // ----------------------------------------------------------
    // Q2: Sliding Window Rate Limiter
    // tryAcquire(key) — returns true if under limit, false if throttled
    // max N requests per windowMs per key
    // ----------------------------------------------------------
    static class SlidingWindowRateLimiter {
        // TODO: add fields
//        ConcurrentHashMap<String, Semaphore>
//        Semaphore semaphore = new Semaphore(10);

        public SlidingWindowRateLimiter(int maxRequests, long windowMs) {
            // TODO
//            semaphore.ac
        }

        public boolean tryAcquire(String key) {
            // TODO
            return false;
        }
    }

    // ----------------------------------------------------------
    // Q3: ReadWrite Lock from Scratch
    // Multiple readers OR one writer at a time
    // Bonus: prevent writer starvation
    // ----------------------------------------------------------
    static class ReadWriteLock {
        // TODO: add fields

        public synchronized void lockRead() throws InterruptedException {
            // TODO
        }

        public synchronized void unlockRead() {
            // TODO
        }

        public synchronized void lockWrite() throws InterruptedException {
            // TODO
        }

        public synchronized void unlockWrite() {
            // TODO
        }
    }

    // ----------------------------------------------------------
    // Q4: Thread-Safe LRU Cache
    // get(key) — returns value or -1
    // put(key, value) — evicts LRU when over capacity
    // Both O(1)
    // ----------------------------------------------------------
    public class LRUCache {
        private final Map<Integer, Integer> map;

        public LRUCache(int capacity) {
            // accessOrder=true — moves entry to tail on get() and put()
            this.map = new LinkedHashMap<>(capacity, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                    return size() > capacity;
                }
            };
        }

        public int get(int key) {
            return map.getOrDefault(key, -1);
        }

        public void put(int key, int value) {
            map.put(key, value);
        }
    }

    // ----------------------------------------------------------
    // Q5: CAS-Based Lock-Free Counter
    // increment() — always increments
    // incrementIfBelow(limit) — only increments if current < limit
    // ----------------------------------------------------------
    static class LockFreeCounter {
        // TODO: add fields (hint: AtomicInteger)

        public int increment() {
            // TODO
            return 0;
        }

        public boolean incrementIfBelow(int limit) {
            // TODO
            return false;
        }
    }
}
