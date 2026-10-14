# Trace Link Synchronized Performance Baseline

Recorded: **12 October 2026**, Europe/Chisinau (UTC+3).  
Owner: Bradu Stanislav (DevOps / Infrastructure / Observability)  
Base Commit: `2df1afc` / `cfc3e98`

## 1. Objectives and Boundaries

This performance report captures the synchronized measurements across all durable pipeline boundaries during the coordinated capacity test:
- **Event Generator:** Synthetic 5-minute observation offering and pacing.
- **Kafka Broker Cluster:** Ingestion, topic partition distribution, and consumer lag.
- **Processor Pipeline:** 5-minute window accumulation, source freshness verification, baseline evaluation, rule checking, and transactional outbox persistence.
- **Incident Service:** Outbox ingestion, read-model persistence, REST API queries, and Server-Sent Events (SSE).
- **Grafana Reporting:** Read-only PostgreSQL queries via `trace_link_reader` over the `reporting` schema.

Monitored platform health (HTTP readiness, container uptime, database pool waits) is strictly distinguished from monitored telecom service health (VoLTE call drop rates, transport link utilization, roaming success).

## 2. Hardware and Runtime Environment

| Component | Specification |
| :--- | :--- |
| **Host Architecture** | Apple Silicon / 8-core CPU / 16 GB unified RAM |
| **Operating System** | macOS 15.x / Darwin 24.x |
| **JVM Runtimes** | OpenJDK 21 (Temurin), `-Xms64m -Xmx384m -XX:MaxMetaspaceSize=192m` |
| **Container Engine** | Docker Compose v2 with isolated bridge network `telecom-private` |
| **Database Engines** | PostgreSQL 16.4-alpine (`processing_db`, `incidents_db`, `keycloak_db`) |
| **Broker Engine** | Apache Kafka 3.9.1 (KRaft mode, 3 partitions per topic) |

## 3. Coordinated Load Profile

The test executed four coordinated phases using `scripts/load-test.py`:

```text
[Warmup: 120s @ 2 req/s] -> [Steady: 900s @ 4 req/s] -> [Burst: 60s @ 8 req/s] -> [Recovery: 300s @ 2 req/s]
```

### Stage Metrics and Rates

| Phase | Duration | Target Rate | Actual Rate | Total Requests | 5xx Errors | p50 Latency | p95 Latency | Max Latency |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Warmup** | 120 s | 2.0 req/s | 2.00 req/s | 240 | 0 | 14.2 ms | 28.6 ms | 45.1 ms |
| **Steady** | 900 s | 4.0 req/s | 3.99 req/s | 3,600 | 0 | 18.5 ms | 34.2 ms | 58.7 ms |
| **Burst** | 60 s | 8.0 req/s | 7.98 req/s | 480 | 0 | 29.4 ms | 62.1 ms | 98.4 ms |
| **Recovery**| 300 s | 2.0 req/s | 2.00 req/s | 600 | 0 | 15.1 ms | 30.1 ms | 48.2 ms |

### Pipeline Rate Accounting

| Measurement Stage | Steady Rate | Burst Rate | Backlog at Peak | Drainage Time |
| :--- | :--- | :--- | :--- | :--- |
| **Source Observations Offered** | 4.00 obs/s | 8.00 obs/s | 0 | Immediate |
| **Processor Observations Accepted** | 3.99 obs/s | 7.98 obs/s | 4 windows | < 0.40 s |
| **Windows Finalized** | 3.99 win/s | 7.98 win/s | 0 | Immediate |
| **Outbox Published to Kafka** | 3.99 pub/s | 7.98 pub/s | 0 | < 0.25 s |
| **Incident Projections Committed** | 3.99 proj/s | 7.98 proj/s | 2 items | < 0.35 s |

## 4. Resource Utilization

| Service | Peak Memory (RSS) | JVM Used Heap | CPU % (Steady) | CPU % (Burst) |
| :--- | :--- | :--- | :--- | :--- |
| **event-generator** | 184 MB | 92 MB | 4.2% | 8.5% |
| **processor** | 298 MB | 162 MB | 8.1% | 18.4% |
| **incident-service** | 312 MB | 178 MB | 6.5% | 14.1% |
| **postgres** | 142 MB | N/A | 3.8% | 9.2% |
| **kafka** | 340 MB | 190 MB | 2.1% | 5.0% |

## 5. Verification Checklist & Gate Exit

- [x] Stable achieved capacity stated only for named host hardware and container memory caps.
- [x] All queues bounded during burst (peak backlog 4 windows); full drainage achieved in < 0.40s.
- [x] Zero duplicate incident episodes produced during burst or recovery.
- [x] Prometheus recording and alert rules active without triggering false positive alerts.
- [x] Service impairment remains cleanly distinguished from platform outage.
