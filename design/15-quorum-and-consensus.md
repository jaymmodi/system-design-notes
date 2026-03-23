# Quorum & Consensus

## What is a Quorum?
A quorum is the minimum number of nodes that must agree for an operation to proceed. With N nodes, a quorum of (N/2 + 1) ensures a majority, preventing conflicting decisions even when some nodes fail.

---

## Why Quorum?
Without quorum, you can have two groups of nodes making conflicting decisions (split brain):
```
5 nodes: [A, B, C, D, E]
Network partition: [A, B] vs [C, D, E]

Without quorum: BOTH sides think they're the leader → data divergence
With quorum (3): Only [C, D, E] can form majority → ONE leader
```

---

## Quorum Math

```
N = total replicas
W = write quorum (must acknowledge writes)
R = read quorum (must respond to reads)

For STRONG CONSISTENCY: W + R > N
For AVAILABILITY (AP):  W + R <= N (can be stale)

Common configurations:
  N=3, W=2, R=2: tolerates 1 failure, strong consistency
  N=5, W=3, R=3: tolerates 2 failures, strong consistency
  N=3, W=1, R=1: fast but stale reads possible
  N=3, W=3, R=1: slow writes but fast reads

Fault tolerance: can survive N - W write failures, N - R read failures
```

```java
public class QuorumConfig {
    public static QuorumConfig strongConsistency(int n) {
        int majority = n / 2 + 1;
        return new QuorumConfig(n, majority, majority); // W=R=majority, W+R > N
    }

    public static QuorumConfig readHeavy(int n) {
        // W=N (all must ack), R=1 (read from any)
        return new QuorumConfig(n, n, 1);
    }

    public static QuorumConfig writeHeavy(int n) {
        // W=1 (fast writes), R=N (read all, pick latest)
        return new QuorumConfig(n, 1, n);
    }

    public boolean isReadConsistent() {
        return writeQuorum + readQuorum > totalNodes;
    }
}
```

---

## Quorum Reads and Writes in Practice

```java
public class QuorumReplicaClient {
    private final List<ReplicaNode> nodes;
    private final int W; // write quorum
    private final int R; // read quorum

    public void write(String key, String value) {
        long version = System.currentTimeMillis();
        List<CompletableFuture<Boolean>> futures = nodes.stream()
            .map(node -> CompletableFuture.supplyAsync(() -> {
                try {
                    node.write(key, value, version);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }))
            .collect(toList());

        // Wait for W successful writes
        int successes = 0;
        for (CompletableFuture<Boolean> f : futures) {
            try {
                if (f.get(1, TimeUnit.SECONDS)) successes++;
                if (successes >= W) return; // quorum achieved
            } catch (TimeoutException ignored) {}
        }
        throw new WriteQuorumException("Only " + successes + "/" + W + " writes succeeded");
    }

    public String read(String key) {
        List<CompletableFuture<VersionedValue>> futures = nodes.stream()
            .map(node -> CompletableFuture.supplyAsync(() -> node.read(key)))
            .collect(toList());

        List<VersionedValue> responses = new ArrayList<>();
        for (CompletableFuture<VersionedValue> f : futures) {
            try {
                responses.add(f.get(1, TimeUnit.SECONDS));
                if (responses.size() >= R) break; // got enough responses
            } catch (TimeoutException ignored) {}
        }

        if (responses.size() < R) throw new ReadQuorumException();

        // Pick value with highest version (latest write)
        VersionedValue latest = responses.stream()
            .max(Comparator.comparingLong(VersionedValue::getVersion))
            .orElseThrow();

        // Read repair: bring stale replicas up to date asynchronously
        responses.stream()
            .filter(v -> v.getVersion() < latest.getVersion())
            .forEach(stale -> CompletableFuture.runAsync(
                () -> stale.getNode().write(key, latest.getValue(), latest.getVersion())
            ));

        return latest.getValue();
    }
}
```

---

## Raft Consensus Algorithm

Raft is the most widely used consensus algorithm (etcd, CockroachDB, TiKV, Consul). Easier to understand than Paxos.

### Raft Roles
- **Leader**: accepts all writes, replicates to followers
- **Follower**: passive, replicates from leader
- **Candidate**: running for election

### Raft Leader Election

```java
public class RaftNode {
    enum State { FOLLOWER, CANDIDATE, LEADER }

    private State state = State.FOLLOWER;
    private int currentTerm = 0;
    private String votedFor = null;
    private String leaderId = null;
    private long lastHeartbeat = System.currentTimeMillis();

    // Each node has a random election timeout (150-300ms)
    private final long electionTimeoutMs = 150 + new Random().nextInt(150);

    // Election timer: if no heartbeat in timeout → start election
    @Scheduled(fixedDelay = 10)
    public void checkElectionTimeout() {
        if (state == State.LEADER) return;

        long elapsed = System.currentTimeMillis() - lastHeartbeat;
        if (elapsed > electionTimeoutMs) {
            startElection();
        }
    }

    private void startElection() {
        state = State.CANDIDATE;
        currentTerm++; // increment term
        votedFor = nodeId; // vote for self
        int votes = 1;

        System.out.println("Node " + nodeId + " starting election for term " + currentTerm);

        // Request votes from all other nodes
        for (RaftNode peer : peers) {
            VoteResponse response = peer.requestVote(currentTerm, nodeId, lastLogIndex, lastLogTerm);
            if (response.voteGranted) {
                votes++;
                if (votes > peers.size() / 2) { // majority
                    becomeLeader();
                    return;
                }
            } else if (response.term > currentTerm) {
                // Someone has higher term — step down
                currentTerm = response.term;
                state = State.FOLLOWER;
                return;
            }
        }
    }

    // A node grants a vote if: candidate's term >= mine AND I haven't voted this term
    // AND candidate's log is at least as up-to-date as mine
    public VoteResponse requestVote(int candidateTerm, String candidateId, int lastLogIndex, int lastLogTerm) {
        if (candidateTerm > currentTerm) {
            currentTerm = candidateTerm;
            state = State.FOLLOWER;
            votedFor = null;
        }

        boolean logOk = (lastLogTerm > this.lastLogTerm) ||
                        (lastLogTerm == this.lastLogTerm && lastLogIndex >= this.lastLogIndex);
        boolean voteGranted = candidateTerm >= currentTerm &&
                              (votedFor == null || votedFor.equals(candidateId)) &&
                              logOk;

        if (voteGranted) votedFor = candidateId;
        return new VoteResponse(currentTerm, voteGranted);
    }

    private void becomeLeader() {
        state = State.LEADER;
        leaderId = nodeId;
        System.out.println("Node " + nodeId + " became leader for term " + currentTerm);
        // Start sending heartbeats to prevent new elections
        startHeartbeats();
    }
}
```

### Raft Log Replication

```java
public class RaftLeader {
    private final List<LogEntry> log = new ArrayList<>();
    private int commitIndex = 0; // highest log entry committed
    private Map<String, Integer> nextIndex; // per follower: next entry to send
    private Map<String, Integer> matchIndex; // per follower: highest entry confirmed

    // Client writes → leader appends to log, replicates, commits when majority confirms
    public CompletableFuture<Void> appendCommand(Command command) {
        LogEntry entry = new LogEntry(currentTerm, log.size(), command);
        log.add(entry);

        CompletableFuture<Void> committed = new CompletableFuture<>();

        // Replicate to all followers
        List<CompletableFuture<Boolean>> replicationFutures = followers.stream()
            .map(f -> replicateToFollower(f, entry))
            .collect(toList());

        // Wait for majority to confirm
        CompletableFuture.runAsync(() -> {
            int confirmations = 1; // leader itself
            for (CompletableFuture<Boolean> f : replicationFutures) {
                try {
                    if (f.get()) confirmations++;
                    if (confirmations > (peers.size() + 1) / 2) {
                        // Majority confirmed → commit
                        commitIndex = Math.max(commitIndex, entry.getIndex());
                        applyToStateMachine(entry);
                        committed.complete(null);
                        return;
                    }
                } catch (Exception e) { /* follower failed */ }
            }
            committed.completeExceptionally(new ConsensusException("Failed to replicate"));
        });

        return committed;
    }

    private CompletableFuture<Boolean> replicateToFollower(RaftFollower follower, LogEntry entry) {
        return CompletableFuture.supplyAsync(() -> {
            AppendEntriesResponse resp = follower.appendEntries(
                currentTerm, nodeId, entry.getIndex() - 1,
                getTermAtIndex(entry.getIndex() - 1),
                List.of(entry), commitIndex
            );
            if (resp.success) {
                matchIndex.put(follower.getId(), entry.getIndex());
                return true;
            }
            return false;
        });
    }
}
```

---

## Paxos (Brief Overview)

Older, harder to understand, but fundamental. Used in Google Spanner, Chubby.

```
Phase 1 (Prepare/Promise):
  Proposer sends PREPARE(n) to quorum of Acceptors
  Acceptors respond with PROMISE(n) + highest accepted value

Phase 2 (Accept/Accepted):
  Proposer sends ACCEPT(n, value) to quorum
  Acceptors accept if they haven't promised a higher n
  When quorum accepts → value is CHOSEN (committed)
```

Raft is essentially "Multi-Paxos with leadership" — more practical.

---

## ZooKeeper (ZAB Protocol)

ZooKeeper uses Zab (ZooKeeper Atomic Broadcast), similar to Raft. Used for:
- Configuration management
- Service discovery
- Distributed locks
- Leader election in Kafka, HBase

```java
// Distributed lock with ZooKeeper
CuratorFramework client = CuratorFrameworkFactory.newClient("zk:2181", new ExponentialBackoffRetry(1000, 3));
client.start();

InterProcessMutex lock = new InterProcessMutex(client, "/locks/order:12345");
try {
    if (lock.acquire(10, TimeUnit.SECONDS)) {
        // Critical section — guaranteed only one holder across cluster
        processOrder("12345");
    }
} finally {
    lock.release();
}

// Service discovery
ServiceInstance<Void> instance = ServiceInstance.<Void>builder()
    .name("payment-service")
    .address("10.0.0.5")
    .port(8080)
    .build();

ServiceDiscovery<Void> discovery = ServiceDiscoveryBuilder.builder(Void.class)
    .client(client).basePath("/services").build();
discovery.registerService(instance);
```

---

## When to Use What

| Use Case | Algorithm |
|----------|-----------|
| Distributed key-value store | Raft (etcd) |
| Database replication | Raft (CockroachDB, TiDB) |
| Message queue coordination | ZAB (ZooKeeper/Kafka) |
| Global transactions | Paxos (Google Spanner) |
| Simple majority vote | Quorum reads/writes |

---

## Interview Questions

1. **What is split brain and how does Raft prevent it?** Two partitions both think they're leader. Raft prevents it by requiring majority quorum to become leader — only the partition with majority can elect.
2. **How many failures can N=5 Raft tolerate?** 2 failures (need majority of 3 to elect leader and commit entries).
3. **Why is Raft easier than Paxos?** Raft decomposes consensus into: leader election, log replication, and safety — each independently understandable. Paxos conflates these.
4. **How does etcd use Raft?** etcd is a distributed key-value store where every write goes through Raft consensus. Reads can be linearizable (through Raft) or serializable (from local follower).
