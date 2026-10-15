-- Keep a partial opening queue bucket unavailable, matching the backend.
-- Its full-bucket opening lies outside the selected interval; never include that movement.
-- Replacing the existing function preserves its restricted EXECUTE grants.
CREATE OR REPLACE FUNCTION app.trace_reference_series(dataset text,metric text,scope text,query_from timestamptz,query_to timestamptz,resolution text)
RETURNS TABLE("from" timestamptz,"to" timestamptz,value numeric,unit text,aggregation text,quality text,coverage numeric,
              numerator numeric,denominator numeric,sum_value numeric,sample_count bigint,snapshot_at timestamptz,source_age_seconds bigint,
              histogram_counts jsonb,histogram_edges jsonb,quality_reason text)
LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE d jsonb; seconds integer; kind text;
BEGIN
 seconds:=CASE resolution WHEN '5m' THEN 300 WHEN '15m' THEN 900 WHEN '1h' THEN 3600 WHEN '6h' THEN 21600 ELSE NULL END;
 IF seconds IS NULL OR query_from IS NULL OR query_to IS NULL OR query_from>=query_to OR query_to-query_from>INTERVAL '720 hours' THEN RAISE EXCEPTION 'Invalid Trace Link query'; END IF;
 SELECT definition INTO d FROM app.trace_reference_definition WHERE metric_id=metric;
 IF d IS NULL THEN RAISE EXCEPTION 'Unsupported metric'; END IF;
 IF NOT EXISTS(SELECT 1 FROM app.trace_dataset t JOIN app.trace_reference_import i USING(dataset_id)
   WHERE t.dataset_id=dataset AND t.history_from<=query_from AND t.history_to_exclusive>=query_to) THEN RAISE EXCEPTION 'Full archive unavailable or query outside history'; END IF;
 kind:=CASE d->>'domain' WHEN 'TRANSPORT' THEN 'LINK' WHEN 'ROAMING' THEN 'ROAMING' ELSE 'CITY' END;
 IF NOT EXISTS(SELECT 1 FROM app.trace_catalogue c JOIN app.trace_inventory_city i USING(catalogue_version)
      WHERE kind='CITY' AND c.dataset_id=dataset AND i.city_id=scope AND i.valid_from<=query_from AND (i.valid_to IS NULL OR i.valid_to>=query_to))
 AND NOT EXISTS(SELECT 1 FROM app.trace_catalogue c JOIN app.trace_inventory_link i USING(catalogue_version) JOIN app.trace_link_direction r USING(catalogue_version,link_id)
      WHERE kind='LINK' AND c.dataset_id=dataset AND i.link_id||':'||r.direction_id=scope AND i.valid_from<=query_from AND (i.valid_to IS NULL OR i.valid_to>=query_to))
 AND NOT EXISTS(SELECT 1 FROM app.trace_catalogue c JOIN app.trace_partner i USING(catalogue_version)
      WHERE kind='ROAMING' AND c.dataset_id=dataset AND i.partner_id||':'||i.direction=scope AND i.valid_from<=query_from AND (i.valid_to IS NULL OR i.valid_to>=query_to)) THEN RAISE EXCEPTION 'Unknown scope'; END IF;
 IF metric='roaming.activeInbound' AND scope NOT LIKE '%:in' OR metric='roaming.activeOutbound' AND scope NOT LIKE '%:out' THEN RAISE EXCEPTION 'Wrong snapshot population'; END IF;
 RETURN QUERY
 WITH source AS (
  SELECT w.window_start,w.source_payload p,date_bin(make_interval(secs=>seconds),w.window_start,'1970-01-01Z'::timestamptz) bucket
  FROM app.trace_reference_window w WHERE w.dataset_id=dataset AND w.source_kind=kind AND w.scope_id=scope
    AND w.window_start>=query_from AND w.window_start+INTERVAL '5 minutes'<=query_to
 ), bins AS (
  SELECT s.bucket,j.ordinality,sum(j.value::numeric) count FROM source s
  CROSS JOIN LATERAL jsonb_array_elements_text(CASE WHEN d->>'aggregation'='HISTOGRAM_P95' THEN s.p->(d#>>'{reference,fields,0}') ELSE '[]'::jsonb END) WITH ORDINALITY j(value,ordinality)
  GROUP BY s.bucket,j.ordinality
 ), pooled AS (
  SELECT b.bucket,jsonb_agg(b.count ORDER BY b.ordinality) histogram FROM bins b GROUP BY b.bucket
 ), buckets AS (
  SELECT generate_series(date_bin(make_interval(secs=>seconds),query_from,'1970-01-01Z'::timestamptz),
   date_bin(make_interval(secs=>seconds),query_to-INTERVAL '1 microsecond','1970-01-01Z'::timestamptz),make_interval(secs=>seconds)) bucket
 ), sums AS (
  SELECT b.bucket,count(s.window_start) received,
   sum(CASE WHEN d->>'aggregation'<>'HISTOGRAM_P95' AND d#>>'{reference,availability}'<>'REQUIRES_ADDITIONAL_PRIMITIVES' THEN (s.p->>(d#>>'{reference,fields,0}'))::numeric END) first_sum,
   sum(CASE WHEN d#>>'{reference,availability}'<>'REQUIRES_ADDITIONAL_PRIMITIVES' THEN (s.p->>(d#>>'{reference,fields,1}'))::numeric END) second_sum,
   sum(CASE WHEN metric LIKE 'transport.link.utilization%' THEN 100*(s.p->>'traffic_gbps')::numeric/NULLIF((s.p->>'effective_capacity_gbps')::numeric,0)*300
            WHEN d->>'aggregation'='TIME_WEIGHTED_MEAN' THEN (s.p->>(d#>>'{reference,fields,0}'))::numeric*300 END) weighted,
   max(CASE WHEN metric='transport.link.utilization.peak' THEN 100*(s.p->>'traffic_gbps')::numeric/NULLIF((s.p->>'effective_capacity_gbps')::numeric,0) END) peak,
   max(s.window_start) snapshot,
   (array_agg(s.p ORDER BY s.window_start DESC) FILTER(WHERE s.window_start IS NOT NULL))[1] last_payload
  FROM buckets b LEFT JOIN source s ON s.bucket=b.bucket GROUP BY b.bucket
 ), arithmetic AS (
  SELECT s.*,p.histogram,opening.source_payload opening_payload,closing.source_payload closing_payload,closing.window_start endpoint,
    CASE WHEN d->>'aggregation'='RATIO_OF_SUMS' THEN s.first_sum END n,
    CASE WHEN d->>'aggregation'='RATIO_OF_SUMS' THEN s.second_sum+CASE WHEN metric='sms.finalizedSuccess' THEN s.first_sum ELSE 0 END END denominator_sum,
    CASE WHEN d->>'aggregation'='COUNT_SUM' THEN s.first_sum*CASE WHEN metric='transport.volume' THEN 37500000000 ELSE 1 END
         WHEN d->>'aggregation'='WEIGHTED_MEAN' THEN s.first_sum
         WHEN d->>'aggregation' IN ('TIME_WEIGHTED_MEAN','MAXIMUM') THEN s.weighted END total_sum,
    CASE WHEN d->>'aggregation'='WEIGHTED_MEAN' THEN CASE WHEN metric='volte.traffic.erlang' THEN s.received*300 WHEN jsonb_array_length(d#>'{reference,fields}')>1 THEN s.second_sum ELSE s.received END
         WHEN d->>'aggregation' IN ('TIME_WEIGHTED_MEAN','MAXIMUM') THEN s.received*300
         WHEN d->>'aggregation'='HISTOGRAM_P95' THEN (SELECT sum(j.value::numeric) FROM jsonb_array_elements_text(p.histogram) j(value)) END samples
  FROM sums s LEFT JOIN pooled p USING(bucket)
  LEFT JOIN LATERAL (SELECT w.source_payload FROM app.trace_reference_window w WHERE w.dataset_id=dataset AND w.source_kind=kind AND w.scope_id=scope
     AND s.bucket>=query_from AND w.window_start<s.bucket AND w.window_start>=s.bucket-INTERVAL '5 minutes' ORDER BY w.window_start DESC LIMIT 1) opening ON d->>'aggregation'='SNAPSHOT_DIFFERENCE'
  LEFT JOIN LATERAL (SELECT w.source_payload,w.window_start FROM app.trace_reference_window w WHERE w.dataset_id=dataset AND w.source_kind=kind AND w.scope_id=scope
     AND w.window_start<LEAST(s.bucket+make_interval(secs=>seconds),query_to) ORDER BY w.window_start DESC LIMIT 1) closing ON d->>'aggregation' IN ('SNAPSHOT_LAST','SNAPSHOT_DIFFERENCE')
 ), calculated AS (
  SELECT a.*,CASE WHEN a.received=0 AND a.endpoint IS NULL OR d#>>'{reference,availability}'='REQUIRES_ADDITIONAL_PRIMITIVES' THEN NULL
   WHEN d->>'aggregation'='COUNT_SUM' THEN a.total_sum
   WHEN d->>'aggregation'='RATIO_OF_SUMS' THEN 100*a.n/NULLIF(a.denominator_sum,0)
   WHEN d->>'aggregation' IN ('WEIGHTED_MEAN','TIME_WEIGHTED_MEAN') THEN a.total_sum/NULLIF(a.samples,0)
   WHEN d->>'aggregation'='MAXIMUM' THEN a.peak
   WHEN d->>'aggregation'='SNAPSHOT_LAST' THEN (a.closing_payload->>(d#>>'{reference,fields,0}'))::numeric
   WHEN d->>'aggregation'='SNAPSHOT_DIFFERENCE' THEN (a.closing_payload->>(d#>>'{reference,fields,0}'))::numeric-(a.opening_payload->>(d#>>'{reference,fields,0}'))::numeric
   WHEN d->>'aggregation'='HISTOGRAM_P95' THEN app.trace_reference_p95(a.histogram,d#>'{histogramSchema,edges}')*CASE WHEN metric='sms.delay.p95' THEN 1000 ELSE 1 END END computed
  FROM arithmetic a
 )
 SELECT c.bucket,c.bucket+make_interval(secs=>seconds),c.computed,d->>'unit',d->>'aggregation',
  CASE WHEN c.received=0 AND (c.endpoint IS NULL OR (d->>'aggregation'='SNAPSHOT_DIFFERENCE' AND c.opening_payload IS NULL)) THEN 'MISSING' WHEN c.computed IS NULL THEN 'INSUFFICIENT_EVIDENCE' WHEN c.endpoint IS NOT NULL AND EXTRACT(EPOCH FROM c.bucket+make_interval(secs=>seconds)-c.endpoint)>600 THEN 'STALE' WHEN c.received<seconds/300 THEN 'PARTIAL' ELSE 'INSUFFICIENT_EVIDENCE' END,
  c.received::numeric/(seconds/300),c.n,c.denominator_sum,c.total_sum,c.samples::bigint,
  CASE WHEN d->>'aggregation'='SNAPSHOT_DIFFERENCE' AND c.opening_payload IS NULL THEN NULL WHEN d->>'aggregation' IN ('SNAPSHOT_LAST','SNAPSHOT_DIFFERENCE') THEN c.endpoint END,
  CASE WHEN d->>'aggregation'='SNAPSHOT_DIFFERENCE' AND c.opening_payload IS NULL THEN NULL WHEN d->>'aggregation' IN ('SNAPSHOT_LAST','SNAPSHOT_DIFFERENCE') THEN EXTRACT(EPOCH FROM c.bucket+make_interval(secs=>seconds)-c.endpoint)::bigint END,
  COALESCE(c.histogram,'[]'::jsonb),CASE WHEN metric='sms.delay.p95' THEN (SELECT jsonb_agg(j.value::numeric*1000 ORDER BY j.ordinality) FROM jsonb_array_elements_text(d#>'{histogramSchema,edges}') WITH ORDINALITY j(value,ordinality)) ELSE COALESCE(d#>'{histogramSchema,edges}','[]'::jsonb) END,
  CASE WHEN c.received=0 AND c.endpoint IS NULL THEN 'NO_SOURCE_SLOTS' WHEN d#>>'{reference,availability}'='REQUIRES_ADDITIONAL_PRIMITIVES' THEN 'ADDITIONAL_PRIMITIVES_REQUIRED'
       WHEN d->>'aggregation'='SNAPSHOT_DIFFERENCE' AND c.opening_payload IS NULL THEN 'OPENING_SNAPSHOT_UNAVAILABLE' WHEN c.endpoint IS NOT NULL AND EXTRACT(EPOCH FROM c.bucket+make_interval(secs=>seconds)-c.endpoint)>600 THEN 'RETAINED_SNAPSHOT_STALE' WHEN c.computed IS NULL THEN 'ZERO_DENOMINATOR'
       WHEN c.received<seconds/300 THEN 'MISSING_SOURCE_SLOTS' ELSE 'SOURCE_QUALITY_UNAVAILABLE' END
 FROM calculated c ORDER BY c.bucket;
END $$;
