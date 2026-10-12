package md.utm.telecom.generator.tracelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import md.utm.telecom.observation.TraceLinkSyntheticSource;

/** Allowlisted detector-visible JSONL only. Commands, checkpoints and evaluator truth have no export path here. */
public final class TraceLinkScenarioArtifacts {
    private TraceLinkScenarioArtifacts() {}
    public static byte[] bytes(List<TraceLinkFixtureGenerator.Window> windows) throws IOException {
        if (windows.size() > 1024) throw new IllegalArgumentException("Artifact window bound exceeded");
        var json = new ObjectMapper(); var content = new StringBuilder(); var ids = new HashSet<String>();
        for (var w : windows) {
            if (w.observations().size() > 3) throw new IllegalArgumentException("Artifact observation bound exceeded");
            var source = new TraceLinkSyntheticSource(Clock.fixed(w.start().plusSeconds(60), ZoneOffset.UTC));
            for (var payload : w.observations()) {
                var e = json.readTree(payload); source.resolve(e);
                if (!Instant.parse(e.path("windowStart").asText()).equals(w.start()) || !ids.add(e.path("eventId").asText()))
                    throw new IllegalArgumentException("Artifact window/identity conflict");
                content.append(e).append('\n');
            }
        }
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }
    /** Atomic no-clobber publication on hard-link capable filesystems. Exact replay leaves the target untouched. */
    public static boolean write(List<TraceLinkFixtureGenerator.Window> windows, Path output) throws IOException {
        byte[] bytes = bytes(windows); var target = output.toAbsolutePath().normalize(); Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), ".trace-link-minute-", ".jsonl");
        try {
            Files.write(temporary, bytes);
            try { Files.createLink(target, temporary); return true; }
            catch (FileAlreadyExistsException replay) {
                if (Files.size(target) != bytes.length || !Arrays.equals(bytes, Files.readAllBytes(target)))
                    throw new IllegalArgumentException("Conflicting existing scenario artifact");
                return false;
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
