# Trace Link Dependency Failure & Recovery Runbook

Author: Bradu Stanislav (DevOps / Infrastructure / Release Engineering)  
Target: Production Support and DevOps Operators  
Date: 14 October 2026

## 1. Overview and Principles

This runbook defines exact operational recovery procedures for independent failure of each component in the Trace Link architecture.

### Core Recovery Invariants
1. **Never delete shared data volumes** (`postgres-data`, `apache-kafka-data`, `grafana-data`) to resolve a failure.
2. **Platform outage is distinct from service impairment:** An unavailable HTTP route or crashed container generates platform alerts, not telecom incident resolutions.
3. **Transactional Outbox durability:** Unacknowledged or failed deliveries remain safely queued in database outbox tables until external dependencies recover.
4. **Idempotency:** Replay or duplicated delivery of observations or detections cannot generate duplicate incident episodes.

---

## 2. Dependency Failure Recovery Procedures

### 1. PostgreSQL Outage (`postgres`)
- **Symptoms:** Actuator readiness fails with `DatabaseException`; HTTP 500 spikes; `TraceLinkDatasourceUnavailable` alert fires.
- **Durable State Protection:** Pipeline buffers uncommitted windows in Kafka; processor pauses consumption before committing corrupted offsets.
- **Recovery Procedure:**
  ```bash
  docker compose restart postgres
  docker compose exec -T postgres pg_isready -U telecom_admin
  ```
- **Post-Recovery Verification:** Confirm processor re-acquires connection pools, drains in-flight outbox items, and resumes window finalization.

### 2. Kafka Broker Outage (`kafka`)
- **Symptoms:** Producers log `TimeoutException`; consumer lag increases; `TraceLinkOutboxAge` alert triggers.
- **Durable State Protection:** Database transactions commit locally; outbox rows accumulate safely in `processing_db.app.detector_outbox`.
- **Recovery Procedure:**
  ```bash
  docker compose restart kafka
  docker compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092
  ```
- **Post-Recovery Verification:** Verify outbox publisher thread reconnects, flushes backlog in ordered sequence, and oldest outbox age falls below 60s.

### 3. Processor Service Outage (`processor`)
- **Symptoms:** Kafka consumer group partitions unassigned; incoming observations accumulate in `telecom.observations.v2`.
- **Durable State Protection:** Offset commits are tied to database transaction commits; uncommitted offsets remain unacknowledged in Kafka.
- **Recovery Procedure:**
  ```bash
  docker compose restart processor
  curl -s http://localhost:8083/actuator/health/readiness | grep UP
  ```
- **Post-Recovery Verification:** Verify consumer group rebalances, catches up on partition lag, and resumes publishing detections.

### 4. ML Inference Service Outage (`ml-service`)
- **Symptoms:** `TraceLinkMlFallbackRate` alert fires; logs report fallback to deterministic rules.
- **Durable State Protection:** Deterministic anomaly rules remain fully authoritative; ML inference failure cannot suppress or delay deterministic detection.
- **Recovery Procedure:**
  ```bash
  docker compose restart ml-service
  curl -s http://localhost:8090/health/ready
  ```
- **Post-Recovery Verification:** Verify fallback rate drops below 5% and inference requests succeed.

### 5. Incident Service Outage (`incident-service`)
- **Symptoms:** REST API returns 502/503; dashboard shows disconnected badge; SSE stream breaks.
- **Durable State Protection:** Kafka retains detection messages up to topic retention (7 days); incident database state remains immutable.
- **Recovery Procedure:**
  ```bash
  docker compose restart incident-service
  curl -s http://localhost:8082/actuator/health/readiness | grep UP
  ```
- **Post-Recovery Verification:** Consumer catches up on `telecom.detections.trace-link.v1`, commits projections, and reconnects SSE listeners.

### 6. Keycloak Identity Service Outage (`keycloak`)
- **Symptoms:** Login page unavailable; dashboard sessions expire; token validation fails.
- **Durable State Protection:** Existing bearer tokens validate until expiry; session cookies are cached.
- **Recovery Procedure:**
  ```bash
  docker compose restart keycloak
  curl -s http://localhost:8080/auth/health/ready
  ```
- **Post-Recovery Verification:** Confirm login flow redirects successfully and analyst tokens are accepted.

### 7. Reverse Proxy / Ingress Outage (`proxy`)
- **Symptoms:** Inability to access `http://telecom.test:8080`.
- **Durable State Protection:** Backend services continue processing, consuming, and persisting without proxy.
- **Recovery Procedure:**
  ```bash
  docker compose restart proxy
  curl -s http://telecom.test:8080/healthz || curl -I http://127.0.0.1:8080/dashboard
  ```
- **Post-Recovery Verification:** Confirm NGINX routes `/auth`, `/api`, `/dashboard`, and `/grafana` correctly.

### 8. Grafana Observability Outage (`grafana`)
- **Symptoms:** `http://telecom.test:8080/grafana/` returns 502; reporting dashboards inaccessible.
- **Durable State Protection:** Application processing, database writes, and incident delivery remain completely unaffected.
- **Recovery Procedure:**
  ```bash
  docker compose restart grafana
  curl -s http://localhost:3000/api/health
  ```
- **Post-Recovery Verification:** Confirm 14-panel dashboard loads via `trace_link_reader` without query errors.

### 9. Web Push Worker / Invalid Subscription Outage
- **Symptoms:** `TraceLinkPendingDeliveryFailure` alert fires.
- **Durable State Protection:** In-app notification history remains durable in PostgreSQL; invalid browser push endpoints are marked inactive without dropping in-app records.
- **Recovery Procedure:**
  - Deactivate expired subscription in `incidents_db.app.notification_subscriptions`.
  - Restart push delivery worker.
- **Post-Recovery Verification:** Confirm permanent failure counter stops incrementing and valid subscriptions receive push notifications.

### 10. Browser SSE Stream Disconnection
- **Symptoms:** UI toast indicates live stream reconnection attempt.
- **Durable State Protection:** REST API endpoints remain authoritative; client polls `/api/v2/trace-link/incidents` on reconnect.
- **Recovery Procedure:**
  - Client automatically retries with exponential backoff.
  - Proxy buffers and passes through SSE events with `proxy_buffering off;`.
- **Post-Recovery Verification:** UI transitions back to connected state without page reload or duplicated cards.
