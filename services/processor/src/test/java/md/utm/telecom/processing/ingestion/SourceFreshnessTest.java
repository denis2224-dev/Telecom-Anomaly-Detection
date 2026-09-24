package md.utm.telecom.processing.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.processing.ObservationInput;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(SourceFreshnessTest.Config.class)
class SourceFreshnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE = "VOLTE-MD-CENTRAL";
    private static final String SOURCE = "VOLTE-ADAPTER";
    private static final Instant START = Instant.parse("2026-09-15T08:00:00Z");
    private static final Instant END = Instant.parse("2026-09-15T08:01:00Z");

    @Autowired IngestionService ingestion;
    @Autowired SourceFreshness freshness;
    @Autowired TestClock clock;
    @Autowired JdbcTemplate jdbc;
    private int offset;

    static class TestClock extends Clock {
        volatile Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({IngestionService.class, SourceFreshness.class, ScopeRegistry.class,
            PayloadCodec.class, ObservationInput.class, DetectionPolicy.class})
    static class Config {
        @Bean DataSource dataSource() {
            Flyway.configure().dataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator")
                    .defaultSchema("app").schemas("app").createSchemas(false).load().migrate();
            return new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_app", "test-runtime");
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TopologyCatalog topology() throws Exception { return TopologyCatalog.load(); }
        @Bean ObservationValidator validator(TopologyCatalog topology) throws Exception { return new ObservationValidator(topology); }
        @Bean TestClock clock() { return new TestClock(); }
    }

    private JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
    }

    @BeforeEach
    @AfterEach
    void clear() {
        owner().update("DELETE FROM app.feature_outbox");
        jdbc.update("DELETE FROM app.source_state");
        jdbc.update("DELETE FROM app.observation_receipt");
        jdbc.update("DELETE FROM app.interval_bucket");
        jdbc.update("DELETE FROM app.rejection_outbox");
        clock.now = START;
    }

    private static ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) ObservationValidator.resource("fixtures/observations/" + name + ".json", MAPPER);
    }

    private void ingest(ObjectNode event) {
        var result = ingestion.ingest(new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8),
                event.get("scopeId").asText(), "telecom.observations.v2", 0, ++offset));
        assertEquals(IngestionResult.Status.ACCEPTED, result.status());
    }

    @Test
    void expectedSourceWithNoKnownActivityIsNeverSeen() {
        assertEquals(SourceFreshness.ActivityFreshness.NEVER_SEEN, freshness.activityFreshness(SCOPE, SOURCE));
    }

    @Test
    void recentServiceActivityIsFresh() throws Exception {
        var event = fixture("normal-volte");
        clock.now = Instant.parse(event.get("emittedAt").asText()).plusSeconds(30);
        ingest(event);

        assertEquals(SourceFreshness.ActivityFreshness.FRESH, freshness.activityFreshness(SCOPE, SOURCE));
    }

    @Test
    void recentNodeActivityIsFresh() throws Exception {
        var ims = fixture("normal-ims");
        clock.now = Instant.parse(ims.get("emittedAt").asText()).plusSeconds(20);
        ingest(ims);

        assertEquals(SourceFreshness.ActivityFreshness.FRESH, freshness.activityFreshness(SCOPE, "IMS-A"));
    }

    @Test
    void recentHeartbeatIsFreshActivity() throws Exception {
        var heartbeat = fixture("heartbeat");
        clock.now = Instant.parse(heartbeat.get("emittedAt").asText()).plusSeconds(10);
        ingest(heartbeat);

        assertEquals(SourceFreshness.ActivityFreshness.FRESH, freshness.activityFreshness(SCOPE, SOURCE));
    }

    @Test
    void heartbeatAloneDoesNotMakeExpectedIntervalCoverageComplete() throws Exception {
        var heartbeat = fixture("heartbeat");
        ingest(heartbeat);

        // Even though heartbeat proves recent source activity:
        assertEquals(SourceFreshness.ActivityFreshness.FRESH, freshness.activityFreshness(SCOPE, SOURCE));

        // It does NOT satisfy SERVICE/NODE interval coverage for that minute:
        clock.now = END.plusSeconds(5); // Before allowed lateness
        assertEquals(SourceFreshness.IntervalCoverage.INCOMPLETE, freshness.intervalCoverage(SCOPE, SOURCE, START, END));

        clock.now = END.plusSeconds(15); // After allowed lateness (10s)
        assertEquals(SourceFreshness.IntervalCoverage.MISSING, freshness.intervalCoverage(SCOPE, SOURCE, START, END));
    }

    @Test
    void activityExactlyAtStaleBoundaryIsFresh() throws Exception {
        var event = fixture("normal-volte");
        Instant emitted = Instant.parse(event.get("emittedAt").asText());
        ingest(event);

        // Exactly 90 seconds after emittedAt is within threshold (age <= 90s)
        clock.now = emitted.plusSeconds(90);
        assertEquals(SourceFreshness.ActivityFreshness.FRESH, freshness.activityFreshness(SCOPE, SOURCE));
    }

    @Test
    void activityJustBeyondStaleAfterSecIsStale() throws Exception {
        var event = fixture("normal-volte");
        Instant emitted = Instant.parse(event.get("emittedAt").asText());
        ingest(event);

        // 91 seconds after emittedAt is beyond threshold (age > 90s)
        clock.now = emitted.plusSeconds(91);
        assertEquals(SourceFreshness.ActivityFreshness.STALE, freshness.activityFreshness(SCOPE, SOURCE));
    }

    @Test
    void replayOldActivityCannotMoveFreshnessForwardIncorrectly() throws Exception {
        var first = fixture("normal-volte");
        var newer = fixture("normal-volte").put("eventId", UUID.randomUUID().toString())
                .put("windowStart", "2026-09-15T08:01:00Z").put("windowEnd", "2026-09-15T08:02:00Z")
                .put("emittedAt", "2026-09-15T08:02:00Z");

        ingest(newer); // Newer event sets source_state to 08:02:00Z
        ingest(first); // Old event replay cannot move state backwards

        // Check freshness evaluated from the newer event time
        clock.now = Instant.parse("2026-09-15T08:03:00Z"); // 60s after 08:02:00Z -> FRESH
        assertEquals(SourceFreshness.ActivityFreshness.FRESH, freshness.activityFreshness(SCOPE, SOURCE));

        clock.now = Instant.parse("2026-09-15T08:03:31Z"); // 91s after 08:02:00Z -> STALE
        assertEquals(SourceFreshness.ActivityFreshness.STALE, freshness.activityFreshness(SCOPE, SOURCE));
    }

    @Test
    void noObservationForExpectedIntervalByEndPlusLatenessIsMissing() {
        // windowEnd is 08:01:00Z; allowedLateness is 10s -> deadline is 08:01:10Z
        clock.now = END.plusSeconds(10);
        assertEquals(SourceFreshness.IntervalCoverage.MISSING, freshness.intervalCoverage(SCOPE, SOURCE, START, END));
    }

    @Test
    void beforeEndPlusAllowedLatenessMustNotPrematurelyMarkMissing() {
        // Before 08:01:10Z, absence is not yet declared MISSING
        clock.now = END.plusSeconds(9);
        assertEquals(SourceFreshness.IntervalCoverage.INCOMPLETE, freshness.intervalCoverage(SCOPE, SOURCE, START, END));
    }
}
