package md.utm.telecom.tracelink;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.tracelink.metrics.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled bounded rows in an isolated transaction; this is not full-archive acceptance. */
class ReferenceSqlParityIT extends IncidentServiceIntegrationTestSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired ObjectMapper json;
 @Autowired MetricDefinitionRegistry definitions;
 @Autowired ReferenceMetricRollupRepository backend;
 private static final Instant FROM=Instant.parse("2026-09-10T09:00:00Z");
 @Test void percentileRejectsInvalidLateBinsBeforeReturningAnEarlyQuantile() {
  assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.queryForObject(
   "SELECT app.trace_reference_p95('[100,0,-1]'::jsonb,'[1,2,3]'::jsonb)",BigDecimal.class));
 }
 @Test void allDictionaryOperatorsMatchReadOnlySqlAtEveryResolution() throws Exception {
  jdbc.update("INSERT INTO app.trace_dataset VALUES ('parity-fixture','fixture',42,true,?, ?,300,'UTC',?)",Timestamp.from(FROM),Timestamp.from(FROM.plusSeconds(86400)),"a".repeat(64));
  jdbc.update("INSERT INTO app.trace_catalogue(catalogue_version,dataset_id,topology_version,active,valid_from) VALUES ('parity-fixture','parity-fixture','fixture',false,?)",Timestamp.from(FROM));
  for(String city:List.of("chi","hin"))jdbc.update("INSERT INTO app.trace_inventory_city(catalogue_version,city_id,display_name,latitude,longitude,demand_share,active,valid_from,synthetic) VALUES ('parity-fixture',?,?,47,28,0.1,true,?,true)",city,city,Timestamp.from(FROM));
  jdbc.update("INSERT INTO app.trace_inventory_link VALUES ('parity-fixture','chi-hin','chi','hin','FIBER',10,true,?,NULL,true)",Timestamp.from(FROM));
  jdbc.update("INSERT INTO app.trace_link_direction VALUES ('parity-fixture','chi-hin','a-to-b',NULL,NULL,10,?,NULL,'controlled fixture')",Timestamp.from(FROM));
  for(String dir:List.of("in","out"))jdbc.update("INSERT INTO app.trace_partner VALUES ('parity-fixture','RO-REF','RO',?,true,?,NULL,true)",dir,Timestamp.from(FROM));
  // Only this isolated test installs a completion marker to exercise the protected read path.
  jdbc.update("INSERT INTO app.trace_reference_import(dataset_id,manifest_sha256,native_rows) VALUES ('parity-fixture',?,362880)","a".repeat(64));
  var payload=json.createObjectNode();
  for(var d:definitions.list(null))for(String field:d.referenceFields()){
   if(field.endsWith("_hist")){var bins=payload.putArray(field);for(int i=0;i<9;i++)bins.add(i+1);}
   else payload.put(field,10);
  }
  payload.put("effective_capacity_gbps",20);payload.put("voice_attempts",20);payload.put("voice_success",10);
  long row=0;
  for(String scope:List.of("chi","chi-hin:a-to-b","RO-REF:in","RO-REF:out"))for(int slot:List.of(0,1,2,4)){
   String kind=scope.equals("chi")?"CITY":scope.startsWith("chi-")?"LINK":"ROAMING";
   jdbc.update("INSERT INTO app.trace_reference_window VALUES ('parity-fixture',?,?,?,'controlled-fixture.csv',?,?,?::jsonb)",kind,scope,Timestamp.from(FROM.plusSeconds(300L*slot)),++row,"b".repeat(64),payload.toString());
  }
  for(var d:definitions.list(null)){
   if(d.metric().equals("voice.success_rate"))continue;
   String scope=d.domain().equals("transport")?"chi-hin:a-to-b":d.domain().equals("roaming")?"RO-REF:"+(d.metric().equals("roaming.activeInbound")?"in":"out"):"chi";
   for(String resolution:List.of("5m","15m","1h","6h")){
    var query=new TraceQuery("parity-fixture",d.domain(),d.metric(),d.domain().equals("transport")?"LINK_DIRECTION":d.domain().equals("roaming")?"PARTNER_DIRECTION":"CITY",scope,FROM,FROM.plusSeconds(7200),resolution);
    var expected=backend.series(query,d);
    if(d.metric().equals("sms.queueGrowth")){assertNull(expected.getFirst().value());assertEquals("OPENING_SNAPSHOT_UNAVAILABLE",expected.getFirst().nullReason());}
    var actual=jdbc.queryForList("SELECT * FROM app.trace_reference_series(?,?,?,?,?,?)","parity-fixture",d.metric(),scope,Timestamp.from(query.from()),Timestamp.from(query.to()),resolution);
    assertEquals(expected.size(),actual.size(),d.metric()+" "+resolution);
    for(int i=0;i<expected.size();i++){
     var point=expected.get(i);var sql=actual.get(i);String label=d.metric()+" "+resolution+" bucket "+i;
     if(point.value()==null)assertNull(sql.get("value"),label);else assertTrue(point.value().subtract((BigDecimal)sql.get("value")).abs().compareTo(new BigDecimal("0.000001"))<=0,label);
     assertEquals(point.quality(),sql.get("quality"),label);
     var edges=json.readTree(sql.get("histogram_edges").toString());assertEquals(point.histogramEdges().size(),edges.size(),label);
     for(int edge=0;edge<edges.size();edge++)assertEquals(0,point.histogramEdges().get(edge).compareTo(edges.get(edge).decimalValue()),label);
     assertTrue(point.coverage().subtract((BigDecimal)sql.get("coverage")).abs().compareTo(new BigDecimal("0.000001"))<=0,label);
    }
   }
  }
 }
}
