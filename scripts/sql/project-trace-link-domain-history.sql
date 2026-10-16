\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('trace-link-md-30d-v1:reference-import', 0));
CREATE TEMP TABLE trace_city_stage (
    ts TIMESTAMPTZ, city_id TEXT, voice_attempts NUMERIC, voice_success NUMERIC,
    voice_drops NUMERIC, voice_holding_seconds NUMERIC, voice_baseline_erlang NUMERIC,
    voice_setup_elapsed_seconds NUMERIC, voice_setup_hist TEXT, sms_submitted NUMERIC,
    sms_delivered NUMERIC, sms_expired NUMERIC, sms_queue NUMERIC, sms_oldest_seconds NUMERIC,
    sms_delay_hist TEXT, data_attempts NUMERIC, data_success NUMERIC, dns_attempts NUMERIC,
    dns_success NUMERIC, user_throughput_mbps NUMERIC, ho_attempts NUMERIC, ho_success NUMERIC
) ON COMMIT DROP;
CREATE TEMP TABLE trace_link_stage (
    ts TIMESTAMPTZ, link_id TEXT, traffic_gbps NUMERIC, effective_capacity_gbps NUMERIC,
    loss_pct NUMERIC, rtt_ms NUMERIC, jitter_ms NUMERIC, interface_errors NUMERIC, queue_drops NUMERIC
) ON COMMIT DROP;
CREATE TEMP TABLE trace_roaming_stage (
    ts TIMESTAMPTZ, country_id TEXT, direction TEXT, partner_id TEXT, active_users NUMERIC,
    reg_attempts NUMERIC, reg_success NUMERIC, data_attempts NUMERIC, data_success NUMERIC,
    voice_attempts NUMERIC, voice_success NUMERIC, sms_attempts NUMERIC, sms_success NUMERIC, rtt_ms NUMERIC
) ON COMMIT DROP;
\copy trace_city_stage FROM '../Trace_Link_Integration_Pack/data/city_kpi_5m.csv' CSV HEADER
\copy trace_link_stage FROM '../Trace_Link_Integration_Pack/data/link_kpi_5m.csv' CSV HEADER
\copy trace_roaming_stage FROM '../Trace_Link_Integration_Pack/data/roaming_kpi_5m.csv' CSV HEADER
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM app.trace_dataset WHERE dataset_id = 'trace-link-md-30d-v1'
        AND synthetic AND seed = 42 AND generator_version = 'reference-1.0'
        AND history_from = '2026-09-10T09:00:00Z' AND history_to_exclusive = '2026-10-10T09:00:00Z'
        AND source_resolution_seconds = 300) THEN
        RAISE EXCEPTION 'Approved dataset must be bootstrapped before domain import';
    END IF;
    IF (SELECT count(*) FROM app.trace_catalogue WHERE dataset_id = 'trace-link-md-30d-v1') <> 1 THEN
        RAISE EXCEPTION 'This pinned fixture adapter requires exactly one dataset catalogue';
    END IF;
END $$;
CREATE TEMP TABLE trace_domain_stage ON COMMIT DROP AS
SELECT 'mobile-data'::text AS domain, 'data.session_success_rate'::text AS metric,
       'CITY'::text AS scope_type, s.city_id AS scope_id, ''::text AS direction,
       ''::text AS partner_id, ''::text AS procedure, s.ts, s.data_success AS numerator,
       s.data_attempts AS denominator, to_jsonb(s) AS source_payload
FROM trace_city_stage s
UNION ALL
SELECT 'transport', 'link.utilization', 'LINK_DIRECTION', s.link_id || ':a-to-b', 'a-to-b', '', '', s.ts,
       s.traffic_gbps * 300, s.effective_capacity_gbps * 300, to_jsonb(s)
FROM trace_link_stage s
UNION ALL
SELECT 'roaming', 'registration.success_rate', 'PARTNER_DIRECTION', s.partner_id || ':' || s.direction,
       s.direction, s.partner_id, 'registration', s.ts, s.reg_success, s.reg_attempts, to_jsonb(s)
FROM trace_roaming_stage s;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM trace_domain_stage s JOIN app.trace_dataset d ON d.dataset_id = 'trace-link-md-30d-v1'
        WHERE s.ts < d.history_from OR s.ts + INTERVAL '5 minutes' > d.history_to_exclusive
           OR s.numerator < 0 OR s.denominator < 0 OR s.numerator IS NULL OR s.denominator IS NULL) THEN
        RAISE EXCEPTION 'Invalid fixture range or primitive';
    END IF;
    IF EXISTS (SELECT 1 FROM trace_city_stage s WHERE NOT EXISTS (
        SELECT 1 FROM app.trace_inventory_city i JOIN app.trace_catalogue c USING (catalogue_version)
        WHERE c.dataset_id = 'trace-link-md-30d-v1' AND i.city_id = s.city_id
          AND i.valid_from <= s.ts AND (i.valid_to IS NULL OR i.valid_to >= s.ts + INTERVAL '5 minutes'))) THEN
        RAISE EXCEPTION 'Fixture city is outside approved inventory';
    END IF;
    IF EXISTS (SELECT 1 FROM trace_link_stage s WHERE NOT EXISTS (
        SELECT 1 FROM app.trace_link_direction i JOIN app.trace_catalogue c USING (catalogue_version)
        WHERE c.dataset_id = 'trace-link-md-30d-v1' AND i.link_id = s.link_id AND i.direction_id = 'a-to-b'
          AND i.effective_capacity_gbps = s.effective_capacity_gbps
          AND i.capacity_valid_from <= s.ts AND (i.capacity_valid_to IS NULL OR i.capacity_valid_to >= s.ts + INTERVAL '5 minutes'))) THEN
        RAISE EXCEPTION 'Fixture capacity is outside declared valid-time capacity';
    END IF;
    IF EXISTS (SELECT 1 FROM trace_roaming_stage s WHERE NOT EXISTS (
        SELECT 1 FROM app.trace_partner i JOIN app.trace_catalogue c USING (catalogue_version)
        WHERE c.dataset_id = 'trace-link-md-30d-v1' AND i.partner_id = s.partner_id AND i.direction = s.direction
          AND i.country_id = s.country_id AND i.valid_from <= s.ts
          AND (i.valid_to IS NULL OR i.valid_to >= s.ts + INTERVAL '5 minutes'))) THEN
        RAISE EXCEPTION 'Fixture partner is outside approved inventory';
    END IF;
END $$;
ANALYZE trace_domain_stage;
SET LOCAL enable_nestloop = off;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM trace_domain_stage s
        JOIN app.trace_metric_window w ON w.dataset_id = 'trace-link-md-30d-v1' AND w.domain = s.domain
          AND w.metric_id = s.metric AND w.scope_type = s.scope_type AND w.scope_id = s.scope_id
          AND w.direction = s.direction AND w.partner_id = s.partner_id AND w.procedure = s.procedure
          AND w.bucket_start = s.ts AND w.policy_version = 'trace-link-md-policy-v1'
          AND w.topology_version = (SELECT topology_version FROM app.trace_catalogue WHERE dataset_id = 'trace-link-md-30d-v1') AND w.revision = 0
        WHERE (w.numerator IS DISTINCT FROM s.numerator OR w.denominator IS DISTINCT FROM s.denominator
          OR w.bucket_end <> s.ts + INTERVAL '5 minutes' OR w.source_resolution_seconds <> 300
          OR w.coverage <> 1 OR w.quality <> 'PARTIAL' OR w.unit <> 'PERCENT'
          OR w.aggregation_type <> 'RATIO_OF_SUMS' OR NOT w.finalized
          OR w.payload IS DISTINCT FROM jsonb_build_object('synthetic', TRUE, 'datasetId', 'trace-link-md-30d-v1',
              'metricVersion', 'trace-link-metrics-v1', 'sourceQuality', NULL, 'sourceCoverage', NULL, 'source', s.source_payload))) THEN
        RAISE EXCEPTION 'Import conflicts with immutable dataset facts';
    END IF;
END $$;
INSERT INTO app.trace_metric_window
    (dataset_id, domain, metric_id, scope_type, scope_id, direction, partner_id, procedure,
     bucket_start, bucket_end, source_resolution_seconds, aggregation_type, numerator, denominator,
     coverage, quality, unit, policy_version, topology_version, revision, finalized, payload)
SELECT c.dataset_id, s.domain, s.metric, s.scope_type, s.scope_id, s.direction, s.partner_id, s.procedure,
       s.ts, s.ts + INTERVAL '5 minutes', 300, 'RATIO_OF_SUMS', s.numerator, s.denominator,
       1, 'PARTIAL', 'PERCENT', 'trace-link-md-policy-v1', c.topology_version, 0, TRUE,
       jsonb_build_object('synthetic', TRUE, 'datasetId', c.dataset_id, 'metricVersion', 'trace-link-metrics-v1', 'sourceQuality', NULL, 'sourceCoverage', NULL, 'source', s.source_payload)
FROM trace_domain_stage s CROSS JOIN app.trace_catalogue c
WHERE c.dataset_id = 'trace-link-md-30d-v1'
ON CONFLICT DO NOTHING;
COMMIT;
