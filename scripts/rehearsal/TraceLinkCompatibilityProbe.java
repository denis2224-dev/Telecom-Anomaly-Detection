import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import md.utm.telecom.processing.detection.TraceLinkInputs;

/** Negative interface probe against unchanged main classes, never a payload adapter. */
public final class TraceLinkCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var raw = json.readTree(Files.readAllLines(Path.of(args[0])).getFirst());
        var output = json.createObjectNode().put("purpose", "Offline interface rejection; no detector-visible adaptation")
                .put("mainCommit", "bccfd01d20373d71cd6b51f7c102ebde45c884ff");
        try {
            TraceLinkInputs.validateEvidence(raw);
            throw new IllegalStateException("Unexpected acceptance of producer wire shape");
        } catch (IllegalArgumentException expected) {
            output.put("unmodifiedProducerEvidence", "REJECTED").put("wireRejection", expected.getMessage());
        }
        JsonNode fixtures = json.readTree(Path.of(args[2]).toFile());
        ObjectNode context = null;
        for (var item : fixtures.path("cases")) if (item.path("id").asText().equals("mobile-dns-only"))
            context = item.path("context").deepCopy();
        if (context == null) throw new IllegalStateException("Missing existing offline fixture");
        TraceLinkInputs.validateContext(context);
        var window = context.withObject("window");
        window.put("to", Instant.parse(window.path("from").asText()).plusSeconds(60).toString())
                .put("sourceResolutionSeconds", 60);
        for (var item : context.path("evidence")) ((ObjectNode) item).set("window", window.deepCopy());
        try {
            TraceLinkInputs.validateContext(context);
            throw new IllegalStateException("Unexpected acceptance of a one-minute context");
        } catch (IllegalArgumentException expected) {
            output.put("existingFixtureWith60SecondWindow", "REJECTED").put("durationRejection", expected.getMessage());
        }
        Files.writeString(Path.of(args[1]), json.writerWithDefaultPrettyPrinter().writeValueAsString(output) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("PASS: existing five-minute fixture accepted; raw producer shape and one-minute context rejected");
    }
}
