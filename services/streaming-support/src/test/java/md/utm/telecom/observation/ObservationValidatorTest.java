package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.*;

class ObservationValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObservationValidator validator = new ObservationValidator();

    ObservationValidatorTest() throws Exception {}

    private ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) ObservationValidator.resource("fixtures/observations/" + name + ".json", mapper);
    }

    @TestFactory
    List<DynamicTest> sharedReferenceCases() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        for (var item : ObservationValidator.resource("fixtures/validation/observation-cases-v2.json", mapper)) {
            tests.add(DynamicTest.dynamicTest(item.get("name").asText(), () -> {
                var event = (ObjectNode) ObservationValidator.resource(
                        "fixtures/observations/" + item.get("base").asText(), mapper);
                item.get("patch").fields().forEachRemaining(field -> {
                    String path = field.getKey();
                    int lastSlash = path.lastIndexOf('/');
                    var parent = lastSlash == 0 ? event : (ObjectNode) event.at(path.substring(0, lastSlash));
                    parent.set(path.substring(lastSlash + 1), field.getValue());
                });
                if (item.get("valid").asBoolean()) assertDoesNotThrow(() -> validator.validate(event));
                else assertThrows(IllegalArgumentException.class, () -> validator.validate(event));
            }));
        }
        return tests;
    }

    @Test
    void allNormalDegradedMissingAndHeartbeatFixtures() throws Exception {
        for (String name : List.of("normal-volte", "degraded-volte", "normal-sms", "degraded-sms",
                "normal-ims", "degraded-ims", "normal-smsc", "degraded-smsc", "normal-transport",
                "sms-no-completions", "missing-volte", "heartbeat")) validator.validate(fixture(name));
    }

    @Test
    void smsSamplesAreBoundedEvenWhenCountsMatch() throws Exception {
        var event = fixture("normal-sms");
        var m = (ObjectNode) event.get("metrics");
        var samples = m.putArray("deliveryDelayMs");
        for (int i = 0; i < 10000; i++) samples.add(1);
        for (String key : List.of("deliveryAttempts", "deliverySuccesses", "deliveredMessages")) m.put(key, 10000);
        validator.validate(event);
        samples.add(1);
        for (String key : List.of("deliveryAttempts", "deliverySuccesses", "deliveredMessages")) m.put(key, 10001);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(event));
    }

    @Test
    void duplicateConflictsAndDefensiveReceiptCopy() throws Exception {
        var event = fixture("normal-volte");
        var batch = new ObservationBatch(validator);
        assertEquals(ObservationBatch.Result.ACCEPTED, batch.accept(event));
        assertEquals(ObservationBatch.Result.DUPLICATE, batch.accept(mapper.readTree(event.toString())));
        var sameNumbers = event.deepCopy();
        ((ObjectNode) sameNumbers.get("metrics")).put("attempts", 1020.0);
        assertEquals(ObservationBatch.Result.DUPLICATE, batch.accept(sameNumbers));
        assertThrows(IllegalArgumentException.class, () -> batch.accept(fixture("degraded-volte")));
        var changedId = event.deepCopy().put("eventId", "00000000-0000-4000-8000-000000000001");
        assertThrows(IllegalArgumentException.class, () -> batch.accept(changedId));
        var changedTime = event.deepCopy().put("windowStart", "2026-09-15T08:01:00Z")
                .put("windowEnd", "2026-09-15T08:02:00Z").put("emittedAt", "2026-09-15T08:02:00Z");
        assertThrows(IllegalArgumentException.class, () -> batch.accept(changedTime));
        ((ObjectNode) event.get("metrics")).put("sip503Count", 1);
        assertThrows(IllegalArgumentException.class, () -> batch.accept(event));
        assertEquals(ObservationBatch.Result.DUPLICATE, batch.accept(fixture("normal-volte")));
    }
}
