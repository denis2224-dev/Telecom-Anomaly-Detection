package md.utm.telecom.processing.ingestion;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayloadCodecTest {
    private final PayloadCodec codec = new PayloadCodec();
    private String canonical(String json) throws Exception {
        return codec.canonical(codec.parse(json.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void sortsNestedObjectsButPreservesArrayOrder() throws Exception {
        assertEquals(codec.hash(canonical("{\"b\":2,\"a\":1}")), codec.hash(canonical("{\"a\":1,\"b\":2}")));
        assertEquals(canonical("{\"z\":[{\"b\":2,\"a\":1}],\"a\":0}"),
                canonical("{\"a\":0,\"z\":[{\"a\":1,\"b\":2}]}"));
        assertNotEquals(codec.hash(canonical("[1,2]")), codec.hash(canonical("[2,1]")));
    }
    @Test void hashesExactMalformedBytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", codec.hash("abc"));
        assertNotEquals(codec.hash(new byte[]{(byte) 0xff}), codec.hash(new byte[]{(byte) 0xfe}));
    }
    @Test void rejectsAmbiguousAndTrailingDocuments() {
        for (String json : new String[]{"", "{} {}", "{\"a\":1,\"a\":2}", "NaN"}) {
            assertThrows(java.io.IOException.class, () -> codec.parse(json.getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void rejectsExcessiveNestingBeforeCanonicalRecursion() {
        String nested = "[".repeat(100) + "0" + "]".repeat(100);
        assertThrows(java.io.IOException.class, () -> codec.parse(nested.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void deliveryDefensivelyCopiesBytes() {
        byte[] raw = {1, 2};
        var delivery = new ObservationDelivery(raw, null, "topic", 0, 0);
        raw[0] = 3;
        delivery.payload()[1] = 4;
        assertArrayEquals(new byte[]{1, 2}, delivery.payload());
    }
}
