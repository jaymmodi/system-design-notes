# Probabilistic Data Structures

## What & Why
Trade exact answers for dramatically reduced memory usage. Perfect when "approximately right" is fine (analytics, monitoring, trending).

---

## 1. HyperLogLog — Count Distinct (Cardinality)

**Problem**: Count unique visitors to a website. Exact counting requires storing every user ID.
- 100M unique users × 8 bytes = 800MB just for one counter
- HyperLogLog gives ~2% error with **12KB of memory**

### How HLL Works
Uses the intuition: the more unique elements you've seen, the more likely you are to have seen a hash with many leading zeros.

```
Hash "user:1" → 0001010101...  (3 leading zeros → suggests ~2^3 = 8 elements seen)
Hash "user:2" → 0000001010...  (6 leading zeros → suggests ~2^6 = 64 elements)
...the more elements, the higher the max leading zeros
```

```java
import com.clearspring.analytics.stream.cardinality.HyperLogLog;
import com.clearspring.analytics.stream.cardinality.HyperLogLogPlus;

public class UniqueVisitorCounter {
    // Standard error: 1.04 / sqrt(2^b) — higher b = more accurate, more memory
    // b=10: 12KB memory, ~1.6% error
    // b=14: 192KB memory, ~0.4% error
    private final HyperLogLog hll = new HyperLogLog(14);

    public void recordVisit(String userId) {
        hll.offer(userId); // O(1), very fast
    }

    public long estimateUniqueVisitors() {
        return hll.cardinality(); // returns estimate ± ~0.4%
    }

    // Redis HyperLogLog
    public void recordVisitRedis(String userId) {
        // Redis PFADD — built-in HLL, 12KB max per key
        redis.execute("PFADD", "daily_visitors:2024-01-15", userId);
    }

    public long estimateRedis() {
        return (Long) redis.execute("PFCOUNT", "daily_visitors:2024-01-15");
    }

    // Merging HLLs — great for rolling windows
    public long estimateWeekly() {
        // PFMERGE merges multiple HLLs into one — then PFCOUNT
        redis.execute("PFMERGE", "weekly_visitors", "daily_visitors:2024-01-09",
            "daily_visitors:2024-01-10", "daily_visitors:2024-01-11" /* ... */);
        return (Long) redis.execute("PFCOUNT", "weekly_visitors");
    }
}
```

### From Scratch (Educational)

```java
public class SimpleHyperLogLog {
    private final int m; // number of registers = 2^b
    private final byte[] registers;
    private final double alphaMM;

    public SimpleHyperLogLog(int b) { // b = log2(m)
        this.m = 1 << b;
        this.registers = new byte[m];
        this.alphaMM = getAlphaMM(m);
    }

    public void add(String element) {
        long hash = murmurHash64(element);

        // Use first b bits to pick a register
        int registerIndex = (int) (hash >>> (64 - Integer.numberOfTrailingZeros(m)));

        // Count leading zeros in remaining bits + 1
        long remaining = hash << Integer.numberOfTrailingZeros(m);
        byte leadingZeros = (byte) (Long.numberOfLeadingZeros(remaining) + 1);

        // Update register with maximum
        registers[registerIndex] = (byte) Math.max(registers[registerIndex], leadingZeros);
    }

    public long estimate() {
        // Harmonic mean of 2^(-register[i]) values
        double sum = 0;
        for (byte reg : registers) {
            sum += Math.pow(2, -reg);
        }
        double estimate = alphaMM * m * m / sum;

        // Small range correction
        if (estimate < 2.5 * m) {
            long zeros = countZeroRegisters();
            if (zeros > 0) estimate = m * Math.log((double) m / zeros);
        }
        return Math.round(estimate);
    }

    private long countZeroRegisters() {
        long count = 0;
        for (byte reg : registers) if (reg == 0) count++;
        return count;
    }
}
```

**Use Cases**: Unique visitors, unique search queries, distinct products viewed, A/B test unique participants.

---

## 2. Count-Min Sketch — Frequency Estimation

**Problem**: Track how often each URL/product/hashtag appears in a stream of millions.
- Exact: HashMap → memory proportional to number of distinct items
- Count-Min Sketch: fixed memory, approximate frequency, never underestimates

### How CMS Works
- 2D array: d rows (hash functions) × w columns (counters)
- **Add**: for each row, hash element → increment that counter
- **Query**: for each row, hash element → read counter → return MIN of all d counters

```java
public class CountMinSketch {
    private final int depth;  // d: number of hash functions
    private final int width;  // w: number of counters per row
    private final long[][] table;
    private final long[] seeds; // different seed per row for independence

    /**
     * @param epsilon  Max relative error (e.g., 0.001 = 0.1%)
     * @param delta    Probability of exceeding epsilon (e.g., 0.99 = 99% confident)
     */
    public CountMinSketch(double epsilon, double delta) {
        // Optimal dimensions:
        this.width = (int) Math.ceil(Math.E / epsilon);     // w = e/ε
        this.depth = (int) Math.ceil(Math.log(1.0 / delta)); // d = ln(1/δ)
        this.table = new long[depth][width];
        this.seeds = new long[depth];
        Random random = new Random(42);
        for (int i = 0; i < depth; i++) seeds[i] = random.nextLong();
        System.out.printf("CMS: %d x %d = %d counters%n", depth, width, depth * width);
    }

    public void add(String item) {
        add(item, 1);
    }

    public void add(String item, long count) {
        for (int i = 0; i < depth; i++) {
            int col = hash(item, seeds[i]);
            table[i][col] += count;
        }
    }

    public long estimate(String item) {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < depth; i++) {
            int col = hash(item, seeds[i]);
            min = Math.min(min, table[i][col]);
        }
        return min; // minimum of all rows = best estimate
    }

    private int hash(String item, long seed) {
        long h = seed;
        for (byte b : item.getBytes()) {
            h = h * 31 + b;
            h ^= h >>> 32;
        }
        return (int) (Math.abs(h) % width);
    }
}
```

### Use Cases

```java
// 1. Trending hashtags
public class TrendingTopics {
    private final CountMinSketch cms = new CountMinSketch(0.001, 0.99);
    private final PriorityQueue<Map.Entry<String, Long>> topK =
        new PriorityQueue<>(Comparator.comparingLong(Map.Entry::getValue));

    public void onTweet(String hashtag) {
        cms.add(hashtag);
        long freq = cms.estimate(hashtag);
        updateTopK(hashtag, freq);
    }

    public List<String> getTop10() {
        return topK.stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .map(Map.Entry::getKey)
            .collect(toList());
    }
}

// 2. DDoS detection — count requests per IP
public class RateLimiter {
    private final CountMinSketch requestCounts = new CountMinSketch(0.01, 0.99);

    public boolean isRateLimited(String ipAddress) {
        requestCounts.add(ipAddress);
        long count = requestCounts.estimate(ipAddress);
        return count > 1000; // block if > 1000 requests/window
    }
}

// 3. Heavy hitter detection in network monitoring
public class NetworkMonitor {
    private final CountMinSketch flowCounts = new CountMinSketch(0.0001, 0.999);

    public void onPacket(String srcIP, String dstIP, int bytes) {
        String flowKey = srcIP + "→" + dstIP;
        flowCounts.add(flowKey, bytes); // track bytes transferred
        if (flowCounts.estimate(flowKey) > 1_000_000_000L) { // 1GB
            alert("Elephant flow detected: " + flowKey);
        }
    }
}
```

---

## 3. MinHash — Set Similarity (Jaccard)

**Problem**: Find near-duplicate documents or similar products out of millions.
- Jaccard similarity = |A ∩ B| / |A ∪ B|
- MinHash estimates this without comparing full sets

```java
public class MinHash {
    private final int numHashFunctions;
    private final long[] seeds;

    public MinHash(int numHashFunctions) {
        this.numHashFunctions = numHashFunctions;
        this.seeds = new long[numHashFunctions];
        Random rand = new Random(42);
        for (int i = 0; i < numHashFunctions; i++) seeds[i] = rand.nextLong();
    }

    // Create a MinHash signature (fingerprint) for a set of tokens
    public long[] signature(Set<String> tokens) {
        long[] sig = new long[numHashFunctions];
        Arrays.fill(sig, Long.MAX_VALUE);

        for (String token : tokens) {
            for (int i = 0; i < numHashFunctions; i++) {
                long h = hash(token, seeds[i]);
                sig[i] = Math.min(sig[i], h); // keep minimum hash value
            }
        }
        return sig;
    }

    // Estimate Jaccard similarity from two signatures
    public double similarity(long[] sig1, long[] sig2) {
        int matches = 0;
        for (int i = 0; i < numHashFunctions; i++) {
            if (sig1[i] == sig2[i]) matches++;
        }
        return (double) matches / numHashFunctions;
        // P(min_h(A) == min_h(B)) = |A ∩ B| / |A ∪ B| = Jaccard(A, B)
    }

    private long hash(String s, long seed) {
        return MurmurHash3.hash64(s.getBytes(), seed);
    }
}

// Find similar products
public class ProductSimilarity {
    private final MinHash minHash = new MinHash(128);

    public List<Product> findSimilar(Product query, List<Product> catalog) {
        long[] querySig = minHash.signature(query.getFeatureTokens());

        return catalog.stream()
            .map(p -> Map.entry(p, minHash.similarity(querySig, minHash.signature(p.getFeatureTokens()))))
            .filter(e -> e.getValue() > 0.5) // > 50% similar
            .sorted(Map.Entry.<Product, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .collect(toList());
    }
}
```

---

## 4. T-Digest — Percentile Estimation

**Problem**: Calculate p99 latency across millions of requests without storing all values.

```java
import com.tdunning.math.stats.TDigest;

public class LatencyTracker {
    private final TDigest digest = TDigest.createDigest(100); // compression factor

    public void recordLatency(double latencyMs) {
        digest.add(latencyMs);
    }

    public double p50() { return digest.quantile(0.5); }
    public double p95() { return digest.quantile(0.95); }
    public double p99() { return digest.quantile(0.99); }
    public double p999() { return digest.quantile(0.999); }

    // Merging across instances
    public LatencyTracker merge(LatencyTracker other) {
        LatencyTracker merged = new LatencyTracker();
        merged.digest.add(this.digest);
        merged.digest.add(other.digest);
        return merged;
    }
}

// Used by Netflix, Prometheus, Elasticsearch for percentile aggregations
```

---

## 5. Skip List — Fast Ordered Operations

Not probabilistic in the same sense, but uses randomization for O(log n) operations on ordered data.

```java
public class SkipList<K extends Comparable<K>, V> {
    private static final int MAX_LEVEL = 16;
    private static final double PROB = 0.5;
    private final Node<K, V> head = new Node<>(null, null, MAX_LEVEL);
    private int level = 1;

    static class Node<K, V> {
        K key; V value;
        Node<K, V>[] next;
        Node(K k, V v, int level) {
            this.key = k; this.value = v;
            this.next = new Node[level];
        }
    }

    public void put(K key, V value) {
        Node<K, V>[] update = new Node[MAX_LEVEL];
        Node<K, V> curr = head;

        for (int i = level - 1; i >= 0; i--) {
            while (curr.next[i] != null && curr.next[i].key.compareTo(key) < 0) {
                curr = curr.next[i];
            }
            update[i] = curr;
        }

        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) update[i] = head;
            level = newLevel;
        }

        Node<K, V> newNode = new Node<>(key, value, newLevel);
        for (int i = 0; i < newLevel; i++) {
            newNode.next[i] = update[i].next[i];
            update[i].next[i] = newNode;
        }
    }

    private int randomLevel() {
        int lvl = 1;
        while (Math.random() < PROB && lvl < MAX_LEVEL) lvl++;
        return lvl;
    }
}
// Redis sorted sets (ZADD, ZRANGE) are implemented using Skip Lists
```

---

## Comparison Summary

| Structure | Problem | Error | Memory |
|-----------|---------|-------|--------|
| Bloom Filter | Is X in set? | FP only | ~10 bits/element |
| HyperLogLog | How many distinct? | ~0.4-2% | 12KB-192KB |
| Count-Min Sketch | How often does X appear? | Overcount only | d×w counters |
| MinHash | How similar are set A and B? | ~1/√k | k hash values × 8 bytes |
| T-Digest | What's the p99? | Higher at extremes | O(compression) |

---

## When to Use

- **HyperLogLog**: Unique visitor counts, distinct values in analytics, A/B test sizing
- **Count-Min Sketch**: Top-K heavy hitters, rate limiting, frequency monitoring
- **MinHash**: Duplicate detection, recommendation systems, document similarity
- **T-Digest**: Latency percentiles in distributed systems (Prometheus uses this)

## When to Avoid

- When exact answers are required (financial transactions, exact counts for billing)
- When dataset is small (just use HashMap/HashSet)
- When the approximation error compounds across many operations

---

## Interview Questions

1. **Redis stores HLL in 12KB — how?** Uses sparse then dense representation. Dense: 16384 registers × 6 bits each = ~12KB
2. **Count-Min Sketch vs HyperLogLog?** CMS = frequency of specific items. HLL = count of distinct items.
3. **How does Kafka track consumer lag with approximate structures?** Uses internal offsets (exact), but metrics aggregation may use approximate counting
4. **What's a heavy hitter and how do you find them?** Item appearing in > 1/k fraction of stream. Use Count-Min Sketch with Space-Saving algorithm for top-K heavy hitters.
