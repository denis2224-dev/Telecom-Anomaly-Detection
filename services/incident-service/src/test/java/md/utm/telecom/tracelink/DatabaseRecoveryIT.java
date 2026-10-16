package md.utm.telecom.tracelink;

import java.sql.*;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import static org.junit.jupiter.api.Assertions.*;

/** Backup/restore is performed only between new disposable databases. */
class DatabaseRecoveryIT {
 @Test void realCustomDumpRestoresImmutableHistorySchemaAndPermissions() throws Exception {
  try(var source=new PostgreSQLContainer("postgres:16.4-alpine").withDatabaseName("incidents_db").withInitScript("db/context-test-init.sql");
      var target=new PostgreSQLContainer("postgres:16.4-alpine").withDatabaseName("incidents_db")){
   source.start();target.start();
   Flyway.configure().dataSource(source.getJdbcUrl(),source.getUsername(),source.getPassword()).defaultSchema("app").schemas("app").locations("classpath:db/migration").load().migrate();
   try(var db=DriverManager.getConnection(source.getJdbcUrl(),source.getUsername(),source.getPassword());var statement=db.createStatement()){
    statement.execute("INSERT INTO app.trace_dataset(dataset_id,generator_version,seed,synthetic,history_from,history_to_exclusive,source_resolution_seconds,timezone,manifest_sha256) VALUES ('recovery-fixture','fixture',42,true,'2026-09-10T09:00:00Z','2026-10-10T09:00:00Z',300,'UTC',repeat('a',64))");
    statement.execute("INSERT INTO app.trace_reference_window VALUES ('recovery-fixture','CITY','chi','2026-09-10T09:00:00Z','controlled-fixture.csv',1,repeat('b',64),'{\"voice_attempts\":100,\"voice_success\":90}')");
   }
   var dump=source.execInContainer("pg_dump","-U",source.getUsername(),"--format=custom","--no-owner","--file=/tmp/trace.dump","incidents_db");assertEquals(0,dump.getExitCode(),dump.getStderr());
   byte[] bytes=source.copyFileFromContainer("/tmp/trace.dump",input->input.readAllBytes());assertTrue(bytes.length>1000);
   target.copyFileToContainer(Transferable.of(bytes,0600),"/tmp/trace.dump");
   try(var db=DriverManager.getConnection(target.getJdbcUrl(),target.getUsername(),target.getPassword());var statement=db.createStatement()){statement.execute("CREATE ROLE incidents_app NOLOGIN");}
   var restored=target.execInContainer("pg_restore","-U",target.getUsername(),"--exit-on-error","--no-owner","--dbname=incidents_db","/tmp/trace.dump");assertEquals(0,restored.getExitCode(),restored.getStderr());
   var flyway=Flyway.configure().dataSource(target.getJdbcUrl(),target.getUsername(),target.getPassword()).defaultSchema("app").schemas("app").locations("classpath:db/migration").load();flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
   try(var db=DriverManager.getConnection(target.getJdbcUrl(),target.getUsername(),target.getPassword());var statement=db.createStatement()){
    statement.execute("SET ROLE incidents_app");
    try(var rows=statement.executeQuery("SELECT source_row_sha256,source_payload->>'voice_success' FROM app.trace_reference_window WHERE dataset_id='recovery-fixture'")){assertTrue(rows.next());assertEquals("b".repeat(64),rows.getString(1));assertEquals("90",rows.getString(2));assertFalse(rows.next());}
    assertThrows(SQLException.class,()->statement.execute("UPDATE app.trace_reference_window SET source_payload='{}'"));
   }
   java.nio.file.Files.writeString(java.nio.file.Path.of("target/restore-evidence.json"),new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("result","PASS","format","PostgreSQL custom archive","bytes",bytes.length,"population","controlled isolated fixture","fullArchiveRestore","PENDING","identitySecretsRestore","PENDING")));
  }
 }
}
