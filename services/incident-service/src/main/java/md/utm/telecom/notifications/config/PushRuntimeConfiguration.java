package md.utm.telecom.notifications.config;

import java.net.URI;
import java.security.Security;
import java.security.Signature;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Utils;
import md.utm.telecom.notifications.push.PushEndpointPolicy;
import md.utm.telecom.notifications.push.VapidWebPushSender;
import md.utm.telecom.notifications.push.WebPushSender;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.notifications", name = "push-enabled", havingValue = "true")
@EnableConfigurationProperties(PushDeliveryProperties.class)
public class PushRuntimeConfiguration {
    @Bean
    WebPushSender webPushSender(NotificationProperties properties, PushEndpointPolicy endpoints) {
        try {
            if (!properties.enabled()) throw new IllegalArgumentException();
            URI subject = URI.create(properties.vapidSubject());
            if (!("mailto".equals(subject.getScheme()) || "https".equals(subject.getScheme()))
                    || subject.getSchemeSpecificPart().isBlank()) throw new IllegalArgumentException();
            if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
            var publicKey = Utils.loadPublicKey(properties.vapidPublicKey());
            var privateKey = Utils.loadPrivateKey(properties.vapidPrivateKey());
            byte[] challenge = new byte[] {84, 114, 97, 99, 101};
            var signer = Signature.getInstance("SHA256withECDSA", "BC");
            signer.initSign(privateKey);
            signer.update(challenge);
            byte[] signature = signer.sign();
            signer.initVerify(publicKey);
            signer.update(challenge);
            if (!signer.verify(signature)) throw new IllegalArgumentException();
            return new VapidWebPushSender(new PushService(properties.vapidPublicKey(),
                    properties.vapidPrivateKey(), properties.vapidSubject()), endpoints);
        } catch (Exception invalid) {
            throw new IllegalStateException("Push VAPID configuration is invalid");
        }
    }
}
