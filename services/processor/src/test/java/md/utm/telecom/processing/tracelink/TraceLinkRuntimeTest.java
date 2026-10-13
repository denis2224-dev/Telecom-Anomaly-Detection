package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkRuntimeTest {
    @Test void identitiesSeparateCountriesDirectionsAndDatasets() throws Exception {
        var json = new ObjectMapper();
        var a = json.readTree("{\"datasetId\":\"a\",\"namespace\":\"reference\",\"domain\":\"ROAMING\",\"scope\":{\"country\":\"RO\",\"direction\":\"OUTBOUND\"}}");
        var b = a.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)b.path("scope")).put("direction", "INBOUND");
        assertNotEquals(TraceLinkWindowStore.streamKey(a), TraceLinkWindowStore.streamKey(b));
        var c = a.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)c).put("datasetId", "b");
        assertNotEquals(TraceLinkWindowStore.streamKey(a), TraceLinkWindowStore.streamKey(c));
    }
    @Test void canonicalObjectOrderDoesNotAlterIdentity() throws Exception {
        var json = new ObjectMapper();
        assertEquals(TraceLinkWindowStore.hash(json.readTree("{\"a\":1,\"b\":2}")),
                TraceLinkWindowStore.hash(json.readTree("{\"b\":2,\"a\":1}")));
    }
    @Test void runtimeIsDisabledWithoutExplicitActivation() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(TraceLinkRuntimeConfiguration.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("traceLinkPoller"));
                    assertFalse(context.containsBean("traceLinkWindowStore"));
                });
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={1,4,16})
    void repeatedPollsNeverQueueMoreThanConfiguredWorkers(int workers) throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(workers);
        var release=new java.util.concurrent.CountDownLatch(1);
        TraceLinkDetectionWorker worker=org.mockito.Mockito.mock();
        org.mockito.Mockito.when(worker.runOnce()).thenAnswer(call->{ entered.countDown(); release.await(); return false; });
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try(var poller=new TraceLinkRuntimeConfiguration.Poller(worker,org.mockito.Mockito.mock(),org.mockito.Mockito.mock(),registry,workers)) {
            for(int i=0;i<100;i++) poller.detect();
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(workers,registry.get("telecom.trace_link.workers.active").gauge().value());
            org.mockito.Mockito.verify(worker,org.mockito.Mockito.times(workers)).runOnce();
            release.countDown();
        } finally { release.countDown(); }
    }
    @Test void configuredWorkerCountMustRemainBounded() {
        for(int invalid:new int[]{0,-1,17}) assertThrows(IllegalArgumentException.class,()->
                new TraceLinkRuntimeConfiguration.Poller(org.mockito.Mockito.mock(),org.mockito.Mockito.mock(),
                        org.mockito.Mockito.mock(),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),invalid));
    }
    @Test void publisherUsesConfiguredTopicWithEpisodePartitionKey() throws Exception {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka=org.mockito.Mockito.mock();
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.support.SqlArrayValue.class),org.mockito.ArgumentMatchers.any(java.util.UUID.class)))
                .thenReturn(java.util.List.of(java.util.Map.of("id","d","episode_id","e","payload","{}")),java.util.List.of());
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        new TraceLinkDeliveryPublisher(jdbc,kafka,"custom.trace.link").publish();
        org.mockito.Mockito.verify(kafka).send("custom.trace.link","e","{}");
    }
}
