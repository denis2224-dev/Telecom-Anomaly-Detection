package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkNativeGeneratorTest {
    @Test void generatedCurrentSourcesValidateAcrossEveryDomainAndStep() throws Exception {
        var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var start=Instant.ofEpochSecond(now.getEpochSecond()/300*300-300);
        try(var in=getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-persisted-windows.json")) {
            var templates=new ObjectMapper().readTree(in);var generator=new TraceLinkRealtimeHarness();var adapter=new TraceLinkWindowAdapter();
            for(var template:templates.path("windows")) {
                var input=generator.generate(template.path("input"),"native-generator-proof",start,now,
                        "native-generator:"+template.path("domain").asText()+":"+template.path("step").asInt());
                var context=adapter.context(input);assertEquals("native-generator-proof",context.path("datasetId").asText());
                assertFalse(adapter.evaluate(context).isEmpty());
                for(var evidence:context.path("evidence")) {
                    assertEquals(now.toString(),evidence.path("freshness").path("observedAt").asText());
                    assertEquals("NATIVE_OBSERVATION",evidence.path("provenance").path("origin").asText());
                    assertTrue(evidence.path("provenance").path("sourceChecksum").asText().matches("[a-f0-9]{64}"));
                }
            }
        }
    }
}
