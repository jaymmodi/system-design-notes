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

## Amazon API Gateway — Deep Dive

### Three flavors

```
REST API Gateway    ← full-featured, most commonly used
                      auth, caching, throttling, request/response transform
                      supports: REST, HTTP, WebSocket (separate product)

HTTP API Gateway    ← newer, cheaper, simpler, lower latency
                      ~70% cheaper than REST API
                      JWT auth only (no Cognito, no Lambda authorizer in same way)
                      no caching, no request transform

WebSocket API       ← persistent connections, pub/sub routing
                      connection management via $connect/$disconnect/$default routes
```

```
                        Client
                          │
                   API Gateway (edge)
                          │
         ┌────────────────┼──────────────────┐
         │                │                  │
    Lambda fn        ECS/Fargate        HTTP backend
    (serverless)     (containers)       (EC2, on-prem)
         │                │                  │
    [business logic]  [service]         [service]
```

---

### Feature Comparison

| Feature | REST API | HTTP API | WebSocket API |
|---------|----------|----------|---------------|
| Lambda integration | ✓ | ✓ | ✓ |
| HTTP backend | ✓ | ✓ | ✓ |
| IAM auth | ✓ | ✓ | ✓ |
| Cognito auth | ✓ | ✓ (JWT) | ✓ |
| Lambda authorizer | ✓ | ✓ | ✓ |
| API key auth | ✓ | ✗ | ✗ |
| Request/response transform | ✓ (VTL templates) | ✗ | ✗ |
| Response caching | ✓ | ✗ | ✗ |
| Usage plans / throttling | ✓ | ✓ (basic) | ✗ |
| WAF integration | ✓ | ✓ | ✗ |
| Custom domain | ✓ | ✓ | ✓ |
| TLS termination | ✓ (automatic) | ✓ (automatic) | ✓ |
| VPC Link (private backend) | ✓ | ✓ | ✗ |
| Price (per 1M requests) | $3.50 | $1.00 | $1.00 + $0.25/M msgs |

---

### TLS Termination + Custom Domain

API Gateway terminates TLS automatically. You bring your cert via ACM:

```
Client ──HTTPS──→ API Gateway (TLS terminated here)
                       │ HTTP or HTTPS (your choice)
                       ↓
                  Backend (Lambda / ECS / EC2)

No cert management on your backend servers.
```

```yaml
# CloudFormation / SAM — custom domain + cert
Resources:
  ApiCertificate:
    Type: AWS::CertificateManager::Certificate
    Properties:
      DomainName: api.myapp.com
      ValidationMethod: DNS

  CustomDomain:
    Type: AWS::ApiGateway::DomainName
    Properties:
      DomainName: api.myapp.com
      CertificateArn: !Ref ApiCertificate
      SecurityPolicy: TLS_1_2        # enforce minimum TLS version

  BasePathMapping:
    Type: AWS::ApiGateway::BasePathMapping
    Properties:
      DomainName: !Ref CustomDomain
      RestApiId: !Ref MyApi
      Stage: prod
      BasePath: v1                   # api.myapp.com/v1 → this API
```

Route 53 alias to API Gateway:
```yaml
  DnsRecord:
    Type: AWS::Route53::RecordSet
    Properties:
      HostedZoneId: !Ref HostedZone
      Name: api.myapp.com
      Type: A
      AliasTarget:
        DNSName: !GetAtt CustomDomain.DistributionDomainName
        HostedZoneId: !GetAtt CustomDomain.DistributionHostedZoneId
```

---

### Authentication — 4 methods

#### 1. IAM Auth (AWS Signature V4) — service-to-service

```
Use when: backend services calling your API (not end users)
           mobile apps using Cognito Identity Pool credentials

Client signs request with AWS credentials → API GW validates signature
No Lambda needed — pure AWS IAM check

// Java SDK — signed request
AwsRequestSigner signer = AwsRequestSigner.create(
    credentialsProvider,
    "execute-api",
    "us-east-1"
);
HttpRequest signedRequest = signer.sign(httpRequest);
```

```yaml
# Terraform — enable IAM auth on a method
resource "aws_api_gateway_method" "get_orders" {
  rest_api_id   = aws_api_gateway_rest_api.api.id
  resource_id   = aws_api_gateway_resource.orders.id
  http_method   = "GET"
  authorization = "AWS_IAM"    # ← IAM auth
}
```

#### 2. Cognito User Pool Authorizer — end-user JWT

```
Use when: your users log in via Cognito (web/mobile apps)

Flow:
  1. User logs in → Cognito issues JWT (ID token + Access token)
  2. Client sends: Authorization: Bearer <jwt>
  3. API GW validates JWT against Cognito User Pool (no Lambda needed)
  4. Request forwarded with user claims in headers
```

```yaml
# CloudFormation
  CognitoAuthorizer:
    Type: AWS::ApiGateway::Authorizer
    Properties:
      Name: CognitoAuth
      RestApiId: !Ref MyApi
      Type: COGNITO_USER_POOLS
      IdentitySource: method.request.header.Authorization
      ProviderARNs:
        - !GetAtt UserPool.Arn

  # Apply to method:
  GetOrdersMethod:
    Type: AWS::ApiGateway::Method
    Properties:
      AuthorizationType: COGNITO_USER_POOLS
      AuthorizerId: !Ref CognitoAuthorizer
```

```java
// Backend Lambda receives user claims automatically
public APIGatewayProxyResponseEvent handleRequest(
        APIGatewayProxyRequestEvent event, Context context) {

    // Cognito claims injected by API GW
    Map<String, Object> claims = (Map<String, Object>)
        event.getRequestContext()
             .getAuthorizer()
             .get("claims");

    String userId = (String) claims.get("sub");           // Cognito user ID
    String email  = (String) claims.get("email");
    String groups = (String) claims.get("cognito:groups"); // group membership
    // No JWT validation needed in Lambda — API GW already did it
}
```

#### 3. Lambda Authorizer — custom auth logic

```
Use when: existing auth system (Auth0, Okta, custom JWT, API key in DB)
           need custom logic: check user status, feature flags, etc.

Flow:
  1. Client sends request with token (any format)
  2. API GW calls your Lambda authorizer with the token
  3. Lambda validates token → returns IAM policy (Allow/Deny)
  4. API GW caches policy (TTL configurable) → avoids Lambda call per request
  5. If Allow → forward to backend
```

```java
// Lambda Authorizer function
public APIGatewayCustomAuthorizerResponse handleRequest(
        APIGatewayCustomAuthorizerRequest request, Context context) {

    String token = request.getAuthorizationToken(); // "Bearer eyJ..."
    String methodArn = request.getMethodArn();
    // arn:aws:execute-api:us-east-1:123456:abc123/prod/GET/orders

    try {
        // Validate token — your custom logic
        UserClaims claims = tokenValidator.validate(token.replace("Bearer ", ""));

        // Build IAM policy — what this user can access
        return buildPolicy(claims.getUserId(), "Allow", methodArn, claims);

    } catch (UnauthorizedException e) {
        return buildPolicy("user", "Deny", methodArn, null);
    }
}

private APIGatewayCustomAuthorizerResponse buildPolicy(
        String principalId, String effect, String resource,
        UserClaims claims) {

    // IAM policy document
    Statement statement = Statement.builder()
        .effect(effect)
        .resource(resource)   // can use wildcard: arn:aws:execute-api:*
        .build();

    PolicyDocument policy = PolicyDocument.builder()
        .statements(List.of(statement))
        .build();

    // Context passed to backend Lambda/service
    Map<String, Object> context = claims != null ? Map.of(
        "userId",    claims.getUserId(),
        "email",     claims.getEmail(),
        "plan",      claims.getPlan(),      // "free" | "pro" | "enterprise"
        "orgId",     claims.getOrgId()
    ) : Map.of();

    return APIGatewayCustomAuthorizerResponse.builder()
        .principalId(principalId)
        .policyDocument(policy)
        .context(context)                  // ← forwarded to backend
        .build();
}
```

```yaml
  LambdaAuthorizer:
    Type: AWS::ApiGateway::Authorizer
    Properties:
      Name: CustomAuth
      RestApiId: !Ref MyApi
      Type: TOKEN                          # TOKEN or REQUEST
      IdentitySource: method.request.header.Authorization
      AuthorizerUri: !Sub
        arn:aws:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/${AuthorizerLambda.Arn}/invocations
      AuthorizerResultTtlInSeconds: 300    # cache policy 5 min — avoid Lambda per request
```

#### 4. API Key — simple rate-limit by client

```
Use when: B2B APIs, partner integrations, per-client throttling
NOT for security — API keys are not auth, anyone can share them
Use alongside IAM or JWT, not instead of

Flow:
  Client sends: x-api-key: abc123def456
  API GW validates key exists + within usage plan limits
  Throttled if exceeded → 429 Too Many Requests
```

```yaml
  ApiKey:
    Type: AWS::ApiGateway::ApiKey
    Properties:
      Name: partner-acme-key
      Enabled: true

  UsagePlan:
    Type: AWS::ApiGateway::UsagePlan
    Properties:
      UsagePlanName: partner-plan
      Throttle:
        RateLimit: 1000          # 1000 req/sec
        BurstLimit: 2000         # burst up to 2000
      Quota:
        Limit: 1000000           # 1M requests per month
        Period: MONTH
      ApiStages:
        - ApiId: !Ref MyApi
          Stage: prod

  UsagePlanKey:
    Type: AWS::ApiGateway::UsagePlanKey
    Properties:
      KeyId: !Ref ApiKey
      KeyType: API_KEY
      UsagePlanId: !Ref UsagePlan
```

---

### Authorization — resource-level with Lambda authorizer

```
Authentication = who are you? (verified above)
Authorization  = what can you do?

Lambda authorizer returns an IAM policy scoped to specific resources:

// User on "free" plan → can only call read endpoints
// User on "pro" plan  → can call all endpoints
private String buildResourceArn(String apiId, String stage, String method, String path) {
    // arn:aws:execute-api:region:account:apiId/stage/method/path
    return String.format("arn:aws:execute-api:us-east-1:123456789:%s/%s/%s/%s",
        apiId, stage, method, path);
}

// Free plan: allow GET only
Statement allowReads = Statement.builder()
    .effect("Allow")
    .resource("arn:aws:execute-api:us-east-1:123456:abc/prod/GET/*")
    .build();

// Pro plan: allow everything
Statement allowAll = Statement.builder()
    .effect("Allow")
    .resource("arn:aws:execute-api:us-east-1:123456:abc/prod/*/*")
    .build();
```

---

### Routing

#### Path-based routing
```yaml
# API GW resource tree maps to URL paths
/                          → root
  /orders                  → GET (list), POST (create)
    /{orderId}             → GET (get one), PUT (update), DELETE
      /items               → GET (order items)
  /products
    /{productId}
  /users
    /{userId}
      /preferences
```

```java
// Each resource+method → integration (Lambda, HTTP, Mock)
// Path parameters available in event
public APIGatewayProxyResponseEvent handleRequest(
        APIGatewayProxyRequestEvent event, Context context) {

    String orderId = event.getPathParameters().get("orderId");
    String method  = event.getHttpMethod();          // GET, POST, etc.
    Map<String, String> query = event.getQueryStringParameters();
    Map<String, String> headers = event.getHeaders();
}
```

#### Stage-based routing (versioning)
```
prod stage:    https://abc123.execute-api.us-east-1.amazonaws.com/prod/orders
staging stage: https://abc123.execute-api.us-east-1.amazonaws.com/staging/orders
v2 stage:      https://abc123.execute-api.us-east-1.amazonaws.com/v2/orders

Each stage → different Lambda alias or different backend URL
prod   → Lambda:prod  alias → stable version
staging → Lambda:staging alias → release candidate
v2     → Lambda:v2 alias → new version (canary: 10% traffic)
```

#### Canary deployments
```yaml
  ApiStage:
    Type: AWS::ApiGateway::Stage
    Properties:
      StageName: prod
      DeploymentId: !Ref Deployment
      CanarySetting:
        PercentTraffic: 10           # 10% to canary
        StageVariableOverrides:
          lambdaAlias: canary        # canary traffic → Lambda:canary alias
```

---

### Load Balancing — VPC Link to private backends

API GW is public-facing. To route to private ECS/EC2 services:

```
Internet → API Gateway → VPC Link → NLB (private) → ECS Service
                                    (in your VPC)
```

```yaml
  VpcLink:
    Type: AWS::ApiGateway::VpcLink
    Properties:
      Name: private-backend-link
      TargetArns:
        - !Ref PrivateNLB        # NLB in your VPC

  # Integration using VPC Link
  Integration:
    Type: AWS::ApiGateway::Method
    Properties:
      Integration:
        Type: HTTP_PROXY
        ConnectionType: VPC_LINK
        ConnectionId: !Ref VpcLink
        Uri: !Sub http://${PrivateNLB.DNSName}/{proxy}
        HttpMethod: ANY
```

```
Traffic flow:
  Client → api.myapp.com (Route53)
        → API Gateway (public, TLS terminated)
        → VPC Link
        → NLB (private subnet, no internet access)
        → ECS tasks (private subnet)
        → RDS / ElastiCache (private subnet)

Backend ECS tasks have NO public IP. Only reachable via NLB → VPC Link.
```

---

### Throttling + Rate Limiting

```
Two levels:

1. Account-level default: 10,000 req/sec, burst 5,000
   (soft limit — request increase from AWS)

2. Stage + method level: override per route
   GET /orders      → 1,000 req/sec
   POST /orders     → 100 req/sec  (writes are more expensive)
   GET /search      → 500 req/sec

3. Usage plan (per API key): throttle individual clients
```

```yaml
  ApiStage:
    Type: AWS::ApiGateway::Stage
    Properties:
      MethodSettings:
        - ResourcePath: /orders
          HttpMethod: GET
          ThrottlingRateLimit: 1000
          ThrottlingBurstLimit: 2000
        - ResourcePath: /orders
          HttpMethod: POST
          ThrottlingRateLimit: 100
          ThrottlingBurstLimit: 200
```

Throttled requests → `429 Too Many Requests` returned by API GW (never reaches backend).

---

### Request/Response Transformation (REST API only)

Using Velocity Template Language (VTL) — transform without Lambda:

```
Client sends:                    Backend receives:
{                                {
  "user_id": "123",    →           "userId": "123",
  "product_id": "456"              "productId": "456",
}                                  "requestTime": "2024-01-01T..."
                                 }
```

```vtl
## Request mapping template (VTL)
{
  "userId": "$input.path('$.user_id')",
  "productId": "$input.path('$.product_id')",
  "requestTime": "$context.requestTime",
  "sourceIp": "$context.identity.sourceIp"
}

## Response mapping template
#set($body = $input.path('$'))
{
  "id": "$body.order_id",
  "status": "$body.order_status",
  "total": $body.amount
}
```

Useful for: legacy backend with different field names, adding metadata, hiding internal fields.

---

### Caching (REST API only)

```yaml
  ApiStage:
    Type: AWS::ApiGateway::Stage
    Properties:
      CacheClusterEnabled: true
      CacheClusterSize: "0.5"       # 0.5GB cache
      MethodSettings:
        - ResourcePath: /products/{productId}
          HttpMethod: GET
          CachingEnabled: true
          CacheTtlInSeconds: 300    # cache 5 min
          CacheDataEncrypted: true
          # Cache key = URL path by default
          # Can add query params or headers to cache key
```

```
GET /products/123  → cache miss → Lambda → response cached 5 min
GET /products/123  → cache hit  → returned immediately, Lambda NOT invoked
GET /products/456  → cache miss (different key) → Lambda invoked

Cache invalidation: client sends Cache-Control: max-age=0
Or: invalidate via API GW console / CLI
```

---

### WAF Integration

```yaml
  WafAcl:
    Type: AWS::WAFv2::WebACL
    Properties:
      Scope: REGIONAL
      DefaultAction:
        Allow: {}
      Rules:
        - Name: RateLimitRule
          Priority: 1
          Action:
            Block: {}
          Statement:
            RateBasedStatement:
              Limit: 2000              # 2000 req per 5 min per IP
              AggregateKeyType: IP
          VisibilityConfig:
            SampledRequestsEnabled: true
            CloudWatchMetricsEnabled: true
            MetricName: RateLimitRule

        - Name: AWSManagedRulesCommonRuleSet
          Priority: 2
          OverrideAction:
            None: {}
          Statement:
            ManagedRuleGroupStatement:
              VendorName: AWS
              Name: AWSManagedRulesCommonRuleSet  # OWASP top 10 protection
          VisibilityConfig:
            SampledRequestsEnabled: true
            CloudWatchMetricsEnabled: true
            MetricName: CommonRules

  WafAssociation:
    Type: AWS::WAFv2::WebACLAssociation
    Properties:
      ResourceArn: !Sub
        arn:aws:apigateway:${AWS::Region}::/restapis/${MyApi}/stages/prod
      WebACLArn: !GetAtt WafAcl.Arn
```

---

### Full architecture — putting it all together

```
                     Route 53
                        │  api.myapp.com → ACM cert → API GW custom domain
                        ↓
                  API Gateway (REST)
                        │
          ┌─────────────┼──────────────┐
          │             │              │
     WAF check    Throttle check   API Key check
          │             │              │
          └─────────────┴──────────────┘
                        │
              Lambda Authorizer
              (validate JWT, return IAM policy, cache 5 min)
                        │
                   Route match
          ┌─────────────┼──────────────┐
          │             │              │
    GET /products  POST /orders   GET /search
     (cached 5m)   (VPC Link)    (Lambda)
          │             │              │
       Cache hit?   NLB → ECS    Lambda fn
          │
       Return cached or invoke Lambda

All traffic: CloudWatch Logs + X-Ray tracing
```

---

### CORS — common gotcha

```yaml
# REST API — enable CORS on each resource
  OptionsMethod:
    Type: AWS::ApiGateway::Method
    Properties:
      HttpMethod: OPTIONS
      AuthorizationType: NONE
      Integration:
        Type: MOCK
        RequestTemplates:
          application/json: '{"statusCode": 200}'
        IntegrationResponses:
          - StatusCode: 200
            ResponseParameters:
              method.response.header.Access-Control-Allow-Origin: "'https://myapp.com'"
              method.response.header.Access-Control-Allow-Methods: "'GET,POST,OPTIONS'"
              method.response.header.Access-Control-Allow-Headers: "'Content-Type,Authorization'"
```

```java
// Lambda must also return CORS headers (API GW doesn't add them to Lambda responses)
return APIGatewayProxyResponseEvent.builder()
    .statusCode(200)
    .headers(Map.of(
        "Access-Control-Allow-Origin",  "https://myapp.com",
        "Access-Control-Allow-Methods", "GET,POST,OPTIONS",
        "Content-Type", "application/json"
    ))
    .body(responseBody)
    .build();
```

---

### REST API vs HTTP API — when to use which

```
Use REST API when:
  ✓ Need request/response transformation (VTL)
  ✓ Need response caching
  ✓ Need API keys + usage plans
  ✓ Need WAF (both support WAF now)
  ✓ Need Cognito authorizer (both support it)
  ✓ Need fine-grained per-method throttling

Use HTTP API when:
  ✓ Just need JWT auth (OIDC/OAuth2) — Auth0, Okta, Cognito
  ✓ Pure Lambda proxy or HTTP proxy — no transform needed
  ✓ Cost matters (70% cheaper)
  ✓ Lower latency (~10ms less overhead than REST API)
  ✓ CORS support built-in (no manual OPTIONS method)
```

---

## Interview Questions

1. **What's the difference between API Gateway and Load Balancer?** LB distributes traffic. API GW also does auth, rate limiting, routing by content, response transformation.
2. **Client-side vs server-side discovery?** Client-side: client queries registry, picks instance (smart client). Server-side: client calls LB, LB does discovery (simpler client, LB is bottleneck).
3. **How does K8s service discovery work?** CoreDNS resolves service names to ClusterIP. kube-proxy routes ClusterIP to one of the pod IPs. Endpoints controller updates pod list.
4. **What is a service mesh and when do you need one?** Sidecar proxies handle all inter-service communication (retries, mTLS, tracing) without code changes. Need it when you have many services and want consistent observability and security without each team reimplementing it.
5. **How do you secure an API Gateway endpoint?** Layered: WAF (IP blocking, OWASP rules) → throttling (rate limit per key/IP) → authentication (Lambda authorizer, Cognito, IAM) → authorization (IAM policy from authorizer scoped to specific routes).
6. **How does API GW route to private ECS services?** VPC Link → NLB in private subnet → ECS tasks. API GW is public; backends have no public IP. NLB is the only entry point into the VPC.
7. **REST API vs HTTP API?** REST API: full-featured (caching, transform, API keys). HTTP API: 70% cheaper, lower latency, JWT only, no transform. Use HTTP API by default unless you need REST API features.
