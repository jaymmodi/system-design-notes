# Back-of-Envelope Estimation & CDN

---

## Back-of-Envelope Estimation

The skill of making quick, rough calculations to validate design decisions. Interviewers want to see your reasoning, not just the answer.

### Essential Numbers

```
Latency:
  L1 cache:     0.5 ns
  L2 cache:     7   ns
  RAM:          100 ns
  SSD (seq):    1   µs
  SSD (rand):   100 µs
  HDD (seek):   10  ms
  Same DC:      0.5 ms
  CDN hit:      10  ms
  Cross-region: 100-150 ms

Throughput:
  Single core:      ~1 billion ops/sec (simple)
  Single DB:        ~1,000-5,000 writes/sec
  Redis:            ~100,000 ops/sec
  Kafka:            ~1,000,000 msgs/sec
  SSD:              ~500 MB/s read, 200 MB/s write
  Gigabit NIC:      ~125 MB/s = 100K requests/sec (at 1KB/req)

Storage units:
  1 KB = 10^3 bytes
  1 MB = 10^6 bytes
  1 GB = 10^9 bytes
  1 TB = 10^12 bytes

Time units:
  1 day  = 86,400 seconds ≈ 10^5 seconds
  1 month = 2.5 million seconds ≈ 2.5 × 10^6
  1 year  = 31 million seconds ≈ 3 × 10^7
```

---

### Estimation Framework

```
1. Ask clarifying questions (get scale)
2. State your assumptions explicitly
3. Calculate bottom-up
4. Sanity check
5. Identify bottlenecks
```

---

### Example: Design Twitter

**Step 1: Clarify and assume**
- 300M monthly active users (MAU)
- 100M daily active users (DAU)
- Users read 10 tweets/day on average
- Users write 1 tweet/week on average

**Step 2: Calculate writes**
```
Daily tweets = 100M DAU × (1 tweet/week ÷ 7 days) ≈ 14M tweets/day
Writes/sec = 14M ÷ 86,400 ≈ 160 writes/sec (very manageable)

Peak = 5× average → 800 writes/sec (still manageable for one DB)
```

**Step 3: Calculate reads**
```
Daily reads = 100M DAU × 10 tweets/day = 1B reads/day
Reads/sec = 1B ÷ 86,400 ≈ 11,500 reads/sec

Peak = 5× → 57,500 reads/sec → need multiple read replicas or cache
```

**Step 4: Storage**
```
Tweet size:
  tweet_id:    8 bytes (int64)
  user_id:     8 bytes
  text:        280 chars × 2 bytes = 560 bytes (Unicode)
  timestamp:   8 bytes
  metadata:    ~50 bytes
  Total:       ≈ 630 bytes ≈ 1 KB (round up generously)

Daily storage = 14M tweets × 1 KB = 14 GB/day
5 years = 14 GB × 365 × 5 ≈ 25 TB (very manageable)

With media (images): 14M tweets × 10% have image × 200KB avg = 280 GB/day
5 years of media ≈ 500 TB → need object storage (S3)
```

**Step 5: Bandwidth**
```
Read bandwidth = 11,500 reads/sec × 1 KB = 11.5 MB/s (tiny)
Write bandwidth = 160 writes/sec × 1 KB = 160 KB/s (tiny)
Media bandwidth = separate CDN concern
```

---

### Example: Design URL Shortener (bit.ly)

```java
// Assumptions:
// 100M new URLs/day created
// Read/write ratio = 100:1 (10B reads/day)

// QPS:
// Write QPS = 100M / 86,400 ≈ 1,200 write/sec
// Read  QPS = 10B  / 86,400 ≈ 115,000 read/sec → HEAVY CACHING needed

// URL entry storage:
// short_url:  6 chars × 1 byte = 6 bytes
// long_url:   2083 chars max, avg 100 bytes
// created_at: 8 bytes
// Total: ~120 bytes ≈ 150 bytes rounded

// Storage:
// 100M entries/day × 150 bytes = 15 GB/day
// 10 years = 15 GB × 365 × 10 = 54 TB total

// URL ID space:
// Characters: [a-z, A-Z, 0-9] = 62 chars
// 6-char short code: 62^6 = 56 billion unique URLs
// At 100M/day: will last 56B / 100M = 560 days before recycling needed
// Use 7 chars for more headroom: 62^7 = 3.5 trillion

// Cache sizing:
// 80/20 rule: 20% of URLs get 80% of traffic
// Daily unique shorts being accessed: 10B reads / 100M unique ≈ 100 reads each
// Cache the top 20% of daily active shorts
// 115,000 read/sec × 80% cache hit rate = only 23,000 DB reads/sec
```

---

### Example: Design WhatsApp

```java
// Assumptions:
// 2B users, 50M DAU, 50B messages/day

// Message QPS:
// 50B messages / 86,400 ≈ 580,000 messages/sec
// This is the HARD part — massive write throughput

// Message storage:
// Text msg: 100 bytes
// 50B/day × 100 bytes = 5 TB/day just for text
// Media: 50B × 30% have media × avg 100KB = 1.5 PB/day (CDN/object store essential)

// At this scale:
// Need Cassandra or similar wide-column DB for message storage
// Partition key: (user_id, conversation_id)
// Clustering key: timestamp
// Each partition = one conversation = sequential reads are fast

// Fan-out problem for group messages:
// 50M groups × avg 10 members × message = 500M fan-out writes/day
// Need async fan-out (message queue)
```

---

## CDN (Content Delivery Network)

### What is it?
Geographically distributed servers that cache content close to users. Request served from nearest edge node instead of origin server.

```
Without CDN:
  User in Tokyo → request → Server in Virginia → response → 200ms

With CDN:
  User in Tokyo → request → Edge in Tokyo → response → 10ms
  (first user: edge fetches from Virginia and caches; all subsequent: 10ms)
```

---

### How CDN Works

```
1. User requests https://example.com/images/logo.png
2. DNS resolves example.com to CDN edge server (based on user's geo)
3. Edge checks local cache: HIT → serve instantly
4. Cache MISS → edge fetches from origin server
5. Edge caches response with TTL from Cache-Control header
6. All future requests for same resource served from edge
```

---

### Push vs Pull CDN

**Pull CDN**: CDN fetches from origin on first miss, caches for TTL.
```
Pros: Simple, origin doesn't need to push
Cons: First user after TTL expiry hits origin (cache miss)
Examples: Cloudflare, Fastly, Akamai
```

**Push CDN**: You upload content to CDN explicitly.
```
Pros: CDN always has content before first request
Cons: Must manage uploads, good for known static content
Examples: AWS CloudFront (can do both), traditional CDNs
```

---

### Java: Setting Cache Headers for CDN

```java
@RestController
public class StaticContentController {

    // Static assets: long TTL, CDN caches aggressively
    @GetMapping("/assets/{filename}")
    public ResponseEntity<Resource> getAsset(@PathVariable String filename) {
        Resource resource = loadResource(filename);

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS)
                .cachePublic()) // CDN can cache this
            .eTag(computeETag(resource)) // for conditional requests
            .body(resource);
        // Cache-Control: public, max-age=31536000
        // CDN will cache for 1 year, serve from edge
    }

    // API responses: short TTL or no cache
    @GetMapping("/api/products/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable String id) {
        Product product = productService.getProduct(id);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES)
                .cachePublic())
            .body(product);
        // CDN caches for 5 minutes — good for product pages with infrequent updates
    }

    // User-specific: no CDN caching
    @GetMapping("/api/user/profile")
    public ResponseEntity<User> getUserProfile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore()) // Don't cache — private user data
            .body(user);
    }

    // Cache-busting: version URLs so CDN serves new content immediately
    // /assets/app.js → /assets/app.v2a3b4c.js (hash in filename)
    // Old URL cached = old content; new hash = cold miss → fresh content
}
```

---

### CDN for Dynamic Content

```java
// Edge Side Includes (ESI): CDN assembles page from cached parts
// Header: personalized (no cache)
// Nav: same for everyone (cache 1 hour)
// Product list: category-specific (cache 5 min)
// Footer: static (cache 24 hours)

// CDN rules for partial caching:
/*
  Vary: Accept-Language  → different cached version per language
  Vary: Accept-Encoding  → gzip vs br (Brotli) versions cached separately
  Vary: Cookie           → DON'T vary on Cookie for CDN (kills cache rate)
*/

// Purge cache when content changes
@EventListener
public void onProductUpdated(ProductUpdatedEvent event) {
    // Invalidate CDN cache for this product
    cloudflareClient.purgeURL(
        "https://example.com/api/products/" + event.getProductId()
    );
    // Or use cache tags for bulk purge:
    cloudflareClient.purgeTag("product:" + event.getProductId());
}
```

---

### CDN Architecture

```
[User: Tokyo]                                 [User: London]
      |                                              |
[CDN Edge: Tokyo] ←─── origin pull ──→ [CDN Edge: London]
      |                                              |
      └──────────────────────────────────────────────┘
                            |
                   [CDN Backbone Network]
                   (fast private network
                    connecting edge nodes)
                            |
                    [Origin Server: US]

CDN Providers:
  Cloudflare: 300+ PoPs (points of presence), DDoS protection
  Fastly:     Developer-friendly, real-time purging, edge computing
  Akamai:     Largest, enterprise, complex
  AWS CloudFront: AWS-native, integrates with S3/ALB
```

---

### CDN for Security

```java
// DDoS protection: CDN absorbs traffic at edge before reaching origin
// Rate limiting at edge (before request reaches your servers)
// WAF (Web Application Firewall): block SQLi, XSS at CDN level
// TLS termination: CDN handles SSL, origin uses HTTP internally (reduces CPU)

// Signed URLs: protect private content
public String generateSignedUrl(String assetKey, Duration validity) {
    Instant expiry = Instant.now().plus(validity);
    String toSign = assetKey + ":" + expiry.getEpochSecond();
    String signature = hmacSha256(toSign, CDN_SIGNING_KEY);

    return "https://cdn.example.com/" + assetKey +
           "?expires=" + expiry.getEpochSecond() +
           "&signature=" + signature;
    // CDN verifies signature — only holder of signed URL can access
    // Used for: paid content, private downloads, time-limited access
}
```

---

## Common Back-of-Envelope Mistakes

```
1. Not rounding aggressively — use powers of 10
   Bad: 86,400 × 12,500 = 1,080,000,000
   Good: ~10^5 × 10^4 = 10^9 ✓

2. Forgetting peak vs average (use 5-10× multiplier for peak)

3. Ignoring replication factor (3× storage for 3 replicas)

4. Not including metadata / index overhead (~20-50% of raw data)

5. Not asking about read/write ratio early — changes everything
```

---

## Interview Questions

1. **Estimate QPS for Instagram.** ~1B users, ~500M DAU, ~10 reads/day: 500M × 10 / 86400 ≈ 58K reads/sec. Writes: 100M photos/day / 86400 ≈ 1200 writes/sec.
2. **How does a CDN reduce origin load?** Caches responses at edge. If cache hit rate is 90%, only 10% of requests reach origin. 100K req/sec CDN → 10K req/sec to origin.
3. **When would you NOT use CDN?** Highly dynamic content that changes per user (personalized dashboards), content requiring auth per request, content that changes faster than CDN TTL.
4. **How do you invalidate CDN cache when you update content?** Versioned URLs (new hash = new URL = fresh miss), explicit purge API call, short TTL + eventual consistency, stale-while-revalidate header.
