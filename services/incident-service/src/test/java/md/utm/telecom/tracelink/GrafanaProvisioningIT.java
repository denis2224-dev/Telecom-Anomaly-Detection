package md.utm.telecom.tracelink;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Grafana/PostgreSQL, empty controlled history. Populated archive parity is a separate gate. */
class GrafanaProvisioningIT {
 @Test @Timeout(180) void allReferencePanelsExecuteWithRestrictedCredentials() throws Exception {
  Path root=Path.of("").toAbsolutePath();while(!Files.isDirectory(root.resolve("infra/observability")))root=root.getParent();
  Path files=root.resolve("infra/observability/grafana");var json=new ObjectMapper();
  String password=UUID.randomUUID().toString();
  try(var network=Network.newNetwork();var pg=new PostgreSQLContainer("postgres:16.4-alpine").withDatabaseName("incidents_db").withInitScript("db/context-test-init.sql").withNetwork(network).withNetworkAliases("trace-pg")){
   pg.start();
   Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).defaultSchema("app").schemas("app").locations("classpath:db/migration").load().migrate();
   try(var db=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var statement=db.createStatement()){
    statement.execute(Files.readString(files.resolve("readonly-role.sql")).replace("\\set ON_ERROR_STOP on", ""));
    statement.execute("ALTER ROLE trace_grafana_reader PASSWORD '"+password+"'");
    statement.execute("INSERT INTO app.trace_dataset(dataset_id,generator_version,seed,synthetic,history_from,history_to_exclusive,source_resolution_seconds,timezone,manifest_sha256) VALUES ('grafana-fixture','fixture',42,true,'2026-09-10T09:00:00Z','2026-10-10T09:00:00Z',300,'UTC','"+"a".repeat(64)+"')");
    statement.execute("INSERT INTO app.trace_catalogue(catalogue_version,dataset_id,topology_version,active,valid_from) VALUES ('grafana-fixture','grafana-fixture','fixture',false,'2026-09-10T09:00:00Z')");
    statement.execute("INSERT INTO app.trace_inventory_city VALUES ('grafana-fixture','chi','Chișinău',47,28,0.1,true,'2026-09-10T09:00:00Z',NULL,true),('grafana-fixture','hin','Hîncești',47,28,0.1,true,'2026-09-10T09:00:00Z',NULL,true)");
    statement.execute("INSERT INTO app.trace_inventory_link VALUES ('grafana-fixture','chi-hin','chi','hin','FIBER',10,true,'2026-09-10T09:00:00Z',NULL,true)");
    statement.execute("INSERT INTO app.trace_link_direction VALUES ('grafana-fixture','chi-hin','a-to-b',NULL,NULL,10,'2026-09-10T09:00:00Z',NULL,'controlled fixture')");
    statement.execute("INSERT INTO app.trace_partner VALUES ('grafana-fixture','RO-DEMO-01','RO','in',true,'2026-09-10T09:00:00Z',NULL,true),('grafana-fixture','RO-DEMO-01','RO','out',true,'2026-09-10T09:00:00Z',NULL,true)");
    // Marker exists only in this disposable database; no archive population is asserted.
    statement.execute("INSERT INTO app.trace_reference_import(dataset_id,manifest_sha256,native_rows) VALUES ('grafana-fixture','"+"a".repeat(64)+"',362880)");
   }
   try(var reader=DriverManager.getConnection(pg.getJdbcUrl(),"trace_grafana_reader",password);var statement=reader.createStatement()){
    assertThrows(SQLException.class,()->statement.execute("SELECT * FROM app.analysts"));
    assertThrows(SQLException.class,()->statement.execute("CREATE TABLE app.forbidden_reader_write(id int)"));
    assertThrows(SQLException.class,()->statement.execute("UPDATE app.trace_reference_window SET source_payload='{}'"));
   }
   try(var grafana=new GenericContainer<>("grafana/grafana:11.2.0").withNetwork(network).withExposedPorts(3000)
    .withEnv("GF_SECURITY_ADMIN_PASSWORD",password).withEnv("GF_AUTH_ANONYMOUS_ENABLED","false")
    .withEnv("TRACE_GRAFANA_PG_HOST","trace-pg").withEnv("TRACE_GRAFANA_PG_PORT","5432").withEnv("TRACE_GRAFANA_PG_PASSWORD",password).withEnv("TRACE_GRAFANA_PG_SSLMODE","disable")
    .withCopyFileToContainer(MountableFile.forHostPath(files.resolve("provisioning")),"/etc/grafana/provisioning")
    .withCopyFileToContainer(MountableFile.forHostPath(files.resolve("dashboards")),"/etc/grafana/trace-link-dashboards")
    .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)))){
    grafana.start();String base="http://"+grafana.getHost()+":"+grafana.getMappedPort(3000);
    String auth="Basic "+Base64.getEncoder().encodeToString(("admin:"+password).getBytes(java.nio.charset.StandardCharsets.UTF_8));var http=HttpClient.newHttpClient();
    var provisioned=http.send(HttpRequest.newBuilder(URI.create(base+"/api/dashboards/uid/trace-link-overview")).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(200,provisioned.statusCode());var dashboard=json.readTree(provisioned.body()).required("dashboard");int panels=0,queries=0;
    var variables=Map.of("datasetId","grafana-fixture","cityId","chi","linkDirectionId","chi-hin:a-to-b","countryId","RO","roamingDirection","both","partnerDirectionId","RO-DEMO-01:out","resolution","15m","domain","all");
    for(var panel:dashboard.required("panels")){
     if(panel.path("type").asText().equals("row"))continue;panels++;
     for(var target:panel.required("targets")){
      String sql=target.required("rawSql").asText();for(var variable:variables.entrySet())sql=sql.replace("${"+variable.getKey()+":sqlstring}","'"+variable.getValue()+"'");
      sql=sql.replace("${__from}","1789030800000").replace("${__to}","1789032600000");
      execute(http,base,auth,json,sql,panel.path("title").asText());queries++;
      String all=sql.replace("'chi'","'all'").replace("'chi-hin:a-to-b'","'all'").replace("'RO-DEMO-01:out'","'all'").replace("'RO'","'all'");
      execute(http,base,auth,json,all,panel.path("title").asText()+" all separate populations");
     }
    }
    int variableQueries=0;
    for(var variable:dashboard.required("templating").required("list"))if(variable.path("type").asText().equals("query")){
     String sql=variable.required("query").asText();for(var item:variables.entrySet())sql=sql.replace("${"+item.getKey()+":sqlstring}","'"+item.getValue()+"'");
     execute(http,base,auth,json,sql,"variable "+variable.required("name").asText());variableQueries++;
    }
    assertEquals(14,panels);assertEquals(4,variableQueries);assertTrue(queries>=18);
    Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/grafana-query-evidence.json"),json.writeValueAsString(Map.of("image","grafana/grafana:11.2.0","panels",panels,"targets",queries,"contexts",2,"variableQueries",variableQueries,"result","PASS","population","empty controlled database","fullArchiveParity","PENDING")));
   }
  }
 }
 private static void execute(HttpClient http,String base,String auth,ObjectMapper json,String sql,String label) throws Exception {
  var body=Map.of("from","1789030800000","to","1789032600000","queries",List.of(Map.of("refId","A","datasource",Map.of("uid","trace-link-postgres","type","postgres"),"rawSql",sql,"format","table","rawQuery",true,"intervalMs",900000,"maxDataPoints",100)));
  var response=http.send(HttpRequest.newBuilder(URI.create(base+"/api/ds/query")).header("Authorization",auth).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(200,response.statusCode(),"Grafana query failed: "+label);var result=json.readTree(response.body()).required("results").required("A");assertFalse(result.has("error"),result.toString());
 }

}
