package md.utm.telecom.processing.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Recursive object ordering; array order and JSON values remain meaningful. */
@Component
public final class PayloadCodec {
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                    .maxStringLength(1048576).maxNumberLength(128).build()).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    public JsonNode parse(byte[] raw) throws IOException {
        if (raw == null) return mapper.nullNode();
        JsonNode tree = mapper.readTree(raw);
        if (tree == null || tree.isMissingNode()) throw new IOException("Empty JSON document");
        return tree;
    }

    /** Best-effort top-level identifier from a malformed document; never regex-scan raw bytes. */
    public String extractEventId(byte[] raw) {
        if (raw == null) return null;
        String eventId = null;
        try (var parser = mapper.createParser(raw)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("eventId".equals(field) && value == JsonToken.VALUE_STRING) eventId = parser.getText();
                parser.skipChildren();
            }
        } catch (IOException invalid) {
            // Retain an identifier parsed before the failure; raw bytes remain authoritative evidence.
        }
        return eventId;
    }

    public String canonical(JsonNode node) {
        try { return mapper.writeValueAsString(ordered(node)); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }

    private JsonNode ordered(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            var result = mapper.createObjectNode();
            fields.forEach((key, value) -> result.set(key, ordered(value)));
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            node.forEach(value -> result.add(ordered(value)));
            return result;
        }
        return node;
    }

    public String hash(String canonical) { return hash(canonical.getBytes(StandardCharsets.UTF_8)); }
    public String hash(byte[] raw) {
        try {
            // A tombstone has no bytes; its null representation is retained separately in the outbox.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw == null ? new byte[0] : raw));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
