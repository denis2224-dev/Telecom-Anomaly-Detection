package md.utm.telecom.processing.kpi;

import java.util.*;
import md.utm.telecom.processing.history.TraceLinkNativeReader.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkMobileDataPrimitiveTest extends TraceLinkPrimitiveTestSupport {
    private Window nativeRow() { return reader.read(dataset,new Scope("CITY","chi","",""),START,START.plusSeconds(300)).getFirst(); }
    @Test void referenceCatalogueIncludesHinAndCauWithoutActivatingLegacyCities() throws Exception {
        for(String city:List.of("hin","cau")) {
            var row=reader.read(dataset,new Scope("CITY",city,"",""),START,START.plusSeconds(300)).getFirst();
            assertEquals(city.toUpperCase(java.util.Locale.ROOT),produce(row,"mobileData.activation.success").path("observation").path("contractCityId").asText());
        }
        for(String city:List.of("rib","tir")) assertThrows(IllegalArgumentException.class,()->reader.read(dataset,new Scope("CITY",city,"",""),START,START.plusSeconds(300)));
    }
    @Test void activationAndDnsDegradeIndependentlyWithoutProvenEligibility() throws Exception {
        var row=nativeRow();
        var dnsFailure=control(row,START,Map.of("data_attempts",100,"data_success",100,"dns_attempts",800,"dns_success",80));
        var activationFailure=control(row,START,Map.of("data_attempts",100,"data_success",10,"dns_attempts",800,"dns_success",800));
        assertEquals(100,produce(dnsFailure,"mobileData.activation.success").path("value").doubleValue());
        assertEquals(10,produce(dnsFailure,"mobileData.dns.success").path("value").doubleValue());
        assertEquals(10,produce(activationFailure,"mobileData.activation.success").path("value").doubleValue());
        assertEquals(100,produce(activationFailure,"mobileData.dns.success").path("value").doubleValue());
        var dns=produce(row,"mobileData.dns.success");
        assertEquals("REQUIRES_ADDITIONAL_PRIMITIVES",dns.path("availability").asText()); assertTrue(dns.path("referenceOnly").asBoolean());
        assertEquals("UNPROVEN",dns.path("procedureEligibility").asText());
        var activation=produce(row,"mobileData.activation.success"); assertEquals("APPROXIMATION",activation.path("availability").asText());
        assertEquals("UNPROVEN",activation.path("procedureEligibility").asText());
        assertEquals("LTE",activation.path("observation").path("accessTechnology").asText());
        assertEquals("CHI",activation.path("observation").path("contractCityId").asText());
        assertEquals(activation.toString(),produce(row,"mobileData.activation.success").toString());
        assertNotEquals(dns.path("observation").path("eventId"),activation.path("observation").path("eventId"));
    }
    @Test void zeroAttemptsMissingAndInvalidCountersStayDistinct() throws Exception {
        var row=nativeRow();
        for(String prefix:List.of("data","dns")) {
            String metric=prefix.equals("data")?"mobileData.activation.success":"mobileData.dns.success";
            var zero=produce(control(row,START,Map.of(prefix+"_attempts",0,prefix+"_success",0)),metric);
            assertTrue(zero.path("value").isNull()); assertEquals("ZERO_DENOMINATOR",zero.path("nullReason").asText());
            assertThrows(IllegalArgumentException.class,()->produce(control(row,START,Map.of(prefix+"_attempts",1,prefix+"_success",2)),metric));
            assertThrows(IllegalArgumentException.class,()->produce(control(row,START,Map.of(prefix+"_attempts",-1)),metric));
            var missing=builder.build(dataset,row.scope(),START,metric,null); assertTrue(missing.path("value").isNull());
            assertEquals("NO_SOURCE_SLOT",missing.path("nullReason").asText());
            var measuredZero=produce(control(row,START,Map.of(prefix+"_attempts",10,prefix+"_success",0)),metric);
            assertFalse(measuredZero.path("value").isNull()); assertEquals(0,measuredZero.path("value").asInt());
        }
    }
    @Test void throughputIsModeledSlotMeanAndNotAttemptOrUserWeighted() throws Exception {
        var row=nativeRow();
        var first=control(row,START,Map.of("user_throughput_mbps",10,"data_attempts",1000,"data_success",900));
        var second=control(row,START.plusSeconds(300),Map.of("user_throughput_mbps",100,"data_attempts",1,"data_success",1));
        var a=produce(first,"mobileData.throughput"); var b=produce(second,"mobileData.throughput");
        assertEquals(1,a.path("observation").path("primitives").path("count").asLong());
        assertTrue(a.path("weightingBasis").asText().contains("MODELED_CITY_SLOT"));
        assertEquals("APPROXIMATION",a.path("availability").asText());
        var rollup=builder.rollup(List.of(b,a),START,START.plusSeconds(600)); assertEquals(55,rollup.path("value").doubleValue());
        assertEquals(2,rollup.path("primitives").path("count").asInt());
        assertEquals("APPROXIMATION",rollup.path("availability").asText()); assertEquals(a.path("weightingBasis"),rollup.path("weightingBasis"));
        assertEquals("UNPROVEN",rollup.path("procedureEligibility").asText()); assertEquals(2,rollup.path("sourceLineage").size());
        assertEquals(a.path("observation").path("datasetId"),rollup.path("datasetId")); assertTrue(rollup.path("rollupId").asText().matches("[0-9a-f]{64}"));
        assertThrows(IllegalArgumentException.class,()->produce(control(row,START,Map.of("user_throughput_mbps",-1)),"mobileData.throughput"));
        assertTrue(builder.build(dataset,row.scope(),START,"mobileData.throughput",null).path("value").isNull());
    }
}
