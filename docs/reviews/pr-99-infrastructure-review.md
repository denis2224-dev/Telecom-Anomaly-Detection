# Infrastructure and Operational Review: PR #99 (Trace Link Integration Readiness)

**Reviewer:** Bradu Stanislav (DevOps / Infrastructure Owner)  
**Date:** 13 October 2026  
**PR Under Review:** [#99](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/99) (Combined Integration Readiness)  
**Related PRs:** [#98](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/98) (Consumer Integration), [#96](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/96) (Producer Reliability)  
**Governing Runbooks & Specs:**
- `docs/runbooks/trace-link-receiving-integration.md`
- `docs/architecture/trace-link-release-preflight.md`
- `docs/architecture/trace-link-migration-ledger.md`
- `docs/runbooks/observability.md`
- `scripts/load-profile.json`

---

## Executive Summary & Readiness Verdict

> **Readiness Verdict: NOT READY / BLOCKED for connected Kafka delivery and shared end-to-end execution.**  
> **Verdict for Isolated Local Component Verification: READY.**
> 
> The static contracts, database isolation, immutable native tables, held outbox constraints, entrypoint collisions, and contract suites are strictly verified. However, **new-domain Kafka delivery must remain disabled, V016 outbox records must remain held in `PENDING_REVIEW`, and no production environment settings may be changed.**

---

## 1. PostgreSQL Migrations, Permissions & Database Isolation

### Processor Service (`processing_db.app`) — Migrations V014–V016
- **V014 (`V014__trace_link_inventory.sql`)**:
  - Registers catalogue, reference cities, links, and directions.
  - Correctly restricted to `activation_status = 'CONTRACT_ONLY'` and `scope_namespace = 'trace-link-reference-v1'`.
  - Runtime permissions: `REVOKE ALL ... FROM processing_app; GRANT SELECT, INSERT ... TO processing_app;`.
- **V015 (`V015__trace_link_dataset_history.sql`)**:
  - Manages native immutable archive datasets and windows (`trace_source_native_window`).
  - Runtime permissions: `GRANT SELECT, INSERT` on datasets/windows; `GRANT UPDATE (rows_imported, updated_at)` restricted to `app.trace_source_import_checkpoint`.
  - Ensures completed historical rows cannot be altered.
- **V016 (`V016__trace_link_domain_windows.sql`)**:
  - Tables: `app.trace_metric_projection` and `app.trace_projection_outbox`.
  - Byte-frozen SHA-256 verified: `bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848`.
  - **Outbox Hold Guard:** `delivery_state text NOT NULL DEFAULT 'PENDING_REVIEW' CHECK (delivery_state = 'PENDING_REVIEW')`.
  - **Immutability Guard:** `processing_app` has **only** `SELECT, INSERT` privileges. It has **no** `UPDATE` or `DELETE` grant. Runtime processes cannot unilaterally release records or update delivery state without an approved additive migration.

### Incident Service (`incidents_db.app`) — Migration V008 (PR #98)
- **V008 (`V008__trace_link_primitive_projections.sql`)**:
  - Adds `source_completed_at` to `app.trace_dataset`.
  - Creates `app.trace_primitive_projection` with strict check constraints:
    - `CHECK (domain = 'volte')` (lowercase)
    - `CHECK (metric_id = 'volte.setup.success')`
    - `CHECK (scope_type = 'CITY')`
    - `CHECK (source_file = 'city_kpi_5m.csv')`
  - Permissions: `REVOKE ALL ... FROM incidents_app; GRANT SELECT, INSERT ... TO incidents_app;`. Projections are append-only.
- **Cross-Service Schema Mismatch (Blocker)**:
  - Processor V016 enforces `CHECK (domain = 'VOLTE')` (uppercase).
  - Incident Service V008 enforces `CHECK (domain = 'volte')` (lowercase). While Denis introduced Java-level translation in `TracePrimitiveIngestionService`, any direct SQL mapping or deserialization discrepancy will cause DB constraint violations.
  - V008 strictly permits **only** `domain = 'volte'` and `scope_type = 'CITY'`. It **cannot** store Trace Link mobile-data, roaming, or transport primitives produced by PR #96 / #99.

### Database Isolation
- Complete physical and logical isolation between `processing_db` and `incidents_db`: separate databases, schemas, Flyway version tracks, and non-overlapping database users (`processing_app` vs `incidents_app`).
- Cross-database queries and shared roles are absent.

---

## 2. Kafka Topic Configuration, Partition Keys & Compatibility

### Topic Provisioning
- In `compose.yaml`, `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"` is active.
- `kafka-init` provisions legacy topics (`telecom.observations.v2`, `telecom.kpis.v2`, `telecom.coverage.v1`, etc.).
- **Blocker:** **Zero Trace Link topics are provisioned.** No topic name (e.g. `telecom.tracelink.projections.v1`), partition layout, replication factor, or retention policy has been approved or configured.

### Partition Keys & Routing
- Candidate state keys (`link_id + direction`, `country_id + partner_id + direction`, `contract_city_id + service`) exist only as design proposals in runbooks.
- Neither Kafka serialisers nor broker partition key hashing have been wired in the code.

### Producer / Consumer Compatibility
- Denis's PR #98 adds an in-memory Java boundary: `TracePrimitiveIngestionService.ingest(byte[])`.
- **There is no active Kafka consumer listener subscribed to any topic** in `incident-service`.
- The processor outbox has no publisher worker, and `V016` explicitly forbids non-`PENDING_REVIEW` states.
- Status remains **`INCOMPATIBLE / PENDING_REVIEW`** as recorded in `docs/evidence/assets/trace-link-integration-20261012/consumer-compatibility.json`.

---

## 3. Restart, Replay, Duplicate Delivery & Failure Recovery

| Workflow / Boundary | Target Behavior | Verified Finding | Status |
| :--- | :--- | :--- | :--- |
| **Native Import Resume** (`processing_db`) | Resumes cleanly from `trace_source_import_checkpoint` without re-inserting committed batches. | Verified in `V015` and `TraceLinkCheckpointTest`. Natural keys prevent duplicate records. | **PASS (Isolated)** |
| **Consumer Replay** (`incidents_db`) | Duplicate payload returns `IDENTICAL_RETRY`; altered payload under same slot returns `CONFLICTING_PAYLOAD`. | Verified in PR #98 component test `TracePrimitiveIngestionIT`. | **PASS (Isolated)** |
| **Held Outbox Guard** | Prohibits premature delivery or uncoordinated retry state mutation. | Verified: `V016` check constraint + lack of `UPDATE` permissions. | **PASS** |
| **Broker/DB Outage Recovery** | Transient failures do not drop records or create duplicate logical projections. | Covered on legacy pipelines via `DependencyOutageIT`, `ReplayIT`, `FinalizerRaceIT`. Trace Link connected pipeline is untested. | **BLOCKED (Connected)** |
| **Scenario Generator Lifecycle** | Interrupted scenarios preserve `STOPPED`/`INTERRUPTED` states; gaps recorded as `UNKNOWN`/`MISSING`. | Verified in `TraceLinkScenarioEngineTest`. | **PASS (Isolated)** |

---

## 4. Deployment Configuration & Service Dependencies

1. **Container Resource Limits Missing in Compose**:
   - `compose.yaml` defines heap ceilings (`JAVA_TOOL_OPTIONS: -Xms64m -Xmx384m -XX:MaxMetaspaceSize=192m`), but completely lacks Docker-level cgroup constraints (`deploy.resources.limits.cpus`, `deploy.resources.limits.memory`).
   - Risk: JVM heap bursts or native memory leaks can cause OOM kills on sibling services (PostgreSQL, Kafka).
2. **Missing Observability Containers**:
   - Prometheus configuration (`infra/observability/prometheus/prometheus.yml`, `alerts.yml`) and Grafana dashboard (`infra/observability/grafana/service-pipeline.json`) are prepared in `infra/observability/`, but the service containers are not yet registered in `compose.yaml`.
3. **Entrypoint Collision Safeguards Verified**:
   - Launching `processor` with conflicting mode flags (`TRACE_LINK_BOOTSTRAP_MODE=true`, `TRACE_LINK_PROJECTION_MODE=true`, `HISTORY_BOOTSTRAP_MODE=true`) aborts prior to Spring context initialization with `"Select only one historical mode"`.

---

## 5. Capacity Profile, Throughput & Lag Verification

Evaluation against `scripts/load-profile.json`:

- **Execution State:** **`BLOCKED`**
- **Warmup:** 120 seconds configured.
- **Steady State:** 900 seconds (15 minutes) configured.
- **Unset Parameters (Blockers):** `targetRateObservationsPerSecond`, `boundedBurst.maximumOfferedObservationsPerSecond`, `boundedBurst.durationSeconds`, `recovery.maximumSeconds`, and `topic` are `null`.
- **Lag & Drain Measurement:** Metrics (`telecom_kafka_consumer_lag`, `telecom_detector_backlog`, `telecom_outbox_oldest_age_seconds`) cannot be evaluated because consumer group subscriptions and outbox dispatchers do not exist.

---

## 6. Backup, Restore & Clean Deployment Verification

- **PostgreSQL Binaries:** `postgres:16.4-alpine` provides `pg_dump` and `pg_restore`.
- **Blockers:**
  1. No reproducible backup/restore script exists for the multi-database setup (`processing_db`, `incidents_db`, `keycloak_db`).
  2. No verified Day 6 backup artifact has been produced or restored to a disposable target.
  3. No post-restore hash verification exists to prove consistent row counts, import checkpoint alignment, and outbox retention.
  4. Status remains **`NOT_VERIFIED / BLOCKED`**.

---

## 7. Prerequisites to Unblock a Shared E2E Test Environment

Before connecting services in a shared test environment, the following must be approved and provisioned:

1. **Harmonize Schema Constraints:**
   - Resolve `domain = 'VOLTE'` (V016) vs `domain = 'volte'` (V008).
   - If Mobile Data, Roaming, or Transport projections are to be ingested by `incident-service`, provide an agreed additive Flyway migration for `incidents_db`.
2. **Provision Dedicated Topic:**
   - Define and approve topic (e.g. `telecom.tracelink.projections.v1`) with 3 partitions and retention in `compose.yaml`.
3. **Outbox & Consumer Wiring:**
   - Supply an additive migration providing a delivery worker role with `UPDATE` privilege on `app.trace_projection_outbox` to transition states (`PENDING_REVIEW` -> `SENT`/`ACKED`).
   - Implement a Spring Kafka consumer listener in `incident-service` invoking `TracePrimitiveIngestionService.ingest()`.
4. **Enforce Container Limits:**
   - Add explicit CPU and RAM limits to `compose.yaml` for `incident-service`, `processor`, and `event-generator`.
5. **Finalize Capacity Parameters:**
   - Populate `targetRateObservationsPerSecond`, burst thresholds, and recovery limits in `scripts/load-profile.json`.
6. **Implement Disposable Backup/Restore Automation:**
   - Provide an executable script taking consistent snapshots of `processing_db` and `incidents_db`, restoring to disposable containers, and validating table row counts and payload SHA-256 hashes.
7. **Execute Connected Scenarios:**
   - Complete the 12 ordered verification cases defined in `docs/architecture/trace-link-release-preflight.md`.

---

## 8. Independent Reproduction Results (Stanislav)

Executed in local environment:

| Test / Verification Target | Command Executed | Result |
| :--- | :--- | :--- |
| **Contract Suite** | `python3 scripts/check-contracts.py` | **PASS**: 43 KPIs, 8 evidence cases, 54 arithmetic cases, and all contracts match. |
| **Repository Python Tests** | `.venv/bin/python -m unittest discover -s tests -v` | **PASS**: 112 tests passed, 0 failures, 0 errors. |
| **Load Test Harness** | `.venv/bin/python -m unittest tests/test_load_test.py` | **PASS**: 5 unit tests passed. |
| **V016 Frozen SHA-256** | `git show HEAD:services/processor/src/main/resources/db/migration/V016__trace_link_domain_windows.sql \| sha256` | **PASS**: `bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848` confirmed. |
| **Migration Sequence** | Inspect packaged Flyway migrations | **PASS**: 16 processor migrations (`V001`–`V016`); 8 incident migrations (`V001`–`V008`). |
| **Capacity Profile State** | Inspect `scripts/load-profile.json` | **CONFIRMED BLOCKED**: Reason recorded, target rates unset. |

---

## Summary of Blockers for PR #99 Merge / Release

1. **[BLOCKER 1] No Kafka Topic Provisioned:** `compose.yaml` lacks topic creation for Trace Link; auto-creation is disabled on the broker.
2. **[BLOCKER 2] Missing Outbox Publisher & Ingestion Consumer:** Processor outbox is strictly held at `PENDING_REVIEW` with no worker or `UPDATE` grant; incident-service has no Kafka listener wired.
3. **[BLOCKER 3] Schema Casing & Scope Conflict:** Processor V016 (`VOLTE`) vs Incident Service V008 (`volte`); V008 lacks storage support for mobile-data, roaming, and transport domains.
4. **[BLOCKER 4] Unset Capacity Profile:** Burst ceiling, target rate, and recovery durations are unpopulated in `scripts/load-profile.json`.
5. **[BLOCKER 5] Unverified Backup/Restore:** No multi-database snapshot/restore verification has been run against a disposable target.

**Conclusion:** PR #99 is approved as an **isolated, contract-safe foundation**. Connecting live Kafka delivery, releasing held V016 records, or modifying production settings remains **BLOCKED** until the above 5 items are resolved.
