package md.utm.telecom.notifications;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import md.utm.telecom.notifications.push.PushSubscriptionCipher;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushSubscriptionCipherTest {
    private final ObjectMapper json = new ObjectMapper();
    private final byte[] key = new byte[32];
    private final PushSubscriptionCipher cipher = new PushSubscriptionCipher(Map.of((short) 1, key), json);

    @Test
    void roundTripKeepsSubscriptionFieldsTogether() {
        var sealed = cipher.seal((short) 1, "https://push.example/endpoint", "public-key", "auth-secret");
        var opened = json.readTree(cipher.open(sealed));
        assertEquals("https://push.example/endpoint", opened.path("endpoint").asText());
        assertEquals("public-key", opened.path("p256dh").asText());
        assertEquals("auth-secret", opened.path("auth").asText());
        assertEquals(64, sealed.endpointHash().length());
        assertEquals(12, sealed.nonce().length);
    }

    @Test
    void wrongVersionAndTamperedCiphertextFailClosed() {
        var sealed = cipher.seal((short) 1, "https://push.example/endpoint", "public-key", "auth-secret");
        var other = new PushSubscriptionCipher(Map.of((short) 2, new byte[32]), json);
        assertThrows(IllegalStateException.class, () -> other.open(sealed));
        byte[] corrupted = sealed.ciphertext();
        corrupted[0] ^= 1;
        var tampered = new PushSubscriptionCipher.Sealed(sealed.endpointHash(), corrupted,
                sealed.nonce(), sealed.keyVersion());
        assertThrows(IllegalStateException.class, () -> cipher.open(tampered));
    }

    @Test
    void lengthLimitsAndStoredValuesExcludePlaintext() {
        assertThrows(IllegalArgumentException.class,
                () -> cipher.seal((short) 1, "x".repeat(2049), "key", "auth"));
        var sealed = cipher.seal((short) 1, "https://push.example/private", "public-key", "auth-secret");
        String stored = sealed.endpointHash() + Arrays.toString(sealed.ciphertext())
                + new String(sealed.ciphertext(), StandardCharsets.ISO_8859_1);
        assertFalse(stored.contains("https://push.example/private"));
        assertFalse(stored.contains("auth-secret"));
        assertTrue(sealed.endpointHash().matches("[0-9a-f]{64}"));
    }
}
