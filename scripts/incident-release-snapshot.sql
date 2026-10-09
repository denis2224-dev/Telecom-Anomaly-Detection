-- Read-only legacy identity snapshot. Works before and after the V004 upgrade.
-- Output contains stable IDs and digests, never raw payloads or analyst notes.
COPY (
  SELECT signature FROM (
    SELECT 'detection|' || detection_id || '|' || md5(payload::text) AS signature
      FROM app.detection_evidence
    UNION ALL
    SELECT 'audit|' || id::text || '|' || md5(to_jsonb(a)::text)
      FROM app.incident_audit AS a
    UNION ALL
    SELECT 'incident|' || id::text || '|' || episode_id
      FROM app.incidents
    UNION ALL
    SELECT 'kpi|' || window_id || '|' || md5(payload::text)
      FROM app.service_kpi_windows
  ) AS preserved
  ORDER BY signature
) TO STDOUT;
