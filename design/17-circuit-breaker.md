# Circuit Breaker & Resilience Patterns

## What is a Circuit Breaker?
Wraps a remote call. If the remote service starts failing repeatedly, the circuit "opens" — further calls are rejected immediately without hitting the failing service. This prevents cascade failures.

Named after electrical circuit breakers: if current is too high, breaker trips to protect the rest of the circuit.

---

## States

```
CLOSED → (failure threshold crossed) → OPEN → (timeout) → HALF_OPEN
  ↑                                                            |
  └──────── (test call succeeds) ─────────────────────────────┘
  └──────── (test call fails) ──────── back to OPEN ──────────┘
```

- **CLOSED**: Normal operation. Calls pass through.
- **OPEN**: Failing. All calls rejected immediately (fast fail).
- **HALF_OPEN**: Testing recovery. Limited calls allowed through.

---

## Java Implementation from Scratch

```java
public class CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }

    private State state = State.CLOSED;
    private int failureCount = 0;
    private int successCount = 0;
    private long openedAt = 0;

    private final int failureThreshold;    // failures before opening
    private final int successThreshold;    // successes in HALF_OPEN before closing
    private final long openDurationMs;     // how long to stay OPEN before HALF_OPEN

    public CircuitBreaker(int failureThreshold, int successThreshold, long openDurationMs) {
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.openDurationMs = openDurationMs;
    }

    public <T> T execute(Supplier<T> operation, Supplier<T> fallback) {
        if (shouldBlock()) {
            System.out.println("Circuit OPEN — fast failing");
            return fallback != null ? fallback.get() : null;
        }

        try {
            T result = operation.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            if (fallback != null) return fallback.get();
            throw e;
        }
    }

    private synchronized boolean shouldBlock() {
        switch (state) {
            case CLOSED:   return false;
            case OPEN:
                if (System.currentTimeMillis() - openedAt >= openDurationMs) {
                    transitionTo(State.HALF_OPEN);
                    return false; // let one request through
                }
                return true;
            case HALF_OPEN: return false; // let limited requests through
            default: return false;
        }
    }

    private synchronized void onSuccess() {
        switch (state) {
            case CLOSED:
                failureCount = 0; // reset on success
                break;
            case HALF_OPEN:
                successCount++;
                if (successCount >= successThreshold) {
                    transitionTo(State.CLOSED);
                }
                break;
        }
    }

    private synchronized void onFailure() {
        switch (state) {
            case CLOSED:
                failureCount++;
                if (failureCount >= failureThreshold) {
                    transitionTo(State.OPEN);
                }
                break;
            case HALF_OPEN:
                transitionTo(State.OPEN); // test call failed — stay open
                break;
        }
    }

    private void transitionTo(State newState) {
        System.out.println("Circuit transitioning: " + state + " → " + newState);
        state = newState;
        if (newState == State.OPEN) openedAt = System.currentTimeMillis();
        if (newState == State.CLOSED) { failureCount = 0; successCount = 0; }
        if (newState == State.HALF_OPEN) successCount = 0;
    }
}
```

### Usage

```java
CircuitBreaker cb = new CircuitBreaker(5, 2, 30_000); // open after 5 failures, 30s open window

public Order getOrder(String orderId) {
    return cb.execute(
        () -> orderServiceClient.getOrder(orderId),    // primary call
        () -> orderCache.get(orderId)                  // fallback: stale cache
    );
}
```

---

## Resilience4j (Production Library)

```java
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.*;
import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.timelimiter.*;

@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker paymentCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)              // last 10 calls
            .failureRateThreshold(50)           // open if 50%+ fail
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3) // test with 3 calls
            .recordExceptions(IOException.class, TimeoutException.class)
            .ignoreExceptions(BusinessException.class) // don't count biz errors
            .build();
        return CircuitBreaker.of("payment-service", config);
    }

    @Bean
    public Retry paymentRetry() {
        RetryConfig config = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .retryExceptions(IOException.class)
            .ignoreExceptions(BusinessException.class)
            .build();
        return Retry.of("payment-retry", config);
    }

    @Bean
    public Bulkhead paymentBulkhead() {
        // Limit concurrent calls to payment service
        BulkheadConfig config = BulkheadConfig.custom()
            .maxConcurrentCalls(20)     // max 20 concurrent
            .maxWaitDuration(Duration.ofMillis(100)) // wait up to 100ms for slot
            .build();
        return Bulkhead.of("payment-bulkhead", config);
    }

    @Bean
    public TimeLimiter paymentTimeLimiter() {
        return TimeLimiter.of(TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofSeconds(2))
            .build());
    }
}

@Service
public class PaymentService {
    private final CircuitBreaker cb;
    private final Retry retry;
    private final Bulkhead bulkhead;

    public PaymentResult processPayment(PaymentRequest request) {
        // Chain: Bulkhead → CircuitBreaker → Retry → TimeLimiter → actual call
        Supplier<PaymentResult> decoratedCall = Decorators
            .ofSupplier(() -> paymentGateway.charge(request))
            .withBulkhead(bulkhead)
            .withCircuitBreaker(cb)
            .withRetry(retry)
            .withFallback(
                List.of(CallNotPermittedException.class, BulkheadFullException.class),
                e -> PaymentResult.fallback("Service unavailable, please retry")
            )
            .decorate();

        return decoratedCall.get();
    }
}
```

---

## Bulkhead Pattern

Isolate failures — like watertight compartments in a ship. One service failing doesn't exhaust resources for others.

```java
// WITHOUT Bulkhead: one slow service exhausts all threads
// ProductService slow → all 200 threads blocked → OrderService also fails

// WITH Bulkhead: each service gets its own thread pool
@Configuration
public class BulkheadConfig {
    @Bean("productServiceExecutor")
    public ExecutorService productServiceExecutor() {
        return new ThreadPoolExecutor(
            5, 10,                    // core 5, max 10 threads
            60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20),  // queue up to 20
            new ThreadPoolExecutor.AbortPolicy() // reject if full → fail fast
        );
    }

    @Bean("inventoryServiceExecutor")
    public ExecutorService inventoryServiceExecutor() {
        return new ThreadPoolExecutor(5, 10, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20), new ThreadPoolExecutor.AbortPolicy());
    }
}

public class OrderService {
    @Autowired @Qualifier("productServiceExecutor")
    private ExecutorService productExecutor;

    @Autowired @Qualifier("inventoryServiceExecutor")
    private ExecutorService inventoryExecutor;

    public OrderDetails getOrderDetails(String orderId) {
        // Each external call uses its own isolated thread pool
        Future<Product> productFuture = productExecutor.submit(
            () -> productService.getProduct(orderId));
        Future<Inventory> inventoryFuture = inventoryExecutor.submit(
            () -> inventoryService.getInventory(orderId));

        // If productService is slow, it only blocks productExecutor threads
        // inventoryExecutor is completely unaffected
        try {
            return new OrderDetails(
                productFuture.get(2, TimeUnit.SECONDS),
                inventoryFuture.get(2, TimeUnit.SECONDS)
            );
        } catch (TimeoutException e) {
            productFuture.cancel(true);
            inventoryFuture.cancel(true);
            return OrderDetails.partial(); // fallback
        }
    }
}
```

---

## Retry Pattern with Exponential Backoff

```java
public class RetryTemplate {
    public <T> T execute(Supplier<T> operation, int maxRetries, Duration initialDelay) {
        Duration delay = initialDelay;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return operation.get();
            } catch (RetryableException e) {
                lastException = e;
                if (attempt == maxRetries) break;

                // Exponential backoff with jitter
                long jitter = (long) (Math.random() * delay.toMillis() * 0.3);
                long sleepMs = delay.toMillis() + jitter;
                System.out.printf("Attempt %d failed, retrying in %dms%n", attempt, sleepMs);

                try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }

                delay = delay.multipliedBy(2); // exponential
                if (delay.toMillis() > 30_000) delay = Duration.ofSeconds(30); // cap at 30s
            } catch (NonRetryableException e) {
                throw e; // don't retry business errors
            }
        }
        throw new RuntimeException("Failed after " + maxRetries + " attempts", lastException);
    }
}
```

---

## Timeout Pattern

```java
// Always set timeouts — without them, one slow service blocks forever
public class TimeoutHttpClient {
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    public <T> T callWithTimeout(HttpRequest request, Class<T> responseType, Duration timeout) {
        try {
            CompletableFuture<HttpResponse<String>> future = client.sendAsync(
                request, HttpResponse.BodyHandlers.ofString());

            HttpResponse<String> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return deserialize(response.body(), responseType);
        } catch (TimeoutException e) {
            throw new ServiceTimeoutException("Service call timed out after " + timeout);
        } catch (Exception e) {
            throw new ServiceException("Service call failed", e);
        }
    }
}

// Rule of thumb for timeouts:
// DB query:    200ms-1s
// HTTP call:   1-5s
// Background:  30s-5min
// Always set both connect timeout AND read timeout
```

---

## Fallback Strategies

```java
public class ProductService {

    // 1. Cached/stale data fallback
    public Product getProductWithCacheFallback(String id) {
        return circuitBreaker.execute(
            () -> productDB.findById(id),
            () -> productCache.get(id) // stale but better than error
        );
    }

    // 2. Default/empty value fallback
    public List<Product> getRecommendations(String userId) {
        return circuitBreaker.execute(
            () -> recommendationService.getForUser(userId),
            () -> popularProductsCache.getTopProducts(10) // generic fallback
        );
    }

    // 3. Fail fast with user-friendly error
    public void checkout(Order order) {
        circuitBreaker.execute(
            () -> { paymentService.charge(order); return null; },
            () -> { throw new ServiceUnavailableException("Payment is temporarily unavailable. Please try again in 30 seconds."); }
        );
    }
}
```

---

## Monitoring & Alerting

```java
// Resilience4j emits metrics — expose to Prometheus/Grafana
@Configuration
public class MetricsConfig {
    @PostConstruct
    public void bindMetrics() {
        // Circuit breaker metrics
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry)
            .bindTo(meterRegistry);
        // Exposes:
        // resilience4j_circuitbreaker_state{name="payment"} = 0/1/2 (closed/open/half-open)
        // resilience4j_circuitbreaker_failure_rate{name="payment"} = 0.0-1.0
        // resilience4j_circuitbreaker_calls_total{name="payment", kind="successful"}

        // Alert when circuit opens for > 5 minutes
        // alert: sum(resilience4j_circuitbreaker_state{state="open"}) > 0 for 5m
    }
}
```

---

## Patterns Summary

| Pattern | Protects Against | Mechanism |
|---------|-----------------|-----------|
| Circuit Breaker | Cascade failure | Fast-fail when service is down |
| Retry | Transient failures | Retry with backoff |
| Bulkhead | Resource exhaustion | Isolate thread pools/semaphores |
| Timeout | Slow dependencies | Cut off slow calls |
| Fallback | Any failure | Return safe default or cached data |
| Rate Limiter | Overload | Cap request rate |

---

## When to Use
- Any inter-service HTTP/gRPC call
- Database connections (separate pool per DB)
- External API calls (payment, email, SMS)

## When to Avoid
- Intra-process calls (no network, no need)
- When you can't provide a meaningful fallback (better to fail clearly)
- Very low latency paths where the overhead matters

---

## Interview Questions

1. **What's the difference between Circuit Breaker and Retry?** Retry: handle transient failures by re-attempting. CB: stop retrying when a service is persistently down. They complement each other — Retry inside CB.
2. **How do you prevent cascade failures?** Circuit breaker (stop calling failing service), bulkhead (isolate resources), timeout (don't wait forever), fallback (degrade gracefully).
3. **What is the thundering herd problem after a circuit reopens?** All clients retry at once when circuit goes HALF_OPEN → overwhelms recovering service. Fix: limit calls in HALF_OPEN state; add jitter to retry backoff.
4. **How does Netflix Hystrix differ from Resilience4j?** Hystrix is deprecated. Resilience4j is its modern replacement — functional API, reactive support, lighter weight, actively maintained.
