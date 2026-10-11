-- Independent PostgreSQL 16 primitive oracle. Run only in a disposable database.
CREATE SCHEMA IF NOT EXISTS tl_parity;
CREATE OR REPLACE FUNCTION tl_parity.require(ok boolean) RETURNS void LANGUAGE plpgsql AS $$
BEGIN IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'INVALID_INPUT'; END IF; END $$;
CREATE OR REPLACE FUNCTION tl_parity.keys(x jsonb, names text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 PERFORM tl_parity.require(jsonb_typeof(x)='object');
 PERFORM tl_parity.require((SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(x) k)=
   (SELECT array_agg(k ORDER BY k) FROM unnest(string_to_array(names,' ')) k));
END $$;
CREATE OR REPLACE FUNCTION tl_parity.num(x jsonb, integer_only boolean DEFAULT false, positive_only boolean DEFAULT false)
RETURNS numeric LANGUAGE plpgsql AS $$ DECLARE n numeric; BEGIN
 PERFORM tl_parity.require(jsonb_typeof(x)='number'); n:=(x#>>'{}')::numeric;
 PERFORM tl_parity.require(n>=0 AND n<=1.7976931348623157e308 AND (NOT positive_only OR n>0)
   AND (NOT integer_only OR (n=trunc(n) AND n<=9007199254740991)));
 RETURN n;
END $$;
CREATE OR REPLACE FUNCTION tl_parity.clean(x jsonb) RETURNS void LANGUAGE plpgsql AS $$ DECLARE r record; BEGIN
 IF jsonb_typeof(x)='object' THEN
  FOR r IN SELECT key,value FROM jsonb_each(x) LOOP
   PERFORM tl_parity.require(r.key IN ('expectedRows','expectedSeconds') OR r.key !~* '(truth|expected|future|scenario|label|outcome)');
   PERFORM tl_parity.clean(r.value);
  END LOOP;
 ELSIF jsonb_typeof(x)='array' THEN
  FOR r IN SELECT value FROM jsonb_array_elements(x) LOOP PERFORM tl_parity.clean(r.value); END LOOP;
 END IF;
END $$;
CREATE OR REPLACE FUNCTION tl_parity.ts(x jsonb, aligned boolean DEFAULT false) RETURNS timestamptz LANGUAGE plpgsql AS $$
DECLARE t timestamptz; BEGIN
 PERFORM tl_parity.require(jsonb_typeof(x)='string' AND (x#>>'{}') !~ '^0000' AND (x#>>'{}') ~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$'); t:=(x#>>'{}')::timestamptz;
 PERFORM tl_parity.require(isfinite(t) AND (NOT aligned OR mod(extract(epoch FROM t),300)=0)); RETURN t;
END $$;
CREATE OR REPLACE FUNCTION tl_parity.array_numbers(x jsonb, size integer, integer_only boolean DEFAULT true) RETURNS numeric[] LANGUAGE plpgsql AS $$
DECLARE a numeric[]:='{}'; r jsonb; BEGIN
 PERFORM tl_parity.require(jsonb_typeof(x)='array' AND jsonb_array_length(x)=size AND size>0);
 FOR r IN SELECT value FROM jsonb_array_elements(x) LOOP a:=array_append(a,tl_parity.num(r,integer_only)); END LOOP; RETURN a;
END $$;
CREATE OR REPLACE FUNCTION tl_parity.rate(n numeric,d numeric) RETURNS numeric LANGUAGE sql IMMUTABLE AS $$ SELECT 100*n/NULLIF(d,0) $$;
CREATE OR REPLACE FUNCTION tl_parity.p95(counts numeric[],edges numeric[]) RETURNS numeric LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE total numeric; running numeric:=0; low numeric:=0; j integer; BEGIN
 SELECT sum(n) INTO total FROM unnest(counts) n; IF total=0 THEN RETURN NULL; END IF;
 PERFORM tl_parity.num(to_jsonb(total),true);
 FOR j IN 1..array_length(counts,1) LOOP
  IF counts[j]>0 AND running+counts[j]>=.95*total THEN RETURN low+(edges[j]-low)*(.95*total-running)/counts[j]; END IF;
  running:=running+counts[j]; low:=edges[j];
 END LOOP; RAISE EXCEPTION 'INVALID_INPUT';
END $$;
CREATE OR REPLACE FUNCTION tl_parity.body(p jsonb,v jsonb,q text DEFAULT 'VALID',reason text DEFAULT NULL,coverage numeric DEFAULT 1,snapshot jsonb DEFAULT 'null')
RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$ SELECT jsonb_build_object('primitives',p,'values',v,'quality',q,'nullReason',reason,'coverage',coverage,'snapshotAt',snapshot) $$;

CREATE OR REPLACE FUNCTION tl_parity.calculate(op text,x jsonb,w jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE
 f timestamptz; t timestamptz; at_time timestamptz; row_time timestamptz; data_from timestamptz; data_to timestamptz;
 r jsonb; h jsonb; first_hist jsonb; selected jsonb; p jsonb:='{}'; v jsonb; latest jsonb:='{}'; snapshot jsonb:='null';
 names text[]; k text; scope_key text; row_key text; seen text[]:='{}'; slots text[]:='{}';
 counts numeric[]; edges numeric[]; voice numeric[]:=ARRAY[0,0,0,0,0,0,0,0,0]::numeric[];
 sms numeric[]:=ARRAY[0,0,0,0,0,0,0,0,0]::numeric[]; arr numeric[]; voice_edges numeric[]:=ARRAY[.5,1,2,3,5,8,15,30,60]::numeric[];
 sms_edges numeric[]:=ARRAY[1,2,5,10,20,40,80,160,320]::numeric[];
 n numeric:=0; d numeric:=0; total numeric; seconds numeric:=0; traffic_time numeric:=0; util_time numeric:=0; peak numeric:=0;
 cap numeric; traffic numeric; duration numeric; coverage numeric:=1; growth numeric; expected numeric; throughput numeric:=0;
 queue numeric:=0; oldest numeric:=0; allocated numeric:=0; users numeric:=0; scale numeric:=1; stale boolean:=false;
 row_count integer:=0; j integer; q text:='VALID'; reason text:=NULL;
BEGIN
 PERFORM tl_parity.clean(x); PERFORM tl_parity.clean(w); PERFORM tl_parity.keys(w,'from to');
 f:=tl_parity.ts(w->'from'); t:=tl_parity.ts(w->'to'); PERFORM tl_parity.require(f<t);
 IF op='ratio' THEN
  PERFORM tl_parity.keys(x,'partitions'); PERFORM tl_parity.require(jsonb_typeof(x->'partitions')='array');
  FOR r IN SELECT value FROM jsonb_array_elements(x->'partitions') LOOP
   PERFORM tl_parity.keys(r,'numerator denominator'); PERFORM tl_parity.require(tl_parity.num(r->'numerator',true)<=tl_parity.num(r->'denominator',true));
   n:=n+tl_parity.num(r->'numerator',true); d:=d+tl_parity.num(r->'denominator',true);
  END LOOP;
  PERFORM tl_parity.num(to_jsonb(n),true); PERFORM tl_parity.num(to_jsonb(d),true);
  RETURN tl_parity.body(jsonb_build_object('numerator',n,'denominator',d),jsonb_build_object('value',tl_parity.rate(n,d)),CASE WHEN d=0 THEN 'INSUFFICIENT_DATA' ELSE 'VALID' END,CASE WHEN d=0 THEN 'ZERO_DENOMINATOR' END);
 ELSIF op='histogram' THEN
  PERFORM tl_parity.keys(x,'histograms outputUnit'); PERFORM tl_parity.require(jsonb_typeof(x->'histograms')='array' AND jsonb_array_length(x->'histograms')>0);
  first_hist:=x->'histograms'->0; PERFORM tl_parity.keys(first_hist,'counts edges unit schemaVersion');
  PERFORM tl_parity.require(first_hist->>'unit' IN ('SECONDS','MILLISECONDS') AND x->>'outputUnit' IN ('SECONDS','MILLISECONDS') AND jsonb_typeof(first_hist->'schemaVersion')='string' AND length(btrim(first_hist->>'schemaVersion'))>0);
  edges:=tl_parity.array_numbers(first_hist->'edges',jsonb_array_length(first_hist->'edges'),false); counts:=array_fill(0::numeric,ARRAY[array_length(edges,1)]);
  FOR j IN 1..array_length(edges,1) LOOP PERFORM tl_parity.require(edges[j]>0 AND (j=1 OR edges[j]>edges[j-1])); END LOOP;
  FOR h IN SELECT value FROM jsonb_array_elements(x->'histograms') LOOP
   PERFORM tl_parity.keys(h,'counts edges unit schemaVersion'); PERFORM tl_parity.require(h->'unit'=first_hist->'unit' AND h->'schemaVersion'=first_hist->'schemaVersion' AND h->'edges'=first_hist->'edges');
   arr:=tl_parity.array_numbers(h->'counts',array_length(edges,1));
   FOR j IN 1..array_length(edges,1) LOOP counts[j]:=counts[j]+arr[j]; PERFORM tl_parity.num(to_jsonb(counts[j]),true); END LOOP;
  END LOOP;
  SELECT sum(a) INTO total FROM unnest(counts) a; PERFORM tl_parity.num(to_jsonb(total),true);
  scale:=CASE WHEN first_hist->>'unit'=x->>'outputUnit' THEN 1 WHEN first_hist->>'unit'='SECONDS' THEN 1000 ELSE .001 END;
  RETURN tl_parity.body(jsonb_build_object('counts',counts,'edges',edges,'sampleCount',total),jsonb_build_object('p95',tl_parity.p95(counts,edges)*scale),CASE WHEN total=0 THEN 'INSUFFICIENT_DATA' ELSE 'VALID' END,CASE WHEN total=0 THEN 'EMPTY_HISTOGRAM' END);
 ELSIF op='utilization' THEN
  PERFORM tl_parity.keys(x,'segments expectedSeconds'); expected:=tl_parity.num(x->'expectedSeconds',false,true); PERFORM tl_parity.require(jsonb_typeof(x->'segments')='array');
  FOR r IN SELECT value FROM jsonb_array_elements(x->'segments') LOOP
   PERFORM tl_parity.keys(r,'trafficGbps effectiveCapacityGbps validSeconds quality');
   traffic:=tl_parity.num(r->'trafficGbps'); duration:=tl_parity.num(r->'validSeconds',false,true); cap:=NULL;
   IF r->'effectiveCapacityGbps'<>'null'::jsonb THEN cap:=tl_parity.num(r->'effectiveCapacityGbps',false,true); PERFORM tl_parity.require(traffic<=cap); END IF;
   PERFORM tl_parity.require(r->>'quality' IN ('VALID','MISSING','STALE')); stale:=stale OR r->>'quality'='STALE';
   IF r->>'quality'='VALID' AND cap IS NOT NULL THEN seconds:=seconds+duration; traffic_time:=traffic_time+traffic*duration; util_time:=util_time+100*traffic/cap*duration; peak:=greatest(peak,100*traffic/cap); END IF;
  END LOOP;
  coverage:=seconds/expected; PERFORM tl_parity.require(coverage<=1);
  IF seconds=0 THEN q:=CASE WHEN stale THEN 'STALE' ELSE 'INSUFFICIENT_DATA' END; reason:=CASE WHEN stale THEN 'STALE_SOURCE' ELSE 'MISSING_CAPACITY' END;
  ELSIF coverage<1 THEN q:='PARTIAL'; reason:='PARTIAL_COVERAGE'; END IF;
  RETURN tl_parity.body(jsonb_build_object('validSeconds',seconds,'trafficTime',traffic_time,'utilizationTime',util_time),jsonb_build_object('meanPct',util_time/NULLIF(seconds,0),'peakPct',CASE WHEN seconds>0 THEN peak END,'trafficMeanGbps',traffic_time/NULLIF(seconds,0),'transferredGbEstimate',CASE WHEN seconds>0 THEN traffic_time END),q,reason,coverage);
 ELSIF op='queue' THEN
  PERFORM tl_parity.keys(x,'openingQueue submitted delivered terminalNegative closingQueue durationSeconds');
  FOREACH k IN ARRAY ARRAY['openingQueue','submitted','delivered','terminalNegative','closingQueue'] LOOP PERFORM tl_parity.num(x->k,true); END LOOP;
  duration:=tl_parity.num(x->'durationSeconds',false,true);
  PERFORM tl_parity.require((x->>'closingQueue')::numeric=(x->>'openingQueue')::numeric+(x->>'submitted')::numeric-(x->>'delivered')::numeric-(x->>'terminalNegative')::numeric);
  growth:=(x->>'closingQueue')::numeric-(x->>'openingQueue')::numeric; d:=(x->>'delivered')::numeric+(x->>'terminalNegative')::numeric; PERFORM tl_parity.num(to_jsonb(d),true);
  RETURN tl_parity.body(x,jsonb_build_object('growth',growth,'growthPerSecond',growth/duration,'finalizedSuccessPct',tl_parity.rate((x->>'delivered')::numeric,d)),CASE WHEN d=0 THEN 'INSUFFICIENT_DATA' ELSE 'VALID' END,CASE WHEN d=0 THEN 'ZERO_DENOMINATOR' END);
 ELSIF op='city_summary' THEN
  PERFORM tl_parity.keys(x,'rows expectedRows'); expected:=tl_parity.num(x->'expectedRows',true,true); PERFORM tl_parity.require(jsonb_typeof(x->'rows')='array'); row_count:=jsonb_array_length(x->'rows'); PERFORM tl_parity.require(row_count<=expected);
  names:=ARRAY['attempts','success','drops','holding','setupSum','smsSubmitted','smsDelivered','smsExpired','dataAttempts','dataSuccess','dnsAttempts','dnsSuccess','hoAttempts','hoSuccess'];
  FOREACH k IN ARRAY names LOOP p:=p||jsonb_build_object(k,0); END LOOP;
  FOR r IN SELECT value FROM jsonb_array_elements(x->'rows') LOOP
   PERFORM tl_parity.keys(r,'cityId ts attempts success drops holding setupSum smsSubmitted smsDelivered smsExpired smsQueue smsOldest dataAttempts dataSuccess dnsAttempts dnsSuccess throughput hoAttempts hoSuccess voiceCounts smsCounts');
   PERFORM tl_parity.require(jsonb_typeof(r->'cityId')='string' AND length(btrim(r->>'cityId'))>0); row_time:=tl_parity.ts(r->'ts',true); PERFORM tl_parity.require(row_time>=f AND row_time<t);
   row_key:=jsonb_build_array(r->'cityId',extract(epoch FROM row_time))::text; PERFORM tl_parity.require(NOT row_key=ANY(seen)); seen:=array_append(seen,row_key);
   IF NOT extract(epoch FROM row_time)::text=ANY(slots) THEN slots:=array_append(slots,extract(epoch FROM row_time)::text); END IF;
   FOREACH k IN ARRAY names LOOP n:=tl_parity.num(r->k,k NOT IN ('holding','setupSum'))+(p->>k)::numeric; IF k NOT IN ('holding','setupSum') THEN PERFORM tl_parity.num(to_jsonb(n),true); END IF; p:=p||jsonb_build_object(k,n); END LOOP;
   PERFORM tl_parity.num(r->'smsQueue',true); PERFORM tl_parity.num(r->'smsOldest'); throughput:=throughput+tl_parity.num(r->'throughput');
   PERFORM tl_parity.require((r->>'success')::numeric<=(r->>'attempts')::numeric AND (r->>'drops')::numeric<=(r->>'success')::numeric AND (r->>'dataSuccess')::numeric<=(r->>'dataAttempts')::numeric AND (r->>'dnsSuccess')::numeric<=(r->>'dnsAttempts')::numeric AND (r->>'hoSuccess')::numeric<=(r->>'hoAttempts')::numeric);
   arr:=tl_parity.array_numbers(r->'voiceCounts',9); SELECT sum(a) INTO total FROM unnest(arr) a; PERFORM tl_parity.require(total=(r->>'success')::numeric);
   FOR j IN 1..9 LOOP voice[j]:=voice[j]+arr[j]; PERFORM tl_parity.num(to_jsonb(voice[j]),true); END LOOP;
   arr:=tl_parity.array_numbers(r->'smsCounts',9); SELECT sum(a) INTO total FROM unnest(arr) a; PERFORM tl_parity.require(total=(r->>'smsDelivered')::numeric);
   FOR j IN 1..9 LOOP sms[j]:=sms[j]+arr[j]; PERFORM tl_parity.num(to_jsonb(sms[j]),true); END LOOP;
   scope_key:=r->>'cityId';
   IF NOT latest ? scope_key OR row_time>tl_parity.ts(latest->scope_key->'ts') THEN latest:=latest||jsonb_build_object(scope_key,r); END IF;
   IF snapshot='null'::jsonb OR row_time>tl_parity.ts(snapshot) THEN snapshot:=r->'ts'; END IF;
  END LOOP;
  FOR r IN SELECT value FROM jsonb_each(latest) LOOP queue:=queue+(r->>'smsQueue')::numeric; oldest:=greatest(oldest,(r->>'smsOldest')::numeric); END LOOP; PERFORM tl_parity.num(to_jsonb(queue),true);
  p:=p||jsonb_build_object('voiceCounts',voice,'smsCounts',sms,'throughputSum',throughput,'rowCount',row_count,'slotCount',cardinality(slots),'queue',queue,'oldest',oldest);
  v:=jsonb_build_object('voiceSR',tl_parity.rate((p->>'success')::numeric,(p->>'attempts')::numeric),'dropRate',tl_parity.rate((p->>'drops')::numeric,(p->>'success')::numeric),'erlang',(p->>'holding')::numeric/NULLIF(cardinality(slots)*300,0),'setupMeanSeconds',(p->>'setupSum')::numeric/NULLIF((p->>'success')::numeric,0),'setupP95Seconds',tl_parity.p95(voice,voice_edges),'smsP95Ms',1000*tl_parity.p95(sms,sms_edges),'smsSR',tl_parity.rate((p->>'smsDelivered')::numeric,(p->>'smsDelivered')::numeric+(p->>'smsExpired')::numeric),'dataSR',tl_parity.rate((p->>'dataSuccess')::numeric,(p->>'dataAttempts')::numeric),'dnsSR',tl_parity.rate((p->>'dnsSuccess')::numeric,(p->>'dnsAttempts')::numeric),'hoSR',tl_parity.rate((p->>'hoSuccess')::numeric,(p->>'hoAttempts')::numeric),'throughputMbps',throughput/NULLIF(row_count,0));
  coverage:=row_count/expected;
  IF (SELECT count(DISTINCT tl_parity.ts(value->'ts')) FROM jsonb_each(latest))>1 THEN RETURN tl_parity.body(p||jsonb_build_object('queue',NULL,'oldest',NULL),v,'PARTIAL','SNAPSHOT_MISALIGNED',coverage); END IF;
  RETURN tl_parity.body(p,v,CASE WHEN row_count=0 THEN 'MISSING' WHEN coverage<1 THEN 'PARTIAL' ELSE 'VALID' END,CASE WHEN row_count=0 THEN 'NO_OBSERVATIONS' WHEN coverage<1 THEN 'PARTIAL_COVERAGE' END,coverage,snapshot);
 ELSIF op='link_snapshot' THEN
  PERFORM tl_parity.keys(x,'linkId cityId at dataFrom dataTo maxAgeSeconds rows'); PERFORM tl_parity.num(x->'maxAgeSeconds');
  PERFORM tl_parity.require(jsonb_typeof(x->'linkId')='string' AND length(btrim(x->>'linkId'))>0 AND jsonb_typeof(x->'cityId')='string' AND length(btrim(x->>'cityId'))>0 AND jsonb_typeof(x->'rows')='array');
  data_from:=tl_parity.ts(x->'dataFrom'); data_to:=tl_parity.ts(x->'dataTo'); PERFORM tl_parity.require(data_from<data_to); at_time:=CASE WHEN x->'at'='null'::jsonb THEN t ELSE tl_parity.ts(x->'at') END;
  FOR r IN SELECT value FROM jsonb_array_elements(x->'rows') LOOP
   PERFORM tl_parity.keys(r,'linkId a b ts trafficGbps effectiveCapacityGbps'); row_time:=tl_parity.ts(r->'ts',true); PERFORM tl_parity.num(r->'trafficGbps');
   IF r->'effectiveCapacityGbps'<>'null'::jsonb THEN cap:=tl_parity.num(r->'effectiveCapacityGbps',false,true); PERFORM tl_parity.require((r->>'trafficGbps')::numeric<=cap); END IF;
   FOREACH k IN ARRAY ARRAY['linkId','a','b'] LOOP PERFORM tl_parity.require(jsonb_typeof(r->k)='string' AND length(btrim(r->>k))>0); END LOOP; PERFORM tl_parity.require(r->'a'<>r->'b');
   row_key:=jsonb_build_array(r->'linkId',extract(epoch FROM row_time))::text; PERFORM tl_parity.require(NOT row_key=ANY(seen)); seen:=array_append(seen,row_key);
   IF r->'linkId'=x->'linkId' AND (x->>'cityId'='all' OR x->'cityId'=r->'a' OR x->'cityId'=r->'b') AND row_time>=data_from AND row_time<data_to AND ((x->'at'='null'::jsonb AND row_time>=f AND row_time<t) OR (x->'at'<>'null'::jsonb AND row_time<=at_time)) AND (selected IS NULL OR row_time>tl_parity.ts(selected->'ts')) THEN selected:=r; END IF;
  END LOOP;
  p:=jsonb_build_object('trafficGbps',NULL,'effectiveCapacityGbps',NULL); v:=jsonb_build_object('utilizationPct',NULL,'ageSeconds',NULL);
  IF at_time<data_from OR (x->'at'='null'::jsonb AND (f<data_from OR t>data_to)) OR (x->'at'<>'null'::jsonb AND at_time>=data_to) THEN RETURN tl_parity.body(p,v,'MISSING','OUTSIDE_DATASET',0); END IF;
  IF selected IS NULL THEN RETURN tl_parity.body(p,v,'MISSING','NO_OBSERVATIONS',0); END IF;
  p:=jsonb_build_object('trafficGbps',selected->'trafficGbps','effectiveCapacityGbps',selected->'effectiveCapacityGbps'); snapshot:=selected->'ts';
  v:=v||jsonb_build_object('ageSeconds',extract(epoch FROM at_time-tl_parity.ts(snapshot)));
  IF extract(epoch FROM at_time-tl_parity.ts(snapshot))>(x->>'maxAgeSeconds')::numeric THEN RETURN tl_parity.body(p,v,'STALE','STALE_SOURCE',0,snapshot); END IF;
  IF selected->'effectiveCapacityGbps'='null'::jsonb THEN RETURN tl_parity.body(p,v,'INSUFFICIENT_DATA','MISSING_CAPACITY',0,snapshot); END IF;
  RETURN tl_parity.body(p,v||jsonb_build_object('utilizationPct',100*(selected->>'trafficGbps')::numeric/(selected->>'effectiveCapacityGbps')::numeric),'VALID',NULL,1,snapshot);
 ELSIF op='roaming_summary' THEN
  PERFORM tl_parity.keys(x,'country direction cityShare maxAgeSeconds rows'); PERFORM tl_parity.require(jsonb_typeof(x->'country')='string' AND (x->>'country'='all' OR x->>'country' ~ '^[A-Z]{2}$') AND x->>'direction' IN ('out','in','both') AND jsonb_typeof(x->'rows')='array');
  scale:=tl_parity.num(x->'cityShare',false,true); PERFORM tl_parity.require(scale<=1); PERFORM tl_parity.num(x->'maxAgeSeconds');
  names:=ARRAY['regAtt','regOk','dataAtt','dataOk','voiceAtt','voiceOk','smsAtt','smsOk']; FOREACH k IN ARRAY names LOOP p:=p||jsonb_build_object(k,0); END LOOP;
  FOR r IN SELECT value FROM jsonb_array_elements(x->'rows') LOOP
   PERFORM tl_parity.keys(r,'countryId partnerId direction ts users regAtt regOk dataAtt dataOk voiceAtt voiceOk smsAtt smsOk'); row_time:=tl_parity.ts(r->'ts',true);
   PERFORM tl_parity.require(jsonb_typeof(r->'countryId')='string' AND r->>'countryId' ~ '^[A-Z]{2}$' AND jsonb_typeof(r->'partnerId')='string' AND length(btrim(r->>'partnerId'))>0 AND r->>'direction' IN ('out','in')); PERFORM tl_parity.num(r->'users',true); FOREACH k IN ARRAY names LOOP PERFORM tl_parity.num(r->k,true); END LOOP;
   FOREACH k IN ARRAY ARRAY['reg','data','voice','sms'] LOOP PERFORM tl_parity.require((r->>(k||'Ok'))::numeric<=(r->>(k||'Att'))::numeric); END LOOP;
   scope_key:=jsonb_build_array(r->'countryId',r->'partnerId',r->'direction')::text; row_key:=scope_key||extract(epoch FROM row_time)::text; PERFORM tl_parity.require(NOT row_key=ANY(seen)); seen:=array_append(seen,row_key);
   IF row_time<f OR row_time>=t OR (x->>'country'<>'all' AND x->'country'<>r->'countryId') OR (x->>'direction'<>'both' AND x->'direction'<>r->'direction') THEN CONTINUE; END IF;
   FOREACH k IN ARRAY names LOOP n:=(p->>k)::numeric+(r->>k)::numeric; PERFORM tl_parity.num(to_jsonb(n),true); p:=p||jsonb_build_object(k,n); END LOOP;
   IF NOT latest ? scope_key OR row_time>tl_parity.ts(latest->scope_key->'ts') THEN latest:=latest||jsonb_build_object(scope_key,r); END IF;
   IF snapshot='null'::jsonb OR row_time>tl_parity.ts(snapshot) THEN snapshot:=r->'ts'; END IF;
  END LOOP;
  FOR r IN SELECT value FROM jsonb_each(latest) LOOP row_count:=row_count+1; users:=users+(r->>'users')::numeric; allocated:=allocated+floor((r->>'users')::numeric*scale+.5); stale:=stale OR extract(epoch FROM t-tl_parity.ts(r->'ts'))>(x->>'maxAgeSeconds')::numeric; END LOOP;
  PERFORM tl_parity.num(to_jsonb(users),true); PERFORM tl_parity.num(to_jsonb(allocated),true); p:=p||jsonb_build_object('users',users);
  v:=jsonb_build_object('users',CASE WHEN row_count>0 AND NOT stale THEN allocated END,'regSR',tl_parity.rate((p->>'regOk')::numeric,(p->>'regAtt')::numeric),'dataSR',tl_parity.rate((p->>'dataOk')::numeric,(p->>'dataAtt')::numeric),'voiceSR',tl_parity.rate((p->>'voiceOk')::numeric,(p->>'voiceAtt')::numeric),'smsSR',tl_parity.rate((p->>'smsOk')::numeric,(p->>'smsAtt')::numeric));
  IF NOT stale AND (SELECT count(DISTINCT tl_parity.ts(value->'ts')) FROM jsonb_each(latest))>1 THEN RETURN tl_parity.body(p||jsonb_build_object('users',NULL),v||jsonb_build_object('users',NULL),'PARTIAL','SNAPSHOT_MISALIGNED',0); END IF;
  RETURN tl_parity.body(p,v,CASE WHEN row_count=0 THEN 'MISSING' WHEN stale THEN 'STALE' ELSE 'VALID' END,CASE WHEN row_count=0 THEN 'NO_OBSERVATIONS' WHEN stale THEN 'STALE_SOURCE' END,CASE WHEN row_count=0 OR stale THEN 0 ELSE 1 END,snapshot);
 ELSE RAISE EXCEPTION 'INVALID_INPUT'; END IF;
EXCEPTION WHEN others THEN RETURN '{"error":"INVALID_INPUT"}'::jsonb;
END $$;
