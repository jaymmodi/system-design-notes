# Bloom Filters

## What is a Bloom Filter?
A **probabilistic data structure** that tests whether an element is a member of a set. It can say:
- **"Definitely NOT in the set"** — 100% accurate
- **"Probably IN the set"** — might be wrong (false positive)

**Never has false negatives. May have false positives.**

Space-efficient: stores membership info in a compact bit array, not the actual elements.

---

## Why Use Bloom Filters?

| Without Bloom Filter | With Bloom Filter |
|---------------------|-------------------|
| DB lookup for every cache miss check | Check filter first → skip DB if "definitely not present" |
| Expensive existence check | O(k) hash operations on small bit array |
| Need to store all elements | Fixed space, no matter how many elements |

---

## How It Works

1. **Bit array** of M bits, all initialized to 0
2. **K hash functions**, each mapping an element to a position in [0, M)
3. **Insert**: hash element K times → set those K bits to 1
4. **Query**: hash element K times → if ALL K bits are 1 → "probably present"; if ANY bit is 0 → "definitely not present"

```
M=10 bits: [0,0,0,0,0,0,0,0,0,0]

Insert "apple" (k=3 hashes → positions 2,5,7):
       [0,0,1,0,0,1,0,1,0,0]

Insert "banana" (k=3 hashes → positions 1,5,9):
       [0,1,1,0,0,1,0,1,0,1]

Query "apple": hash → 2,5,7 → bits 1,1,1 → PROBABLY PRESENT ✓
Query "mango":  hash → 3,5,6 → bits 0,1,0 → DEFINITELY NOT PRESENT ✓
Query "grape":  hash → 1,5,7 → bits 1,1,1 → PROBABLY PRESENT (FALSE POSITIVE!)
```

---

## Full Java Implementation

```java
import java.util.BitSet;
import java.security.MessageDigest;

public class BloomFilter {
    private final BitSet bitSet;
    private final int bitSetSize;
    private final int numHashFunctions;

    /**
     * @param expectedElements  Expected number of elements to insert
     * @param falsePositiveRate Desired false positive probability (e.g., 0.01 = 1%)
     */
    public BloomFilter(int expectedElements, double falsePositiveRate) {
        // Optimal bit array size: m = -(n * ln(p)) / (ln(2)^2)
        this.bitSetSize = optimalBitSetSize(expectedElements, falsePositiveRate);

        // Optimal number of hash functions: k = (m/n) * ln(2)
        this.numHashFunctions = optimalNumHashFunctions(bitSetSize, expectedElements);

        this.bitSet = new BitSet(bitSetSize);

        System.out.printf("BloomFilter: %d bits, %d hash functions, ~%.1f%% FPR%n",
            bitSetSize, numHashFunctions, falsePositiveRate * 100);
    }

    public void add(String element) {
        int[] positions = getHashPositions(element);
        for (int pos : positions) {
            bitSet.set(pos);
        }
    }

    public boolean mightContain(String element) {
        int[] positions = getHashPositions(element);
        for (int pos : positions) {
            if (!bitSet.get(pos)) {
                return false; // Definitely NOT present
            }
        }
        return true; // Probably present (might be false positive)
    }

    private int[] getHashPositions(String element) {
        int[] positions = new int[numHashFunctions];
        // Use double hashing: h_i(x) = h1(x) + i * h2(x)
        // This avoids computing k independent hash functions
        long hash1 = murmurHash(element, 0);
        long hash2 = murmurHash(element, hash1);

        for (int i = 0; i < numHashFunctions; i++) {
            positions[i] = (int) Math.abs((hash1 + (long) i * hash2) % bitSetSize);
        }
        return positions;
    }

    private long murmurHash(String data, long seed) {
        // Simplified — in production use Guava's Hashing.murmur3_128()
        byte[] bytes = data.getBytes();
        long h = seed ^ bytes.length;
        for (byte b : bytes) {
            h ^= (b & 0xFFL);
            h *= 0xc4ceb9fe1a85ec53L;
            h ^= h >> 33;
        }
        return h;
    }

    private static int optimalBitSetSize(int n, double p) {
        return (int) (-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }

    private static int optimalNumHashFunctions(int m, int n) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    public double currentFalsePositiveRate() {
        // Approximate current FPR based on fill ratio
        long bitsSet = bitSet.cardinality();
        double fillRatio = (double) bitsSet / bitSetSize;
        return Math.pow(fillRatio, numHashFunctions);
    }

    public double fillRatio() {
        return (double) bitSet.cardinality() / bitSetSize;
    }
}
```

### Usage Example

```java
// Test it
BloomFilter filter = new BloomFilter(1_000_000, 0.01); // 1M elements, 1% FPR

filter.add("user:alice");
filter.add("user:bob");
filter.add("user:charlie");

System.out.println(filter.mightContain("user:alice"));   // true (present)
System.out.println(filter.mightContain("user:dave"));    // false (not present, no FP here)
System.out.println(filter.mightContain("user:mallory")); // false or true (possible FP)

// Measure actual FPR
int falsePositives = 0;
int total = 100_000;
for (int i = 0; i < total; i++) {
    String notAdded = "test:nonexistent:" + i;
    if (filter.mightContain(notAdded)) falsePositives++;
}
System.out.printf("Actual FPR: %.2f%%%n", 100.0 * falsePositives / total); // ~1%
```

---

## Using Guava's BloomFilter (Production Ready)

```java
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

// Guava's implementation is well-tested and production-ready
BloomFilter<String> emailBloom = BloomFilter.create(
    Funnels.stringFunnel(StandardCharsets.UTF_8),
    10_000_000, // expected insertions
    0.001       // 0.1% false positive rate
);

// Add known spam email domains
emailBloom.put("known-spam@malicious.com");
emailBloom.put("phisher@evil.org");

// Check incoming email
String incomingEmail = "user@gmail.com";
if (emailBloom.mightContain(incomingEmail)) {
    // Might be spam — do expensive DB lookup to confirm
    boolean isActuallySpam = spamDatabase.isSpam(incomingEmail);
}
// If mightContain returns false → definitely not spam → skip DB entirely
```

---

## Real-World Use Cases

### 1. Database/Cache: Avoid Null Lookups

```java
// Problem: users request non-existent user IDs → DB hit every time
// Solution: Bloom filter tracks which IDs exist

public class UserService {
    private final BloomFilter<String> existingUsers;

    @PostConstruct
    public void init() {
        // Populate from DB at startup (or load from snapshot)
        existingUsers = BloomFilter.create(Funnels.stringFunnel(UTF_8), 50_000_000, 0.01);
        db.getAllUserIds().forEach(existingUsers::put);
    }

    public Optional<User> getUser(String userId) {
        // Fast check: definitely doesn't exist → skip DB
        if (!existingUsers.mightContain(userId)) {
            return Optional.empty(); // 100% certain: not in DB
        }
        // Might exist → check DB (pays the FP cost ~1% of the time)
        return db.findById(userId);
    }

    public User createUser(User user) {
        User saved = db.save(user);
        existingUsers.put(saved.getId()); // add to filter
        return saved;
    }
}
```

### 2. Chrome Safe Browsing

```java
// Google Chrome uses Bloom filters to check URLs against malicious list
// Without filter: every URL lookup → network request to Google
// With filter: check local filter → only send to Google if "might be malicious"

public class SafeBrowsing {
    private final BloomFilter<String> maliciousURLs;

    public boolean isSafe(String url) {
        if (!maliciousURLs.mightContain(url)) {
            return true; // Definitely safe — no network call needed
        }
        // Might be malicious — check with Google's server
        return !googleSafeBrowsingAPI.isMalicious(url);
    }
}
```

### 3. Cassandra/HBase: SSTable Optimization

```java
// Each SSTable (file) has a Bloom filter for its keys
// Read path: check Bloom filter → skip file if key definitely not there
// Reduces disk I/O dramatically for key lookups

public class SSTable {
    private final BloomFilter<byte[]> keyFilter;
    private final File dataFile;

    public Optional<byte[]> get(byte[] key) {
        // Check Bloom filter — O(k) hash operations, no disk I/O
        if (!keyFilter.mightContain(key)) {
            return Optional.empty(); // Skip this SSTable entirely
        }
        // Key might be here — search in the actual file (binary search on index)
        return searchDataFile(key);
    }
}
```

### 4. Distributed Duplicate Detection (Deduplication)

```java
// Crawlers / event pipelines: "have we seen this URL/event before?"
public class WebCrawler {
    private final BloomFilter<String> visitedURLs =
        BloomFilter.create(Funnels.stringFunnel(UTF_8), 100_000_000, 0.001);

    public void crawl(String startUrl) {
        Queue<String> queue = new LinkedList<>();
        queue.add(startUrl);

        while (!queue.isEmpty()) {
            String url = queue.poll();

            if (visitedURLs.mightContain(url)) {
                continue; // Probably already visited — skip
            }

            // Might not be visited — process it
            processURL(url);
            visitedURLs.put(url); // mark as visited

            // Add new links
            for (String link : extractLinks(url)) {
                if (!visitedURLs.mightContain(link)) {
                    queue.add(link);
                }
            }
        }
    }
}
```

---

## Counting Bloom Filter

Standard Bloom filter can't delete elements (clearing a bit might affect other elements). Counting BF solves this.

```java
public class CountingBloomFilter {
    private final int[] counts; // array of counters instead of bits
    private final int numHashFunctions;
    private final int size;

    public void add(String element) {
        for (int pos : getHashPositions(element)) {
            counts[pos]++; // increment counter
        }
    }

    public void remove(String element) {
        if (!mightContain(element)) return;
        for (int pos : getHashPositions(element)) {
            counts[pos] = Math.max(0, counts[pos] - 1); // decrement counter
        }
    }

    public boolean mightContain(String element) {
        for (int pos : getHashPositions(element)) {
            if (counts[pos] == 0) return false;
        }
        return true;
    }
}
```

---

## Scalable Bloom Filter

When you don't know the number of elements in advance:

```java
// Chain of Bloom filters, each 2x larger than the last
// When current filter is ~50% full, create a new one
// Query all filters in chain

public class ScalableBloomFilter {
    private final List<BloomFilter<String>> filters = new ArrayList<>();
    private final double fillRatioThreshold = 0.5;

    public ScalableBloomFilter() {
        filters.add(BloomFilter.create(Funnels.stringFunnel(UTF_8), 10_000, 0.01));
    }

    public void add(String element) {
        BloomFilter<String> current = filters.get(filters.size() - 1);
        if (current.approximateElementCount() > 10_000 * (1 << (filters.size() - 1)) * fillRatioThreshold) {
            // Create larger filter (geometric growth)
            int newSize = 10_000 * (1 << filters.size());
            filters.add(BloomFilter.create(Funnels.stringFunnel(UTF_8), newSize, 0.01));
        }
        filters.get(filters.size() - 1).put(element);
    }

    public boolean mightContain(String element) {
        return filters.stream().anyMatch(f -> f.mightContain(element));
    }
}
```

---

## Space Comparison

```
1 million email addresses:
  HashSet<String>:   ~50 MB (store full strings)
  BloomFilter(1%):   ~1.2 MB (10 bits/element)
  BloomFilter(0.1%): ~1.8 MB (slightly more bits)

Rule of thumb: ~10 bits per element for 1% FPR
Formula: bits = n * log2(1/p) / ln(2) ≈ n * 1.44 * log2(1/p)
```

---

## When to Use
- Large-scale membership testing where false positives are tolerable
- Preventing unnecessary DB/cache lookups (save I/O)
- Deduplication in streaming pipelines
- Network: routing tables, weak credentials check

## When to Avoid
- When false positives are unacceptable (use HashSet instead)
- When you need to delete elements (use Counting BF or Cuckoo Filter)
- When your set is small (HashSet is fine)
- When you need to retrieve elements, not just check membership

---

## Interview Questions

1. **Can you delete from a Bloom filter?** No (standard). Setting a bit to 0 might affect other elements. Use Counting Bloom Filter for deletions.
2. **Why no false negatives?** Once all K bits are set during insertion, they stay set. If ANY bit is 0, the element was never inserted.
3. **How do you choose M and K?** M (bits) = -(n * ln(p)) / ln(2)^2; K (hashes) = (m/n) * ln(2). Guava's BloomFilter.create() does this for you.
4. **How does Cassandra use Bloom filters?** Each SSTable has a BF for its keys. Read path checks BF first — if key "definitely not here," skip that file entirely. Huge I/O savings.
