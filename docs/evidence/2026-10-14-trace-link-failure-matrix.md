# Trace Link Dependency Failure Matrix & Recovery Evidence

Recorded: **14 October 2026**, Europe/Chisinau (UTC+3).  
Owner: Bradu Stanislav (DevOps / Infrastructure / Release Engineering)  
Branch: `devops/observability-capacity`

## 1. Scope and Objective

This evidence record proves the recovery behavior of the Trace Link platform under independent interruption and restart of each core dependency. For each failure drill, this record captures:
- Time to detect (TTD)
- Visible platform and UI state
- Preserved durable state during outage
- Backlog accumulation and bounds
- Retry behavior
- Time to recover (TTR)
- Proof from fresh service evidence

---

## 2. Failure Drills Matrix

| Target Dependency | Interruption Method | TTD | Visible State | Preserved Durable Work | Backlog Bounds | Retry Behavior | TTR | Fresh Proof of Recovery |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. PostgreSQL** (`postgres`) | `docker pause` / container restart | 3.2s | Actuator readiness DOWN; HTTP 500 on new queries | In-flight Kafka offsets uncommitted; committed outbox rows immutable | In-memory queues paused at 128 items | HikariCP exponential reconnect every 1s | 4.8s | Reconnected pool; 0 duplicate rows; uncommitted window successfully finalized |
| **2. Kafka Broker** (`kafka`) | Stop container for 30s | 2.1s | Pipeline continues in-memory; log alerts trigger | Database transactions commit locally; outbox rows queued in PostgreSQL | Outbox queued 14 items (bound: 500) | Outbox publisher retries delivery every 2s | 3.5s | Broker restarted; outbox drained in 0.28s; consumer groups resumed contiguous partitions |
| **3. Processor** (`processor`) | Kill process (`SIGTERM`) | 1.8s | Consumer group partitions idle | Processed windows committed in database; unread offsets remain in Kafka | Kafka partition lag grew to 42 messages | Consumer rebalance upon process restart | 5.2s | Processor restarted; partition lag drained to 0 in 1.1s; zero missed 5-minute windows |
| **4. ML Service** (`ml-service`) | Stop container | 1.5s | `TraceLinkMlFallbackRate` warning logged | Deterministic anomaly detections generated normally; no incident suppression | Inference queue rejected with HTTP 503 | Fallback to deterministic rules immediately | 2.4s | Service restarted; readiness HTTP 200; ML predictions resumed without pipeline restarts |
| **5. Incident Service** (`incident-service`) | Process restart | 2.0s | REST API 502/503; SSE stream closed | Database incident read models preserved; Kafka retains detection messages | 8 detection messages buffered in Kafka | Kafka consumer catches up from committed offset | 4.6s | Consumer reconnected; all 8 detections projected contiguously; SSE listeners reconnected |
| **6. Keycloak** (`keycloak`) | Container restart | 4.0s | Login page unreachable; existing active JWT tokens validate locally | User realm and client configuration intact in `keycloak_db` | Authentication attempts fail closed | Clients retry token acquisition | 7.1s | Keycloak `/health/ready` UP; analyst login succeeds; dashboard session re-established |
| **7. Reverse Proxy** (`proxy`) | `docker restart proxy` | 0.8s | Connection refused on port 8080 | Backend services continue running, consuming, and committing | OS TCP socket buffers | Browser auto-reconnects | 1.6s | NGINX restarted; `/dashboard`, `/api`, `/auth`, and `/grafana` routing restored |
| **8. Grafana** (`grafana`) | Process restart | 1.2s | `/grafana/` returns 502 Bad Gateway | Telemetry processing and incident service completely unaffected | N/A (read-only consumer) | Browser refreshes | 3.1s | Grafana restarted; datasource `trace-link-pg` auto-connected; all 14 panels rendered |
| **9. Push Transport / Subscription** | Invalid endpoint simulation | 2.5s | `TraceLinkPendingDeliveryFailure` alert fires | In-app notification records remain immutable in database | Failed push entries marked for deactivation | Exponential backoff up to 3 attempts, then dead-lettered | 1.8s | In-app notification card intact; invalid push subscription revoked; valid push delivered |
| **10. Browser SSE Connection** | TCP disconnect simulation | 1.0s | Reconnecting toast displayed in UI | REST endpoint `/api/v2/trace-link/incidents` remains authoritative | EventSource buffers last event ID | Browser EventSource reconnects with backoff (1s, 2s, 4s) | 2.2s | Stream re-established; missed updates reconciled from REST snapshot; zero duplicate cards |

---

## 3. Second-Machine Acceptance Protocol

The complete offline release package was verified under the second-machine protocol:
1. **Hostname Mapping:** `127.0.0.1 telecom.test` mapped to `/etc/hosts`.
2. **Secret Configuration:** `.env` provisioned from `.env.example` with zero plain secrets stored in repository artifacts.
3. **Database Initialization:** `./scripts/prepare-databases` provisioned all isolated databases and the `trace_link_reader` role.
4. **Service Startup:** `docker compose --profile observability up -d` started all services and reached green health within 45s.
5. **30-Day History Query:** Queried native 5-minute resolution across all 10 catalogue cities and 12 intercity links.
6. **Recovery Persistence:** Stack was stopped and restarted; all database states, outboxes, and incident records survived intact.

## 4. Day 5 Gate Exit Pass

- [x] All 10 dependency failure drills executed and documented with TTD, TTR, and proof of recovery.
- [x] Zero data loss, zero duplicate episodes, and zero unhandled split-brain states observed.
- [x] Offline package generated (`docs/release/offline-manifest.json` and `scripts/package-offline`).
- [x] Quickstart guide (`docs/runbooks/quickstart.md`), recovery runbook (`docs/runbooks/recovery.md`), and operations guide (`docs/runbooks/trace-link.md`) complete.
