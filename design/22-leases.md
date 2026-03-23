# Distributed Leases

## What is a Lease?
A **time-limited grant** of a resource or authority. Unlike a permanent lock, a lease automatically expires if the holder fails — preventing indefinite blocking in distributed systems.

> "A lease is a contract that gives its holder specific rights for a limited period." — Chubby (Google) paper

---

## Why Leases Over Locks?

```
Problem with traditional locks:
  1. Client acquires lock
  2. Client crashes (or network partition)
  3. Lock is NEVER released → other clients wait forever

Problem with timeouts alone:
  1. Client acquires lock with 30s timeout
  2. Client is slow (GC pause, slow disk)
  3. Timeout fires → another client gets lock
  4. TWO clients hold lock simultaneously → data corruption!

Leases solve this:
  1. Client acquires lease with TTL = 30s
  2. Client is slow (GC pause)
  3. Lease expires → next client can acquire
  4. Old client: before acting, CHECKS if lease is still valid
  5. Old client's lease expired → it STOPS, doesn't corrupt data
```

The critical difference: **the lease holder MUST stop acting when the lease expires**, not just when it's revoked.

---

## Lease Components

```java
public class Lease {
    private final String resourceId;    // what's being leased
    private final String holderId;      // who holds it
    private final Instant expiresAt;    // when it expires (absolute time)
    private final long version;         // monotonically increasing for fencing

    public boolean isValid() {
        return Instant.now().isBefore(expiresAt);
    }

    public boolean isExpired() {
        return !isValid();
    }
}
```

---

## Lease Renewal (Heartbeat)

```java
public class LeaseClient {
    private final LeaseServer server;
    private final String holderId;
    private volatile Lease currentLease;
    private final ScheduledExecutorService scheduler;

    public Lease acquire(String resourceId, Duration ttl) {
        currentLease = server.grant(resourceId, holderId, ttl);
        startRenewalTask(resourceId, ttl);
        return currentLease;
    }

    // Renew at 1/3 of TTL to have buffer for failures
    private void startRenewalTask(String resourceId, Duration ttl) {
        long renewIntervalMs = ttl.toMillis() / 3; // renew at T/3

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (currentLease != null && currentLease.isValid()) {
                    currentLease = server.renew(resourceId, holderId, ttl);
                }
            } catch (Exception e) {
                log.warn("Lease renewal failed for {}: {}", resourceId, e.getMessage());
                // If renewal fails, lease will expire naturally
                // The holder must stop acting after expiry
            }
        }, renewIntervalMs, renewIntervalMs, TimeUnit.MILLISECONDS);
    }

    // CRITICAL: Check lease before every operation
    public void performCriticalWork(Runnable work) {
        if (currentLease == null || currentLease.isExpired()) {
            throw new LeaseExpiredException("Cannot perform work — lease expired");
        }
        work.run();
        // Check AGAIN after work — lease might have expired during work
        if (currentLease.isExpired()) {
            log.error("DANGER: Lease expired during critical work — potential inconsistency!");
            // Handle: compensating action, rollback, alert
        }
    }
}
```

---

## Fencing Tokens — Preventing Split Brain

Even with leases, there's a race: what if network delay makes old holder act AFTER new holder acquires lease?

```
Time:
  0s: Client A acquires lease (expires 30s)
  15s: A is paused (GC, slow disk)
  30s: Lease expires
  31s: Client B acquires lease
  35s: Client A wakes up — doesn't know 5s passed
  35s: BOTH A and B think they hold the lease!
```

**Fencing token** solves this: the lease server issues a monotonically increasing token. Storage server rejects writes from tokens lower than the highest it has seen.

```java
public class FencedLeaseServer {
    private final AtomicLong tokenCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, Lease> activeLeases = new ConcurrentHashMap<>();

    // Each new lease gets a higher token
    public Lease grant(String resourceId, String holderId, Duration ttl) {
        long token = tokenCounter.incrementAndGet(); // strictly increasing
        Lease lease = new Lease(resourceId, holderId, Instant.now().plus(ttl), token);
        activeLeases.put(resourceId, lease);
        return lease;
    }
}

// Storage server uses token to reject stale writes
public class FencedStorageServer {
    private final ConcurrentHashMap<String, Long> highestTokenSeen = new ConcurrentHashMap<>();

    public void write(String key, String value, long fencingToken) {
        Long current = highestTokenSeen.get(key);
        if (current != null && fencingToken <= current) {
            throw new StaleWriteException(
                "Fencing token " + fencingToken + " <= seen " + current + ". Stale writer rejected."
            );
        }
        highestTokenSeen.put(key, fencingToken);
        store.put(key, value);
    }
}

// Martin Kleppmann's formulation:
// 1. Client A acquires lease, gets token 33
// 2. A pauses for 60 seconds
// 3. Lease expires, B acquires new lease, gets token 34
// 4. B writes to storage with token 34 → accepted
// 5. A wakes, tries to write with token 33 → REJECTED (33 < 34)
// Safety guaranteed even without reliable clocks!
```

---

## Clock Skew — The Silent Killer of Lease Logic

```java
// PROBLEM: Servers have different clocks
// Server A: lease expires at 12:00:30.000
// Server B's clock: 12:00:28.500 (1.5s behind)
// B thinks A's lease hasn't expired yet → B waits
// A's clock: 12:00:30.001 → A stops
// Server C's clock: 12:00:31.000 → thinks lease expired → grants to B

// NTP synchronization reduces drift to ~10-100ms but doesn't eliminate it

// Solutions:
// 1. Clock skew buffer: subtract max_clock_skew from TTL when checking
// 2. Lease holder: stop acting BEFORE expiry (safety margin)
// 3. Use logical clocks (Lamport) or hybrid logical clocks (HLC) instead of wall clock

public class SafeLeaseChecker {
    private static final long MAX_CLOCK_SKEW_MS = 200; // assume max 200ms skew

    public boolean isSafeToAct(Lease lease) {
        Instant safeExpiry = lease.getExpiresAt().minusMillis(MAX_CLOCK_SKEW_MS);
        return Instant.now().isBefore(safeExpiry);
        // Stop acting 200ms BEFORE expiry to account for clock skew
    }
}
```

---

## Leader Leases (Raft + Leases)

In Raft, reading from the leader could be stale if there's a newer leader. Leader leases fix this:

```java
// A Raft leader can serve reads without going through consensus IF:
// It has a valid lease — meaning no other node could have become leader in this period

public class RaftLeaderWithLease {
    private Instant leaseExpiry; // when this leader's lease expires
    private final Duration ELECTION_TIMEOUT = Duration.ofMillis(300);

    // On each successful AppendEntries response (quorum), extend lease
    // Lease duration = election timeout — ensures: if lease is valid, no election happened
    public void onQuorumAck() {
        // Safe: no new leader could have been elected while we hold the lease
        leaseExpiry = Instant.now().plus(ELECTION_TIMEOUT);
    }

    // Linearizable read without round-trip to quorum
    public <T> T linearizableRead(Supplier<T> readOp) {
        if (Instant.now().isBefore(leaseExpiry)) {
            return readOp.get(); // fast: serve locally without consensus
        }
        // Lease expired — must go through quorum to confirm leadership
        refreshLeadershipViaQuorum();
        return readOp.get();
    }
}
// Used by: TiKV, CockroachDB, etcd for linearizable reads without quorum overhead
```

---

## Chubby (Google) — Lease-based Distributed Lock Service

Google's Chubby (precursor to ZooKeeper) is built entirely on leases:

```
Architecture:
  Chubby Cell: 5 servers, one master (elected via Paxos)
  Clients acquire leases on Chubby files/nodes
  Master grants leases with TTL ~12 seconds
  Clients renew every 7 seconds (KeepAlive RPC)
  If client fails to renew → lease expires → master can grant to another

Key properties:
  1. Master failure: replicas elect new master via Paxos; new master issues
     grace period (jeopardy period) to reconnecting clients
  2. Client cache: Chubby maintains client-side caches with lease on cache validity
     When data changes, Chubby invalidates client cache via lease expiry
```

---

## Leases in Data Systems

```java
// DynamoDB: conditional writes with version-based "leases"
dynamoDB.putItem(PutItemRequest.builder()
    .tableName("WorkerLeases")
    .item(Map.of(
        "shardId",    AttributeValue.fromS("shard-001"),
        "workerId",   AttributeValue.fromS(myWorkerId),
        "expiresAt",  AttributeValue.fromN(String.valueOf(expiryEpoch)),
        "version",    AttributeValue.fromN("5")
    ))
    .conditionExpression("version = :v OR expiresAt < :now")
    .expressionAttributeValues(Map.of(
        ":v",   AttributeValue.fromN("4"),      // must be version I know
        ":now", AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond()))
    ))
    .build());
// Used by Kinesis: each shard has a lease, one consumer holds it at a time

// Kafka consumer group coordinator: each partition is a "lease" held by one consumer
// If consumer dies → session timeout → partition lease given to another consumer
// session.timeout.ms = lease TTL
// heartbeat.interval.ms = renewal interval (set to 1/3 of session.timeout.ms)

// Example: Kafka lease configuration
Properties props = new Properties();
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45000);  // 45s lease TTL
props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000); // renew every 3s (T/15)
// If consumer doesn't heartbeat for 45s → partition reassigned (lease expires)
```

---

## When to Use Leases

- **Distributed leader election**: leader's authority is lease-bounded
- **Kafka partition ownership**: each partition assigned to consumer via lease
- **Database connection pooling**: connections are leased to threads with TTL
- **Cache coherence**: Chubby-style cache validity leases
- **Distributed task assignment**: workers lease shards/tasks (Kinesis, Kafka)

## When to Avoid / Watch Out

- **Not a substitute for transactions**: lease expiry is best-effort (clock skew can cause brief overlap)
- **Always use fencing tokens** when absolute safety is needed
- **Renewal jitter**: stagger renewals to avoid thundering herd on the lease server
- **Don't make TTL too short**: frequent renewals under load can cause cascading failures

---

## Interview Questions

1. **What's the difference between a lease and a lock?** Lock: held indefinitely until released. Lease: automatically expires if not renewed — prevents indefinite blocking when holder fails.
2. **How does Kafka use leases?** Consumer group coordinator grants each partition to a consumer. Consumer must heartbeat before session.timeout.ms. Missed heartbeat → partition reassigned (lease expired).
3. **What is a fencing token and why is it needed?** Monotonically increasing number issued with each new lease. Storage server rejects writes with lower token → prevents stale leader from corrupting data after lease expiry.
4. **How does clock skew affect leases?** Clocks can differ by up to 200ms. Lease holder should stop acting slightly BEFORE expiry to account for this. Or use logical/hybrid clocks.
