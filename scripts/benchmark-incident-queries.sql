-- Disposable PostgreSQL only. Apply V001-V004 first, then explicitly opt in:
--   PGOPTIONS='-c telecom.benchmark_only=on' psql -X -v ON_ERROR_STOP=1 \
--     -d <disposable_database> -f scripts/benchmark-incident-queries.sql
-- This creates 10,000 incidents, 120,000 detections, and 20,000 audit rows.
DO $$
BEGIN
    IF current_setting('telecom.benchmark_only', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Set telecom.benchmark_only=on on a disposable database';
    END IF;
END
$$;
SET synchronous_commit = off;
INSERT INTO app.detection_evidence
  (detection_id, episode_id, sequence, phase, service, scope_id,
   window_start, window_end, detected_at, payload)
SELECT 'd-' || i || '-' || seq,
       'episode-' || i,
       seq,
       CASE WHEN seq = 1 THEN 'OPEN' ELSE 'UPDATE' END,
       CASE WHEN i % 2 = 0 THEN 'VOLTE' ELSE 'SMS' END,
       'scope-' || lpad((i % 100)::text, 3, '0'),
       TIMESTAMPTZ '2026-09-01 00:00:00+00' + i * INTERVAL '1 minute' + (seq - 1) * INTERVAL '1 minute',
       TIMESTAMPTZ '2026-09-01 00:00:00+00' + i * INTERVAL '1 minute' + seq * INTERVAL '1 minute',
       TIMESTAMPTZ '2026-09-01 00:00:10+00' + i * INTERVAL '1 minute' + seq * INTERVAL '1 minute',
       jsonb_build_object('schemaVersion', 2, 'detectionId', 'd-' || i || '-' || seq,
         'episodeId', 'episode-' || i, 'sequence', seq,
         'phase', CASE WHEN seq = 1 THEN 'OPEN' ELSE 'UPDATE' END,
         'service', CASE WHEN i % 2 = 0 THEN 'VOLTE' ELSE 'SMS' END,
         'scopeId', 'scope-' || lpad((i % 100)::text, 3, '0'),
         'impact', jsonb_build_object('extraFailedAttempts', 53, 'uniqueSubscribers', null))
FROM generate_series(1, 10000) AS i
CROSS JOIN generate_series(1, 12) AS seq;
INSERT INTO app.incidents
  (id, episode_id, service, scope_id, status, technical_state, severity,
   first_observed_at, detected_at, last_observed_at, latest_sequence)
SELECT md5(i::text)::uuid,
       'episode-' || i,
       CASE WHEN i % 2 = 0 THEN 'VOLTE' ELSE 'SMS' END,
       'scope-' || lpad((i % 100)::text, 3, '0'),
       'OPEN', 'ONGOING', 'HIGH',
       TIMESTAMPTZ '2026-09-01 00:00:00+00' + i * INTERVAL '1 minute',
       TIMESTAMPTZ '2026-09-01 00:01:10+00' + i * INTERVAL '1 minute',
       TIMESTAMPTZ '2026-09-01 00:12:00+00' + i * INTERVAL '1 minute',
       12
FROM generate_series(1, 10000) AS i;
INSERT INTO app.incident_audit
  (id, incident_id, actor_kind, action, occurred_at, request_id, detection_id)
SELECT md5('audit-' || i || '-' || n)::uuid,
       md5(i::text)::uuid,
       'SYSTEM',
       CASE WHEN n = 1 THEN 'OPEN' ELSE 'UPDATE' END,
       TIMESTAMPTZ '2026-09-01 00:00:00+00' + i * INTERVAL '1 minute' + n * INTERVAL '1 minute',
       md5('request-' || i || '-' || n)::uuid,
       'd-' || i || '-' || n
FROM generate_series(1, 10000) AS i
CROSS JOIN generate_series(1, 2) AS n;
ANALYZE app.incidents;
ANALYZE app.detection_evidence;
ANALYZE app.incident_audit;
