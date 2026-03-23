# API Gateway & Service Discovery

---

## API Gateway

### What is it?
A single entry point for all client requests. Handles cross-cutting concerns so individual services don't have to.

```
Clients (Web, Mobile, 3rd party)
              ↓
       [  API Gateway  ]
    auth | routing | rate limit
    logging | transform | LB
    /    |    \     \    \
  svc-A svc-B svc-C svc-D svc-E
```

### What API Gateway Does

| Concern | Without Gateway | With Gateway |
|---------|-----------------|--------------|
| Auth | Every service validates JWT | Gateway validates once |
| Rate limiting | Every service implements | Centralized |
| SSL termination | Every service handles TLS | Gateway terminates TLS |
| Request routing | Clients know all service URLs | One URL for all |
| Response transformation | Client handles | Gateway aggregates |
| Logging/tracing | Per-service | Centralized |

---

### Java: Spring Cloud Gateway

```java
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}

@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
            // Route /api/products/** → product-service
            .route("product-service", r -> r
                .path("/api/products/**")
                .filters(f -> f
                    .stripPrefix(1)                    // remove /api prefix
                    .addRequestHeader("X-From-Gateway", "true")
                    .retry(config -> config.setRetries(3)
                        .setStatuses(HttpStatus.SERVICE_UNAVAILABLE))
                    .circuitBreaker(config -> config
                        .setName("product-cb")
                        .setFallbackUri("forward:/fallback/products"))
                    .requestRateLimiter(config -> config
                        .setRateLimiter(redisRateLimiter())
                        .setKeyResolver(userKeyResolver()))
                )
                .uri("lb://product-service") // lb:// = load balanced via discovery
            )

            // Route /api/orders/** → order-service with auth required
            .route("order-service", r -> r
                .path("/api/orders/**")
                .filters(f -> f
                    .filter(jwtAuthFilter())  // auth required for orders
                    .stripPrefix(1)
                )
                .uri("lb://order-service")
            )

            // Websocket route
            .route("notification-ws", r -> r
                .path("/ws/**")
                .uri("lb:ws://notification-service")
            )
            .build();
    }

    // JWT Auth Filter
    @Bean
    public GlobalFilter jwtAuthFilter() {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getPath().toString();

            // Public paths skip auth
            if (isPublicPath(path)) return chain.filter(exchange);

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            try {
                Claims claims = jwtUtil.parse(authHeader.substring(7));
                // Add user context to downstream request
                ServerHttpRequest mutated = request.mutate()
                    .header("X-User-Id", claims.getSubject())
                    .header("X-User-Roles", String.join(",", claims.get("roles", List.class)))
                    .build();
                return chain.filter(exchange.mutate().request(mutated).build());
            } catch (JwtException e) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        };
    }

    @Bean
    public RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(10, 20, 1); // 10 req/sec, burst 20, token per second
    }

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> Mono.justOrEmpty(
            exchange.getRequest().getHeaders().getFirst("X-User-Id")
        ).defaultIfEmpty("anonymous");
    }
}
```

---

### Request Aggregation (BFF Pattern)

```java
// Backend For Frontend: aggregate multiple service calls into one response
// Mobile app makes 1 request, gateway calls 3 services and merges response

@RestController
public class ProductDetailAggregator {

    @GetMapping("/api/product-detail/{productId}")
    public Mono<ProductDetailResponse> getProductDetail(@PathVariable String productId,
                                                        @RequestHeader("X-User-Id") String userId) {
        Mono<Product> product = productClient.getProduct(productId);
        Mono<List<Review>> reviews = reviewClient.getReviews(productId);
        Mono<Inventory> inventory = inventoryClient.getInventory(productId);
        Mono<Boolean> inWishlist = wishlistClient.isInWishlist(userId, productId);

        // Execute all calls in parallel, merge results
        return Mono.zip(product, reviews, inventory, inWishlist)
            .map(tuple -> new ProductDetailResponse(
                tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4()
            ));
        // One HTTP call from mobile → 4 parallel backend calls → merged response
    }
}
```

---

## Service Discovery

### What & Why
Microservices are dynamically deployed — IPs and ports change. Service discovery lets services find each other without hardcoded addresses.

```
Service A wants to call Service B:
  Without discovery: hardcode "http://10.0.1.5:8080" — breaks when B moves/scales
  With discovery: ask registry "where is service-b?" → get current instances
```

---

### Types

#### Client-Side Discovery
Client asks registry directly, picks an instance, calls it.

```java
// Spring Cloud with Eureka (client-side)
@SpringBootApplication
@EnableEurekaClient
public class OrderService { }

// application.yml
// eureka:
//   client:
//     serviceUrl:
//       defaultZone: http://eureka-server:8761/eureka/

@Service
public class ProductClient {
    @Autowired
    private DiscoveryClient discoveryClient;

    @Autowired
    @LoadBalanced  // Spring's load-balanced RestTemplate uses client-side discovery
    private RestTemplate restTemplate;

    public Product getProduct(String id) {
        // restTemplate resolves "product-service" via Eureka, load balances across instances
        return restTemplate.getForObject(
            "http://product-service/products/" + id,
            Product.class
        );
    }

    // Manual discovery (when you need control)
    public List<ServiceInstance> getProductInstances() {
        return discoveryClient.getInstances("product-service");
        // Returns: [{host: "10.0.1.5", port: 8080}, {host: "10.0.1.6", port: 8080}]
    }
}
```

#### Server-Side Discovery (More Common in K8s)
Client calls a load balancer/proxy; LB does discovery.

```
Client → [Load Balancer / Service Mesh] → Service Instance
               ↕
         [Service Registry]
```

```yaml
# Kubernetes: kube-proxy handles discovery transparently
# Service DNS: http://product-service.default.svc.cluster.local:8080
# K8s does server-side discovery via kube-proxy and CoreDNS
```

---

### Eureka (Netflix/Spring)

```java
// Register with Eureka
@SpringBootApplication
@EnableEurekaServer
public class EurekaServer { }

// application.yml for Eureka Server
// spring.application.name: eureka-server
// eureka.client.register-with-eureka: false
// eureka.client.fetch-registry: false

// Service registers itself
@SpringBootApplication
@EnableDiscoveryClient
public class ProductService {
    // spring.application.name: product-service
    // eureka.client.serviceUrl.defaultZone: http://eureka:8761/eureka/
    // Registers as "product-service" with its current IP:port
    // Sends heartbeats every 30s; removed after 90s of no heartbeat
}
```

---

### Consul (HashiCorp)

```java
// Consul: service registry + health checking + key-value store
@Configuration
public class ConsulConfig {

    @Bean
    public ConsulClient consulClient() {
        return new ConsulClient("consul-server", 8500);
    }

    // Register service
    @PostConstruct
    public void registerService() {
        NewService service = new NewService();
        service.setId("product-service-" + instanceId);
        service.setName("product-service");
        service.setAddress(getHostIP());
        service.setPort(8080);

        // Health check
        NewService.Check check = new NewService.Check();
        check.setHttp("http://" + getHostIP() + ":8080/actuator/health");
        check.setInterval("10s");
        check.setDeactivateCriticalServiceAfter("30s"); // deregister if unhealthy 30s
        service.setCheck(check);

        consulClient.agentServiceRegister(service);
    }

    // Discover service
    public List<HealthService> getHealthyInstances(String serviceName) {
        Response<List<HealthService>> response = consulClient
            .getHealthServices(serviceName, true, null); // true = only healthy
        return response.getValue();
    }
}
```

---

### etcd for Service Discovery

```java
// etcd: simple key-value, strong consistency via Raft
// Register: PUT /services/product-service/{instanceId} → {"host": "...", "port": 8080}
// Discover: LIST /services/product-service/ → all instances
// Watch: WATCH /services/product-service/ → get notified on changes

public class EtcdServiceRegistry {
    private final Client etcd;

    public void register(String serviceName, String instanceId, String host, int port) throws Exception {
        // Create lease for TTL-based health (if service dies, lease expires → entry removed)
        long leaseId = etcd.getLeaseClient().grant(30).get().getID(); // 30s TTL

        // Start keepalive
        etcd.getLeaseClient().keepAlive(leaseId, Observers.observer(response -> {}));

        String key = "/services/" + serviceName + "/" + instanceId;
        String value = "{\"host\":\"" + host + "\",\"port\":" + port + "}";

        etcd.getKVClient().put(
            ByteSequence.from(key, UTF_8),
            ByteSequence.from(value, UTF_8),
            PutOption.newBuilder().withLeaseId(leaseId).build()
        ).get();
    }

    public List<ServiceInstance> discover(String serviceName) throws Exception {
        String prefix = "/services/" + serviceName + "/";
        GetResponse response = etcd.getKVClient().get(
            ByteSequence.from(prefix, UTF_8),
            GetOption.newBuilder().withPrefix(ByteSequence.from(prefix, UTF_8)).build()
        ).get();

        return response.getKvs().stream()
            .map(kv -> parseInstance(kv.getValue().toString(UTF_8)))
            .collect(toList());
    }

    // Watch for changes (instances coming/going)
    public void watchService(String serviceName, Consumer<ServiceInstance> onAdd, Consumer<String> onRemove) {
        String prefix = "/services/" + serviceName + "/";
        etcd.getWatchClient().watch(
            ByteSequence.from(prefix, UTF_8),
            WatchOption.newBuilder().withPrefix(ByteSequence.from(prefix, UTF_8)).build(),
            watchResponse -> watchResponse.getEvents().forEach(event -> {
                if (event.getEventType() == WatchEvent.EventType.PUT) {
                    onAdd.accept(parseInstance(event.getKeyValue().getValue().toString(UTF_8)));
                } else if (event.getEventType() == WatchEvent.EventType.DELETE) {
                    onRemove.accept(event.getKeyValue().getKey().toString(UTF_8));
                }
            })
        );
    }
}
```

---

### Service Mesh (Istio / Linkerd)

```
Without service mesh: each service handles its own discovery, retries, mTLS, tracing
With service mesh: sidecar proxy (Envoy) handles all of it transparently

[Service A] → [Envoy sidecar] → network → [Envoy sidecar] → [Service B]
                     ↕ (mTLS, retries, circuit breaking, tracing, metrics)
               [Control Plane: Istio/Linkerd]
                     ↕ (configuration, policy)
               [Service Registry (etcd/ZooKeeper)]
```

```yaml
# Istio VirtualService: traffic management without code changes
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: product-service
spec:
  hosts: [product-service]
  http:
  - match: [uri: {prefix: /api/v2}]
    route: [{destination: {host: product-service, subset: v2}}]
  - route: [{destination: {host: product-service, subset: v1}}]
```

---

## API Gateway vs Load Balancer vs Service Mesh

| Feature | LB (L4/L7) | API Gateway | Service Mesh |
|---------|-----------|-------------|--------------|
| Protocol-aware | L7: yes | Yes | Yes |
| Auth/authz | No | Yes | Yes (mTLS) |
| Rate limiting | No | Yes | Yes |
| Service discovery | No | Yes | Yes |
| Request transformation | No | Yes | Limited |
| Observability | Basic | Yes | Full |
| East-west traffic | No | Limited | Yes |
| Code changes needed | No | Config | No (sidecar) |

---

## When to Use What

| Use Case | Solution |
|----------|----------|
| Public API management | API Gateway (Kong, AWS API GW, Spring Cloud GW) |
| Internal service-to-service (small) | Client-side discovery (Eureka + Ribbon) |
| Kubernetes-native | K8s Services + Ingress, or Istio |
| Multi-cloud / complex traffic | Service Mesh (Istio) |
| Simple health-checked registry | Consul |

---

## Interview Questions

1. **What's the difference between API Gateway and Load Balancer?** LB distributes traffic. API GW also does auth, rate limiting, routing by content, response transformation.
2. **Client-side vs server-side discovery?** Client-side: client queries registry, picks instance (smart client). Server-side: client calls LB, LB does discovery (simpler client, LB is bottleneck).
3. **How does K8s service discovery work?** CoreDNS resolves service names to ClusterIP. kube-proxy routes ClusterIP to one of the pod IPs. Endpoints controller updates pod list.
4. **What is a service mesh and when do you need one?** Sidecar proxies handle all inter-service communication (retries, mTLS, tracing) without code changes. Need it when you have many services and want consistent observability and security without each team reimplementing it.
