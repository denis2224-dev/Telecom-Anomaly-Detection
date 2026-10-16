package md.utm.telecom.tracelink.metrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceMetricRollupTest {
    private final ObjectMapper json=new ObjectMapper();
    private final MetricDefinitionRegistry definitions=new MetricDefinitionRegistry(json);
    private static final Instant FROM=Instant.parse("2026-09-10T09:00:00Z");
    ReferenceMetricRollupTest() throws Exception {}
    private ReferenceMetricRollupRepository.Source row(int slot,String data) {
        return new ReferenceMetricRollupRepository.Source(FROM.plusSeconds(slot*300L),json.readTree(data),"city_kpi_5m.csv",slot+1,"a".repeat(64),"b".repeat(64),"reference-1.0",42,"trace-link-geography-v2");
    }
    @Test void poolsAttemptsAndPreservesMissingZeroAndQualification() {
        var d=definitions.require("volte","volte.setup.success");
        var result=ReferenceMetricRollupRepository.rollup(d,FROM,FROM.plusSeconds(900),List.of(
                row(0,"{\"voice_success\":99,\"voice_attempts\":100}"),row(1,"{\"voice_success\":1,\"voice_attempts\":2}")));
        assertEquals(0,result.value().compareTo(new BigDecimal("10000").divide(new BigDecimal("102"),java.math.MathContext.DECIMAL128)));
        assertEquals("PARTIAL",result.quality());assertEquals(3,result.expectedSlots());assertEquals(2,result.receivedSlots());assertNull(result.sourceCoverage());
        var zero=ReferenceMetricRollupRepository.rollup(d,FROM,FROM.plusSeconds(300),List.of(row(0,"{\"voice_success\":0,\"voice_attempts\":0}")));
        assertNull(zero.value());assertEquals("ZERO_DENOMINATOR",zero.nullReason());
        var missing=ReferenceMetricRollupRepository.rollup(d,FROM,FROM.plusSeconds(300),List.of());
        assertEquals("MISSING",missing.quality());assertNull(missing.numerator());
    }
    @Test void weightsUtilizationByTimeWithContemporaneousCapacity() {
        var result=ReferenceMetricRollupRepository.rollup(definitions.require("transport","transport.link.utilization"),FROM,FROM.plusSeconds(600),List.of(
                row(0,"{\"traffic_gbps\":5,\"effective_capacity_gbps\":10}"),row(1,"{\"traffic_gbps\":9,\"effective_capacity_gbps\":30}")));
        assertEquals(0,result.value().compareTo(new BigDecimal("40")));
        assertEquals("INSUFFICIENT_EVIDENCE",result.quality());assertEquals("SOURCE_QUALITY_UNAVAILABLE",result.qualityReason());
    }
    @Test void poolsHistogramsAndConvertsSmsSecondsToMilliseconds() {
        var result=ReferenceMetricRollupRepository.rollup(definitions.require("sms","sms.delay.p95"),FROM,FROM.plusSeconds(600),List.of(
                row(0,"{\"sms_delay_hist\":[100,0,0,0,0,0,0,0,0]}"),row(1,"{\"sms_delay_hist\":[0,0,0,0,0,0,0,0,1]}")));
        assertEquals(0,result.value().compareTo(new BigDecimal("959.5")));
        assertEquals(101,result.sampleCount());assertEquals(new BigDecimal("100"),result.histogramCounts().getFirst());
        assertThrows(IllegalArgumentException.class,()->ReferenceMetricRollupRepository.quantile(List.of(BigDecimal.ONE),List.of(BigDecimal.ONE,BigDecimal.TEN)));
    }
    @Test void snapshotsRetainAgeAndMissingProcedurePrimitivesStayUnavailable() {
        var result=ReferenceMetricRollupRepository.rollup(definitions.require("sms","sms.queue"),FROM,FROM.plusSeconds(600),List.of(
                row(0,"{\"sms_queue\":100}"),row(1,"{\"sms_queue\":3}")));
        assertEquals(0,result.value().compareTo(new BigDecimal("3")));assertEquals(FROM.plusSeconds(300),result.snapshotAt());assertEquals(300,result.sourceAgeSeconds());
        var loss=ReferenceMetricRollupRepository.rollup(definitions.require("transport","transport.packetLoss"),FROM,FROM.plusSeconds(300),List.of(row(0,"{\"loss_pct\":99}")));
        assertNull(loss.value());assertEquals("ADDITIONAL_PRIMITIVES_REQUIRED",loss.nullReason());
        var dns=ReferenceMetricRollupRepository.rollup(definitions.require("mobile-data","mobileData.dns.success"),FROM,FROM.plusSeconds(300),List.of(row(0,"{\"dns_success\":1,\"dns_attempts\":2}")));
        assertNull(dns.value());assertEquals("INSUFFICIENT_EVIDENCE",dns.quality());
    }
}
