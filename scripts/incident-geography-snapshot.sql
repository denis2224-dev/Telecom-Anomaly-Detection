-- Read-only signatures for V004 catalogue and immutable coverage rows.
-- Compare before and after a restart or staged feature-off rehearsal.
COPY (
  SELECT signature FROM (
    SELECT 'catalogue|' || catalogue_version || '|' || content_sha256 AS signature
      FROM app.geo_catalogue_versions
    UNION ALL
    SELECT 'city|' || catalogue_version || '|' || city_id || '|'
           || md5(to_jsonb(c)::text)
      FROM app.geo_cities AS c
    UNION ALL
    SELECT 'node|' || catalogue_version || '|' || node_id || '|'
           || md5(to_jsonb(n)::text)
      FROM app.geo_nodes AS n
    UNION ALL
    SELECT 'binding|' || catalogue_version || '|' || scope_id || '|'
           || md5(to_jsonb(b)::text)
      FROM app.geo_scope_bindings AS b
    UNION ALL
    SELECT 'role|' || catalogue_version || '|' || scope_id || '|' || role || '|'
           || md5(to_jsonb(r)::text)
      FROM app.geo_scope_roles AS r
    UNION ALL
    SELECT 'coverage|' || coverage_id || '|' || payload_sha256
      FROM app.scope_window_coverage
  ) AS preserved
  ORDER BY signature
) TO STDOUT;
