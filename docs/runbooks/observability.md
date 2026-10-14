# Observability configuration

The Prometheus scrape configuration, alert rules, and Grafana dashboard are prepared for the
service-assurance pipeline:

- [`prometheus.yml`](../../infra/observability/prometheus/prometheus.yml)
- [`alerts.yml`](../../infra/observability/prometheus/alerts.yml)
- [`service-pipeline.json`](../../infra/observability/grafana/service-pipeline.json)

## Metric contract

Application services expose Actuator health and Prometheus scrape endpoints:

| Metric | Required labels | Meaning |
| --- | --- | --- |
| `telecom_source_heartbeat_timestamp_seconds` | `source_id`, `scope_id` | Unix timestamp of the latest accepted source heartbeat |
| `telecom_source_missing_intervals` | `source_id`, `scope_id` | Count of missing source windows |
| `telecom_kafka_consumer_lag` | `consumer`, `topic` | Current consumer lag |
| `telecom_finalizer_delay_seconds` | `scope_id` | Delay before a window is finalized |
| `telecom_detector_backlog` | none or bounded service labels | Pending detector work |
| `telecom_outbox_oldest_age_seconds` | none or bounded service labels | Age of the oldest pending outbox item |

IDs such as incident, request, run, and event identifiers belong in logs or
traces, not metric labels. Source and scope labels must come from the bounded
topology rather than arbitrary request input.

## Health semantics

The dashboard separates process readiness (`up`) from service health. If a
source stops emitting and its heartbeat series disappears, the heartbeat panel
shows `UNKNOWN` rather than green. Missing source intervals remain an explicit
service problem even when the container and scrape endpoint are still healthy.

Application services expose metrics through `/actuator/prometheus` with bounded
application tags (`application`).

## Loading the dashboard

Import `service-pipeline.json` into Grafana and select the Prometheus data
source when prompted. The dashboard refreshes every 15 seconds and covers
process readiness, source freshness, missing intervals, Kafka lag, finalizer
delay, detector backlog, and outbox age.

## Alert Operational Runbook

All 11 Prometheus alerts configured in `infra/observability/prometheus/alerts.yml` define condition, duration, rationale, owner action, and recovery verification:

### 1. `TraceLinkSourceStale`
- **Condition:** `telecom_processor_sources{state="stale"} > 0` for 2 minutes
- **Rationale:** Observations must arrive within the 600-second freshness window to avoid undetected impairments.
- **Owner Action:** Inspect event-generator heartbeat and upstream Kafka topic lag for `telecom.observations.v2`.
- **Recovery Verification:** Verify source state transitions back to `active` and consumer lag returns to zero.

### 2. `TraceLinkFinalizerLag`
- **Condition:** `histogram_quantile(0.95, sum by (le) (rate(telecom_processor_finalizer_delay_seconds_bucket[5m]))) > 60` for 5 minutes
- **Rationale:** Excessive delay in 5-minute window finalization delays incident detection and reporting.
- **Owner Action:** Check `processing_db` connection pool saturation, thread pool health, and Kafka partition assignments.
- **Recovery Verification:** Ensure finalizer p95 latency drops below 60s for two consecutive 5m evaluation intervals.

### 3. `TraceLinkOutboxAge`
- **Condition:** `max(telecom_processor_outbox_oldest_age_seconds) > 300` for 5 minutes
- **Rationale:** Outbox rows must publish to Kafka atomically within 5 minutes to prevent backlog accumulation.
- **Owner Action:** Verify publisher worker threads and Kafka broker cluster availability.
- **Recovery Verification:** Confirm oldest outbox age falls below 60s and queue drains to zero.

### 4. `TraceLinkDetectorBacklog`
- **Condition:** `max(telecom_processor_detector_job_age_seconds) > 120 or telecom_processor_detector_backlog_total > 500` for 3 minutes
- **Rationale:** Backlog exceeding 500 jobs indicates queue accumulation and impending lease expirations.
- **Owner Action:** Scale worker concurrency via `TRACE_LINK_WORKERS` or inspect CPU/memory throttling.
- **Recovery Verification:** Ensure detector job age drops below 30s and backlog returns to baseline.

### 5. `TraceLinkMlFallbackRate`
- **Condition:** `sum(rate(telecom_ml_inference_fallback_total[5m])) / clamp_min(sum(rate(telecom_ml_inference_requests_total[5m])), 1) > 0.25` for 10 minutes
- **Rationale:** Fallback exceeding 25% suggests inference server timeouts, model crash, or concurrency exhaustion. Deterministic detection remains authoritative.
- **Owner Action:** Inspect `ml-service` container health, HTTP 503 limits, and Uvicorn concurrency logs.
- **Recovery Verification:** Verify ML readiness returns to UP and fallback rate drops below 5%.

### 6. `TraceLinkApiErrors`
- **Condition:** `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count[5m])), 1) > 0.05` for 5 minutes
- **Rationale:** REST API error rate above 5% impairs analyst dashboards and automated integrations.
- **Owner Action:** Inspect `incident-service` error logs, database query timeouts, and proxy routing.
- **Recovery Verification:** Ensure 5xx HTTP response rate drops below 1% for 5 minutes.

### 7. `TraceLinkSseSaturation`
- **Condition:** `sum(telecom_incident_sse_active_connections) > 500 or telecom_incident_sse_dropped_events_total > 0` for 2 minutes
- **Rationale:** Dropped SSE events force clients to fall back to expensive REST polling.
- **Owner Action:** Check proxy connection limits, memory utilization, and client reconnect frequency.
- **Recovery Verification:** Verify dropped event counter remains static and active connections normalize.

### 8. `TraceLinkPendingDeliveryFailure`
- **Condition:** `sum(rate(telecom_incident_delivery_permanent_failures_total[5m])) > 0 or max(telecom_incident_delivery_pending_oldest_age_seconds) > 300` for 3 minutes
- **Rationale:** Pending deliveries older than 5 minutes indicate notification worker or endpoint stalls.
- **Owner Action:** Check delivery retry queues and external push gateway connectivity.
- **Recovery Verification:** Verify permanent failure rate returns to 0 and pending queue drains.

### 9. `TraceLinkDatasourceUnavailable`
- **Condition:** `absent(up{job="processor"}) or absent(up{job="incident-service"})` for 3 minutes
- **Rationale:** Target scrape failure prevents observability and capacity accounting.
- **Owner Action:** Restart failed container processes and inspect Docker/container logs.
- **Recovery Verification:** Confirm `up` metric equals 1 for both processor and incident-service.

### 10. `TraceLinkDatabasePoolExhaustion`
- **Condition:** `(telecom_db_pool_active / clamp_min(telecom_db_pool_max, 1) > 0.9) or (telecom_db_pool_wait_count > 10)` for 2 minutes
- **Rationale:** Exhausted connection pools cause transaction timeouts and HTTP 500 spikes.
- **Owner Action:** Inspect long-running queries, transaction locks, and pool sizing in `application.yaml`.
- **Recovery Verification:** Confirm pool utilization drops below 70% and wait count drops to zero.

### 11. `TraceLinkGrafanaDatasourceFailure`
- **Condition:** `rate(grafana_datasource_query_errors_total{datasource="trace-link-pg"}[5m]) > 0.1` for 3 minutes
- **Rationale:** Grafana dashboard failures prevent visual monitoring without impacting pipeline processing.
- **Owner Action:** Verify `trace_link_reader` credentials, reporting schema views, and PostgreSQL connection limit.
- **Recovery Verification:** Confirm Grafana panel queries return 200 without SQL execution errors.

