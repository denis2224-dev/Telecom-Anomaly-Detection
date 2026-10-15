package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import md.utm.telecom.processing.history.TraceLinkNativeReader;
import md.utm.telecom.processing.history.TraceLinkNativeReader.*;

/** Explicit local artifact job. No delivery intent, topic, incident or new durable schema. */
public final class TraceLinkDomainProducer {
    private record Meters(Counter produced,Counter duplicates,Counter invalid,Counter sourceRows,Timer duration) {}
    private final TraceLinkNativeReader reader;
    private final TraceLinkDomainPrimitiveBuilder builder;
    private final Map<String,Meters> meters=new HashMap<>();
    public TraceLinkDomainProducer(TraceLinkNativeReader reader,MeterRegistry registry) throws Exception {
        this.reader=reader; builder=new TraceLinkDomainPrimitiveBuilder();
        for(String domain:List.of("transport","roaming","mobile_data")) {
            meters.put(domain,new Meters(counter(registry,domain,"produced"),counter(registry,domain,"duplicate"),counter(registry,domain,"invalid"),
                    Counter.builder("telecom.trace_link.native.rows.read").tag("domain",domain).register(registry),
                    Timer.builder("telecom.trace_link.primitive.duration").tag("domain",domain).register(registry)));
        }
    }
    private static Counter counter(MeterRegistry r,String domain,String outcome) {
        return Counter.builder("telecom.trace_link.producer.observations").description("Local artifact outcomes; produced only after successful new artifact creation")
                .tag("domain",domain).tag("outcome",outcome).register(r);
    }
    public List<ObjectNode> calculate(String datasetId,Scope scope,Instant from,Instant to) throws Exception {
        Meters m=meters.get(domain(scope)); long started=System.nanoTime();
        try {
            Dataset d=reader.dataset(datasetId); var rows=reader.read(d,scope,from,to); m.sourceRows().increment(rows.size());
            var byStart=new HashMap<Instant,Window>();
            for(var w:rows) if(byStart.putIfAbsent(w.start(),w)!=null) throw new IllegalArgumentException("Conflicting native slot");
            var output=new ArrayList<ObjectNode>();
            for(Instant start=from;start.isBefore(to);start=start.plusSeconds(300))
                for(String metric:TraceLinkDomainPrimitiveBuilder.metrics(scope.kind())) output.add(builder.build(d,scope,start,metric,byStart.get(start)));
            return List.copyOf(output);
        } catch(Exception invalid) { m.invalid().increment(); throw invalid; }
        finally { m.duration().record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS); }
    }
    public long write(String datasetId,Scope scope,Instant from,Instant to,Path output) throws Exception {
        var rows=calculate(datasetId,scope,from,to); var m=meters.get(domain(scope));
        StringBuilder content=new StringBuilder(); rows.forEach(r->content.append(r).append('\n'));
        byte[] bytes=content.toString().getBytes(StandardCharsets.UTF_8);
        Path target=output.toAbsolutePath().normalize(); Files.createDirectories(target.getParent());
        Path temporary=Files.createTempFile(target.getParent(),".trace-link-primitives-",".jsonl");
        try {
            Files.write(temporary,bytes);
            // Atomic no-clobber publication: a competing writer cannot replace an existing artifact.
            try { Files.createLink(target,temporary); m.produced().increment(rows.size()); }
            catch(FileAlreadyExistsException replay) {
                if(!Arrays.equals(bytes,Files.readAllBytes(target))) { m.invalid().increment(); throw new IllegalArgumentException("Conflicting existing producer artifact"); }
                m.duplicates().increment(rows.size());
            }
        } finally { Files.deleteIfExists(temporary); }
        return rows.size();
    }
    private static String domain(Scope scope) {
        return switch(scope.kind()) { case "LINK" -> "transport"; case "ROAMING" -> "roaming"; case "CITY" -> "mobile_data"; default -> throw new IllegalArgumentException("Unsupported producer domain"); };
    }
}
