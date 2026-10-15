package md.utm.telecom.notifications;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import md.utm.telecom.notifications.config.PushDeliveryProperties;
import md.utm.telecom.notifications.push.PushDeliveryPolicy;
import md.utm.telecom.notifications.push.PushSubscriptionCipher;
import md.utm.telecom.notifications.push.WebPushSender;
import md.utm.telecom.notifications.repository.PushDeliveryStore;
import md.utm.telecom.notifications.service.PushDeliveryWorker;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PushDeliveryWorkerTest {
    private final Instant now = Instant.parse("2026-10-13T09:00:00Z");
    private final UUID id = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private final PushDeliveryStore store = mock(PushDeliveryStore.class);
    private final WebPushSender sender = mock(WebPushSender.class);
    private final PushSubscriptionCipher cipher = new PushSubscriptionCipher(Map.of((short) 1, new byte[32]), json);

    private PushDeliveryWorker worker() {
        return new PushDeliveryWorker(store, new PushDeliveryProperties(50, 2000, 60, 6),
                cipher, sender, json, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void pending(boolean enabled, Instant expiration, String url) {
        var sealed = cipher.seal((short) 1, "https://fcm.googleapis.com/send/test", "public", "auth");
        var work = new PushDeliveryStore.Work(id, subscriptionId, 1, sealed.endpointHash(),
                sealed.ciphertext(), sealed.nonce(), sealed.keyVersion(), expiration,
                enabled, true, true, "CRITICAL", url);
        when(store.claim(anyString(), eq(now), eq(60), eq(6)))
                .thenReturn(Optional.of(id), Optional.empty());
        when(store.load(eq(id), anyString(), eq(now))).thenReturn(Optional.of(work));
    }

    @Test
    void payloadContainsOnlyTheApprovedLockScreenFields() throws Exception {
        String url = "/incidents/" + UUID.randomUUID();
        pending(true, null, url);
        when(sender.send(any(), any())).thenAnswer(invocation -> {
            var payload = json.readTree((byte[]) invocation.getArgument(1));
            assertEquals(3, payload.size());
            assertEquals("Trace Link incident update", payload.path("title").asText());
            assertEquals("CRITICAL", payload.path("severity").asText());
            assertEquals(url, payload.path("url").asText());
            return 201;
        });
        worker().poll();
        verify(store).finish(eq(id), anyString(), eq(now),
                eq(new PushDeliveryPolicy.Outcome("DELIVERED", null, now, false)));
    }

    @Test
    void disabledAnalystAndUnsafeUrlNeverReachTransport() {
        pending(false, null, "/incidents/" + UUID.randomUUID());
        worker().poll();
        verifyNoInteractions(sender);
        reset(store);
        pending(true, null, "https://attacker.test/incident");
        worker().poll();
        verifyNoInteractions(sender);
    }

    @Test
    void expirationDisablesSubscriptionAndTransientFailureRetries() throws Exception {
        pending(true, now, "/incidents/" + UUID.randomUUID());
        worker().poll();
        verifyNoInteractions(sender);
        verify(store).finish(eq(id), anyString(), eq(now),
                eq(new PushDeliveryPolicy.Outcome("FAILED", "SUBSCRIPTION_EXPIRED", now, true)));
        reset(store);
        pending(true, null, "/incidents/" + UUID.randomUUID());
        when(sender.send(any(), any())).thenReturn(503);
        worker().poll();
        verify(store).finish(eq(id), anyString(), eq(now),
                eq(new PushDeliveryPolicy.Outcome("RETRY", "TRANSIENT", now.plusSeconds(10), false)));
    }
}
