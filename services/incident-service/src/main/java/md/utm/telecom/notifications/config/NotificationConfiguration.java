package md.utm.telecom.notifications.config;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import md.utm.telecom.notifications.push.PushSubscriptionCipher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class NotificationConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.notifications", name = "push-enabled", havingValue = "true")
    PushSubscriptionCipher pushSubscriptionCipher(NotificationProperties properties, ObjectMapper json) {
        if (blank(properties.vapidPublicKey()) || blank(properties.vapidPrivateKey())
                || blank(properties.vapidSubject()) || blank(properties.encryptionKey())) {
            throw new IllegalStateException("Push configuration is incomplete");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.encryptionKey());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Push encryption key is invalid", invalid);
        }
        try {
            return new PushSubscriptionCipher(Map.of((short) 1, key), json);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
