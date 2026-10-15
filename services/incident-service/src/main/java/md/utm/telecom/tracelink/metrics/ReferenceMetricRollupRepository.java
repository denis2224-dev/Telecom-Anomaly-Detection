package md.utm.telecom.tracelink.metrics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static md.utm.telecom.tracelink.metrics.MetricSeriesResponse.*;

/** Whole native slots only; these immutable sources are never detector truth. */
@Repository
@Transactional(readOnly = true)
public class ReferenceMetricRollupRepository {
    private static final MathContext MATH = MathContext.DECIMAL128;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ReferenceMetricRollupRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public boolean imported(String dataset) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app.trace_reference_import WHERE dataset_id=?)",Boolean.class,dataset));
    }
    public List<Point> series(TraceQuery q, MetricDefinitionRegistry.Definition definition) {
        String kind = q.domain().equals("transport") ? "LINK" : q.domain().equals("roaming") ? "ROAMING" : "CITY";
        boolean snapshot=definition.aggregation().startsWith("SNAPSHOT_");
        List<Source> sources = jdbc.query("""
                SELECT w.window_start,w.source_payload::text,w.source_file,w.source_row_number,w.source_row_sha256,
                       d.manifest_sha256,d.generator_version,d.seed,c.catalogue_version
                FROM app.trace_reference_window w JOIN app.trace_dataset d USING(dataset_id)
                JOIN app.trace_catalogue c USING(dataset_id) JOIN app.trace_reference_import i USING(dataset_id)
                WHERE w.dataset_id=? AND w.source_kind=? AND w.scope_id=?
                  AND c.valid_from<=? AND (c.valid_to IS NULL OR c.valid_to>=?)
                  AND (w.window_start>=? OR (? AND w.window_start=(SELECT max(prior.window_start)
                    FROM app.trace_reference_window prior WHERE prior.dataset_id=w.dataset_id AND prior.source_kind=w.source_kind
                    AND prior.scope_id=w.scope_id AND prior.window_start<?)))
                  AND w.window_start<? AND (? OR w.window_start+INTERVAL '5 minutes'<=?)
                ORDER BY w.window_start LIMIT 8643
                """, (r,n)->new Source(r.getTimestamp(1).toInstant(),json.readTree(r.getString(2)),r.getString(3),r.getLong(4),
                        r.getString(5).trim(),r.getString(6).trim(),r.getString(7),r.getLong(8),r.getString(9)),
                q.datasetId(),kind,q.scopeId(),Timestamp.from(q.from()),Timestamp.from(q.to()),
                Timestamp.from(q.from()),snapshot,Timestamp.from(q.from()),Timestamp.from(q.to()),snapshot,Timestamp.from(q.to()));
        List<Point> result = new ArrayList<>(); int cursor=0;
        for (Instant start=q.effectiveFrom();start.isBefore(q.effectiveTo());start=start.plusSeconds(q.bucketSeconds())) {
            Instant end=start.plusSeconds(q.bucketSeconds()); var bucket=new ArrayList<Source>();
            while(cursor<sources.size() && sources.get(cursor).start().isBefore(end)) {var source=sources.get(cursor++);if(!source.start().isBefore(q.from())&&!source.start().plusSeconds(300).isAfter(q.to()))bucket.add(source);}
            Source opening=null,closing=null;
            if(snapshot)for(var source:sources){if(source.start().isBefore(start))opening=source;if(source.start().isBefore(end))closing=source;else break;}
            Point calculated=rollup(definition,start,end,bucket,opening,closing);
            var members=new ArrayList<>(bucket);if(opening!=null&&!members.contains(opening))members.add(opening);if(closing!=null&&!members.contains(closing))members.add(closing);
            var provenance=members.stream().map(s->new Provenance(identity(q.datasetId()+"|"+q.domain()+"|"+q.metric()+"|"+q.scopeId()+"|"+s.start()+"|"+definition.policyVersion()),
                    identity(q.datasetId()+"|trace-link-reference-v1|"+kind+"|"+q.scopeId()+"|"+s.start()+"|300|trace-link-reference-read-v1"),s.row(),s.sha(),s.file(),s.manifest(),s.generator(),s.seed(),
                    s.catalogue(),"trace-link-reference-read-v1",null,null,calculated.quality(),calculated.qualityReason(),"trace-link-reference-v1")).toList();
            result.add(new Point(start,end,calculated.value(),calculated.coverage(),calculated.quality(),calculated.numerator(),
                    calculated.denominator(),calculated.sumValue(),calculated.sampleCount(),calculated.nullReason(),calculated.qualityReason(),
                    calculated.expectedSlots(),calculated.receivedSlots(),null,null,provenance.stream().map(Provenance::projectionId).toList(),provenance,
                    calculated.snapshotAt(),calculated.sourceAgeSeconds(),calculated.histogramCounts(),calculated.histogramEdges(),calculated.states()));
        }
        return List.copyOf(result);
    }
    record Source(Instant start,JsonNode payload,String file,long row,String sha,String manifest,String generator,long seed,String catalogue) {}

    static Point rollup(MetricDefinitionRegistry.Definition d,Instant from,Instant to,List<Source> rows) {return rollup(d,from,to,rows,null,null);}
    static Point rollup(MetricDefinitionRegistry.Definition d,Instant from,Instant to,List<Source> rows,Source opening,Source closing) {
        int expected=Math.toIntExact(Duration.between(from,to).getSeconds()/300),received=rows.size();
        BigDecimal coverage=BigDecimal.valueOf(received).divide(BigDecimal.valueOf(expected),MATH);
        String reason=received==0 && closing==null?"NO_SOURCE_SLOTS":d.referenceAvailability().equals("REQUIRES_ADDITIONAL_PRIMITIVES")?"ADDITIONAL_PRIMITIVES_REQUIRED":null;
        BigDecimal value=null,numerator=null,denominator=null,sum=null;Long count=null;Instant snapshot=null;
        var histogram=new ArrayList<BigDecimal>(); var edges=d.histogramEdges();
        if(reason==null) {
            var fields=d.referenceFields();
            switch(d.aggregation()) {
                case "COUNT_SUM" -> {sum=sum(rows,fields.getFirst());if(d.metric().equals("transport.volume"))sum=sum.multiply(new BigDecimal("37500000000"));value=sum;}
                case "RATIO_OF_SUMS" -> {
                    numerator=sum(rows,fields.getFirst());denominator=sum(rows,fields.get(1));
                    if(d.metric().equals("sms.finalizedSuccess"))denominator=denominator.add(numerator);
                    value=divide(numerator.multiply(BigDecimal.valueOf(100)),denominator);
                    if(value==null)reason="ZERO_DENOMINATOR";
                }
                case "WEIGHTED_MEAN" -> {
                    sum=sum(rows,fields.getFirst());
                    if(d.metric().equals("volte.traffic.erlang"))count=300L*received;
                    else if(fields.size()>1)count=sum(rows,fields.get(1)).longValueExact();
                    else count=(long)received;
                    value=divide(sum,BigDecimal.valueOf(count));if(value==null)reason="ZERO_DENOMINATOR";
                }
                case "TIME_WEIGHTED_MEAN","MAXIMUM" -> {
                    sum=BigDecimal.ZERO;BigDecimal peak=null;
                    for(var row:rows) {
                        BigDecimal sample=field(row,fields.getFirst());
                        if(d.metric().startsWith("transport.link.utilization"))sample=divide(sample.multiply(BigDecimal.valueOf(100)),field(row,fields.get(1)));
                        if(sample==null){reason="MISSING_EFFECTIVE_CAPACITY";break;}
                        sum=sum.add(sample.multiply(BigDecimal.valueOf(300)));peak=peak==null?sample:peak.max(sample);
                    }
                    count=300L*received;value=reason!=null?null:d.aggregation().equals("MAXIMUM")?peak:divide(sum,BigDecimal.valueOf(count));
                }
                case "SNAPSHOT_LAST" -> {var last=closing!=null?closing:rows.getLast();value=field(last,fields.getFirst());snapshot=last.start();}
                case "SNAPSHOT_DIFFERENCE" -> {
                    if(opening==null||closing==null||Duration.between(opening.start(),from).getSeconds()>300)reason="OPENING_SNAPSHOT_UNAVAILABLE";
                    else {value=field(closing,fields.getFirst()).subtract(field(opening,fields.getFirst()));snapshot=closing.start();}
                }
                case "HISTOGRAM_P95" -> {
                    for(int i=0;i<edges.size();i++)histogram.add(BigDecimal.ZERO);
                    for(var row:rows){var bins=row.payload().required(fields.getFirst());
                        if(!bins.isArray()||bins.size()!=edges.size())throw new IllegalArgumentException("Incompatible histogram bins");
                        for(int i=0;i<edges.size();i++)histogram.set(i,histogram.get(i).add(new BigDecimal(bins.get(i).asText())));
                    }
                    count=histogram.stream().reduce(BigDecimal.ZERO,BigDecimal::add).longValueExact();
                    value=quantile(histogram,edges);if(value==null)reason="EMPTY_HISTOGRAM";
                }
                case "STATE_TIMELINE" -> reason="STATE_EVENTS_UNAVAILABLE";
                default -> throw new IllegalArgumentException("Unsupported aggregation");
            }
        }
        // Reference CSV files provide no measured source quality/coverage. Arithmetic is qualified, never healthy evidence.
        String quality=received==0 && snapshot==null?"MISSING":snapshot!=null&&Duration.between(snapshot,to).getSeconds()>600?"STALE":reason!=null?"INSUFFICIENT_EVIDENCE":received<expected?"PARTIAL":"INSUFFICIENT_EVIDENCE";
        return new Point(from,to,value,coverage,quality,numerator,denominator,sum,count,reason,
                reason!=null?reason:quality.equals("STALE")?"RETAINED_SNAPSHOT_STALE":received<expected?"MISSING_SOURCE_SLOTS":"SOURCE_QUALITY_UNAVAILABLE",expected,received,null,null,
                List.of(),List.of(),snapshot,snapshot==null?null:Duration.between(snapshot,to).getSeconds(),histogram,edges,List.of());
    }
    private static String identity(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}}
    private static BigDecimal field(Source row,String key){var node=row.payload().required(key);BigDecimal value=new BigDecimal(node.asText());
        if(value.signum()<0)throw new IllegalArgumentException("Negative primitive");return value;}
    private static BigDecimal sum(List<Source> rows,String key){return rows.stream().map(row->field(row,key)).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static BigDecimal divide(BigDecimal n,BigDecimal d){return d.signum()==0?null:n.divide(d,MATH);}
    static BigDecimal quantile(List<BigDecimal> counts,List<BigDecimal> edges){
        if(counts.isEmpty()||counts.size()!=edges.size())throw new IllegalArgumentException("Incompatible histogram edges");
        BigDecimal total=BigDecimal.ZERO,previous=BigDecimal.ZERO;
        for(int i=0;i<counts.size();i++){if(counts.get(i).signum()<0||edges.get(i).compareTo(previous)<=0)throw new IllegalArgumentException("Invalid histogram");total=total.add(counts.get(i));previous=edges.get(i);}
        if(total.signum()==0)return null;
        BigDecimal rank=total.multiply(new BigDecimal("0.95")),cumulative=BigDecimal.ZERO;previous=BigDecimal.ZERO;
        for(int i=0;i<counts.size();i++){BigDecimal next=cumulative.add(counts.get(i));if(counts.get(i).signum()>0&&next.compareTo(rank)>=0)
            return previous.add(rank.subtract(cumulative).divide(counts.get(i),MATH).multiply(edges.get(i).subtract(previous)));
            cumulative=next;previous=edges.get(i);}
        return edges.getLast();
    }
}
