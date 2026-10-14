package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import md.utm.telecom.processing.history.TraceLinkNativeReader.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkRoamingPrimitiveTest extends TraceLinkPrimitiveTestSupport {
    private Window nativeRow(String direction) {
        return reader.read(dataset,new Scope("ROAMING","RO-DEMO-01",direction,"RO"),START,START.plusSeconds(300)).getFirst();
    }
    @Test void partnerCountryDirectionAndProcedureRemainIndependent() throws Exception {
        var out=nativeRow("out"); var in=nativeRow("in");
        for(String metric:TraceLinkDomainPrimitiveBuilder.metrics("ROAMING")) {
            var result=produce(out,metric); var e=result.path("observation");
            assertEquals("RO-DEMO-01",e.path("scopeId").asText()); assertEquals("RO",e.path("countryId").asText());
            assertEquals("out",e.path("direction").asText()); assertEquals("SUPPORTED",result.path("availability").asText());
            assertNotEquals(e.path("eventId"),produce(in,metric).path("observation").path("eventId"));
            assertThrows(IllegalArgumentException.class,()->builder.rollup(List.of(result,produce(in,metric)),START,START.plusSeconds(300)));
            assertEquals(result.toString(),produce(out,metric).toString());
        }
        assertEquals("REGISTRATION",produce(out,"roaming.registration.success").path("observation").path("procedure").asText());
        assertThrows(IllegalArgumentException.class,()->builder.build(dataset,new Scope("ROAMING","RO","out","RO"),START,"roaming.registration.success",out));
        assertThrows(IllegalArgumentException.class,()->builder.build(dataset,new Scope("ROAMING","RO-DEMO-01","out","IT"),START,"roaming.registration.success",out));
        assertThrows(IllegalArgumentException.class,()->builder.build(dataset,in.scope(),START,"roaming.registration.success",out));
    }
    @Test void ratiosUseSumsAndZeroAttemptsRemainNull() throws Exception {
        var first=control(nativeRow("out"),START,Map.of("reg_attempts",1000,"reg_success",990));
        var second=control(nativeRow("out"),START.plusSeconds(300),Map.of("reg_attempts",10,"reg_success",9));
        var a=produce(first,"roaming.registration.success"); var b=produce(second,"roaming.registration.success");
        assertEquals(99,a.path("value").doubleValue()); assertEquals(90,b.path("value").doubleValue());
        var rollup=builder.rollup(List.of(b,a,a),START,START.plusSeconds(600));
        assertEquals(98.91089108910891,rollup.path("value").doubleValue(),1e-12);
        assertEquals(999,rollup.path("primitives").path("numerator").asLong()); assertEquals(1010,rollup.path("primitives").path("denominator").asLong());
        for(String prefix:List.of("reg","data","voice")) {
            String metric=switch(prefix){ case "reg" -> "roaming.registration.success"; case "data" -> "roaming.data.success"; default -> "roaming.voice.success"; };
            var zero=produce(control(nativeRow("out"),START,Map.of(prefix+"_attempts",0,prefix+"_success",0)),metric);
            assertTrue(zero.path("value").isNull()); assertEquals("ZERO_DENOMINATOR",zero.path("nullReason").asText());
            assertThrows(IllegalArgumentException.class,()->produce(control(nativeRow("out"),START,Map.of(prefix+"_attempts",1,prefix+"_success",2)),metric));
        }
    }
    @Test void degradedMissingPartialAndStaleEvidenceAreExplicit() throws Exception {
        var row=nativeRow("out"); var degraded=control(row,START,Map.of("data_attempts",100,"data_success",40));
        assertEquals(40,produce(degraded,"roaming.data.success").path("value").doubleValue());
        var missing=builder.build(degraded.dataset(),row.scope(),START.plusSeconds(300),"roaming.data.success",null);
        var partial=builder.rollup(List.of(produce(degraded,"roaming.data.success"),missing),START,START.plusSeconds(600));
        assertEquals(1,partial.path("presentSlots").asInt()); assertEquals(2,partial.path("expectedSlots").asInt());
        assertEquals(0.5,partial.path("slotCoverage").doubleValue()); assertEquals(1,partial.path("sourceLineage").size());
        assertEquals("INSUFFICIENT_EVIDENCE",partial.path("quality").asText());
        var stale=new Window(row.dataset(),row.scope(),row.start(),row.naturalId(),row.file(),row.rowNumber(),row.rowSha256(),row.raw(),row.payload(),"STALE",null);
        assertThrows(IllegalArgumentException.class,()->produce(stale,"roaming.registration.success"));
        ObjectNode payload=(ObjectNode)row.payload(); payload.remove("reg_attempts");
        var incomplete=new Window(row.dataset(),row.scope(),row.start(),row.naturalId(),row.file(),row.rowNumber(),row.rowSha256(),row.raw(),payload,null,null);
        assertThrows(IllegalArgumentException.class,()->produce(incomplete,"roaming.registration.success"));
        for(String metric:List.of("roaming.activeOutbound","roaming.sms.finalizedSuccess")) assertThrows(IllegalArgumentException.class,()->produce(row,metric));
    }
}
