# Rate Limiting

## What & Why
Controls how many requests a client can make in a time window. Protects services from:
- Accidental overload (buggy clients)
- Intentional abuse (DDoS, brute force)
- Fair resource sharing between tenants

---

## Algorithms

### 1. Token Bucket
Tokens accumulate at a fixed rate. Each request consumes one token. Allows burst up to bucket capacity.

```java
public class TokenBucketRateLimiter {
    private final long capacity;       // max tokens (burst limit)
    private final double refillRate;   // tokens per second
    private double tokens;
    private long lastRefillTime;

    public TokenBucketRateLimiter(long capacity, double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity; // start full
        this.lastRefillTime = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true; // allowed
        }
        return false; // rate limited
    }

    public synchronized boolean tryAcquire(int permits) {
        refill();
        if (tokens >= permits) {
            tokens -= permits;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillTime) / 1e9; // seconds
        tokens = Math.min(capacity, tokens + elapsed * refillRate);
        lastRefillTime = now;
    }
}

// Usage
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10); // 10 RPS, burst 100
if (!limiter.tryAcquire()) {
    throw new RateLimitExceededException("429 Too Many Requests");
}
// Guava RateLimiter is a well-tested token bucket implementation
RateLimiter guavaLimiter = RateLimiter.create(10.0); // 10 permits/second
guavaLimiter.acquire(); // blocks until token available
```

**Pros**: Allows burst traffic, smooth refill
**Cons**: Hard to track globally across distributed nodes (in-memory only)

---

### 2. Leaky Bucket
Requests enter a queue (bucket), processed at fixed rate. Excess requests dropped or queued.

```java
public class LeakyBucketRateLimiter {
    private final BlockingQueue<Runnable> bucket;
    private final ScheduledExecutorService processor;
    private final int leakRatePerSecond;

    public LeakyBucketRateLimiter(int bucketSize, int leakRatePerSecond) {
        this.bucket = new LinkedBlockingQueue<>(bucketSize);
        this.leakRatePerSecond = leakRatePerSecond;
        this.processor = Executors.newSingleThreadScheduledExecutor();

        long intervalMs = 1000 / leakRatePerSecond;
        processor.scheduleAtFixedRate(this::processOne, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    public boolean submit(Runnable request) {
        return bucket.offer(request); // returns false if bucket full → DROP
    }

    private void processOne() {
        Runnable request = bucket.poll();
        if (request != null) request.run();
    }
}
// Pros: Output rate is perfectly smooth (no burst)
// Cons: Bursty traffic is dropped or delayed; less forgiving than token bucket
```

---

### 3. Fixed Window Counter
Count requests per time window. Reset at window boundary.

```java
public class FixedWindowRateLimiter {
    private final int maxRequests;
    private final long windowMs;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public boolean isAllowed(String clientId) {
        WindowCounter counter = counters.computeIfAbsent(clientId, k -> new WindowCounter());
        return counter.increment(maxRequests, windowMs);
    }

    static class WindowCounter {
        private long windowStart = System.currentTimeMillis();
        private int count = 0;

        synchronized boolean increment(int max, long windowMs) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= windowMs) {
                // New window — reset
                windowStart = now;
                count = 0;
            }
            if (count < max) {
                count++;
                return true;
            }
            return false;
        }
    }
}
// BIG PROBLEM: Edge case — burst at window boundary
// Allow 100 req at 11:59:59 + 100 req at 12:00:00 = 200 req in 2 seconds!
```

---

### 4. Sliding Window Log — Most Accurate

```java
// Store timestamp of each request. Count requests within rolling window.
public class SlidingWindowLogRateLimiter {
    private final int maxRequests;
    private final long windowMs;
    private final ConcurrentHashMap<String, Deque<Long>> logs = new ConcurrentHashMap<>();

    public boolean isAllowed(String clientId) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;

        Deque<Long> log = logs.computeIfAbsent(clientId, k -> new ArrayDeque<>());

        synchronized (log) {
            // Remove timestamps outside the window
            while (!log.isEmpty() && log.peekFirst() <= windowStart) {
                log.pollFirst();
            }

            if (log.size() < maxRequests) {
                log.addLast(now);
                return true;
            }
            return false;
        }
    }
}
// Pros: No boundary problem, most accurate
// Cons: Memory: stores timestamp per request (heavy for high-volume)
```

---

### 5. Sliding Window Counter — Best Balance (Use This)

```java
// Approximate: blend current + previous window counts based on overlap
public class SlidingWindowCounterRateLimiter {
    private final int maxRequests;
    private final long windowMs;
    private final Map<String, long[]> counters = new ConcurrentHashMap<>();
    // [0] = previous window count, [1] = current window count, [2] = current window start

    public boolean isAllowed(String clientId) {
        long now = System.currentTimeMillis();

        long[] state = counters.computeIfAbsent(clientId, k -> new long[]{0, 0, now});

        synchronized (state) {
            long windowStart = state[2];
            long elapsed = now - windowStart;

            if (elapsed >= 2 * windowMs) {
                // Two windows ago — reset completely
                state[0] = 0; state[1] = 0; state[2] = now;
            } else if (elapsed >= windowMs) {
                // Rolled into next window
                state[0] = state[1]; // previous = current
                state[1] = 0;        // reset current
                state[2] = windowStart + windowMs;
            }

            // Weighted count: previous_window * overlap_fraction + current_window
            double overlapFraction = 1.0 - (double)(now - state[2]) / windowMs;
            double estimatedCount = state[0] * overlapFraction + state[1];

            if (estimatedCount < maxRequests) {
                state[1]++;
                return true;
            }
            return false;
        }
    }
}
```

---

## Distributed Rate Limiting with Redis

In-memory solutions only work per-node. For distributed systems, use Redis:

```java
public class RedisRateLimiter {
    private final JedisPool jedisPool;
    private final int maxRequests;
    private final int windowSeconds;

    // Sliding window using Sorted Set
    public boolean isAllowed(String clientId) {
        String key = "ratelimit:" + clientId;
        long now = System.currentTimeMillis();
        long windowStart = now - windowSeconds * 1000L;

        // Lua script — atomic execution on Redis
        String luaScript = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window_start = tonumber(ARGV[2])
            local max_requests = tonumber(ARGV[3])
            local window_seconds = tonumber(ARGV[4])

            -- Remove old entries outside window
            redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)

            -- Count requests in window
            local count = redis.call('ZCARD', key)

            if count < max_requests then
                -- Add this request (score = timestamp, member = unique ID)
                redis.call('ZADD', key, now, now .. math.random())
                redis.call('EXPIRE', key, window_seconds * 2)
                return 1
            else
                return 0
            end
            """;

        try (Jedis jedis = jedisPool.getResource()) {
            Long result = (Long) jedis.eval(
                luaScript,
                List.of(key),
                List.of(String.valueOf(now), String.valueOf(windowStart),
                        String.valueOf(maxRequests), String.valueOf(windowSeconds))
            );
            return result == 1;
        }
    }
}
```

---

## Multi-Tier Rate Limiting

```java
// Layer multiple limits: per-second, per-minute, per-day
public class MultiTierRateLimiter {
    private final RedisRateLimiter perSecond;
    private final RedisRateLimiter perMinute;
    private final RedisRateLimiter perDay;

    public MultiTierRateLimiter(JedisPool pool) {
        perSecond = new RedisRateLimiter(pool, 10, 1);     // 10 req/sec
        perMinute = new RedisRateLimiter(pool, 100, 60);   // 100 req/min
        perDay    = new RedisRateLimiter(pool, 10_000, 86400); // 10K req/day
    }

    public RateLimitResult checkLimit(String clientId) {
        if (!perSecond.isAllowed(clientId))
            return RateLimitResult.limited("rate limit: 10/sec exceeded");
        if (!perMinute.isAllowed(clientId))
            return RateLimitResult.limited("rate limit: 100/min exceeded");
        if (!perDay.isAllowed(clientId))
            return RateLimitResult.limited("rate limit: 10K/day exceeded");
        return RateLimitResult.allowed();
    }
}
```

---

## Rate Limit by Different Dimensions

```java
public class FlexibleRateLimiter {
    // Limit by IP (prevent DDoS)
    public boolean checkByIP(String ip) {
        return limiter.isAllowed("ip:" + ip);
    }

    // Limit by user (fair usage)
    public boolean checkByUser(String userId) {
        return limiter.isAllowed("user:" + userId);
    }

    // Limit by API key / tenant (SaaS tiers)
    public boolean checkByApiKey(String apiKey) {
        int limit = getTierLimit(apiKey); // free: 100/hr, pro: 10K/hr
        return limiter.isAllowedWithLimit("apikey:" + apiKey, limit);
    }

    // Limit by endpoint (expensive endpoints get lower limits)
    public boolean checkByEndpoint(String userId, String endpoint) {
        int limit = endpointLimits.getOrDefault(endpoint, DEFAULT_LIMIT);
        return limiter.isAllowedWithLimit("user:" + userId + ":endpoint:" + endpoint, limit);
    }
}
```

---

## HTTP Response Headers (Standard)

```java
@Component
public class RateLimitFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String clientId = extractClientId(request);
        RateLimitStatus status = rateLimiter.checkWithStatus(clientId);

        // Standard rate limit response headers
        response.setHeader("X-RateLimit-Limit", String.valueOf(status.getLimit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(status.getRemaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(status.getResetTime()));
        response.setHeader("X-RateLimit-Window", "60"); // seconds

        if (!status.isAllowed()) {
            response.setStatus(429); // Too Many Requests
            response.setHeader("Retry-After", String.valueOf(status.getRetryAfterSeconds()));
            response.getWriter().write("{\"error\": \"Rate limit exceeded\"}");
            return;
        }

        chain.doFilter(req, res);
    }
}
```

---

## Algorithm Comparison

| Algorithm | Memory | Burst | Accuracy | Complexity |
|-----------|--------|-------|----------|------------|
| Token Bucket | O(1) | Yes | Good | Low |
| Leaky Bucket | O(bucket) | No | Good | Low |
| Fixed Window | O(1) | Boundary issue | Poor | Very Low |
| Sliding Log | O(requests) | No | Perfect | High |
| Sliding Counter | O(1) | Approximate | Very Good | Low |

**Best choice**: Sliding Window Counter + Redis (distributed, accurate, efficient)

---

## When to Use Rate Limiting
- Public APIs (protect from abuse and overload)
- Login endpoints (brute force protection)
- Password reset / OTP endpoints
- Expensive operations (file upload, ML inference)
- Multi-tenant SaaS (fair resource allocation)

## When It's Not Enough
- DDoS attacks at network layer → need WAF/CDN (Cloudflare, AWS Shield)
- Sophisticated attackers rotating IPs → behavioral analysis needed
- Inside a trusted internal network → circuit breaker is more appropriate

---

## Interview Questions

1. **Token Bucket vs Leaky Bucket?** Token bucket allows burst; leaky bucket smooths output to constant rate.
2. **How do you rate limit in a distributed system?** Centralized Redis with atomic Lua scripts. Or use a distributed counter with slight inaccuracy (local counter + periodic sync).
3. **How does Nginx rate limiting work?** Uses leaky bucket: `limit_req_zone $binary_remote_addr rate=10r/s; limit_req zone=api burst=20 nodelay;`
4. **What's the race condition in naive distributed rate limiting?** Two nodes read count=99, both see < 100, both increment → actual count 101. Fix: atomic Lua scripts on Redis or use Redis INCR + EXPIRE.
