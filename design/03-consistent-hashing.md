# Consistent Hashing

## What is it?
A technique to distribute data/requests across nodes such that when nodes are added or removed, **only K/N keys need to be remapped** (K = keys, N = nodes) — compared to naive modulo hashing which remaps almost everything.

---

## The Problem with Naive Hashing

```java
// Simple modulo hashing
public int getNode(String key, int numNodes) {
    return Math.abs(key.hashCode()) % numNodes;
}

// With 3 nodes:  key "user123" → node 1
// Add 1 node (4 nodes): key "user123" → node 3  ← DIFFERENT NODE!
// Result: ~75% of all keys map to different nodes → CACHE MISS STORM
```

When you add or remove a node from a cluster with N nodes:
- **Naive hashing**: ~(N-1)/N of all keys remapped (almost everything)
- **Consistent hashing**: ~K/N keys remapped (only the minimum necessary)

---

## How Consistent Hashing Works

1. **Hash space** is a ring from 0 to 2^32 - 1
2. **Each node** is hashed to one (or more) positions on the ring
3. **Each key** is hashed to a position on the ring
4. A key belongs to the **first node clockwise** from its position

```
        0
     /     \
   N3       N1
    |       |
   N2-------+

Key K hashes to a point → walks clockwise → finds first node → that node owns K
```

---

## Full Java Implementation

```java
import java.security.MessageDigest;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.List;
import java.util.ArrayList;

public class ConsistentHashRing {
    // TreeMap keeps positions sorted — binary search for O(log n) lookup
    private final SortedMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodes; // replicas per physical node

    public ConsistentHashRing(int virtualNodes) {
        this.virtualNodes = virtualNodes;
    }

    // Add a physical node (creates virtualNodes replicas on ring)
    public void addNode(String node) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = hash(node + "#" + i);
            ring.put(hash, node);
        }
    }

    // Remove a node and all its virtual nodes
    public void removeNode(String node) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = hash(node + "#" + i);
            ring.remove(hash);
        }
    }

    // Find which node owns this key
    public String getNode(String key) {
        if (ring.isEmpty()) return null;
        long hash = hash(key);

        // Find first node at or after the key's position
        SortedMap<Long, String> tailMap = ring.tailMap(hash);

        // If no node after key, wrap around to the first node (ring!)
        Long nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        return ring.get(nodeHash);
    }

    // Get N successor nodes (for replication)
    public List<String> getNodes(String key, int count) {
        if (ring.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        long hash = hash(key);
        // Start from the key's position and walk the ring
        SortedMap<Long, String> tailMap = ring.tailMap(hash);

        // Iterate ring, wrap around if needed
        for (Map.Entry<Long, String> entry : Iterables.concat(tailMap.entrySet(), ring.entrySet())) {
            if (seen.add(entry.getValue())) {
                result.add(entry.getValue());
                if (result.size() == count) break;
            }
        }
        return result;
    }

    // MD5-based hash function → 64-bit position on ring
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes("UTF-8"));
            // Take first 8 bytes as long
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return hash;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

### Usage Example

```java
public class CacheCluster {
    private final ConsistentHashRing ring;
    private final Map<String, CacheNode> nodes = new HashMap<>();

    public CacheCluster() {
        this.ring = new ConsistentHashRing(150); // 150 virtual nodes per server
    }

    public void addServer(String serverId, CacheNode node) {
        nodes.put(serverId, node);
        ring.addNode(serverId);
        System.out.println("Added server " + serverId);
    }

    public void removeServer(String serverId) {
        nodes.remove(serverId);
        ring.removeNode(serverId);
        System.out.println("Removed server " + serverId + " — only ~1/N keys need rehashing");
    }

    public String get(String key) {
        String serverId = ring.getNode(key);
        return nodes.get(serverId).get(key);
    }

    public void set(String key, String value) {
        String serverId = ring.getNode(key);
        nodes.get(serverId).set(key, value);
    }
}

// Test it
ConsistentHashRing ring = new ConsistentHashRing(150);
ring.addNode("server1");
ring.addNode("server2");
ring.addNode("server3");

System.out.println(ring.getNode("user:1001"));  // → "server2"
System.out.println(ring.getNode("user:1002"));  // → "server1"

ring.addNode("server4"); // Add new node
System.out.println(ring.getNode("user:1001"));  // → might still be "server2" (most keys unchanged)
```

---

## Virtual Nodes — Why They Matter

Without virtual nodes, physical nodes may cluster on the ring, causing uneven load.

```
Without virtual nodes (3 nodes):
Ring: ----N1--------N2-N3----
         large gap    ^small gap

N1 handles ~60% of keys, N3 handles ~5% — very uneven!

With 150 virtual nodes per server:
Ring: -N2-N1-N3-N1-N2-N3-N2-N1-N3-...
         much more even distribution
```

```java
// Visualize distribution
public Map<String, Integer> analyzeDistribution(ConsistentHashRing ring, int numKeys) {
    Map<String, Integer> distribution = new HashMap<>();
    for (int i = 0; i < numKeys; i++) {
        String node = ring.getNode("key:" + i);
        distribution.merge(node, 1, Integer::sum);
    }
    return distribution;
}

// With 150 virtual nodes, distribution should be within 5-10% of ideal
```

---

## Hotspot Problem — Weighted Nodes

If servers have different capacities, assign more virtual nodes to powerful servers:

```java
public class WeightedConsistentHash {
    public void addNode(String node, int weight) {
        // weight = capacity multiplier
        // A server with weight=2 gets 2x virtual nodes → handles ~2x the keys
        int vNodes = 100 * weight;
        for (int i = 0; i < vNodes; i++) {
            long hash = hash(node + "#" + i);
            ring.put(hash, node);
        }
    }
}

// Server with 16 cores → weight 4 → 400 virtual nodes
// Server with 4 cores  → weight 1 → 100 virtual nodes
// Server with 4 cores handles ~20% of traffic, big server ~80%
```

---

## Rendezvous Hashing (Alternative)

Also called Highest Random Weight (HRW). Simpler implementation, same properties:

```java
public class RendezvousHashing {
    private final List<String> nodes = new ArrayList<>();

    public String getNode(String key) {
        return nodes.stream()
            .max(Comparator.comparingLong(node -> hash(node + ":" + key)))
            .orElseThrow();
    }

    private long hash(String input) {
        // use a fast hash like MurmurHash
        return MurmurHash3.hash64(input.getBytes());
    }
}
// Pros: simpler, no ring data structure needed
// Cons: O(N) lookup vs O(log N) for consistent hash ring
```

---

## Where is Consistent Hashing Used?

| System | Use |
|--------|-----|
| **Cassandra** | Partitions data across ring of nodes |
| **DynamoDB** | Key-value storage partitioning |
| **Memcached** | Client-side key → cache node mapping |
| **Akamai CDN** | Route requests to edge servers |
| **Apache Kafka** | Partition keys to brokers |
| **Nginx** | `consistent_hash` upstream module |

---

## When to Use
- Distributing cache keys across a cache cluster
- Partitioning data across database nodes
- Load balancing with session affinity
- Any system where nodes come and go frequently

## When to Avoid
- Small, stable cluster where simple modulo is fine
- When you need strict ordering guarantees
- When load imbalance is a bigger concern than reshuffling (consider range-based sharding)

---

## Interview Questions

1. **Why 150-200 virtual nodes?** Empirically gives good distribution without too much memory overhead on the ring
2. **What happens when a node fails in Cassandra?** Its position on the ring becomes vacant, the next node clockwise picks up the keys (with replication, another replica already has them)
3. **How is Cassandra's partitioner related to consistent hashing?** Cassandra's Murmur3Partitioner uses consistent hashing — tokens on the ring define partition ranges
4. **Difference between consistent hashing and range-based sharding?** Consistent hashing uses hash of key → balanced but no range queries. Range sharding allows range queries but risks hotspots on sequential writes.
