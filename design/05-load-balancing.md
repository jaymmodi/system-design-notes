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

## Interview Questions

1. **How does a load balancer differ from an API gateway?** LB: routes traffic, health checks. API GW: auth, rate limiting, transformation, routing — LB is a subset
2. **How do you handle session persistence?** Sticky sessions (IP hash), or better: move session to shared store (Redis) → any server can handle any request
3. **What happens when a load balancer itself is a SPOF?** Use active-active or active-passive pair, virtual IPs (Keepalived/VRRP)
4. **How does AWS ALB differ from NLB?** ALB = L7 (HTTP/HTTPS, content-based routing), NLB = L4 (TCP/UDP, ultra-low latency)
