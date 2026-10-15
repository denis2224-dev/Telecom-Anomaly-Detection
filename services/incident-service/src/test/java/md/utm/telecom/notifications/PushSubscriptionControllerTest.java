package md.utm.telecom.notifications;

import java.util.Base64;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.notifications.push.WebPushSender;
import md.utm.telecom.notifications.service.PushDeliveryWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.public-origin=http://telecom.test:8080",
        "spring.kafka.listener.auto-startup=false",
        "app.notifications.push-enabled=true",
        "app.notifications.vapid-public-key=test-public-key",
        "app.notifications.vapid-private-key=test-private-key",
        "app.notifications.vapid-subject=mailto:test@example.invalid",
        "app.notifications.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class PushSubscriptionControllerTest extends IncidentServiceIntegrationTestSupport {
    @MockitoBean WebPushSender sender;
    @MockitoBean PushDeliveryWorker worker;
    @Autowired MockMvc mvc;
    @Autowired AnalystRepository analysts;
    @Autowired ObjectMapper json;
    private Analyst actor;
    private Analyst other;

    @BeforeEach
    void seed() {
        actor = analysts.saveAndFlush(new Analyst("https://identity.test/realm", UUID.randomUUID().toString(), "Actor"));
        other = analysts.saveAndFlush(new Analyst("https://identity.test/realm", UUID.randomUUID().toString(), "Other"));
    }

    private RequestPostProcessor login(Analyst analyst) {
        return oidcLogin().idToken(token -> token.issuer(analyst.getIssuer()).subject(analyst.getSubject()))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
    }

    private String body(String endpoint) {
        byte[] key = new byte[65];
        key[0] = 4;
        return json.writeValueAsString(java.util.Map.of("endpoint", endpoint,
                "p256dh", Base64.getUrlEncoder().withoutPadding().encodeToString(key),
                "auth", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16])));
    }

    @Test
    void csrfOwnershipUpsertAndDeleteAreEnforced() throws Exception {
        String request = body("https://fcm.googleapis.com/send/one");
        mvc.perform(post("/api/notifications/push-subscriptions").session(authenticatedSession())
                .with(login(actor)).contentType("application/json").content(request))
                .andExpect(status().isForbidden());
        String response = mvc.perform(post("/api/notifications/push-subscriptions").session(authenticatedSession())
                .with(login(actor)).with(csrf()).contentType("application/json").content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("id").asText();
        mvc.perform(post("/api/notifications/push-subscriptions").session(authenticatedSession())
                .with(login(actor)).with(csrf()).contentType("application/json").content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/notifications/push-subscriptions").session(authenticatedSession())
                .with(login(other)).with(csrf()).contentType("application/json").content(request))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/notifications/push-subscriptions/" + id).session(authenticatedSession())
                .with(login(other)).with(csrf())).andExpect(status().isNotFound());
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(delete("/api/notifications/push-subscriptions/" + id).session(authenticatedSession())
                    .with(login(actor)).with(csrf())).andExpect(status().isNoContent());
        }
    }

    @Test
    void anonymousDisabledAndSubscriptionLimitAreEnforced() throws Exception {
        mvc.perform(get("/api/notifications/push/config")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/push/config").session(authenticatedSession()).with(login(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.vapidPrivateKey").doesNotExist());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/notifications/push-subscriptions").session(authenticatedSession())
                    .with(login(actor)).with(csrf()).contentType("application/json")
                    .content(body("https://fcm.googleapis.com/send/limit-" + i)))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/notifications/push-subscriptions").session(authenticatedSession())
                .with(login(actor)).with(csrf()).contentType("application/json")
                .content(body("https://fcm.googleapis.com/send/limit-6"))).andExpect(status().isConflict());
        actor.setEnabled(false);
        analysts.flush();
        mvc.perform(get("/api/notifications/push/config").session(authenticatedSession()).with(login(actor)))
                .andExpect(status().isForbidden());
    }
}
