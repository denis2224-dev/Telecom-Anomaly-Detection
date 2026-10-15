package md.utm.telecom.processing.kpi;

import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;
import md.utm.telecom.processing.history.*;
import md.utm.telecom.processing.history.TraceLinkNativeReader.Scope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkDomainArchiveTest extends TraceLinkPrimitiveTestSupport {
    @Test
    @EnabledIfSystemProperty(named="trace.link.archive",matches=".+")
    void suppliedFullArchiveProducesIndependentSqlParityWithoutDeliveryWrites() throws Exception {
        var bootstrap=new TraceLinkDatasetBootstrap(jdbc,new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())),1000);
        var imported=bootstrap.execute(Path.of(System.getProperty("trace.link.archive")));
        assertEquals(362880,imported.nativeRows()); assertEquals(imported,bootstrap.execute(Path.of(System.getProperty("trace.link.archive"))));
        var registry=new SimpleMeterRegistry(); var evidence=JSON.createObjectNode();
        evidence.put("datasetId",imported.datasetId()); evidence.put("manifestSha256",imported.manifestSha256()); evidence.put("nativeRows",imported.nativeRows());
        var cases=evidence.putArray("parity");
        try {
            var producer=new TraceLinkDomainProducer(reader,registry);
            for(var scope:List.of(normal.scope(),new Scope("ROAMING","RO-DEMO-01","out","RO"),new Scope("ROAMING","RO-DEMO-01","in","RO"),new Scope("CITY","chi","",""))) {
                var all=producer.calculate(imported.datasetId(),scope,START,START.plusSeconds(3600));
                for(String metric:TraceLinkDomainPrimitiveBuilder.metrics(scope.kind())) {
                    var rows=all.stream().filter(r->r.path("observation").path("metricId").asText().equals(metric)).toList();
                    var result=builder.rollup(rows,START,START.plusSeconds(3600)); assertEquals(12,result.path("presentSlots").asInt());
                    String expression=switch(metric) {
                        case "transport.traffic" -> "sum((payload->>'traffic_gbps')::numeric*300)/sum(300)";
                        case "transport.link.utilization" -> "sum(100*(payload->>'traffic_gbps')::numeric/(payload->>'effective_capacity_gbps')::numeric*300)/sum(300)";
                        case "transport.effectiveCapacity" -> "(array_agg((payload->>'effective_capacity_gbps')::numeric ORDER BY window_start DESC))[1]";
                        case "transport.rtt.snapshot" -> "(array_agg((payload->>'rtt_ms')::numeric ORDER BY window_start DESC))[1]";
                        case "transport.jitter" -> "(array_agg((payload->>'jitter_ms')::numeric ORDER BY window_start DESC))[1]";
                        case "mobileData.throughput" -> "sum((payload->>'user_throughput_mbps')::numeric)/count(*)";
                        default -> {
                            String prefix=metric.contains("registration")?"reg":metric.contains("voice")?"voice":metric.contains("dns")?"dns":"data";
                            yield "100*sum((payload->>'"+prefix+"_success')::numeric)/nullif(sum((payload->>'"+prefix+"_attempts')::numeric),0)";
                        }
                    };
                    Double expected=jdbc.queryForObject("SELECT "+expression+" FROM app.trace_source_native_window WHERE dataset_id=? AND source_kind=? AND scope_id=? AND direction=? AND window_start>=? AND window_start<?",Double.class,
                            imported.datasetId(),scope.kind(),scope.id(),scope.direction(),Timestamp.from(START),Timestamp.from(START.plusSeconds(3600)));
                    assertNotNull(expected); assertEquals(expected,result.path("value").doubleValue(),1e-9);
                    cases.addObject().put("metric",metric).put("scope",scope.id()).put("direction",scope.direction()).put("nativeSlots",12)
                            .put("java",result.path("value").doubleValue()).put("postgresql",expected);
                }
            }
        } finally { registry.close(); }
        Path output=Path.of("target/trace-link-domain-archive-parity.json"); Files.createDirectories(output.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),evidence);
    }
}
