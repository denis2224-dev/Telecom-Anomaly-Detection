-- Schema and least-privilege reporting views for Grafana / read-only analytics.
-- Target database: incidents_db
-- Owner: incidents_migrator

CREATE SCHEMA IF NOT EXISTS reporting;

-- 1. Active City Inventory View
CREATE OR REPLACE VIEW reporting.trace_inventory_city AS
SELECT
    city.city_id,
    city.display_name,
    city.latitude,
    city.longitude,
    city.demand_share,
    city.active,
    city.synthetic,
    c.catalogue_version,
    c.topology_version
FROM app.trace_inventory_city city
JOIN app.trace_catalogue c ON c.catalogue_version = city.catalogue_version
WHERE c.active AND city.active;

-- 2. Active Intercity Link Inventory View
CREATE OR REPLACE VIEW reporting.trace_inventory_link AS
SELECT
    link.link_id,
    link.endpoint_a_city_id,
    link.endpoint_b_city_id,
    link.link_type,
    link.nominal_capacity_gbps,
    link.active,
    link.synthetic,
    c.catalogue_version,
    c.topology_version
FROM app.trace_inventory_link link
JOIN app.trace_catalogue c ON c.catalogue_version = link.catalogue_version
WHERE c.active AND link.active;

-- 3. Unified Metric Time Series View (Chart-Safe)
-- Calculates numeric 'value' according to operator aggregation type while preserving raw primitives.
CREATE OR REPLACE VIEW reporting.trace_metric_series AS
SELECT
    w.dataset_id,
    w.domain,
    w.metric_id,
    w.scope_type,
    w.scope_id,
    w.direction,
    w.partner_id,
    w.procedure,
    w.bucket_start,
    w.bucket_end,
    w.source_resolution_seconds,
    w.aggregation_type,
    w.numerator,
    w.denominator,
    CASE
        WHEN w.aggregation_type = 'RATIO_OF_SUMS' THEN
            CASE WHEN w.denominator > 0 THEN ROUND((100.0 * w.numerator / w.denominator)::numeric, 4) ELSE NULL END
        WHEN w.aggregation_type IN ('WEIGHTED_MEAN', 'TIME_WEIGHTED_MEAN') THEN
            CASE WHEN w.sample_count > 0 THEN ROUND((w.sum_value / w.sample_count)::numeric, 4) ELSE NULL END
        WHEN w.aggregation_type = 'SNAPSHOT_LAST' THEN
            COALESCE(w.snapshot_value, (w.payload->>'snapshot')::numeric)
        WHEN w.aggregation_type = 'COUNT_SUM' THEN
            COALESCE(w.sum_value, w.numerator, (w.payload->>'count')::numeric)
        ELSE
            COALESCE(w.snapshot_value, w.sum_value, (w.payload->>'value')::numeric)
    END AS value,
    w.sum_value,
    w.sample_count,
    w.coverage,
    w.quality,
    w.unit,
    w.policy_version,
    w.topology_version,
    w.revision,
    w.finalized
FROM app.trace_metric_window w
WHERE w.finalized;

-- 4. Transport Link Snapshot View
-- Extracts link throughput, capacity, utilization, packet loss, and RTT from transport windows.
CREATE OR REPLACE VIEW reporting.trace_link_snapshot AS
SELECT
    w.dataset_id,
    w.scope_id AS link_id,
    w.direction,
    w.bucket_start,
    w.bucket_end,
    COALESCE((w.payload->>'trafficGbps')::numeric, ROUND((w.numerator / 1000.0)::numeric, 3)) AS traffic_gbps,
    COALESCE((w.payload->>'effectiveCapacityGbps')::numeric, ROUND((w.denominator / 1000.0)::numeric, 3)) AS effective_capacity_gbps,
    CASE
        WHEN w.denominator > 0 THEN ROUND((100.0 * w.numerator / w.denominator)::numeric, 2)
        WHEN (w.payload->>'effectiveCapacityGbps')::numeric > 0 THEN
            ROUND((100.0 * (w.payload->>'trafficGbps')::numeric / (w.payload->>'effectiveCapacityGbps')::numeric)::numeric, 2)
        ELSE NULL
    END AS utilization_percent,
    (w.payload->>'lostPackets')::bigint AS lost_packets,
    (w.payload->>'expectedPackets')::bigint AS expected_packets,
    CASE
        WHEN (w.payload->>'expectedPackets')::numeric > 0 THEN
            ROUND((100.0 * (w.payload->>'lostPackets')::numeric / (w.payload->>'expectedPackets')::numeric)::numeric, 4)
        ELSE 0.0000
    END AS packet_loss_rate,
    COALESCE((w.payload->>'rttSumMs')::numeric / NULLIF((w.payload->>'rttSampleCount')::numeric, 0), 0.0) AS rtt_ms,
    COALESCE((w.payload->>'jitterMs')::numeric, 0.0) AS jitter_ms,
    w.quality,
    w.coverage
FROM app.trace_metric_window w
WHERE w.domain = 'transport' AND w.finalized;

-- 5. Roaming Snapshot View
-- Extracts registration attempts, successes, rates, and active subscriber cohorts.
CREATE OR REPLACE VIEW reporting.trace_roaming_snapshot AS
SELECT
    w.dataset_id,
    w.partner_id,
    COALESCE(p.country_id, split_part(w.scope_id, ':', 1)) AS country_id,
    w.direction,
    w.bucket_start,
    w.bucket_end,
    w.denominator AS registration_attempts,
    w.numerator AS registration_successes,
    CASE
        WHEN w.denominator > 0 THEN ROUND((100.0 * w.numerator / w.denominator)::numeric, 3)
        ELSE NULL
    END AS registration_success_rate,
    COALESCE((w.payload->>'activeSubscribers')::bigint, (w.payload->>'subscribers')::bigint, w.numerator::bigint) AS active_subscribers,
    w.quality,
    w.coverage
FROM app.trace_metric_window w
LEFT JOIN app.trace_partner p ON p.partner_id = w.partner_id AND p.direction = w.direction
WHERE w.domain = 'roaming' AND w.finalized;

-- 6. Incident Annotations View (Sanitized for Operator Visualization)
-- Strictly excludes analyst identities, sessions, audit history, push subscriptions, and keys.
CREATE OR REPLACE VIEW reporting.trace_incident_annotations AS
SELECT
    i.id AS incident_id,
    i.episode_id,
    i.service,
    i.scope_id,
    i.severity,
    i.status,
    i.technical_state,
    i.detected_at,
    i.first_observed_at,
    i.last_observed_at,
    format('%s %s on %s: %s (State: %s)', i.severity, i.service, i.scope_id, i.status, i.technical_state) AS summary,
    jsonb_build_array(i.service, i.severity, i.status, i.technical_state, i.scope_id) AS tags
FROM app.incidents i;

-- 7. Role and Least-Privilege Grants
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'trace_link_reader') THEN
        CREATE ROLE trace_link_reader LOGIN PASSWORD 'trace_link_reader_secret';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE incidents_db TO trace_link_reader;
GRANT USAGE ON SCHEMA reporting TO trace_link_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA reporting TO trace_link_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA reporting GRANT SELECT ON TABLES TO trace_link_reader;

-- Enforce strict database boundary: reader has zero access to internal app tables or sensitive analyst/push data.
REVOKE ALL ON SCHEMA app FROM trace_link_reader;
