package md.utm.telecom.tracelink;

import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

@TestPropertySource(properties="telecom.trace-link.enabled=true")
class TraceLinkReceiverIT extends IncidentServiceIntegrationTestSupport {
    @Autowired TraceLinkIncidentService receiver;
    @Autowired MockMvc mvc;
    @Autowired AnalystRepository analysts;

    @Test void reorderedReplayAndReconciliationProduceOneOrderedEpisode() {
        var first = TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        var second = TraceLinkReceiverFixtures.envelope(2,"UPDATE","HIGH");
        var third = TraceLinkReceiverFixtures.envelope(3,"UPDATE","CRITICAL");
        var fourth = TraceLinkReceiverFixtures.envelope(4,"UNKNOWN","CRITICAL");
        var fifth = TraceLinkReceiverFixtures.envelope(5,"UPDATE","CRITICAL");
        var sixth = TraceLinkReceiverFixtures.envelope(6,"UPDATE","CRITICAL");
        var seventh = TraceLinkReceiverFixtures.envelope(7,"RECOVERY","CRITICAL");
        String episode = first.required("lifecycle").required("episodeId").asText();
        assertEquals("STORED_PENDING_GAP", receiver.ingest(episode,third.toString()).disposition());
        assertEquals(0, receiver.list(20,0).total());
        assertEquals(1,receiver.ingest(episode,first.toString()).appliedCount());
        assertEquals(2,receiver.ingest(episode,second.toString()).appliedCount());
        assertEquals("DUPLICATE",receiver.ingest(episode,third.toString()).disposition());
        receiver.ingest(episode,fourth.toString()); receiver.ingest(episode,fifth.toString());
        receiver.ingest(episode,sixth.toString()); receiver.ingest(episode,seventh.toString());
        assertEquals(7,receiver.reconcile(episode).latestSequence());
        assertEquals(7,receiver.evidence(episode,100,0).total());
        assertEquals("RECOVERED",receiver.detail(episode).required("lifecycle").required("technicalState").asText());
        assertEquals(7,((Number)entityManager.createNativeQuery("SELECT count(*) FROM app.trace_link_incident_audit").getSingleResult()).intValue());
        assertEquals(1,receiver.list(20,0).total());
    }

    @Test void ingestsProducedEnvelopeArtifactOrExplicitProtocolOracle() throws Exception {
        String producedFile=System.getProperty("traceLink.producedFile");
        tools.jackson.databind.JsonNode input;
        if(producedFile!=null) {
            java.nio.file.Path path=java.nio.file.Path.of(producedFile);
            assertTrue(java.nio.file.Files.isRegularFile(path),"Producer artifact must exist: "+path);
            input=TraceLinkReceiverFixtures.JSON.readTree(path);
            System.out.println("Receiver artifact boundary test: actual producer file "+path);
        } else {
            try(var stream=getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-detection-envelopes.json")) {
                assertNotNull(stream); input=TraceLinkReceiverFixtures.JSON.readTree(stream);
            }
            System.out.println("Receiver protocol test: independent shared hand oracle; no producer-file claim");
        }
        var detections=input.isArray()?input:input.required("detections");
        assertTrue(detections.isArray()); assertFalse(detections.isEmpty());
        java.util.Set<String> domains=new java.util.HashSet<>(),episodes=new java.util.HashSet<>();
        for(var envelope:detections) {
            domains.add(envelope.required("context").required("domain").asText());
            String episode=envelope.required("lifecycle").required("episodeId").asText(); episodes.add(episode);
            receiver.ingest(episode,envelope.toString());
        }
        assertEquals(java.util.Set.of("TRANSPORT","ROAMING","MOBILE_DATA"),domains);
        assertEquals(episodes.size(),receiver.list(100,0).total());
        for(String episode:episodes) {
            var detail=receiver.detail(episode);
            assertEquals("RECOVERED",detail.required("lifecycle").required("technicalState").asText());
            assertEquals(episode,detail.required("lifecycle").required("episodeId").asText());
            assertEquals("DUPLICATE",receiver.ingest(episode,detail.toString()).disposition());
        }
    }
    @Test void conflictingReplayDoesNotOverwriteImmutableEvidence() {
        var first = TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        String episode = first.required("lifecycle").required("episodeId").asText();
        receiver.ingest(episode,first.toString());
        var changed = first.deepCopy(); changed.withObject("cause").put("probableCause","different content");
        assertThrows(IllegalArgumentException.class, () -> receiver.ingest(episode,changed.toString()));
        assertEquals(1,receiver.evidence(episode,20,0).total());
    }

    @Test void rollbackRemovesEvidenceProjectionAndAudit() {
        var first=TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        String episode=first.required("lifecycle").required("episodeId").asText();
        receiver.ingest(episode,first.toString());
        org.springframework.test.context.transaction.TestTransaction.flagForRollback();
        org.springframework.test.context.transaction.TestTransaction.end();
        org.springframework.test.context.transaction.TestTransaction.start();
        entityManager.createNativeQuery("SET LOCAL ROLE incidents_app").executeUpdate();
        assertEquals(0,receiver.list(20,0).total());
        assertEquals(0,((Number)entityManager.createNativeQuery("SELECT count(*) FROM app.trace_link_detection_evidence").getSingleResult()).intValue());
        assertEquals(0,((Number)entityManager.createNativeQuery("SELECT count(*) FROM app.trace_link_incident_audit").getSingleResult()).intValue());
    }

    @Test void newReceiverInstanceRebuildsFromCommittedState() {
        var first=TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        var second=TraceLinkReceiverFixtures.envelope(2,"UPDATE","HIGH");
        String episode=first.required("lifecycle").required("episodeId").asText();
        receiver.ingest(episode,first.toString());
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
        org.springframework.test.context.transaction.TestTransaction.start();
        try {
            entityManager.createNativeQuery("SET LOCAL ROLE incidents_app").executeUpdate();
            var restarted=new TraceLinkIncidentService(entityManager,new md.utm.telecom.evidence.service.EpisodeLock(entityManager),
                    TraceLinkReceiverFixtures.JSON,event -> { });
            assertEquals("DUPLICATE",restarted.ingest(episode,first.toString()).disposition());
            assertEquals(2,restarted.ingest(episode,second.toString()).latestSequence());
            assertEquals(2,restarted.evidence(episode,20,0).total());
        } finally {
            // Discard the uncommitted second transition, then remove the committed test seed as test admin.
            org.springframework.test.context.transaction.TestTransaction.flagForRollback();
            org.springframework.test.context.transaction.TestTransaction.end();
            org.springframework.test.context.transaction.TestTransaction.start();
            entityManager.createNativeQuery("DELETE FROM app.trace_link_incident_audit").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM app.trace_link_incidents").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM app.trace_link_detection_evidence").executeUpdate();
            org.springframework.test.context.transaction.TestTransaction.flagForCommit();
            org.springframework.test.context.transaction.TestTransaction.end();
            org.springframework.test.context.transaction.TestTransaction.start();
        }
    }
    @Test void independentDomainsKeepDistinctEpisodes() {
        for(String domain:List.of("TRANSPORT","ROAMING","MOBILE_DATA")) {
            var first=TraceLinkReceiverFixtures.envelope(domain,1,"OPEN","HIGH");
            receiver.ingest(first.required("lifecycle").required("episodeId").asText(),first.toString());
        }
        assertEquals(3,receiver.list(20,0).total());
    }
    @Test void requiresSessionAndEnabledAnalystAndReturnsProvenance() throws Exception {
        mvc.perform(get("/api/v2/trace-link/incidents")).andExpect(status().isUnauthorized());
        var analyst = analysts.save(new Analyst("https://identity.test/realm","trace-receiver","Trace receiver"));
        var requestUser = oidcLogin().idToken(token -> token.issuer("https://identity.test/realm").subject("trace-receiver"))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
        var first = TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        String episode = first.required("lifecycle").required("episodeId").asText();
        receiver.ingest(episode,first.toString());
        mvc.perform(get("/api/v2/trace-link/incidents").with(requestUser).session(authenticatedSession()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].context.datasetId").value("trace-link-md-30d-v1"));
        mvc.perform(get("/api/v2/trace-link/incidents/"+episode+"/evidence").with(requestUser).session(authenticatedSession()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v2/trace-link/incidents?limit=101").with(requestUser).session(authenticatedSession()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/v2/trace-link/incidents/"+"f".repeat(64)).with(requestUser).session(authenticatedSession()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        analyst.setEnabled(false); analysts.saveAndFlush(analyst);
        mvc.perform(get("/api/v2/trace-link/incidents").with(requestUser).session(authenticatedSession()))
                .andExpect(status().isForbidden());
    }
}
