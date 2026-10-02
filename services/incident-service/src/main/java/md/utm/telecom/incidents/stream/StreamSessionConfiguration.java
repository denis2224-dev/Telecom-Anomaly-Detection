package md.utm.telecom.incidents.stream;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StreamSessionConfiguration {
    @Bean
    ServletListenerRegistrationBean<HttpSessionListener> streamSessionListener(
            IncidentStreamRegistry streams) {
        HttpSessionListener listener = new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                streams.closeSession(event.getSession().getId());
            }
        };
        return new ServletListenerRegistrationBean<>(listener);
    }
}
