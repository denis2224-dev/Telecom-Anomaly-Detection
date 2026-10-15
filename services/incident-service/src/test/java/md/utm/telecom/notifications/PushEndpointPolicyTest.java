package md.utm.telecom.notifications;

import java.util.Base64;
import md.utm.telecom.notifications.push.PushEndpointPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PushEndpointPolicyTest {
    @Test
    void onlyApprovedHttpsProviderOriginsAreAccepted() {
        var policy = new PushEndpointPolicy("fcm.googleapis.com");
        byte[] key = new byte[65];
        key[0] = 4;
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        assertDoesNotThrow(() -> policy.validate("https://fcm.googleapis.com/send/test", publicKey, auth));
        for (String endpoint : new String[] {"http://fcm.googleapis.com/test", "https://127.0.0.1/test",
                "https://fcm.googleapis.com.attacker.test/test", "https://user@fcm.googleapis.com/test",
                "https://fcm.googleapis.com:8443/test", "https://fcm.googleapis.com/test#fragment"}) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate(endpoint, publicKey, auth));
        }
    }
}
