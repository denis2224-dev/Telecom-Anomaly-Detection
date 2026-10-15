package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.history.*;
import md.utm.telecom.processing.history.TraceLinkNativeReader.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkTransportPrimitiveTest extends TraceLinkPrimitiveTestSupport {
    @Test void persistedSourceMapsUnitsPrimitivesAndNullableQuality() throws Exception {
        var result=produce(normal,"transport.traffic"); var e=result.path("observation");
        assertEquals(11.680978,result.path("value").doubleValue(),1e-12);
        assertEquals(3504.2934,e.path("primitives").path("weightedSum").doubleValue(),1e-9);
        assertEquals(300,e.path("primitives").path("validSeconds").asInt()); assertEquals("GBPS",e.path("unit").asText());
        assertEquals("APPROXIMATION",result.path("availability").asText()); assertTrue(result.path("sourceCoverage").isNull()); assertTrue(result.path("sourceQuality").isNull());
        assertEquals(normal.rowSha256(),result.path("provenance").path("sourceRowSha256").asText());
        assertEquals("INSUFFICIENT_EVIDENCE",e.path("quality").asText());
        assertEquals(result.toString(),produce(normal,"transport.traffic").toString());
        for(String metric:TraceLinkDomainPrimitiveBuilder.metrics("LINK")) assertNotNull(produce(normal,metric).get("value"));
    }
    @Test void capacityChangesUseSlotValidTimePrimitivesAndSnapshotLast() throws Exception {
        var a=control(normal,START,Map.of("traffic_gbps",10,"effective_capacity_gbps",20));
        var b=control(normal,START.plusSeconds(300),Map.of("traffic_gbps",10,"effective_capacity_gbps",10));
        assertEquals(50,produce(a,"transport.link.utilization").path("value").doubleValue());
        assertEquals(100,produce(b,"transport.link.utilization").path("value").doubleValue());
        var rollup=builder.rollup(List.of(produce(a,"transport.link.utilization"),produce(b,"transport.link.utilization")),START,START.plusSeconds(600));
        assertEquals(45000,rollup.path("primitives").path("weightedSum").doubleValue()); assertEquals(600,rollup.path("primitives").path("validSeconds").asInt());
        assertEquals(75,rollup.path("value").doubleValue());
        var capacity=builder.rollup(List.of(produce(a,"transport.effectiveCapacity"),produce(b,"transport.effectiveCapacity")),START,START.plusSeconds(600));
        assertEquals(10,capacity.path("value").doubleValue()); assertEquals(START.plusSeconds(300).toString(),capacity.path("snapshotAt").asText());
        assertFalse(rollup.toString().contains("LAG"));
    }
    @Test void invalidMissingAndMeasuredZeroAreDistinct() throws Exception {
        for(var change:List.of(Map.<String,Object>of("effective_capacity_gbps",0),Map.<String,Object>of("traffic_gbps",-1),Map.<String,Object>of("effective_capacity_gbps","")))
            assertThrows(IllegalArgumentException.class,()->produce(control(normal,START,change),"transport.traffic"));
        var zero=produce(control(normal,START,Map.of("traffic_gbps",0)),"transport.traffic"); assertEquals(0,zero.path("value").asInt()); assertFalse(zero.path("value").isNull());
        var missing=builder.build(dataset,normal.scope(),START,"transport.traffic",null);
        assertTrue(missing.path("value").isNull()); assertEquals("MISSING",missing.path("observation").path("quality").asText());
        assertEquals("NO_SOURCE_SLOT",missing.path("nullReason").asText());
        var empty=builder.rollup(List.of(missing),START,START.plusSeconds(300)); assertEquals(0,empty.path("presentSlots").asInt()); assertTrue(empty.path("value").isNull());
    }
    @Test void reverseInventoryDoesNotInventReverseSourceOrPacketCounters() throws Exception {
        var reverse=new Scope("LINK","chi-orh","b-to-a",""); assertTrue(reader.read(dataset,reverse,START,START.plusSeconds(300)).isEmpty());
        assertEquals("MISSING",builder.build(dataset,reverse,START,"transport.traffic",null).path("observation").path("quality").asText());
        assertThrows(IllegalArgumentException.class,()->builder.build(dataset,reverse,START,"transport.traffic",normal));
        for(String metric:List.of("transport.packetLoss","transport.rtt.mean","transport.link.state"))
            assertThrows(IllegalArgumentException.class,()->produce(normal,metric));
        assertFalse(produce(normal,"transport.rtt.snapshot").toString().contains("lostPackets"));
    }
    @Test void replayConflictChecksumAndHalfOpenWindowsFailClosed() throws Exception {
        var a=produce(normal,"transport.traffic"); var duplicate=builder.rollup(List.of(a,a),START,START.plusSeconds(300));
        assertEquals(1,duplicate.path("presentSlots").asInt()); assertEquals(1,duplicate.path("duplicateInputs").asInt());
        var conflicting=a.deepCopy(); conflicting.put("value",999); assertThrows(IllegalArgumentException.class,()->builder.rollup(List.of(a,conflicting),START,START.plusSeconds(300)));
        var corrupt=new Window(dataset,normal.scope(),START,normal.naturalId(),normal.file(),1,normal.rowSha256(),normal.raw()+" ",normal.payload(),null,null);
        assertThrows(IllegalArgumentException.class,()->produce(corrupt,"transport.traffic"));
        var badPayload=(ObjectNode)normal.payload(); badPayload.put("traffic_gbps",0);
        var mismatch=new Window(dataset,normal.scope(),START,normal.naturalId(),normal.file(),1,normal.rowSha256(),normal.raw(),badPayload,null,null);
        assertThrows(IllegalArgumentException.class,()->produce(mismatch,"transport.traffic"));
        assertThrows(IllegalArgumentException.class,()->builder.build(dataset,normal.scope(),START.plusSeconds(1),"transport.traffic",null));
        assertThrows(IllegalArgumentException.class,()->builder.rollup(List.of(a),START.minusSeconds(300),START));
        assertThrows(IllegalArgumentException.class,()->reader.dataset("unfinished-or-unknown"));
    }
}
