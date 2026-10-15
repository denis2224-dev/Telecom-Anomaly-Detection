package md.utm.telecom.generator.tracelink;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;
import static md.utm.telecom.generator.tracelink.TraceLinkTransportScenarioTest.*;

class TraceLinkReliabilityRegressionTest {
    @TempDir Path output;

    @Test void firstCommandCannotBindAnUnrelatedRunIdentity() throws Exception {
        var engine = new TraceLinkScenarioEngine(new TraceLinkScenarioEngineTest.TestClock(), 8);
        var command = command(Type.NORMAL_CONTROL, Scope.data("chi"));
        assertThrows(IllegalArgumentException.class, () -> engine.start("a".repeat(64), command));
        assertTrue(engine.checkpoint().isEmpty());
    }

    @Test void artifactsRejectTrailingDocumentsAndDuplicateKeysBeforeAnyOutput() throws Exception {
        var window = new TraceLinkFixtureGenerator().generate(command(Type.NORMAL_CONTROL, Scope.data("chi"))).getFirst();
        var valid = window.observations().getFirst();
        for (var payload : List.of(valid + " {}", valid.replaceFirst("\\{", "{\"eventId\":\"hidden-conflict\","))) {
            var malformed = new TraceLinkFixtureGenerator.Window(window.start(), List.of(payload));
            assertThrows(Exception.class, () -> TraceLinkScenarioArtifacts.write(List.of(malformed), output.resolve("invalid.jsonl")));
            assertFalse(Files.exists(output.resolve("invalid.jsonl")));
        }
    }
}
