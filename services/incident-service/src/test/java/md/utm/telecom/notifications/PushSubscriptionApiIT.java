package md.utm.telecom.notifications;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.notifications.push.WebPushSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual protected API and encrypted persistence; the sender is controlled and never delivers. */
@TestPropertySource(properties={"app.notifications.enabled=true","app.notifications.push-enabled=true",
 "app.notifications.vapid-public-key=controlled-test-key","app.notifications.vapid-private-key=controlled-test-key",
 "app.notifications.vapid-subject=mailto:test@example.invalid",
 "app.notifications.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class PushSubscriptionApiIT extends IncidentServiceIntegrationTestSupport {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
 @MockitoBean WebPushSender sender;
 private static final String ISSUER="http://telecom.test:8080/auth/realms/telecom";
 private UUID actor(){var id=UUID.randomUUID();jdbc.update("INSERT INTO app.analysts(id,issuer,subject,display_name) VALUES (?,?,?,'Push test')",id,ISSUER,id.toString());return id;}
 private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,UUID actor){return request.session(authenticatedSession()).with(oidcLogin().idToken(token->token.issuer(ISSUER).subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ANALYST")));}
 private String subscription(int index){byte[] publicKey=new byte[65];publicKey[0]=4;var encoder=Base64.getUrlEncoder().withoutPadding();return json.writeValueAsString(java.util.Map.of("endpoint","https://fcm.googleapis.com/fcm/send/controlled-"+index,"p256dh",encoder.encodeToString(publicKey),"auth",encoder.encodeToString(new byte[16])));}
 @Test void encryptedIdempotentSubscriptionsRequireCsrfAndRemainOwnerPrivate() throws Exception {
  var actor=actor();var other=actor();String body=subscription(0);
  mvc.perform(get("/api/notifications/push/config")).andExpect(status().isUnauthorized());
  mvc.perform(auth(post("/api/notifications/push-subscriptions"),actor).contentType("application/json").content(body)).andExpect(status().isForbidden());
  var response=mvc.perform(auth(post("/api/notifications/push-subscriptions"),actor).with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse();
  String id=json.readTree(response.getContentAsString()).required("id").asText();assertTrue(response.getHeader("Location").endsWith(id));
  mvc.perform(auth(post("/api/notifications/push-subscriptions"),actor).with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
  mvc.perform(auth(post("/api/notifications/push-subscriptions"),other).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict());
  mvc.perform(auth(delete("/api/notifications/push-subscriptions/"+id),other).with(csrf())).andExpect(status().isNotFound());
  var ciphertext=jdbc.queryForObject("SELECT payload_ciphertext FROM app.push_subscription WHERE id=?",byte[].class,UUID.fromString(id));assertFalse(new String(ciphertext,StandardCharsets.UTF_8).contains("fcm.googleapis.com"));
  mvc.perform(auth(delete("/api/notifications/push-subscriptions/"+id),actor).with(csrf())).andExpect(status().isNoContent());
  assertFalse(jdbc.queryForObject("SELECT enabled FROM app.push_subscription WHERE id=?",Boolean.class,UUID.fromString(id)));
 }
 @Test void endpointAllowlistSubscriptionCapAndDisabledProfileAreEnforced() throws Exception {
  var actor=actor();
  mvc.perform(auth(post("/api/notifications/push-subscriptions"),actor).with(csrf()).contentType("application/json").content(subscription(0).replace("https://fcm.googleapis.com", "http://127.0.0.1"))).andExpect(status().isBadRequest());
  for(int i=0;i<5;i++)mvc.perform(auth(post("/api/notifications/push-subscriptions"),actor).with(csrf()).contentType("application/json").content(subscription(i))).andExpect(status().isCreated());
  mvc.perform(auth(post("/api/notifications/push-subscriptions"),actor).with(csrf()).contentType("application/json").content(subscription(5))).andExpect(status().isConflict());
  jdbc.update("UPDATE app.analysts SET enabled=false WHERE id=?",actor);entityManager.clear();
  mvc.perform(auth(get("/api/notifications/push/config"),actor)).andExpect(status().isForbidden());
 }
}
