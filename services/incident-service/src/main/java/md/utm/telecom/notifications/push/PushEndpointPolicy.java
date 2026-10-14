package md.utm.telecom.notifications.push;

import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PushEndpointPolicy {
    private final Set<String> hosts;

    public PushEndpointPolicy(@Value("${app.notifications.push-endpoint-hosts:fcm.googleapis.com,updates.push.services.mozilla.com,web.push.apple.com}") String hosts) {
        this.hosts = Arrays.stream(hosts.split(",")).map(String::trim)
                .filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String endpoint, String p256dh, String auth) {
        try {
            URI uri = URI.create(endpoint);
            byte[] publicKey = Base64.getUrlDecoder().decode(p256dh);
            byte[] secret = Base64.getUrlDecoder().decode(auth);
            if (endpoint.length() > 2048 || !"https".equals(uri.getScheme())
                    || uri.getHost() == null || !hosts.contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || publicKey.length != 65 || publicKey[0] != 4 || secret.length != 16) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid browser push subscription");
        }
    }
}
