# Leader Election & Gossip Protocol

## Leader Election

### What & Why
In a distributed system, certain operations require a single coordinator (leader): write ordering, job scheduling, shard assignment. Leader election is the process of choosing one node to be that coordinator.

---

### Election Algorithms

#### 1. Bully Algorithm
Highest ID wins. Any node can trigger an election.

```java
public class BullyElection {
    private final String nodeId;
    private final List<String> higherNodes; // nodes with higher IDs
    private final List<String> allNodes;
    private volatile String currentLeader;

    public void startElection() {
        System.out.println(nodeId + " starting election");

        if (higherNodes.isEmpty()) {
            // I have the highest ID — declare myself leader
            becomeLeader();
            return;
        }

        // Send ELECTION message to all nodes with higher ID
        boolean anyHigherNodeAlive = false;
        for (String higherNode : higherNodes) {
            try {
                boolean responded = sendElectionMessage(higherNode); // wait 1s for OK
                if (responded) anyHigherNodeAlive = true;
            } catch (TimeoutException ignored) {}
        }

        if (!anyHigherNodeAlive) {
            // No higher node responded — I win
            becomeLeader();
        }
        // Otherwise, wait for COORDINATOR message from winner
    }

    public void onElectionMessage(String fromNode) {
        // I have higher ID — respond OK and start my own election
        sendOK(fromNode);
        startElection();
    }

    private void becomeLeader() {
        currentLeader = nodeId;
        // Broadcast COORDINATOR message to all nodes
        allNodes.forEach(n -> sendCoordinator(n, nodeId));
        System.out.println(nodeId + " is the new leader");
    }
}
// O(N^2) messages. Simple but chatty.
```

#### 2. Ring Election (Chang-Roberts)
Nodes arranged in a logical ring. Election messages travel the ring.

```java
public class RingElection {
    private final String nodeId;
    private String nextNode; // neighbor in ring
    private volatile boolean participating = false;
    private volatile String currentLeader;

    public void startElection() {
        participating = true;
        sendElectionMessage(nodeId, nextNode); // pass my ID to next node
    }

    public void onElectionMessage(String candidateId) {
        if (candidateId.compareTo(nodeId) > 0) {
            // Candidate has higher ID — forward it
            participating = true;
            sendElectionMessage(candidateId, nextNode);
        } else if (candidateId.compareTo(nodeId) < 0) {
            if (!participating) {
                // Replace with my higher ID
                participating = true;
                sendElectionMessage(nodeId, nextNode);
            }
            // If participating, discard lower ID
        } else {
            // candidateId == nodeId — message made full circle, I'm the leader!
            currentLeader = nodeId;
            sendLeaderAnnouncement(nodeId, nextNode);
        }
    }

    public void onLeaderAnnouncement(String leaderId) {
        currentLeader = leaderId;
        participating = false;
        if (!leaderId.equals(nodeId)) {
            // Forward announcement around ring
            sendLeaderAnnouncement(leaderId, nextNode);
        }
    }
}
// O(N) messages average. Good for ring topologies.
```

#### 3. ZooKeeper-based Election (Production Pattern)

```java
// Most practical: use ZooKeeper ephemeral sequential nodes
public class ZooKeeperLeaderElection {
    private final CuratorFramework client;
    private final LeaderLatch latch;
    private final String serviceName;

    public ZooKeeperLeaderElection(CuratorFramework client, String serviceName) {
        this.client = client;
        this.serviceName = serviceName;
        this.latch = new LeaderLatch(client, "/election/" + serviceName);
    }

    public void start() throws Exception {
        latch.addListener(new LeaderLatchListener() {
            @Override
            public void isLeader() {
                System.out.println(getNodeId() + " became leader for " + serviceName);
                onBecomeLeader();
            }

            @Override
            public void notLeader() {
                System.out.println(getNodeId() + " lost leadership for " + serviceName);
                onLoseLeadership();
            }
        });
        latch.start(); // participate in election
    }

    public boolean isLeader() {
        return latch.hasLeadership();
    }

    public String getCurrentLeader() throws Exception {
        return latch.getLeader().getId();
    }

    public void stop() throws Exception {
        latch.close(); // relinquish leadership
    }
}

// Usage: only leader runs scheduled jobs
@Scheduled(fixedDelay = 60000)
public void runScheduledJob() {
    if (!election.isLeader()) {
        return; // only leader should run this
    }
    // Process job...
}
```

#### 4. etcd-based Election (Modern Pattern)

```java
// etcd uses Raft internally — provides strongly consistent leader election
public class EtcdLeaderElection {
    private final Client etcdClient;
    private final String electionName;
    private Election election;
    private LeaseKeepAlive leaseKeepAlive;

    public void campaign() throws ExecutionException, InterruptedException {
        // Create a lease (heartbeat) — if node dies, lease expires → leadership lost
        Lease leaseClient = etcdClient.getLeaseClient();
        long leaseId = leaseClient.grant(10).get().getID(); // 10 second TTL

        // Keep lease alive
        leaseKeepAlive = leaseClient.keepAlive(leaseId, new StreamObserver<>() {
            @Override public void onNext(LeaseKeepAliveResponse r) { /* renewed */ }
            @Override public void onError(Throwable t) { onLoseLeadership(); }
            @Override public void onCompleted() {}
        });

        // Campaign for leadership
        this.election = etcdClient.getElectionClient();
        // This BLOCKS until this node becomes leader
        election.campaign(ByteSequence.from(electionName, UTF_8),
                         ByteSequence.from(nodeId, UTF_8)).get();

        System.out.println(nodeId + " won election: " + electionName);
        onBecomeLeader();
    }

    public void resign() throws ExecutionException, InterruptedException {
        election.resign().get();
        leaseKeepAlive.close();
    }
}
```

---

## Gossip Protocol

### What is Gossip?
A decentralized protocol where nodes periodically exchange state with random neighbors. Like how rumors spread — information reaches all nodes in O(log N) rounds without any central coordinator.

```
Round 1: Node A infects B, C
Round 2: A,B,C each infect 2 more → 9 nodes know
Round 3: 9 nodes → up to 27 nodes know
...
O(log N) rounds to reach all N nodes
```

---

### Why Gossip?
- **No SPOF**: no central server
- **Self-healing**: nodes joining/leaving handled automatically
- **Scalable**: O(log N) convergence regardless of cluster size
- **Fault tolerant**: works even with many node failures

---

### Java Gossip Implementation

```java
public class GossipNode {
    private final String nodeId;
    private final Map<String, NodeState> membershipView = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public GossipNode(String nodeId, String host, int port) {
        this.nodeId = nodeId;
        // Add self to view
        membershipView.put(nodeId, new NodeState(nodeId, host, port, 0, NodeStatus.ALIVE));
    }

    public void start() {
        // Gossip to random peers every 1 second
        scheduler.scheduleAtFixedRate(this::gossip, 0, 1, TimeUnit.SECONDS);
        // Check for failed nodes every 5 seconds
        scheduler.scheduleAtFixedRate(this::checkFailures, 5, 5, TimeUnit.SECONDS);
    }

    private void gossip() {
        // Pick k random live nodes (fanout)
        List<NodeState> liveNodes = membershipView.values().stream()
            .filter(n -> n.getStatus() == NodeStatus.ALIVE && !n.getNodeId().equals(nodeId))
            .collect(toList());

        if (liveNodes.isEmpty()) return;

        Collections.shuffle(liveNodes);
        int fanout = Math.min(3, liveNodes.size()); // gossip to 3 random nodes

        for (int i = 0; i < fanout; i++) {
            NodeState target = liveNodes.get(i);
            sendGossipMessage(target, new GossipMessage(nodeId, membershipView));
        }
    }

    // Merge received gossip state
    public void onGossipReceived(GossipMessage message) {
        for (Map.Entry<String, NodeState> entry : message.getMembershipView().entrySet()) {
            String id = entry.getKey();
            NodeState received = entry.getValue();
            NodeState current = membershipView.get(id);

            if (current == null || received.getHeartbeat() > current.getHeartbeat()) {
                // Received fresher info — update our view
                membershipView.put(id, received);
            }
        }
    }

    // Each node increments its own heartbeat counter periodically
    @Scheduled(fixedDelay = 1000)
    public void heartbeat() {
        NodeState self = membershipView.get(nodeId);
        membershipView.put(nodeId, self.withHeartbeat(self.getHeartbeat() + 1));
    }

    // Mark nodes that haven't sent heartbeats as suspected/failed
    private void checkFailures() {
        long now = System.currentTimeMillis();
        membershipView.values().forEach(node -> {
            if (!node.getNodeId().equals(nodeId)) {
                long lastSeen = node.getLastUpdated();
                if (now - lastSeen > FAILURE_TIMEOUT_MS) {
                    if (node.getStatus() == NodeStatus.ALIVE) {
                        membershipView.put(node.getNodeId(), node.withStatus(NodeStatus.SUSPECTED));
                        System.out.println("Suspecting node: " + node.getNodeId());
                    } else if (now - lastSeen > DEAD_TIMEOUT_MS) {
                        membershipView.put(node.getNodeId(), node.withStatus(NodeStatus.DEAD));
                        System.out.println("Marking node DEAD: " + node.getNodeId());
                    }
                }
            }
        });
    }
}
```

---

### Gossip for Cluster Membership (Cassandra SWIM)

Cassandra uses **SWIM** (Scalable Weakly-consistent Infection-style Membership) — an advanced gossip protocol.

```java
// SWIM adds "indirect pinging" to distinguish crash from network partition:
// 1. Node A → pings Node B (direct)
// 2. No response → ask k random nodes to ping B on A's behalf
// 3. If none can reach B → B is suspected dead
// This prevents false positives from A-B network partition

public class SWIMMembership {
    public void checkNode(String suspectedNode) {
        // Direct ping
        if (ping(suspectedNode)) return; // alive

        // Indirect ping via k random other nodes
        List<String> probes = getRandomNodes(3);
        boolean anySuccess = probes.parallelStream()
            .anyMatch(probe -> requestIndirectPing(probe, suspectedNode));

        if (!anySuccess) {
            // Truly unreachable — mark as suspect
            markSuspect(suspectedNode);
        }
    }
}
```

---

### Gossip for Anti-Entropy (Data Sync)

Gossip can also sync data between nodes (not just membership):

```java
// Merkle tree gossip: detect and fix diverged replicas
public class AntiEntropyService {
    private final Map<String, String> dataStore;
    private final GossipNode gossipNode;

    // Periodically compare Merkle tree with random peer
    @Scheduled(fixedDelay = 30000)
    public void antiEntropy() {
        String peer = gossipNode.getRandomPeer();
        MerkleTree localTree = buildMerkleTree(dataStore);
        MerkleTree peerTree = fetchMerkleTree(peer);

        // Find which ranges differ (O(log N) to identify divergence)
        List<KeyRange> divergedRanges = localTree.diff(peerTree);

        for (KeyRange range : divergedRanges) {
            // Exchange data in diverged ranges
            Map<String, String> peerData = fetchRange(peer, range);
            Map<String, String> localData = getRange(range);

            // Merge: use vector clocks or timestamps to resolve conflicts
            mergeData(localData, peerData);
        }
    }
}
// Used by: Cassandra, Riak, Dynamo for replica repair
```

---

### Where Gossip is Used

| System | Use |
|--------|-----|
| **Cassandra** | Membership, ring topology, schema sync |
| **Consul** | Service discovery, health checking (Serf) |
| **Redis Cluster** | Node failure detection |
| **Akka Cluster** | Node membership |
| **Riak** | Membership, anti-entropy |
| **AWS DynamoDB** | Internal ring management |

---

## When to Use

**Leader Election**:
- Scheduled job coordination (only one instance runs cron jobs)
- Master selection in replication
- Shard assignment coordinator

**Gossip**:
- Large clusters (> 100 nodes) where broadcast is impractical
- Node membership tracking
- Configuration propagation
- When eventual consistency of cluster state is acceptable

## When to Avoid

**Leader Election**:
- When you don't need coordination (stateless services)
- When the election overhead is too high for the operation frequency

**Gossip**:
- When you need guaranteed delivery (use reliable broadcast)
- When convergence latency matters (O(log N) rounds × gossip interval)
- Small clusters where a central registry (ZooKeeper) is simpler

---

## Interview Questions

1. **How does Cassandra know when a node joins/leaves?** Gossip protocol — new node seeds list of contacts, gossips membership. Within O(log N) rounds, all nodes know about the newcomer.
2. **How does ZooKeeper's ephemeral node work for leader election?** Node creates `/election/leader` ephemeral node. If it dies, ZK deletes the node, others compete to re-create it.
3. **What's the difference between Gossip and Raft?** Gossip: eventual consistency, O(log N) convergence, decentralized. Raft: strong consistency, synchronous, centralized (leader-based).
4. **How fast does Gossip converge?** With N nodes and fanout k, convergence takes ~log_k(N) rounds. With 1000 nodes, fanout 3: ~6 rounds × 1s interval = ~6 seconds.
