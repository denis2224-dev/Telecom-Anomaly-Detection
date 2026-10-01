package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import md.utm.telecom.observation.ObservationValidator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class DetectionMigrationTest {
    @Test void upgradeBackfillsJobsWithoutChangingExistingEpisodeOrEvidence() throws Exception {
        var admin = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("postgres"),"test_admin","test-admin"));
        admin.execute("CREATE DATABASE detection_upgrade OWNER processing_migrator");
        admin.execute("GRANT CONNECT ON DATABASE detection_upgrade TO processing_app");
        var owner = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("detection_upgrade"),"processing_migrator","test-migrator"));
        owner.execute("CREATE SCHEMA app");
        owner.execute("GRANT USAGE ON SCHEMA app TO processing_app");
        Flyway.configure().dataSource(PostgresFixture.url("detection_upgrade"),"processing_migrator","test-migrator")
                .defaultSchema("app").schemas("app").createSchemas(false).target("5").load().migrate();
        var payload = ObservationValidator.resource("fixtures/features/voice-worked-v2.json",new ObjectMapper());
        for (int minute = 0; minute < 2; minute++) {
            owner.update("""
                    INSERT INTO app.interval_bucket(scope_id,window_start,window_end,accepted_input_count,created_at,updated_at)
                    VALUES ('VOLTE-MD-CENTRAL','2026-09-15 08:00Z'::timestamptz + ? * interval '1 minute',
                        '2026-09-15 08:01Z'::timestamptz + ? * interval '1 minute',1,now(),now())
                    """,minute,minute);
            owner.update("""
                    INSERT INTO app.feature_outbox(window_id,scope_id,window_start,window_end,feature_version,payload,payload_hash,intended_topic,kafka_key,created_at)
                    VALUES (?, 'VOLTE-MD-CENTRAL','2026-09-15 08:00Z'::timestamptz + ? * interval '1 minute',
                        '2026-09-15 08:01Z'::timestamptz + ? * interval '1 minute',2,?::jsonb,?, 'telecom.kpis.v2','VOLTE-MD-CENTRAL',now())
                    """,String.valueOf(minute+1).repeat(64),minute,minute,payload.toString(),"a".repeat(64));
        }
        owner.update("INSERT INTO app.voice_evaluated_window(window_id) VALUES (?)","1".repeat(64));
        owner.update("INSERT INTO app.voice_episode_state VALUES ('VOLTE-MD-CENTRAL','{\"active\":true,\"sequence\":7}'::jsonb)");
        owner.update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES (?, 'telecom.kpis.v2','VOLTE-MD-CENTRAL',?::jsonb)","1".repeat(64),payload.toString());
        var episodes = owner.queryForList("SELECT * FROM app.voice_episode_state");
        var output = owner.queryForList("SELECT id,topic,kafka_key,payload,created_at,published_at FROM app.voice_delivery");
        var evaluated = owner.queryForList("SELECT * FROM app.voice_evaluated_window");
        var features = owner.queryForList("SELECT * FROM app.feature_outbox ORDER BY window_id");
        var flyway = Flyway.configure().dataSource(PostgresFixture.url("detection_upgrade"),"processing_migrator","test-migrator")
                .defaultSchema("app").schemas("app").createSchemas(false).load();
        flyway.migrate(); flyway.validate();
        assertEquals(episodes,owner.queryForList("SELECT * FROM app.voice_episode_state"));
        assertEquals(output,owner.queryForList("SELECT id,topic,kafka_key,payload,created_at,published_at FROM app.voice_delivery"));
        assertEquals(evaluated,owner.queryForList("SELECT * FROM app.voice_evaluated_window"));
        assertEquals(features,owner.queryForList("SELECT * FROM app.feature_outbox ORDER BY window_id"));
        assertEquals(2,owner.queryForObject("SELECT count(*) FROM app.detection_job",Integer.class));
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM app.detection_job WHERE completed_at IS NOT NULL",Integer.class));
        assertEquals(owner.queryForObject("SELECT evaluated_at FROM app.voice_evaluated_window",java.sql.Timestamp.class),
                owner.queryForObject("SELECT completed_at FROM app.detection_job WHERE completed_at IS NOT NULL",java.sql.Timestamp.class));
        var runtime = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("detection_upgrade"),"processing_app","test-runtime"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.update("UPDATE app.voice_delivery SET payload='{}'::jsonb"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.execute("CREATE TABLE app.forbidden(id int)"));
    }
}
