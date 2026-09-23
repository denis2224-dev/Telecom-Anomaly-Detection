# Observability configuration

The Prometheus scrape configuration and Grafana dashboard are prepared for the
service-assurance pipeline:

- [`prometheus.yml`](../../infra/observability/prometheus/prometheus.yml)
- [`service-pipeline.json`](../../infra/observability/grafana/service-pipeline.json)

## Metric contract

Application owners must expose these metrics from the service boundaries:

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
demo topology rather than arbitrary request input.

## Health semantics

The dashboard separates process readiness (`up`) from service health. If a
source stops emitting and its heartbeat series disappears, the heartbeat panel
shows `UNKNOWN` rather than green. Missing source intervals remain an explicit
service problem even when the container and scrape endpoint are still healthy.

The current Compose stack does not include Prometheus or Grafana and the current
Java services expose Actuator health only. These files become live after the
monitoring services and application metrics endpoints are added. This is
configuration and metric-contract evidence, not a claim that live dashboards
already exist.

## Loading the dashboard

Import `service-pipeline.json` into Grafana and select the Prometheus data
source when prompted. The dashboard refreshes every 15 seconds and covers
process readiness, source freshness, missing intervals, Kafka lag, finalizer
delay, detector backlog, and outbox age.
