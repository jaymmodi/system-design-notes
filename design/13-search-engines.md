# Search Engines & Elasticsearch

## What & Why
Full-text search, relevance scoring, and complex aggregations over large datasets. SQL LIKE queries don't scale — inverted indexes do.

---

## Inverted Index — The Core Data Structure

```
Documents:
  doc1: "quick brown fox"
  doc2: "brown dog"
  doc3: "fox jumps over dog"

Inverted Index:
  "brown" → [doc1, doc2]
  "dog"   → [doc2, doc3]
  "fox"   → [doc1, doc3]
  "quick" → [doc1]
  "jumps" → [doc3]
  "over"  → [doc3]

Query "fox dog":
  fox → {doc1, doc3}
  dog → {doc2, doc3}
  intersection or union → doc3 (contains both) ranked highest
```

```java
// Simple inverted index implementation
public class InvertedIndex {
    // term → list of (docId, position) postings
    private final Map<String, List<Posting>> index = new HashMap<>();

    public void addDocument(int docId, String text) {
        String[] words = tokenize(text); // lowercase, remove punctuation
        for (int i = 0; i < words.length; i++) {
            String term = stem(words[i]); // "running" → "run"
            index.computeIfAbsent(term, k -> new ArrayList<>())
                 .add(new Posting(docId, i)); // docId + position for phrase queries
        }
    }

    public List<Integer> search(String query) {
        String[] terms = tokenize(query);
        if (terms.length == 0) return List.of();

        // Get postings for each term
        List<Set<Integer>> docSets = Arrays.stream(terms)
            .map(t -> index.getOrDefault(stem(t), List.of()).stream()
                .map(p -> p.docId)
                .collect(Collectors.toSet()))
            .collect(toList());

        // AND: intersect all doc sets
        return docSets.stream()
            .reduce((a, b) -> { a.retainAll(b); return a; })
            .orElse(Set.of())
            .stream()
            .sorted()
            .collect(toList());
    }

    private String[] tokenize(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9 ]", "").split("\\s+");
    }

    private String stem(String word) {
        // Simplified: in practice use Porter/Snowball stemmer
        if (word.endsWith("ing")) return word.substring(0, word.length() - 3);
        if (word.endsWith("ed")) return word.substring(0, word.length() - 2);
        return word;
    }
}
```

---

## Elasticsearch Overview

Elasticsearch = distributed search engine built on Apache Lucene.
- Documents stored as JSON
- Index = collection of documents (like a DB table, but schema-flexible)
- Each index split into **shards** (primary + replica shards)
- Queries distributed across all shards → results merged

---

## Java with Elasticsearch Client

```java
import co.elastic.clients.elasticsearch.*;
import co.elastic.clients.elasticsearch.core.*;

public class ProductSearchService {
    private final ElasticsearchClient client;

    // Index a product document
    public void indexProduct(Product product) throws IOException {
        IndexResponse response = client.index(i -> i
            .index("products")
            .id(product.getId())
            .document(product)
        );
        System.out.println("Indexed: " + response.id() + " result: " + response.result());
    }

    // Full-text search with relevance scoring
    public SearchResult<Product> search(String query, int page, int size) throws IOException {
        SearchResponse<Product> response = client.search(s -> s
            .index("products")
            .from(page * size)
            .size(size)
            .query(q -> q
                .multiMatch(m -> m
                    .query(query)
                    .fields("name^3", "description^1", "tags^2") // field boosting
                    .fuzziness("AUTO") // typo tolerance: "pnone" matches "phone"
                    .type(TextQueryType.BestFields)
                )
            )
            .highlight(h -> h
                .fields("name", f -> f.preTags("<em>").postTags("</em>"))
                .fields("description", f -> f.preTags("<em>").postTags("</em>"))
            )
            .sort(so -> so.score(sc -> sc.order(SortOrder.Desc))), // sort by relevance
            Product.class
        );

        return new SearchResult<>(
            response.hits().total().value(),
            response.hits().hits().stream()
                .map(h -> new SearchHit<>(h.source(), h.score(), h.highlight()))
                .collect(toList())
        );
    }

    // Autocomplete / suggestions
    public List<String> autocomplete(String prefix) throws IOException {
        SearchResponse<Product> response = client.search(s -> s
            .index("products")
            .size(10)
            .query(q -> q
                .matchPhrasePrefix(m -> m
                    .field("name.autocomplete") // field analyzed with edge_ngram
                    .query(prefix)
                    .maxExpansions(50)
                )
            ),
            Product.class
        );

        return response.hits().hits().stream()
            .map(h -> h.source().getName())
            .distinct()
            .collect(toList());
    }
}
```

---

## Aggregations — Analytics on Search Results

```java
// Faceted search: show "100 results in Electronics, 200 in Books" + price histogram
public FacetedSearchResult facetedSearch(String query, Map<String, String> filters) throws IOException {
    SearchResponse<Product> response = client.search(s -> s
        .index("products")
        .query(q -> buildFilteredQuery(q, query, filters))
        .size(20) // return 20 products
        .aggregations("by_category", a -> a
            .terms(t -> t.field("category.keyword").size(20)) // category facets
        )
        .aggregations("price_ranges", a -> a
            .range(r -> r
                .field("price")
                .ranges(
                    range -> range.to(25.0),
                    range -> range.from(25.0).to(50.0),
                    range -> range.from(50.0).to(100.0),
                    range -> range.from(100.0)
                )
            )
        )
        .aggregations("avg_price", a -> a
            .avg(avg -> avg.field("price"))
        ),
        Product.class
    );

    // Extract aggregation results
    Map<String, Long> categoryFacets = response.aggregations().get("by_category")
        .sterms().buckets().array().stream()
        .collect(toMap(b -> b.key().stringValue(), b -> b.docCount()));

    double avgPrice = response.aggregations().get("avg_price").avg().value();

    return new FacetedSearchResult(
        response.hits().hits().stream().map(Hit::source).collect(toList()),
        categoryFacets,
        avgPrice
    );
}
```

---

## Index Mapping (Schema Definition)

```java
// Define how fields are indexed
String mapping = """
{
  "mappings": {
    "properties": {
      "id":          { "type": "keyword" },
      "name":        {
        "type": "text",
        "analyzer": "english",
        "fields": {
          "keyword": { "type": "keyword" },
          "autocomplete": { "type": "text", "analyzer": "autocomplete" }
        }
      },
      "description": { "type": "text", "analyzer": "english" },
      "price":       { "type": "float" },
      "category":    { "type": "keyword" },
      "tags":        { "type": "keyword" },
      "created_at":  { "type": "date" },
      "location":    { "type": "geo_point" }
    }
  },
  "settings": {
    "analysis": {
      "analyzer": {
        "autocomplete": {
          "tokenizer": "autocomplete_tokenizer",
          "filter": ["lowercase"]
        }
      },
      "tokenizer": {
        "autocomplete_tokenizer": {
          "type": "edge_ngram",
          "min_gram": 2,
          "max_gram": 10,
          "token_chars": ["letter", "digit"]
        }
      }
    }
  }
}
""";

// Edge n-gram for "iphone":
// "ip", "iph", "ipho", "iphon", "iphone"
// User types "iph" → matches stored "iph" token → instant autocomplete
```

---

## Relevance Scoring (TF-IDF / BM25)

```java
// Elasticsearch uses BM25 (Best Match 25) by default
// Score factors:
//   TF (Term Frequency): how often term appears in document
//   IDF (Inverse Document Frequency): how rare term is across all documents
//   Field length normalization: "iphone" in 2-word title > in 1000-word description

// Boost fields and boost by business logic
SearchResponse<Product> response = client.search(s -> s
    .query(q -> q
        .functionScore(fs -> fs
            .query(fq -> fq.match(m -> m.field("name").query(userQuery)))
            .functions(
                // Boost popular products
                fn -> fn.fieldValueFactor(fv -> fv.field("sales_count").modifier(FieldValueFactorModifier.Log1p).factor(0.5)),
                // Boost recently added
                fn -> fn.gauss(g -> g.field("created_at").placement(
                    p -> p.origin(JsonData.of("now")).scale(JsonData.of("30d")).decay(0.5)
                ))
            )
            .boostMode(FunctionBoostMode.Sum)
        )
    ),
    Product.class
);
```

---

## Keeping Elasticsearch in Sync with the DB

```java
// Option 1: Dual write (write DB + ES in same request)
@Transactional
public Product createProduct(Product product) {
    Product saved = productRepository.save(product); // DB
    searchIndex.indexProduct(saved); // ES
    // If ES fails: DB has it, ES doesn't → inconsistency
    // Use saga pattern or accept eventual consistency
    return saved;
}

// Option 2: CDC via Debezium (recommended)
// MySQL binlog → Debezium → Kafka → ES consumer
@KafkaListener(topics = "mysql.products.products")
public void onProductChange(ChangeEvent event) {
    switch (event.getOp()) {
        case "c", "u" -> esClient.indexProduct(event.getAfter());
        case "d" -> esClient.deleteProduct(event.getBefore().getId());
    }
}

// Option 3: Polling (simple but laggy)
@Scheduled(fixedDelay = 5000) // every 5 seconds
public void syncChanges() {
    Instant lastSync = getLastSyncTime();
    List<Product> changed = productRepository.findByUpdatedAtAfter(lastSync);
    changed.forEach(p -> esClient.indexProduct(p));
    updateLastSyncTime(Instant.now());
}
```

---

## Elasticsearch Cluster Architecture

```
           [Kibana]  [Your App]
                ↓        ↓
         [Coordinating Nodes]   ← receive queries, scatter/gather
              ↓    ↓    ↓
    [Data Node 1] [DN 2] [DN 3] ← store shards, execute queries

Index "products" → 3 primary shards + 1 replica each
  Shard 0 (primary) on DN1, (replica) on DN2
  Shard 1 (primary) on DN2, (replica) on DN3
  Shard 2 (primary) on DN3, (replica) on DN1
```

```java
// Sizing guidance:
// Rule: keep shard size between 10GB - 50GB
// Too many small shards → overhead. Too few large shards → slow recovery.
// Start with: number_of_shards = ceil(total_data_GB / 30)
```

---

## Geo Search

```java
// Find restaurants near user
SearchResponse<Restaurant> response = client.search(s -> s
    .index("restaurants")
    .query(q -> q
        .geoDistance(g -> g
            .field("location")
            .location(gl -> gl.latlon(ll -> ll.lat(40.7128).lon(-74.0060))) // NYC
            .distance("5km")
        )
    )
    .sort(so -> so
        .geoDistance(g -> g
            .field("location")
            .location(gl -> gl.latlon(ll -> ll.lat(40.7128).lon(-74.0060)))
            .order(SortOrder.Asc) // nearest first
        )
    ),
    Restaurant.class
);
```

---

## When to Use Elasticsearch
- Full-text search (e-commerce product search, blog search)
- Log analysis and observability (ELK stack)
- Autocomplete and typeahead
- Faceted search and aggregations
- Geo-based search
- Complex analytics queries

## When to Avoid
- Primary write store (no ACID, not optimized for writes)
- Simple exact-match lookups (use a DB with an index)
- Transactional data (use PostgreSQL)
- Tiny datasets (< 10K records — SQL LIKE is fine)

---

## Interview Questions

1. **How does an inverted index work?** Maps terms to the documents containing them. Query → look up each term → intersect/union posting lists → rank by relevance score.
2. **How do you keep ES in sync with MySQL?** CDC (Debezium → Kafka → ES consumer) is the most robust. Dual-writes work but risk inconsistency.
3. **What is the N+1 problem with ES?** Fetching a list of IDs from ES, then making N DB calls to enrich. Fix: store all needed fields in ES (denormalize) or use mget.
4. **What is a mapping explosion?** Dynamic mapping creates a field for every unique key → millions of fields → OOM. Fix: explicit mappings, disable dynamic mapping.
