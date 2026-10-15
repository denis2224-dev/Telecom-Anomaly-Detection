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
@TestPropertySource(properties={"app.notifications.enabled=true","app.notifications.push-enabled=true","telecom.trace-link.enabled=true",
 "app.notifications.vapid-public-key=controlled-test-key","app.notifications.vapid-private-key=controlled-test-key",
 "app.notifications.vapid-subject=mailto:test@example.invalid",
 "app.notifications.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class PushSubscriptionApiIT extends IncidentServiceIntegrationTestSupport {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
 @Autowired md.utm.telecom.tracelink.TraceLinkIncidentService receiver;
 @Autowired md.utm.telecom.notifications.repository.PushDeliveryStore delivery;
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
 @Test void committedNotificationQueueRetainsIdentityAndFencesExpiredDeliveryOwners() throws Exception {
  var actor=actor();
  mvc.perform(auth(post("/api/notifications/push-subscriptions"),actor).with(csrf()).contentType("application/json").content(subscription(0))).andExpect(status().isCreated());
  var resource=new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath:contracts/fixtures/trace-link/*detection-envelopes.json")[0];
  tools.jackson.databind.JsonNode envelope;
  try(var input=resource.getInputStream()){envelope=json.readTree(input).required("detections").get(0);}
  String episode=envelope.required("lifecycle").required("episodeId").asText();
  receiver.ingest(episode,envelope.toString());receiver.ingest(episode,envelope.toString());
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.push_delivery",Long.class));
  var now=java.time.Instant.now();var id=delivery.claim("owner-a",now,60,6).orElseThrow();
  assertEquals(1,delivery.load(id,"owner-a",now).orElseThrow().attempt());
  assertTrue(delivery.claim("owner-b",now.plusSeconds(1),60,6).isEmpty());
  assertEquals(id,delivery.claim("owner-b",now.plusSeconds(61),60,6).orElseThrow());
  delivery.finish(id,"owner-a",now.plusSeconds(62),md.utm.telecom.notifications.push.PushDeliveryPolicy.classify(201,1,6,now.plusSeconds(62)));
  assertEquals("IN_FLIGHT",jdbc.queryForObject("SELECT state FROM app.push_delivery WHERE id=?",String.class,id));
  assertEquals(2,delivery.load(id,"owner-b",now.plusSeconds(62)).orElseThrow().attempt());
  delivery.finish(id,"owner-b",now.plusSeconds(62),md.utm.telecom.notifications.push.PushDeliveryPolicy.classify(503,2,6,now.plusSeconds(62)));
  assertTrue(delivery.claim("owner-c",now.plusSeconds(63),60,6).isEmpty());
  assertEquals(id,delivery.claim("owner-c",now.plusSeconds(83),60,6).orElseThrow());
  delivery.finish(id,"owner-c",now.plusSeconds(84),md.utm.telecom.notifications.push.PushDeliveryPolicy.classify(410,3,6,now.plusSeconds(84)));
  assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM app.push_delivery WHERE id=?",String.class,id));
  assertFalse(jdbc.queryForObject("SELECT enabled FROM app.push_subscription WHERE analyst_id=?",Boolean.class,actor));
 }
}
