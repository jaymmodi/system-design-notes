# Infrastructure: Kubernetes & Containers for Data Platforms

## Netflix Infrastructure Interview Focus
- Containers and runtimes (Docker, containerd)
- Identity and security (mTLS, SPIFFE, RBAC)
- Compute infrastructure for analytics (Flink/Spark on K8s, spot instances)

---

## Containers & Docker

### Key Concepts
```dockerfile
# Multi-stage build — smaller production image
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline  # cache dependencies
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre-alpine  # small runtime image
WORKDIR /app
COPY --from=builder /app/target/service.jar .
EXPOSE 8080
# Don't run as root
USER 1001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "service.jar"]

# Key JVM flags for containers:
# -XX:MaxRAMPercentage=75.0  → use 75% of container memory limit
# -XX:+UseContainerSupport   → JVM aware of container CPU limits (default in JDK 11+)
# Without: JVM uses HOST memory/CPU counts → over-allocates → OOMKilled
```

### Container Networking Modes
```
bridge (default): container has own IP, NAT to host
host: container shares host network stack (faster, less isolation)
overlay: multi-host networking (used in K8s)
none: no network

For data platform: overlay networking between pods across nodes
```

---

## Kubernetes for Data Workloads

### Core Concepts

```yaml
# Pod: smallest deployable unit (one or more containers sharing network/storage)
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: flink-taskmanager
    image: flink:1.18
    resources:
      requests:
        memory: "4Gi"
        cpu: "2"
      limits:
        memory: "8Gi"     # OOMKilled if exceeded
        cpu: "4"
    env:
    - name: TASK_MANAGER_MEMORY_PROCESS_SIZE
      value: "7500m"      # slightly under limit (leave room for JVM overhead)
```

```yaml
# Deployment: manages ReplicaSet, handles rolling updates
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
spec:
  replicas: 10
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 2        # allow 2 extra pods during update
      maxUnavailable: 0  # never take pods down before new ones ready
  selector:
    matchLabels:
      app: order-service
  template:
    spec:
      containers:
      - name: order-service
        image: order-service:v2.1.0
        readinessProbe:       # only route traffic when ready
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        livenessProbe:        # restart if unhealthy
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          periodSeconds: 30
          failureThreshold: 3
```

---

## Flink on Kubernetes

```yaml
# FlinkDeployment (using Flink Kubernetes Operator)
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: user-events-pipeline
spec:
  image: flink:1.18
  flinkVersion: v1_18
  flinkConfiguration:
    taskmanager.numberOfTaskSlots: "2"
    state.backend: "rocksdb"
    state.checkpoints.dir: "s3://netflix-flink-checkpoints/user-events"
    execution.checkpointing.interval: "60000"    # checkpoint every 60s
    execution.checkpointing.mode: "EXACTLY_ONCE"
  serviceAccount: flink
  jobManager:
    resource:
      memory: "2048m"
      cpu: 1
  taskManager:
    resource:
      memory: "8192m"    # includes RocksDB state size
      cpu: 2
  job:
    jarURI: "s3://netflix-artifacts/user-events-pipeline.jar"
    parallelism: 20
    upgradeMode: stateful  # preserve state during upgrades (via savepoint)
    args: ["--kafka-topic", "user-events", "--iceberg-table", "datalake.user_events"]
```

### Flink Savepoints for Zero-Downtime Upgrades

```bash
# 1. Trigger savepoint (consistent state snapshot)
kubectl exec -it flink-jobmanager -- flink savepoint <job-id> s3://checkpoints/savepoints/

# 2. Cancel the job
kubectl exec -it flink-jobmanager -- flink cancel <job-id>

# 3. Deploy new version with savepoint path
kubectl apply -f new-job-deployment.yaml
# --fromSavepoint s3://checkpoints/savepoints/savepoint-abc123

# Job restores state from savepoint, processes from Kafka offset saved in savepoint
# Zero data loss, zero duplicate processing
```

---

## Spark on Kubernetes

```java
// SparkContext or SparkSession in cluster mode on K8s
SparkSession spark = SparkSession.builder()
    .appName("daily-aggregation")
    .master("k8s://https://k8s-api:443")
    .config("spark.kubernetes.container.image", "spark:3.5.0")
    .config("spark.executor.instances", "50")
    .config("spark.executor.memory", "8g")
    .config("spark.executor.cores", "4")
    .config("spark.kubernetes.executor.request.cores", "4")
    // Use spot instances for executors (80% cheaper)
    .config("spark.kubernetes.node.selector.node-type", "spot")
    // Tolerations for spot nodes
    .config("spark.kubernetes.executor.annotation.cluster-autoscaler.kubernetes.io/safe-to-evict", "false")
    // S3A for reading from object storage
    .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
    .getOrCreate();
```

---

## Auto-Scaling

```yaml
# Horizontal Pod Autoscaler (HPA) — scale based on CPU/memory/custom metrics
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: order-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: order-service
  minReplicas: 5
  maxReplicas: 50
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  # Custom metric: scale based on Kafka consumer lag
  - type: External
    external:
      metric:
        name: kafka_consumer_lag
        selector:
          matchLabels:
            topic: orders
      target:
        type: AverageValue
        averageValue: "10000"  # scale up if lag > 10K per replica
```

```yaml
# Cluster Autoscaler — add/remove nodes based on pending pods
# Node Groups:
#   on-demand: always-on for critical workloads
#   spot: auto-scaled, 80% cheaper, can be evicted

# For Flink/Spark: spot nodes are fine for stateless executors
# State is in checkpoints on S3 — eviction just restarts task from checkpoint
```

---

## Identity and Security

### mTLS (Mutual TLS)
```
Regular TLS: client verifies server certificate
mTLS: BOTH sides verify each other's certificate

Every service-to-service call at Netflix uses mTLS:
  Service A presents certificate: "I am order-service"
  Service B verifies: "order-service cert signed by Netflix CA? Trust it"
  Service B presents certificate: "I am payment-service"
  Service A verifies: "payment-service cert signed by Netflix CA? Trust it"
  → Both sides authenticated, all traffic encrypted
```

### SPIFFE / SPIRE (Service Identity)
```
Problem: How does order-service prove it IS order-service to payment-service?
  Can't hardcode passwords — rotate frequently, secret sprawl
  IP-based identity fails in dynamic K8s environments (pods change IPs)

SPIFFE: specification for service identity (SVID = SPIFFE Verifiable Identity Document)
SPIRE: implementation of SPIFFE

How it works:
  1. SPIRE agent runs on each K8s node
  2. Attests to K8s API: "This pod is order-service (based on K8s service account)"
  3. Issues short-lived X.509 certificate: "This pod = spiffe://netflix.com/order-service"
  4. Certificate auto-rotates every hour
  5. mTLS: both sides present SPIFFE certificate → verified by SPIRE
```

### RBAC in Kubernetes
```yaml
# Service Account: identity for a pod
apiVersion: v1
kind: ServiceAccount
metadata:
  name: order-service-sa
  namespace: production

# Role: what can this service account do?
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: order-service-role
rules:
- apiGroups: [""]
  resources: ["configmaps", "secrets"]
  verbs: ["get", "list"]     # read config, NOT write/delete

# RoleBinding: bind role to service account
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: order-service-binding
subjects:
- kind: ServiceAccount
  name: order-service-sa
roleRef:
  kind: Role
  name: order-service-role
```

---

## Compute Infrastructure for Analytics

### Spot Instances
```java
// Spot instances: 60-80% cheaper, but can be evicted with 2-minute warning
// Perfect for: Flink task managers, Spark executors (stateless, checkpointed)
// Not for: Flink/Spark job managers, Kafka brokers (stateful, need stability)

// Handle spot eviction gracefully:
public class SpotInstanceHandler {
    @EventListener(ContextRefreshedEvent.class)
    public void registerSpotEvictionHandler() {
        // Poll EC2 instance metadata for interruption notice
        scheduler.scheduleAtFixedRate(() -> {
            try {
                HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://169.254.169.254/latest/meta-data/spot/interruption-action"))
                        .build(),
                    BodyHandlers.ofString()
                );
                if (response.statusCode() == 200) {
                    // Will be evicted in 2 minutes!
                    log.warn("Spot eviction notice received! Gracefully shutting down...");
                    triggerCheckpoint(); // save Flink state now
                    deregisterFromLoadBalancer(); // stop receiving traffic
                    finishInFlightRequests(Duration.ofSeconds(90)); // drain
                    System.exit(0); // clean shutdown
                }
            } catch (Exception ignored) {}
        }, 0, 5, TimeUnit.SECONDS);
    }
}
```

### Gang Scheduling for Spark/Flink
```
Problem: Spark needs 50 executors simultaneously, but K8s schedules pods one by one
  → If cluster has 40 spots free, 40 executors start, 10 wait
  → Job is blocked: can't make progress without all executors

Solutions:
  1. Volcano (K8s batch scheduling): gang scheduling, queue management, fair share
  2. Yunikorn (Apache): resource-aware batch scheduling
  3. Flink's built-in: active resource manager requests all TMs atomically

// Flink: request resources up front
env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, Time.seconds(10)));
// Flink JobManager requests all TaskManagers before starting execution
```

---

## Persistent Storage in K8s for Data Workloads

```yaml
# PersistentVolumeClaim for RocksDB state backend
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: flink-rocksdb-state
spec:
  accessModes: [ReadWriteOnce]
  storageClassName: gp3-encrypted    # SSD, encrypted at rest
  resources:
    requests:
      storage: 100Gi  # RocksDB local state
```

```java
// Data at rest encryption: always use encrypted storage classes
// Data in transit encryption: mTLS for service-to-service

// S3 encryption for Iceberg/checkpoints:
// SSE-S3: S3-managed keys (simplest)
// SSE-KMS: AWS KMS managed keys (audit trail, key rotation)
// SSE-C: customer-managed keys (most control)

// In practice: SSE-KMS for compliance-sensitive data
spark.config("spark.hadoop.fs.s3a.server-side-encryption-algorithm", "SSE-KMS")
     .config("spark.hadoop.fs.s3a.server-side-encryption.key", kmsKeyArn);
```

---

## When to Use What

| Workload | Compute Strategy |
|----------|-----------------|
| Stateless API services | K8s Deployment + HPA, spot nodes ok |
| Flink Job Manager | On-demand nodes, high availability |
| Flink Task Managers | Spot nodes (checkpointed state) |
| Spark Driver | On-demand node (small, short-lived) |
| Spark Executors | Spot nodes (stateless, restart cheap) |
| Kafka brokers | Dedicated VMs or on-demand (stateful) |
| Druid Historical | On-demand (stateful, local disk) |
| Druid Broker/Router | K8s, spot ok (stateless) |

---

## Interview Questions

1. **How do you run Flink on Kubernetes safely?** Flink Kubernetes Operator, job manager on on-demand node, task managers on spot (with checkpoint recovery), savepoints for zero-downtime upgrades, RBAC for S3 access.
2. **What is mTLS and why does Netflix use it everywhere?** Mutual TLS: both client and server authenticate with certificates. Ensures every service-to-service call is from a known, trusted service. Prevents spoofing even inside the cluster.
3. **What is SPIFFE/SPIRE?** SPIFFE: spec for service identity using short-lived X.509 certs. SPIRE: implementation. Each pod gets a cert proving "I am order-service" — automatically rotated. Used for mTLS authentication.
4. **How do you handle spot instance eviction in a streaming pipeline?** Poll EC2 metadata for eviction notice (2-min warning). On notice: trigger Flink savepoint/checkpoint, drain in-flight work, clean shutdown. K8s reschedules task manager, restores from checkpoint, catches up on Kafka lag.
5. **What's the difference between requests and limits in K8s?** Requests: guaranteed allocation, used for scheduling. Limits: hard cap (CPU throttled, memory OOMKilled if exceeded). Always set requests = limits × 0.8 for predictable performance.
