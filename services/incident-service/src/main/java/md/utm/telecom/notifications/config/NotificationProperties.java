package md.utm.telecom.notifications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notifications")
public record NotificationProperties(boolean enabled, boolean pushEnabled,
                                     String vapidPublicKey, String vapidPrivateKey,
                                     String vapidSubject, String encryptionKey) {}
