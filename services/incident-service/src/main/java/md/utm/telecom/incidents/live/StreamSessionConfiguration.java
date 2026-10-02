package md.utm.telecom.incidents.live;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StreamSessionConfiguration {
    @Bean
    ServletListenerRegistrationBean<HttpSessionListener> streamSessionListener(IncidentStream stream) {
        return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent event) { stream.closeSession(event.getSession()); }
        });
    }
}
