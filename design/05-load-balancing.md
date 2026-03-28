# Load Balancing

## What is it?
Distributes incoming traffic across multiple servers to ensure no single server is overwhelmed, maximizing availability and throughput.

---

## Why Load Balance?
- **Availability**: If one server dies, others absorb traffic
- **Scalability**: Add more servers to handle more load
- **Latency**: Route to least-loaded/closest server

---

## Load Balancing Algorithms

### 1. Round Robin
Each request goes to next server in sequence. Simple, equal distribution.

```java
public class RoundRobinBalancer {
    private final List<String> servers;
    private final AtomicInteger counter = new AtomicInteger(0);

    public RoundRobinBalancer(List<String> servers) {
        this.servers = servers;
    }

    public String getServer() {
        int index = counter.getAndIncrement() % servers.size();
        return servers.get(index);
    }
}

// With servers [A, B, C]: requests go A, B, C, A, B, C, ...
// Problem: doesn't account for different server capacities or request weights
```

### 2. Weighted Round Robin
Servers get proportional traffic based on weight.

```java
public class WeightedRoundRobinBalancer {
    private final List<ServerWeight> serverWeights;
    private int currentIndex = -1;
    private int currentWeight = 0;
    private int gcd; // Greatest common divisor of all weights
    private int maxWeight;

    // Nginx-style Smooth Weighted Round Robin
    public synchronized String getServer() {
        // Each server tracks "current_weight" which rises by weight each round
        // Server with highest current_weight gets picked, then reduced by total weight
        // This produces smooth distribution: [A, A, B, A, B, C] for weights [3,2,1]
        List<Server> servers = new ArrayList<>(serverWeights);
        int totalWeight = servers.stream().mapToInt(Server::getWeight).sum();

        servers.forEach(s -> s.currentWeight += s.weight);
        Server best = servers.stream().max(Comparator.comparingInt(s -> s.currentWeight)).get();
        best.currentWeight -= totalWeight;
        return best.address;
    }
}
```

### 3. Least Connections
Route to server with fewest active connections. Best for variable-length requests.

```java
public class LeastConnectionsBalancer {
    private final ConcurrentHashMap<String, AtomicInteger> connectionCounts;
    private final List<String> servers;

    public String getServer() {
        return servers.stream()
            .min(Comparator.comparingInt(s -> connectionCounts.get(s).get()))
            .orElseThrow();
    }

    public void onRequestStart(String server) {
        connectionCounts.get(server).incrementAndGet();
    }

    public void onRequestEnd(String server) {
        connectionCounts.get(server).decrementAndGet();
    }
}
// Use case: video streaming (some requests take 1hr), DB queries (variable time)
```

### 4. IP Hash (Sticky Sessions)
Same client always goes to same server — based on client IP hash.

```java
public class IPHashBalancer {
    private final List<String> servers;

    public String getServer(String clientIP) {
        int hash = Math.abs(clientIP.hashCode());
        return servers.get(hash % servers.size());
        // Problem: when servers added/removed, most clients re-hash to different server
        // Better: use consistent hashing (see 03-consistent-hashing.md)
    }
}
// Use case: stateful apps (shopping cart in memory), WebSocket connections
```

### 5. Least Response Time
Route to server with lowest average response time AND fewest connections.

```java
public class LeastResponseTimeBalancer {
    private final Map<String, EWMA> responseTimes = new HashMap<>(); // Exponential weighted moving avg
    private final Map<String, AtomicInteger> connections = new HashMap<>();

    public String getServer() {
        return servers.stream()
            .min(Comparator.comparingDouble(s ->
                responseTimes.get(s).getAverage() * (connections.get(s).get() + 1)))
            .orElseThrow();
    }
}
```

### 6. Random with Two Choices (Power of Two Choices)
Pick 2 random servers, route to the better one. Near-optimal with O(1) overhead.

```java
public class PowerOfTwoChoices {
    private final List<String> servers;
    private final Random random = new Random();
    private final Map<String, AtomicInteger> load = new HashMap<>();

    public String getServer() {
        // Pick 2 random servers
        int i = random.nextInt(servers.size());
        int j;
        do { j = random.nextInt(servers.size()); } while (j == i);

        // Route to the less loaded one
        String s1 = servers.get(i), s2 = servers.get(j);
        return load.get(s1).get() <= load.get(s2).get() ? s1 : s2;
    }
}
// Near-optimal performance with far less coordination than pure Least Connections
```

---

## Layer 4 vs Layer 7 Load Balancing

### Layer 4 (Transport Layer)
- Operates on TCP/UDP — doesn't inspect content
- Routes based on IP + port only
- **Very fast** — no content parsing
- Examples: AWS NLB, HAProxy in TCP mode

```
Client → [L4 LB: sees IP:port only] → Server
         Routes based on IP hash or round robin
         Cannot route /api to one pool, /static to another
```

### Layer 7 (Application Layer)
- Inspects HTTP headers, URL, cookies
- Can route based on content
- **More features** but slightly more overhead
- Examples: AWS ALB, Nginx, HAProxy in HTTP mode

```java
// L7 load balancer can do content-based routing:
// /api/v1/* → api-servers pool
// /images/* → image-servers pool
// Host: payments.example.com → payments-service pool
// Cookie: user_segment=beta → canary pool (A/B testing!)

// Example: Nginx configuration (pseudo-code)
/*
upstream api_servers {
    least_conn;
    server api1:8080 weight=3;
    server api2:8080 weight=3;
    server api3:8080 weight=1; # smaller server
}

upstream image_servers {
    ip_hash;
    server img1:8080;
    server img2:8080;
}

server {
    location /api/ {
        proxy_pass http://api_servers;
    }
    location /images/ {
        proxy_pass http://image_servers;
    }
}
*/
```

---

## Health Checks

Load balancers must detect unhealthy servers and stop sending traffic to them.

```java
public class HealthChecker {
    private final Map<String, ServerStatus> serverStatuses = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public void startHealthChecks(List<String> servers) {
        scheduler.scheduleAtFixedRate(() -> {
            for (String server : servers) {
                checkServer(server);
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    private void checkServer(String server) {
        try {
            HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://" + server + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                serverStatuses.put(server, ServerStatus.HEALTHY);
            } else {
                markUnhealthy(server, "HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            markUnhealthy(server, e.getMessage());
        }
    }

    private void markUnhealthy(String server, String reason) {
        ServerStatus current = serverStatuses.get(server);
        current.incrementFailureCount();

        if (current.getFailureCount() >= 3) { // 3 consecutive failures
            serverStatuses.put(server, ServerStatus.DOWN);
            alertOncall(server, reason);
        }
    }

    public boolean isHealthy(String server) {
        return serverStatuses.getOrDefault(server, ServerStatus.HEALTHY).isUp();
    }
}
```

---

## Load Balancer Patterns

### Active-Passive (Failover)
```
Active LB handles all traffic
Passive LB monitors via heartbeat
If Active fails → Passive takes over (via virtual IP / DNS swap)
```

### Active-Active
```
Multiple LBs share traffic
If one fails, others absorb its load
Higher throughput but requires coordination
```

### Global Server Load Balancing (GSLB) / GeoDNS
```
DNS resolves to different IP based on user's location
User in EU → eu-servers pool
User in US → us-servers pool
```

---

## Java: Simple Reverse Proxy LB (illustration)

```java
public class SimpleLoadBalancer {
    private final List<BackendServer> servers;
    private final LoadBalancingStrategy strategy;
    private final HealthChecker healthChecker;

    public void start(int port) throws IOException {
        ServerSocket server = new ServerSocket(port);
        System.out.println("LB listening on port " + port);

        while (true) {
            Socket clientSocket = server.accept();
            // Handle each connection in a thread pool
            executor.submit(() -> handleConnection(clientSocket));
        }
    }

    private void handleConnection(Socket client) {
        // Get next healthy server
        BackendServer backend = strategy.getNext(
            servers.stream()
                .filter(s -> healthChecker.isHealthy(s.getAddress()))
                .collect(toList())
        );

        // Proxy the request
        try (Socket backendSocket = new Socket(backend.getHost(), backend.getPort())) {
            // Forward client → backend
            CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> pipe(client.getInputStream(), backendSocket.getOutputStream())),
                CompletableFuture.runAsync(() -> pipe(backendSocket.getInputStream(), client.getOutputStream()))
            ).join();
        } catch (IOException e) {
            healthChecker.reportFailure(backend.getAddress());
        }
    }
}
```

---

## Load Balancing in Microservices

### Client-Side Load Balancing (e.g., Spring Cloud LoadBalancer)
```java
@Configuration
public class LoadBalancerConfig {
    @Bean
    @LoadBalanced // Spring's RestTemplate uses client-side LB
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

@Service
public class OrderService {
    @Autowired
    private RestTemplate restTemplate;

    public Product getProduct(String productId) {
        // "product-service" is resolved via service registry (Eureka/Consul)
        // client-side LB (Ribbon/Spring CLB) picks which instance to call
        return restTemplate.getForObject(
            "http://product-service/products/" + productId,
            Product.class
        );
    }
}
```

---

## When to Use What Algorithm

| Scenario | Algorithm |
|----------|-----------|
| Stateless services, equal servers | Round Robin |
| Servers of different sizes | Weighted Round Robin |
| Long-lived connections (DB, WebSocket) | Least Connections |
| Stateful apps, need session affinity | IP Hash / Consistent Hash |
| Mixed request durations | Least Response Time |
| High-performance, simple | Power of Two Choices |

## When NOT to Load Balance
- Single server that isn't bottlenecked (adds unnecessary hop)
- Batch jobs that need access to all data (use different partitioning)

---

## Stateful Connections — WebSockets and Streaming

### Why stateful is hard for load balancers

HTTP is stateless — each request is independent, any server can handle it. WebSockets and streaming are different:

```
HTTP (stateless):
  Request 1 → Server A
  Request 2 → Server B   ← fine, no state shared
  Request 3 → Server A

WebSocket (stateful):
  CONNECT → Server A     ← TCP connection established, state lives here
  message → Server A     ← MUST go to same server (same TCP connection)
  message → Server A     ← ALB can't route this mid-connection
  message → Server B     ← ✗ BROKEN — different TCP connection entirely
```

Once a WebSocket handshake completes, **all frames on that connection go to the same backend**. The LB can't move it. The challenge is: what happens when that backend goes down?

---

### Netflix Streaming Architecture on AWS

Netflix video streaming is a long-lived stateful connection — a 2-hour movie = 2-hour open connection. Here's how they build it:

```
                        Route 53 (GeoDNS)
                             │
                    ┌────────┴─────────┐
                    │                  │
              us-east-1            eu-west-1
                    │
             AWS Global Accelerator   ← anycast IPs, routes to nearest edge
                    │
              NLB (Layer 4)           ← TCP passthrough, preserves client IP
                    │                    ultra-low latency, handles millions of
                    │                    concurrent TCP connections
              ┌─────┴──────┐
         AZ-1a             AZ-1b
              │                │
        [Streaming Server] [Streaming Server]
              │
        [Chunk Server / CDN Origin]
```

**Why NLB not ALB for streaming?**
```
ALB terminates TLS, parses HTTP — adds ~1ms overhead, fine for APIs
NLB passes raw TCP through — sub-millisecond, scales to millions of connections
Video chunks are large binary blobs — no HTTP routing logic needed
NLB preserves real client IP natively — no X-Forwarded-For needed
```

---

### WebSocket on AWS — ALB + Sticky Sessions

For interactive apps (chat, live notifications, collaborative editing):

```
Client
  │  HTTP Upgrade: websocket
  ↓
ALB  (Layer 7 — understands WebSocket upgrade)
  │  ALB detects Upgrade header → switches to TCP passthrough mode
  │  Sticky session cookie set on initial HTTP handshake
  ↓
Target Group (EC2 / ECS)
  ├── ws-server-1  ← client pinned here via stickiness cookie
  ├── ws-server-2
  └── ws-server-3
```

ALB stickiness config:
```
Target Group → Attributes:
  Stickiness type:     load balancer generated cookie
  Stickiness duration: 1 day (longer than expected session)
  Cookie name:         AWSALB
```

What happens in practice:
```
1. Client opens WebSocket → ALB picks ws-server-1, sets AWSALB cookie
2. All subsequent frames on that TCP connection → ws-server-1 automatically
   (same TCP connection, ALB just forwards)
3. Connection drops → client reconnects with AWSALB cookie → ALB routes to ws-server-1 again
4. ws-server-1 is unhealthy → ALB routes to new server → client must re-establish app state
```

---

### The Real Problem — State on the Backend

Stickiness solves routing but creates a new problem: **what does the backend server store?**

#### Bad pattern — state in server memory
```
ws-server-1 memory:
  userId:123 → {subscriptions: [room:A, room:B], lastSeen: ...}

ws-server-1 crashes:
  All state for 10,000 connected clients → gone
  Clients reconnect → ws-server-2 has no idea about their subscriptions
```

#### Good pattern — externalize session state to Redis

```
Client ──WebSocket──→ ws-server-1
                           │
                    on connect: load state from Redis
                    on message: update state in Redis
                    on disconnect: state persists in Redis
                           │
                        Redis
                    userId:123 → {subscriptions, cursor, presence}

Client reconnects → ws-server-2:
  load state from Redis → seamless, no lost state
  ws-server-1 failure → transparent to client
```

```java
// WebSocket handler — stateless server, stateful Redis
@ServerEndpoint("/ws/{userId}")
public class StreamingHandler {

    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userId) {
        // Load state from Redis — server holds no state itself
        UserSession state = redis.get("session:" + userId, UserSession.class);
        session.getUserProperties().put("state", state);
        subscriptionManager.subscribe(userId, session);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        UserSession state = (UserSession) session.getUserProperties().get("state");
        state.update(message);
        redis.set("session:" + state.userId(), state);   // persist immediately
    }

    @OnClose
    public void onClose(Session session) {
        // State survives in Redis — client can reconnect to any server
        subscriptionManager.unsubscribe(session);
    }
}
```

---

### Netflix-specific: how video chunk delivery avoids stateful LB entirely

Netflix sidesteps the stateful problem by making streaming **mostly stateless at the delivery layer**:

```
Client player:
  1. Calls playback API → gets manifest (list of chunk URLs)
  2. Each chunk URL points directly to CDN (Open Connect Appliance)
  3. Downloads chunks independently — each is a stateless HTTP GET
  4. ABR (Adaptive Bitrate) logic runs on client — picks quality per chunk

LB only handles:
  - Playback API calls (stateless, short-lived)
  - License/DRM requests (stateless)
  - CDN origin fetch (stateless)

Result: ALB works fine — no sticky sessions needed for the hot path
```

```
Client
  │ POST /api/v1/playback  (which streams are available, which CDN node)
  ↓
ALB → Playback API (stateless, any server)
  │
  └── returns: manifest with 200 chunk URLs pointing to cdn.netflix.com

Client
  │ GET cdn.netflix.com/chunk_001.ts  (stateless, pure HTTP)
  │ GET cdn.netflix.com/chunk_002.ts
  │ GET cdn.netflix.com/chunk_003.ts  ...
  ↓
Open Connect CDN (Netflix's own CDN, co-located at ISPs)
```

---

### AWS Architecture Summary — Stateless vs Stateful

```
                    ┌─────────────────────────────────────┐
                    │           Route 53 (GeoDNS)          │
                    └──────────────┬──────────────────────┘
                                   │
              ┌────────────────────┼───────────────────────┐
              │                    │                        │
    ┌─────────▼────────┐  ┌────────▼───────┐   ┌──────────▼──────────┐
    │  ALB (L7)        │  │  NLB (L4)      │   │  CloudFront (CDN)   │
    │  REST APIs       │  │  WebSocket     │   │  Video chunks       │
    │  Stateless       │  │  Streaming     │   │  Static assets      │
    │  Round-robin     │  │  TCP passthru  │   │  Edge-cached        │
    └─────────┬────────┘  └────────┬───────┘   └─────────────────────┘
              │                    │
    ┌─────────▼────────┐  ┌────────▼───────┐
    │  API Servers     │  │  WS Servers    │
    │  Stateless       │  │  Stateless     │
    │  ASG 2-50        │  │  ASG 2-20      │
    └──────────────────┘  └────────┬───────┘
                                   │
                            ┌──────▼──────┐
                            │    Redis    │  ← session state, subscriptions
                            │  ElastiCache│     presence, cursors
                            └─────────────┘
```

---

### When to use NLB vs ALB for stateful workloads

| | ALB | NLB |
|---|---|---|
| **WebSocket** | ✓ (understands Upgrade header) | ✓ (TCP passthrough) |
| **Video streaming** | ✗ (overhead, 1MB max body parsing) | ✓ (raw TCP, no overhead) |
| **Sticky sessions** | ✓ cookie-based | ✓ source IP-based |
| **TLS termination** | ✓ | ✓ (TLS passthrough or terminate) |
| **Health checks** | HTTP /health | TCP or HTTP |
| **Static IP** | ✗ (DNS only) | ✓ (Elastic IP per AZ) |
| **Millions of concurrent connections** | ✗ (L7 parsing overhead) | ✓ (designed for this) |
| **Use when** | HTTP apps, WebSocket chat | Raw TCP, streaming, gaming, IoT |

---

## Interview Questions

1. **How does a load balancer differ from an API gateway?** LB: routes traffic, health checks. API GW: auth, rate limiting, transformation, routing — LB is a subset
2. **How do you handle session persistence?** Sticky sessions (IP hash), or better: move session to shared store (Redis) → any server can handle any request
3. **What happens when a load balancer itself is a SPOF?** Use active-active or active-passive pair, virtual IPs (Keepalived/VRRP)
4. **How does AWS ALB differ from NLB?** ALB = L7 (HTTP/HTTPS, content-based routing), NLB = L4 (TCP/UDP, ultra-low latency, millions of connections)
5. **How would you design WebSocket infrastructure for 1M concurrent connections?** NLB → stateless WS servers → externalize all state to Redis. Stickiness is a routing convenience, not a requirement when state is in Redis.
6. **How does Netflix avoid stateful LB for video streaming?** Playback API returns chunk URLs pointing directly to CDN. Each chunk is a stateless GET — no sticky sessions needed on the hot path.
