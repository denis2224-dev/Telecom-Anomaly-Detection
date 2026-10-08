package md.utm.telecom.geography;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;

/** Explicitly labelled controlled API/DB evidence, without sessions, headers or credentials. */
final class GeographicEvidenceArtifacts {
    static void write(String name, JsonNode evidence) throws IOException {
        Path directory = Path.of("target", "geographic-investigation");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name + ".json"), evidence.toPrettyString() + "\n");
    }
}
