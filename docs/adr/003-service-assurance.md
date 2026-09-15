# ADR 003: Service-assurance infrastructure target and safe transition

Date: 2026-09-15

Owner: Bradu Stanislav (DevOps, identity and observability)

Status: Accepted as the Day 01 infrastructure target; runtime provisioning remains pending Day 02.

## Context

Revision 3 changes the product boundary from subscriber-risk processing to
customer-facing telecom service assurance. The required core scenarios are VoLTE
call-setup degradation and SMS delivery delay/backlog, with normal and telemetry-gap
controls. This decision freezes the deployment target without treating an edit to a
Compose file as a completed runtime migration.

The common guide requires a private streaming boundary, one public browser origin,
three logical PostgreSQL databases, and versioned Kafka topics. Infrastructure
health alone is not service health: the later observability work must independently
measure source freshness, finalization/detector lag, delivery backlog and incident
episode delivery.

## Inventory at the Day 01 boundary

| Area | Current state checked on 2026-09-15 | Target / unresolved work | Owner |
| --- | --- | --- | --- |
| Docker tooling | Docker Engine 29.3.1 and Docker Compose v5.1.1 are installed. No telecom-named Docker volumes were listed. | Do not infer a fresh volume from that check alone; preserve a discovered existing volume. | Stanislav |
| Java tooling | `java -version` did not resolve a Java runtime on this host. | Java 21 is required before Java service integration evidence. | Ion / Sergiu |
| PostgreSQL | Compose pins `postgres:16.4-alpine`, retains `postgres-data`, and currently bootstraps the legacy `telecom` database with two schemas. | Keep PostgreSQL major 16; add `processing_db`, `incidents_db` and `keycloak_db` with isolated runtime/migrator roles. Retain `telecom` until a reviewed migration. | Stanislav; Ion/Denis consume their databases |
| Kafka | Compose pins Bitnami Kafka 3.7.1, retains `kafka-data`, and currently creates Revision 1/2 topics. | Create the v2 service-assurance topic set beside v1; producers and consumers are not implemented by this ADR. | Stanislav; Ion/Sergiu agree semantics |
| Runnable applications | Generator and processor have Java source/health boundaries, but no Compose images. ML service, incident service, dashboard, Keycloak, proxy and monitoring deployment artifacts are not present. | Add only owner-supplied runnable artifacts in later tasks. | Respective owners |

The volume check is read-only. No container was started, stopped, recreated, or
deleted while taking this inventory.

## Decision

### Network and port registry

`telecom-private` remains the internal single-host Docker network. PostgreSQL and
Kafka host ports stay bound to `127.0.0.1`; application services must not publish
their own ports when added. NGINX will be the only public entry point.

| Reservation | Local address / internal address | Owner | Current state |
| --- | --- | --- | --- |
| Public origin and canonical hostname | `http://telecom.test:8080` | Stanislav + David + Denis | Reserved; proxy/host alias comes on Day 03. |
| Event generator | `event-generator:8081` | Ion | Java boundary exists; no Compose image. |
| Incident service | `incident-service:8082` | Denis | Reserved; no Compose artifact. |
| Processor | `processor:8083` | Ion + Sergiu | Java boundary exists; no Compose image. |
| ML service | `ml-service:8000` | Sergiu | Reserved; no Compose artifact. |
| PostgreSQL | `127.0.0.1:5432` / `postgres:5432` | Stanislav | Current infrastructure service. |
| Kafka | `127.0.0.1:9094` / `kafka:9092` | Stanislav | Current infrastructure service. |

The canonical issuer will be
`http://telecom.test:8080/auth/realms/telecom`. Browser and backend must use that
same issuer. Identity realm/client provisioning, browser login and proxy routing are
explicitly Day 03 work and are not claimed here.

### PostgreSQL boundary

PostgreSQL 16 remains a single local server with one preserved named volume. The
target uses separate logical databases, which isolate access but not host failure or
capacity:

| Database | Writer | Target runtime role | Target migration/owner role |
| --- | --- | --- | --- |
| `processing_db` | processor | `processing_app` | `processing_migrator` |
| `incidents_db` | incident service | `incidents_app` | `incidents_migrator` |
| `keycloak_db` | Keycloak | `keycloak_app` | Keycloak-managed schema |

Runtime roles will receive DML only in their own database. `PUBLIC CONNECT`, runtime
DDL, and cross-database access are denied as part of Day 02 acceptance. The existing
`telecom` bootstrap database and its schema credentials are legacy transition state:
they are neither deleted nor presented as the target design.

### Kafka boundary

The initialization job keeps all Revision 1/2 topics intact and creates these
Revision 3 topics with the configured partition count (default three):

| Topic | Retention | Purpose | Owner |
| --- | --- | --- | --- |
| `telecom.observations.v2` | 24 hours | Authoritative service/node/heartbeat input | Ion |
| `telecom.kpis.v2` | 7 days | Finalized KPI/feature output | Ion + Sergiu |
| `telecom.detections.v2` | 7 days | Ordered episode detections | Sergiu + Denis |
| `telecom.observations.invalid.v2` | 7 days | Rejected invalid input | Ion |
| `telecom.observations.late.v2` | 7 days | Late input retained for evidence | Ion |

Topic creation is idempotent. Retention and partitions are explicit, but have not
yet been verified against a running broker in this task. Keys, publication,
consumption, receipts, outboxes, finalization and replay are application work and
remain unimplemented.

### Configuration ownership

`.env.example` names every Day 01 variable owner in its section comments. Stanislav
owns infrastructure ports, database names and topic provisioning; Denis/David own
identity and browser configuration; Ion/Sergiu own the processor/generator and
topic semantics; Sergiu owns ML configuration. Secrets stay only in untracked local
environment files or a secret store. Example passwords are local placeholders, not
shared credentials.

## Consequences and next steps

- Day 02 must back up useful state, retain the PostgreSQL 16 volume and existing
  credentials, create only missing roles/databases, and prove runtime DML plus
  denied DDL/cross-database access.
- Day 03 must provision the Keycloak realm, `telecom-web` client, `app_roles`,
  synthetic identities, NGINX, and a `telecom.test` host alias without introducing
  a proxy-to-backend discovery readiness cycle.
- A fresh Compose initialization can create the reserved v2 topics. Existing
  volumes require an explicit inventory before any topic/configuration change;
  neither a Compose edit nor init-script edit upgrades persistent data.
- Resource limits and measured capacity are deliberately not invented here. The
  eventual container budgets and achieved rates are recorded from a reproducible
  measured run before release.

## Day 01 acceptance record

| Check | Result |
| --- | --- |
| Current/target boundary documented | Pass: this ADR distinguishes the legacy stack, target configuration and pending work. |
| Ports, hostname, databases and v2 topics reserved | Pass: documented and represented in `compose.yaml` / `.env.example`. |
| New environment-variable owner recorded | Pass: grouped ownership comments are in `.env.example` and summarized above. |
| Existing volume deleted or replaced | Not performed. |
| Runtime database grants, broker topics, OIDC or browser login verified | Pending later integration tasks; not claimed by this decision. |
