package md.utm.telecom.notifications.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.util.EntityUtils;

public final class VapidWebPushSender implements WebPushSender {
    private final PushService service;
    private final PushEndpointPolicy endpoints;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public VapidWebPushSender(PushService service, PushEndpointPolicy endpoints) {
        this.service = service;
        this.endpoints = endpoints;
    }

    @Override
    public int send(Target target, byte[] payload) throws Exception {
        endpoints.validate(target.endpoint(), target.p256dh(), target.auth());
        var notification = new Notification(target.endpoint(), target.p256dh(), target.auth(), payload, 60);
        var post = service.preparePost(notification, Encoding.AES128GCM);
        var request = HttpRequest.newBuilder(URI.create(target.endpoint()))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofByteArray(EntityUtils.toByteArray(post.getEntity())));
        for (var header : post.getAllHeaders()) {
            if (!header.getName().equalsIgnoreCase("Content-Length")
                    && !header.getName().equalsIgnoreCase("Host")) {
                request.header(header.getName(), header.getValue());
            }
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
