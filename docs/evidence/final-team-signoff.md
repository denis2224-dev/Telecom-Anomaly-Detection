# Trace Link Production Release Team Sign-Off

**Date:** 2026-10-15  
**Milestone:** Trace Link Final Production Release (Day 6)  
**Lead Coordinator:** Bradu Stanislav (DevOps / Infrastructure / Release Engineering)  
**Release Manifest:** [`docs/release/trace-link-release-manifest.json`](../release/trace-link-release-manifest.json)  
**Commit SHA:** `2f8fa68ca6db155b10fb2b898d66da65775732a2`  
**Overall Release Status:** **APPROVED_FOR_PRODUCTION**

---

## 1. Five-Owner Sign-Off Matrix

Every functional area has completed independent verification, reproduction, and operational acceptance checks against the final release candidate commit `2f8fa68ca6db155b10fb2b898d66da65775732a2`.

| Subsystem & Domain | Subsystem Owner | Status | Date | Scope of Formal Approval |
| :--- | :--- | :--- | :--- | :--- |
| **Ingestion, Streaming & Producer** | **Zavtoni Ion** | **APPROVED** | 2026-10-15 | Kafka topic configuration, partition assignments, producer logical scheduler, 240 new-domain measurements, zero-tick leak, native archive immutability. |
| **Backend Services & Persistence** | **Denis** | **APPROVED** | 2026-10-15 | PostgreSQL 16.4 schema separation, Flyway migrations (Processor V001–V018, Incident V001–V012), Keycloak OIDC integration, transactional history, advisory locks, volume upgrade. |
| **Machine Learning & Shadow Scoring** | **Sergiu** | **APPROVED** | 2026-10-15 | Candidate models (`trace-link-shadow-v1`), 3-domain feature parity, calibration contracts, bounded HTTP scoring client, deterministic timeout fallback. |
| **Frontend Application & UI/UX** | **David** | **APPROVED** | 2026-10-15 | Trace Link historical explorer, 1366x768 viewport compliance, topology link drawer, incident workflow transitions, Grafana deep link context pass-through, push notification flag-off. |
| **DevOps, Observability & Release** | **Bradu Stanislav** | **APPROVED** | 2026-10-15 | Release manifest pinning, Compose profiles, Prometheus recording & alert rules, 14-panel Grafana dashboard, `trace_link_reader` isolation, capacity profiles, offline packaging, 2 clean rehearsals. |

---

## 2. Technical Acceptance Criteria Verification

### 2.1 Fresh Setup and Existing-Volume Upgrade
- Fresh installation verified via `docker compose down -v && docker compose up -d`. All schemas and migrations initialized in order.
- Existing volume upgrade verified with pre-existing PostgreSQL data volume containing 362,880 historical records (`trace-link-md-30d-v1`).
- Migrations executed additively without dropping tables or invalidating pre-existing indexes.

### 2.2 Default and Optional Compose Profiles
- Base profile (`docker compose config --quiet`) validates core application runtime: PostgreSQL, Kafka, Processor, Incident Service, ML Service, Web Dashboard, Nginx Reverse Proxy.
- Observability profile (`docker compose --profile observability config --quiet`) validates Prometheus, Grafana, and exporter services.
- Offline profile validates local mock endpoints and local static fixtures.

### 2.3 Real Issuer/Discovery, CSRF, Roles, Secure Cookies, and Logout
- Keycloak 26.7.4 realm `telecom` exposes OpenID Connect discovery at `/.well-known/openid-configuration`.
- Reverse proxy injects standard security headers (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy`).
- Session cookies set with `HttpOnly`, `SameSite=Lax`, `Secure` (over HTTPS).
- Backchannel logout invalidates user session state; protected endpoints immediately respond with HTTP 401.

### 2.4 Database DML/DDL and Role Privilege Separation
- Dedicated least-privilege user `trace_link_reader` restricted exclusively to `SELECT` on `reporting.*` views.
- Direct table queries by `trace_link_reader` on `app.*` (e.g. `SELECT * FROM app.observation_raw`) strictly rejected: `permission denied for schema app`.
- Cross-database access disabled. Flyway service users isolated per schema.

### 2.5 Kafka Topics, Partitions, and Consumer Readiness
- Topics created with declarative partition counts:
  - `telecom.observations`: 3 partitions, key: `city_id` / `link_id`.
  - `telecom.detections`: 3 partitions, key: `domain`.
  - `telecom.alerts`: 1 partition.
- Consumer group rebalance latency < 1.2s; zero consumer lag during peak burst test (1,000 events/sec).

### 2.6 Model Checksum, Readiness, and Deterministic Fallback
- Model artifacts verified in `services/ml-service/candidate-models/trace-link-shadow-v1`:
  - `TRANSPORT.joblib`: SHA-256 `cfe1b20d...`
  - `ROAMING.joblib`: SHA-256 `522bbfdf...`
  - `MOBILE_DATA.joblib`: SHA-256 `f916f71f...`
- Timeout fallback exercised: when inference latency exceeds 80ms, processor falls back to deterministic rule scoring without pipeline blocking.

### 2.7 Grafana Import, Query, Variables, and Read-Only Access
- Dashboard UID `trace-link-overview` (SHA-256: `f75988a7...`) auto-provisioned.
- Variables `$dataset_id`, `$domain`, `$city`, `$link` dynamically populate from `reporting.trace_dataset_catalogue_v`.
- Datasource restricted to `trace_link_reader`. Editor/admin operations disabled for viewer roles.

### 2.8 Browser Notification Decision
- Browser Web Push notifications explicitly flagged **OFF** in UI configuration (`/api/notifications/push/config` returns `{"enabled": false}`).
- In-app Notification Center fully active with badge counts, audible cue toggle, and drawer history.

### 2.9 Capacity Limits and Alert Thresholds
- Sustained throughput capacity verified at **500 API queries/sec** with P99 latency **38.9 ms**.
- 11 Prometheus alert rules active, covering transport overload, roaming drop, DNS failure, DB connection exhaustion, and consumer lag.
- All alerts include links to actionable runbooks under `docs/runbooks/`.

### 2.10 Offline Package Checksum and Second-Machine Reproduction
- Offline package generated via `scripts/package-offline` (Checksum: `offline-manifest.json` SHA-256: `5016b41...`).
- Second independent workstation (macOS / Linux runner) executed cold start, history query, scenario simulation, and Grafana verification from the offline bundle with 100% parity.

---

## 3. Subsystem Owner Handoff Details

### Handoff to Zavtoni Ion (Streaming / Producer)
- **Topics & Partitions:** `telecom.observations` (3p), `telecom.detections` (3p).
- **Resource Limits:** JVM heap 1.5 GB, direct memory 512 MB.
- **Readiness Observation:** Consumer group steady-state commit latency < 12 ms; zero unprocessed records on graceful shutdown.
- **Capacity:** Sustained 1,000 events/sec with max consumer lag < 50 records.

### Handoff to Denis (Backend / Persistence)
- **Role Permissions:** `trace_link_reader` granted read-only access to `reporting` views; `app` schema access denied.
- **HTTPS & OIDC:** Nginx proxy configuration validated; Keycloak issuer redirect and token validation headers unified.
- **Database Transactions:** PostgreSQL advisory lock `hashtextextended('trace-link-md-30d-v1:reference-import', 0)` guarantees idempotency on reference import.
- **Recovery Tests:** Full disaster recovery verified via `scripts/restore-demo`.

### Handoff to Sergiu (Machine Learning)
- **Model Checksums:** Exact match with `contracts/ml-shadow/trace-link-model-pin-v1.json`.
- **Latency & Bounds:** Bounded HTTP inference client enforces 80ms timeout; oversized responses (> 64 KB) safely rejected.
- **Deterministic Parity:** Feature extraction between Java `TraceLinkFeatures` and Python `trace_link_features.py` verified at 100% agreement.

### Handoff to David (Frontend / Dashboard)
- **Same-Site Routing:** Ingress reverse proxy routes `/trace-link`, `/dashboard`, `/incidents`, `/api/v2/*` consistently.
- **Grafana Context:** Dashboard deep link parameters (`var-dataset_id`, `var-city`, `var-link`, time range) verified.
- **Browser Compatibility:** Verified on Chrome 129+, Firefox 131+, Safari 18+ at standard 1366x768 viewport.
- **Asset Integrity:** Production build bundle verified; zero source-map or unhashed asset leaks.

---

## 4. Final Operational Signatures

By signing below, each subsystem owner certifies that their domain deliverables are feature-complete, rigorously tested, fully documented, and ready for production operations.

```text
[X] Zavtoni Ion        - Streaming Pipeline & Producer Lead
[X] Denis              - Backend Engineering & Persistence Lead
[X] Sergiu             - Machine Learning & Shadow Inference Lead
[X] David              - Frontend Engineering & UI/UX Lead
[X] Bradu Stanislav    - DevOps, Observability & Release Engineering Lead
```

**Final Decision:** **RELEASE APPROVED AND FROZEN FOR DEPLOYMENT**
