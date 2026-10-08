package md.utm.telecom.geography;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeographyCatalogueTest {
    @Test
    void cityActivationRequiresEnabledCatalogueAndScheduledMinute() throws Exception {
        var activation = java.time.Instant.parse("2026-09-15T08:00:00Z");
        var enabled = new GeographyCatalogue(activation.toString(), true);
        assertFalse(enabled.activeAt(activation.minusSeconds(60)));
        assertTrue(enabled.activeAt(activation));

        var disabled = new GeographyCatalogue(activation.toString(), false);
        assertFalse(disabled.active());
        assertFalse(disabled.activeAt(activation.plusSeconds(60)));
        assertEquals("CONTRACT_ONLY", disabled.root().path("activation").path("status").asText());
    }

    @Test
    void rejectsCyclesDanglingAndCrossCityParentsBeforeImport() throws Exception {
        var valid = new GeographyCatalogue("");
        for (String parent : new String[]{"CELL-MD-CHI-01", "SITE-MD-UNKNOWN", "SITE-MD-BAL-01"}) {
            ObjectNode document = (ObjectNode) valid.root();
            for (var node : document.path("nodes"))
                if (node.path("nodeId").asText().equals("CELL-MD-CHI-01"))
                    ((ObjectNode) node).put("parentId", parent);
            assertThrows(IllegalArgumentException.class,
                    () -> new GeographyCatalogue("", document, valid.topology()), parent);
        }
    }

    @Test
    void rejectsDuplicateNodesAndWrongContainmentDepth() throws Exception {
        var valid = new GeographyCatalogue("");
        ObjectNode duplicate = (ObjectNode) valid.root();
        duplicate.withArray("nodes").add(duplicate.withArray("nodes").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,
                () -> new GeographyCatalogue("", duplicate, valid.topology()));
        ObjectNode wrongDepth = (ObjectNode) valid.root();
        for (var node : wrongDepth.path("nodes"))
            if (node.path("nodeId").asText().equals("CELL-MD-CHI-01"))
                ((ObjectNode) node).put("parentId", "AGG-MD-CHI-01");
        assertThrows(IllegalArgumentException.class,
                () -> new GeographyCatalogue("", wrongDepth, valid.topology()));
    }

    @Test
    void returnedDocumentsCannotMutateValidatedAuthority() throws Exception {
        var valid = new GeographyCatalogue("");
        ((ObjectNode) valid.root()).remove("nodes");
        ((ObjectNode) valid.scope("VOLTE-MD-CHI")).put("legacy", true);
        ((ObjectNode) valid.strictScope("VOLTE-MD-CHI")).put("service", "SMS");
        assertFalse(valid.root().path("nodes").isEmpty());
        assertFalse(valid.scope("VOLTE-MD-CHI").path("legacy").asBoolean());
        assertEquals("VOLTE", valid.strictScope("VOLTE-MD-CHI").path("service").asText());
    }
}
