package md.utm.telecom.notifications.service;

import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import md.utm.telecom.notifications.config.PushDeliveryProperties;
import md.utm.telecom.notifications.push.PushDeliveryPolicy;
import md.utm.telecom.notifications.push.PushSubscriptionCipher;
import md.utm.telecom.notifications.push.WebPushSender;
import md.utm.telecom.notifications.repository.PushDeliveryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(prefix = "app.notifications", name = "push-enabled", havingValue = "true")
public class PushDeliveryWorker {
    private final PushDeliveryStore store;
    private final PushDeliveryProperties properties;
    private final PushSubscriptionCipher cipher;
    private final WebPushSender sender;
    private final ObjectMapper json;
    private final Clock clock;

    public PushDeliveryWorker(PushDeliveryStore store, PushDeliveryProperties properties,
            PushSubscriptionCipher cipher, WebPushSender sender, ObjectMapper json, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.cipher = cipher;
        this.sender = sender;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.notifications.delivery.poll-ms:2000}")
    public void poll() {
        for (int i = 0; i < properties.batchSize(); i++) {
            if (Thread.currentThread().isInterrupted()) return;
            String owner = UUID.randomUUID().toString();
            var id = store.claim(owner, clock.instant(), properties.leaseSeconds(), properties.maxAttempts());
            if (id.isEmpty()) return;
            deliver(id.get(), owner);
        }
    }

    private void deliver(UUID id, String owner) {
        var loaded = store.load(id, owner, clock.instant());
        if (loaded.isEmpty()) return;
        var work = loaded.get();
        var now = clock.instant();
        if (!work.analystEnabled() || !work.subscriptionEnabled() || !work.matchingOwner()) {
            store.finish(id, owner, now, new PushDeliveryPolicy.Outcome("FAILED", "RECIPIENT_INACTIVE", now, false));
            return;
        }
        if (work.expirationTime() != null && !work.expirationTime().isAfter(now)) {
            store.finish(id, owner, now, new PushDeliveryPolicy.Outcome("FAILED", "SUBSCRIPTION_EXPIRED", now, true));
            return;
        }
        if (!work.incidentUrl().matches("^/incidents/[0-9a-f-]{36}$")) {
            store.finish(id, owner, now, new PushDeliveryPolicy.Outcome("FAILED", "UNSAFE_URL", now, false));
            return;
        }
        byte[] plain;
        WebPushSender.Target target;
        try {
            plain = cipher.open(new PushSubscriptionCipher.Sealed(work.endpointHash(),
                    work.ciphertext(), work.nonce(), work.keyVersion()));
            try {
                var subscription = json.readTree(plain);
                target = new WebPushSender.Target(subscription.path("endpoint").asText(),
                        subscription.path("p256dh").asText(), subscription.path("auth").asText());
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (RuntimeException invalid) {
            store.finish(id, owner, clock.instant(), new PushDeliveryPolicy.Outcome(
                    "FAILED", "SUBSCRIPTION_INVALID", clock.instant(), false));
            return;
        }
        int status;
        try {
            byte[] payload = json.writeValueAsBytes(Map.of("title", "Trace Link incident update",
                    "severity", work.severity(), "url", work.incidentUrl()));
            status = sender.send(target, payload);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            status = 0;
        } catch (IllegalArgumentException invalid) {
            store.finish(id, owner, clock.instant(), new PushDeliveryPolicy.Outcome(
                    "FAILED", "SUBSCRIPTION_INVALID", clock.instant(), false));
            return;
        } catch (Exception unavailable) {
            status = 0;
        }
        now = clock.instant();
        store.finish(id, owner, now, PushDeliveryPolicy.classify(status, work.attempt(), properties.maxAttempts(), now));
    }
}
