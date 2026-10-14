# Trace Link Operations and Administration Guide

Author: Bradu Stanislav (DevOps / Infrastructure / Release Engineering)  
Target: Platform Operations, SRE, and DevOps Teams  
Release Increment: Trace Link Release Candidate

## 1. System Architecture and Component Topology

The Trace Link service assurance platform is organized into isolated network tiers:

```text
[External Clients / Browsers]
           │
           ▼
[NGINX Proxy (8080)] ──┬──> [Keycloak (8080/auth)]
                       ├──> [Dashboard Web App]
                       ├──> [Incident Service (8082)]
                       └──> [Grafana (3000/grafana)]

[Kafka Broker (9092/9094)] <───> [Processor (8083)] <───> [ML Service (8090)]
                                      │
                                      ▼
                        [PostgreSQL 16.4 (5432)]
                          ├── processing_db
                          ├── incidents_db (app & reporting schemas)
                          └── keycloak_db
```

---

## 2. Security Boundaries and Database Roles

### Runtime Database Isolation
- **`processing_db`:** Owned by `processing_migrator`; runtime access restricted to `processing_app`. Stores raw observation state, 5-minute sliding windows, and transactional detector outbox.
- **`incidents_db`:** Owned by `incidents_migrator`; runtime access restricted to `incidents_app` for the `app` schema. Schema `reporting` exposes read-only views for Grafana.
- **`keycloak_db`:** Owned by `keycloak_app`; dedicated identity and realm storage.
- **`trace_link_reader`:** Dedicated least-privilege reporting role:
  ```sql
  GRANT CONNECT ON DATABASE incidents_db TO trace_link_reader;
  GRANT USAGE ON SCHEMA reporting TO trace_link_reader;
  GRANT SELECT ON ALL TABLES IN SCHEMA reporting TO trace_link_reader;
  REVOKE ALL ON SCHEMA app FROM trace_link_reader;
  ```
  This role has zero access to internal application tables, user credentials, analyst sessions, or Web Push subscription secrets.

---

## 3. Routine Operations and Maintenance

### Database Migrations
Migrations are managed via Flyway during service startup:
- Processor: `services/processor/src/main/resources/db/migration/` (V001 through V017)
- Incident Service: `services/incident-service/src/main/resources/db/migration/` (V001 through V011)

To verify migration status without starting full services:
```bash
./scripts/prepare-databases
```

### Checking Stack Health and Verification
Run the standard health and contract verification suite:
```bash
python3 scripts/check-contracts.py
python3 scripts/check-trace-link-grafana
python3 scripts/check-trace-link-parity.py --matrix
docker compose config --quiet
docker compose --profile observability config --quiet
```

### Performing Coordinated Backups
Run demo backup to archive database states and versioned configs:
```bash
./scripts/backup-demo --output-dir backups/maintenance-$(date +%Y%m%d)
```

To test restore into an isolated environment without overwriting active data:
```bash
./scripts/restore-demo --backup-dir backups/maintenance-$(date +%Y%m%d) --target-prefix isolated_ --dry-run
```

---

## 4. Observability and Monitoring

### Scrape Endpoints
- Event Generator: `http://localhost:8081/actuator/prometheus`
- Incident Service: `http://localhost:8082/actuator/prometheus`
- Processor: `http://localhost:8083/actuator/prometheus`
- Prometheus UI: `http://localhost:9090`
- Grafana Overview: `http://telecom.test:8080/grafana/d/trace-link-overview`

### Capacity Limits
- Processor maximum JVM heap: 384 MB
- Incident Service maximum JVM heap: 384 MB
- Kafka topic retention:
  - Raw observations (`telecom.observations.v2`): 24 hours
  - Derived KPIs & detections (`telecom.detections.trace-link.v1`): 7 days
- PostgreSQL connection pool limits:
  - Applications: Max 10 connections per service
  - Grafana reader (`trace-link-pg`): Max 5 connections
