# Graph Databases

## What is a Graph Database?
Stores data as **nodes** (entities) and **edges** (relationships), with properties on both. Optimized for traversing relationships — queries that are painful in SQL become natural.

---

## Why Graph Databases?
In SQL, a "friend of a friend" query requires multiple expensive JOINs:
```sql
-- Friends of friends (2 hops) in SQL:
SELECT DISTINCT f2.user_id
FROM friendships f1
JOIN friendships f2 ON f1.friend_id = f2.user_id
WHERE f1.user_id = 123 AND f2.user_id != 123;

-- 3 hops? JOINs triple. 4 hops? Exponential complexity.
-- Graph DB: MATCH (u)-[:FRIEND*2]->(fof) WHERE u.id = 123 RETURN fof
```

Graph databases shine when:
- Relationships are **first-class citizens**
- Queries traverse **unknown depth** of relationships
- **Pattern matching** across connected data

---

## Core Concepts

```
Node: (Person {name: "Alice", age: 30})
Edge: -[:FOLLOWS {since: "2020-01-01"}]->
Path: (Alice)-[:FOLLOWS]->(Bob)-[:FOLLOWS]->(Charlie)

Graph:
  (Alice) --FRIENDS_WITH--> (Bob)
     |                        |
  WORKS_AT                 WORKS_AT
     ↓                        ↓
  (TechCorp) <--LOCATED_IN-- (NYC)
```

---

## Neo4j — The Most Popular Graph DB

### Cypher Query Language

```cypher
-- Create nodes
CREATE (alice:Person {id: '1', name: 'Alice', age: 30})
CREATE (bob:Person {id: '2', name: 'Bob', age: 25})
CREATE (charlie:Person {id: '3', name: 'Charlie', age: 28})

-- Create relationships
MATCH (a:Person {name: 'Alice'}), (b:Person {name: 'Bob'})
CREATE (a)-[:FOLLOWS {since: date('2020-01-15')}]->(b)

-- Find Alice's followers
MATCH (follower)-[:FOLLOWS]->(alice:Person {name: 'Alice'})
RETURN follower.name

-- Friends of friends (2 hops)
MATCH (alice:Person {name: 'Alice'})-[:FOLLOWS*2]->(fof)
WHERE NOT (alice)-[:FOLLOWS]->(fof) AND fof <> alice
RETURN fof.name, count(*) as mutualFriends
ORDER BY mutualFriends DESC

-- Shortest path between two people
MATCH path = shortestPath(
  (alice:Person {name: 'Alice'})-[:FOLLOWS*]-(bob:Person {name: 'Bob'})
)
RETURN path, length(path) as degrees
```

---

## Java with Neo4j Driver

```java
import org.neo4j.driver.*;

public class SocialGraphService {
    private final Driver driver;

    public SocialGraphService(String uri, String user, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    // Create a friendship relationship
    public void addFollow(String followerId, String followeeId) {
        try (Session session = driver.session()) {
            session.run("""
                MATCH (follower:Person {id: $followerId}),
                      (followee:Person {id: $followeeId})
                MERGE (follower)-[r:FOLLOWS]->(followee)
                ON CREATE SET r.since = datetime()
                """,
                Map.of("followerId", followerId, "followeeId", followeeId)
            );
        }
    }

    // Get recommendations: people followed by people I follow (not already following)
    public List<String> getRecommendations(String userId, int limit) {
        try (Session session = driver.session()) {
            Result result = session.run("""
                MATCH (me:Person {id: $userId})-[:FOLLOWS]->(friend)-[:FOLLOWS]->(suggestion)
                WHERE NOT (me)-[:FOLLOWS]->(suggestion) AND suggestion.id <> $userId
                WITH suggestion, count(friend) as mutualFriends
                RETURN suggestion.name as name, mutualFriends
                ORDER BY mutualFriends DESC
                LIMIT $limit
                """,
                Map.of("userId", userId, "limit", limit)
            );

            return result.stream()
                .map(r -> r.get("name").asString() + " (" + r.get("mutualFriends").asLong() + " mutual)")
                .collect(Collectors.toList());
        }
    }

    // Fraud detection: find circular payment patterns
    public List<List<String>> findCircularPayments(String userId, int maxDepth) {
        try (Session session = driver.session()) {
            Result result = session.run("""
                MATCH path = (start:Account {id: $userId})-[:SENT_PAYMENT*2..%d]->(start)
                WHERE ALL(n IN nodes(path)[1..] WHERE n <> start OR n = last(nodes(path)))
                RETURN [node IN nodes(path) | node.id] as cycle
                LIMIT 10
                """.formatted(maxDepth),
                Map.of("userId", userId)
            );

            return result.stream()
                .map(r -> r.get("cycle").asList(v -> v.asString()))
                .collect(Collectors.toList());
        }
    }

    // Influence score: how many people can person X reach within 3 hops?
    public long calculateReach(String userId) {
        try (Session session = driver.session()) {
            return session.run("""
                MATCH (person:Person {id: $userId})-[:FOLLOWS*1..3]->(reachable)
                WHERE reachable.id <> $userId
                RETURN count(DISTINCT reachable) as reach
                """,
                Map.of("userId", userId)
            ).single().get("reach").asLong();
        }
    }
}
```

---

## Spring Data Neo4j

```java
@Node("Person")
public class Person {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String email;

    @Relationship(type = "FOLLOWS", direction = Relationship.Direction.OUTGOING)
    private List<Person> following = new ArrayList<>();

    @Relationship(type = "FOLLOWS", direction = Relationship.Direction.INCOMING)
    private List<Person> followers = new ArrayList<>();
}

@RelationshipProperties
public class FollowsRelationship {
    @Id @GeneratedValue
    private Long id;
    private LocalDate since;

    @TargetNode
    private Person target;
}

public interface PersonRepository extends Neo4jRepository<Person, Long> {
    @Query("MATCH (p:Person {name: $name})-[:FOLLOWS*2]->(fof) " +
           "WHERE NOT (p)-[:FOLLOWS]->(fof) AND fof <> p " +
           "RETURN DISTINCT fof LIMIT 10")
    List<Person> findFriendsOfFriends(String name);

    @Query("MATCH path = shortestPath((p1:Person {id: $id1})-[*]-(p2:Person {id: $id2})) " +
           "RETURN length(path)")
    Integer degreesOfSeparation(String id1, String id2);
}
```

---

## Graph Algorithms

### PageRank (Link importance)
```java
// Neo4j Graph Data Science library
String query = """
    CALL gds.graph.project('socialGraph', 'Person', 'FOLLOWS')
    YIELD graphName, nodeCount, relationshipCount

    CALL gds.pageRank.write('socialGraph', {
        maxIterations: 20,
        dampingFactor: 0.85,
        writeProperty: 'pageRankScore'
    })
    YIELD nodePropertiesWritten, ranIterations
    """;

// After running, most influential accounts have highest pageRankScore
```

### Community Detection (Louvain)
```java
// Find clusters of tightly connected users (communities)
String communityQuery = """
    CALL gds.louvain.write('socialGraph', {
        writeProperty: 'communityId'
    })
    YIELD communityCount, modularity

    // Now users with same communityId are in the same cluster
    MATCH (p:Person)
    RETURN p.communityId, collect(p.name) as members
    ORDER BY size(members) DESC
    LIMIT 10
    """;
```

---

## Use Cases

### 1. Social Networks
```java
// LinkedIn: "People you may know"
// Instagram: "Explore" page
// Twitter: "Who to follow"
// Cypher pattern: (me)-[:FOLLOWS]->(friend)-[:FOLLOWS]->(suggestion)
```

### 2. Fraud Detection
```java
// Find account rings: multiple accounts sharing same device/IP/card
String fraudQuery = """
    MATCH (a1:Account)-[:USED_DEVICE]->(d:Device)<-[:USED_DEVICE]-(a2:Account)
    WHERE a1 <> a2
    WITH d, collect(DISTINCT a1) + collect(DISTINCT a2) as suspiciousAccounts
    WHERE size(suspiciousAccounts) > 3
    RETURN d.deviceId, suspiciousAccounts
    """;
```

### 3. Knowledge Graphs
```java
// Google Knowledge Graph, Wikidata
// "Who directed movies starring actors who also starred in Inception?"
String knowledgeQuery = """
    MATCH (inception:Movie {title: 'Inception'})<-[:ACTED_IN]-(actor)-[:ACTED_IN]->(other)
    <-[:DIRECTED]-(director)
    RETURN DISTINCT director.name, collect(other.title) as movies
    """;
```

### 4. Network & IT Infrastructure
```java
// "Which servers are affected if datacenter X goes down?"
String impactQuery = """
    MATCH (dc:Datacenter {name: 'DC-West'})<-[:HOSTED_IN]-(server)
    <-[:DEPENDS_ON*1..5]-(dependent)
    RETURN DISTINCT dependent.name, labels(dependent)
    ORDER BY dependent.name
    """;
```

### 5. Recommendation Engine
```java
// Collaborative filtering via graph traversal
String recoQuery = """
    MATCH (user:User {id: $userId})-[:PURCHASED]->(product:Product)
                                   <-[:PURCHASED]-(similarUser)
                                   -[:PURCHASED]->(recommendation:Product)
    WHERE NOT (user)-[:PURCHASED]->(recommendation)
    WITH recommendation, count(DISTINCT similarUser) as popularity
    RETURN recommendation.name, popularity
    ORDER BY popularity DESC
    LIMIT 10
    """;
```

---

## Graph DB vs RDBMS vs Document DB

| Aspect | Graph DB | RDBMS | Document DB |
|--------|----------|-------|-------------|
| Best for | Relationships, traversal | Structured, transactions | Hierarchical documents |
| Multi-hop queries | O(log n) per hop | O(n) JOINs, exponential | Not native |
| Schema | Flexible | Rigid | Flexible |
| ACID | Yes (Neo4j) | Yes | Varies |
| Query language | Cypher, Gremlin | SQL | MongoDB query |
| Scaling | Harder to shard | Well-understood | Easy horizontal |

---

## Graph DB vs Graph in RDBMS

```java
// Can you model a graph in PostgreSQL? Yes, but...
// Friend recommendations with 3 hops in PostgreSQL = 3x JOINs or recursive CTE
// Slow on large datasets

// PostgreSQL recursive CTE (graph traversal)
// WITH RECURSIVE friends AS (
//   SELECT friend_id FROM friendships WHERE user_id = 123
//   UNION
//   SELECT f2.friend_id FROM friendships f2
//   JOIN friends ON f2.user_id = friends.friend_id
//   WHERE depth < 3 -- must manually track depth
// )
// SELECT * FROM friends;

// Works, but:
// - Slow on large social graphs
// - Hard to express complex graph patterns
// - No built-in graph algorithms
```

---

## When to Use Graph DB
- Social networks (friend suggestions, mutual connections)
- Fraud detection (account rings, transaction cycles)
- Recommendation engines (collaborative filtering)
- Knowledge graphs
- Network topology / dependency analysis
- Access control (who can see what, role hierarchies)

## When to Avoid
- Simple relationships (foreign keys in PostgreSQL are fine)
- Primary data store for non-graph workloads
- High write throughput with simple lookups (use RDBMS or document DB)
- When data is naturally hierarchical without complex traversal (use document DB)

---

## Interview Questions

1. **When would you use a graph DB instead of SQL?** When queries frequently traverse unknown-depth relationships, or when the pattern is graph-like (social, fraud, dependencies)
2. **How does Neo4j store data on disk?** Nodes and relationships stored in fixed-size record files. Adjacency lists (relationships) are stored as doubly-linked lists — traversal is O(1) pointer following, not index lookups.
3. **What is Gremlin?** Apache TinkerPop graph traversal language, used by Amazon Neptune, JanusGraph — alternative to Cypher.
4. **How would you implement "6 degrees of separation"?** BFS from source node, find target within 6 hops: `MATCH path = shortestPath((a)-[*..6]-(b)) RETURN length(path)`
