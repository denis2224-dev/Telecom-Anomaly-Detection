package md.utm.telecom.notifications.config;

import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import md.utm.telecom.notifications.push.PushEndpointPolicy;
import md.utm.telecom.notifications.push.PushSubscriptionCipher;
import md.utm.telecom.notifications.push.WebPushSender;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class PushConfigurationTest {
    private ApplicationContextRunner context(NotificationProperties properties, boolean enabled) {
        return new ApplicationContextRunner()
                .withUserConfiguration(NotificationConfiguration.class, PushRuntimeConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(NotificationProperties.class, () -> properties)
                .withBean(PushEndpointPolicy.class, () -> new PushEndpointPolicy("fcm.googleapis.com"))
                .withPropertyValues("app.notifications.push-enabled=" + enabled,
                        "app.notifications.delivery.batch-size=50", "app.notifications.delivery.poll-ms=2000",
                        "app.notifications.delivery.lease-seconds=60", "app.notifications.delivery.max-attempts=6");
    }

    @Test
    void featureOffNeedsNoKeysAndFeatureOnRejectsMissingKeys() {
        context(new NotificationProperties(true, false, null, null, null, null), false).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertTrue(ctx.getBeansOfType(PushSubscriptionCipher.class).isEmpty());
            assertTrue(ctx.getBeansOfType(WebPushSender.class).isEmpty());
        });
        context(new NotificationProperties(true, true, null, null, null, null), true)
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    @Test
    void matchingVapidKeysStartAndMismatchedKeysFail() throws Exception {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var first = generator.generateKeyPair();
        var second = generator.generateKeyPair();
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String publicKey = encoder.encodeToString(Utils.encode((ECPublicKey) first.getPublic()));
        String privateKey = encoder.encodeToString(Utils.encode((ECPrivateKey) first.getPrivate()));
        String wrongPrivateKey = encoder.encodeToString(Utils.encode((ECPrivateKey) second.getPrivate()));
        String aes = Base64.getEncoder().encodeToString(new byte[32]);
        context(new NotificationProperties(true, true, publicKey, privateKey,
                "mailto:test@example.invalid", aes), true).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(1, ctx.getBeansOfType(WebPushSender.class).size());
            assertEquals(1, ctx.getBeansOfType(PushSubscriptionCipher.class).size());
        });
        context(new NotificationProperties(true, true, publicKey, wrongPrivateKey,
                "mailto:test@example.invalid", aes), true).run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }
}
