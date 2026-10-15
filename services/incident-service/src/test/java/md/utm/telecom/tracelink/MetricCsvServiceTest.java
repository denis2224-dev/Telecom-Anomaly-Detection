package md.utm.telecom.tracelink;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.tracelink.export.MetricCsvService;
import md.utm.telecom.tracelink.metrics.MetricSeriesResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class MetricCsvServiceTest {
    @Test
    void exactValuesMissingBucketsAndEscapedProvenanceSurviveExport() throws Exception {
        Instant from = Instant.parse("2026-10-10T09:00:00Z");
        var point = new MetricSeriesResponse.Point(from, from.plusSeconds(900),
                new BigDecimal("98.910891"), BigDecimal.ONE, "VALID",
                new BigDecimal("999"), new BigDecimal("1010"), null, null);
        var missing = new MetricSeriesResponse.Point(from.plusSeconds(900), from.plusSeconds(1800),
                null, BigDecimal.ZERO, "MISSING", null, null, null, null);
        var series = new MetricSeriesResponse("trace-link-md-30d-v1", "volte", "voice.success_rate",
                "CITY", "chi", "15m", from, from.plusSeconds(1800), from, from.plusSeconds(1800),
                false, false, 300, "PERCENT", "RATIO_OF_SUMS", "policy,version", "topology", true,
                null, List.of(point, missing));
        var writer = new StringWriter();
        new MetricCsvService(new ObjectMapper()).write(series, writer);
        String csv = writer.toString();
        assertEquals(3, csv.split("\r\n").length);
        assertTrue(csv.contains("\"98.910891\",\"PERCENT\",\"1\",\"VALID\",\"999\",\"1010\""));
        assertTrue(csv.contains("\"policy,version\""));
        assertTrue(csv.contains("\"" + missing.to() + "\",,\"PERCENT\",\"0\",\"MISSING\""));
        assertTrue(csv.endsWith("\r\n"));
    }
}
