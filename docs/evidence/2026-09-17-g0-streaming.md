# Revision 3 Day 03: G0 streaming/source semantics

Date: 2026-09-17 (Europe/Chisinau). Branch: `feature/streaming-contracts`.
Commit before task: `d2e2bc433e192cc92c187f81392e9266040f4fb9`.
Result: **Streaming/source-semantics portion of G0: PASS** (automated acceptance).
Preflight: clean working tree; `git fetch origin` and
`git pull --ff-only origin feature/streaming-contracts` confirmed that exact HEAD.

The local Revision 3 common guide and Ion plan (15 September, Day 03/04 page 7)
were checked against current contracts. This record covers automated streaming
acceptance; Sergiu/Denis fixture signoff and the full team gate remain pending.

## Inventory and interpretation

`contracts/topology/demo-scopes-v2.json`, topologyVersion **`2-baseline`**, is
unchanged. No duplicate fixture or topology files were introduced.

| Scope | Service | Authoritative SERVICE source | NODE ID -> authoritative source |
| --- | --- | --- | --- |
| VOLTE-MD-CENTRAL | VOLTE | VOLTE-ADAPTER | IMS-A -> IMS-A; TRANSPORT-A -> TRANSPORT-A |
| SMS-MD-ROUTE-A | SMS | SMS-ADAPTER | SMSC-A -> SMSC-A; TRANSPORT-A -> TRANSPORT-A |

`TopologyCatalog` is the single Java parser and immutable interpretation.
`BoundaryConfiguration` injects the same catalog into `ObservationValidator` and
`ScopeRegistry`. Scope/node records, dependency lists and scope maps are immutable.
The processor boundary validates schema, time, counters and authority before
returning the registry's canonical scope. There are no writes or Kafka receipts.

SERVICE must match the scope's service and serviceSourceId. NODE must match a
declared node/source pair in that scope. HEARTBEAT may use that scope's declared
service source or any declared node source; it proves activity only. Unknown
scopes throw, including registry predicates. Unknown publishers fail authority.
Shared transport is authorized separately in each explicitly declared scope.

Missing resources, missing/blank versions, incomplete/wrongly typed fields,
unknown fields, duplicate scopes, duplicate nodes and ambiguous node reporters
fail startup. One reporter cannot describe two nodes in the same scope because
the natural observation key contains source/scope/kind/minute, not node ID.
Tests also use opaque scope and reporter IDs with nodeId != sourceId.

## G0 fixture acceptance

All canonical paths below are under `contracts/fixtures/observations/`.
All five observations cover `[2026-09-15T08:00:00Z, 2026-09-15T08:01:00Z)`.

| Set | Fixture | eventId |
| --- | --- | --- |
| Voice service | normal-volte.json | de4a242d-9a0b-5e4b-824c-26cd56b71ed5 |
| Voice node | normal-ims.json | 2e26efef-c876-5ed5-b758-c5ce76d13c30 |
| Voice transport | normal-transport.json | c727637d-3729-566a-b338-41927338771e |
| SMS service | normal-sms.json | 88b487c9-3231-5050-b58c-cc78984506eb |
| SMS node | normal-smsc.json | 031c8c0e-7089-531b-9d66-4dde5b3c4e2f |

`ObservationInputTest.g0ServiceAndNodeEvidenceHaveMatchingScopeAndMinute` proves
schema/semantic acceptance, authoritative ownership, identical scope resolution
and aligned minutes for both sets. It additionally derives SMS transport evidence
from the existing transport fixture in memory, setting scopeId to SMS-MD-ROUTE-A
and a distinct eventId `00000000-0000-4000-8000-000000000003`.
These fixtures are approved by automated source-contract checks, not claimed as
teammate signoff or proof of live delivery.

Negative tests reject SMS-ADAPTER claiming VoLTE; VOLTE-ADAPTER claiming SMS;
SMSC-A in the VoLTE scope; IMS-A in the SMS scope; SMSC-A node with IMS-A source;
IMS-A node with TRANSPORT-A source; service/node publisher role swaps; unknown
scope/source/node; and unknown or other-scope heartbeat publishers. Boundary
mutation tests verify authority failures are not merely schema failures. All six
valid scope/source heartbeat combinations pass. Catalog tests exercise malformed
inventory, duplicates, defensive copies and custom opaque mappings.

## Partition-key decision

`telecom.observations.v2` record key: exact case-sensitive **scopeId as UTF-8**.
This co-locates SERVICE/NODE/HEARTBEAT evidence for future per-scope minute windows.
It is separate from event ID and natural interval identity. Broker append order
does not imply event-time order. Partition-count/partitioner changes need a future
coordinated transition. See [the contract](../../contracts/README.md#kafka-observation-partition-key).
No producer, consumer, KafkaTemplate or KafkaListener was added.

## Reproducible verification

Commands run from the repository root in PowerShell:

```powershell
.\.venv\Scripts\python.exe scripts/check-contracts.py
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
$env:JAVA_HOME='C:\OrangeSystems\Program\.tools\jdk21\jdk-21.0.12.1+1'
$env:MAVEN_USER_HOME='C:\OrangeSystems\Program\.maven-wrapper-home'
$env:MAVEN_OPTS='-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2'
.\mvnw.cmd test
.\mvnw.cmd package -DskipTests
.\.venv\Scripts\python.exe scripts/check-streaming-smoke.py --java 'C:\OrangeSystems\Program\.tools\jdk21\jdk-21.0.12.1+1\bin\java.exe'
git diff --check
```

Python results: **12 independent observation fixtures pass**, and
**12 unittest tests pass** with no failures.

Java: **BUILD SUCCESS, 118 tests, 0 failures, 0 errors, 0 skipped**.

| Suite | Passed |
| --- | ---: |
| ObservationValidatorTest | 29 |
| TopologyCatalogTest | 29 |
| ObservationGeneratorTest | 3 |
| Generator HealthProbeTest | 1 |
| ScopeRegistryTest | 28 |
| ObservationInputTest | 26 |
| Processor HealthProbeTest | 2 |

Packaging: **BUILD SUCCESS**, all reactor modules, using the tested source.
Local ignored logs: `target/day03-maven-test.log`,
`target/day03-maven-package.log`; detailed results in each module's
`target/surefire-reports/`. Java health tests used real embedded KRaft brokers
and verified readiness UP then DOWN after broker shutdown while liveness stayed UP.

Packaged smoke: **3 PASS checks, exit 0**. Two previews produced the same ten
payloads; Python accepted all ten and classified all ten retries as duplicates.
Both packaged services started with Kafka unavailable and returned liveness
HTTP 200/UP and readiness HTTP 503/DOWN. Logs/preview are in ignored
`target/streaming-smoke/`. `git diff --check` passed without whitespace errors.

## Docker and database review

```powershell
& 'C:\Program Files\Git\bin\bash.exe' ./scripts/up
& 'C:\Program Files\Git\bin\bash.exe' ./scripts/verify
docker compose exec -T kafka /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic telecom.observations.v2
```

Both scripts exited **0**. Existing PostgreSQL/Kafka containers were reused;
kafka-init ran successfully. Verification found PostgreSQL ready, schemas
`incidents`/`processing`, and topics `telecom.observations.v2`,
`telecom.detections.v1`, `telecom.telemetry.v1`, `telecom.detections.dlq.v1`.
The observations topic has **3 partitions, replication factor 1,
retention.ms=86400000**; each partition has leader/replica/ISR broker 1.
No volumes were removed, no grants changed, and no unrelated data was reset.

Read-only live review used:

```powershell
docker compose exec -T postgres psql -U telecom_admin -d telecom -P pager=off -c "SELECT datname, pg_get_userbyid(datdba) AS owner, datacl FROM pg_database WHERE NOT datistemplate ORDER BY datname; SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname NOT LIKE 'pg_%' ORDER BY rolname; SELECT nspname, pg_get_userbyid(nspowner) AS owner, nspacl FROM pg_namespace WHERE nspname IN ('processing','incidents','public') ORDER BY nspname; SELECT r.rolname, n.nspname, has_schema_privilege(r.rolname,n.oid,'USAGE') AS usage, has_schema_privilege(r.rolname,n.oid,'CREATE') AS create_objects FROM pg_roles r CROSS JOIN pg_namespace n WHERE r.rolname IN ('processing_app','incident_app') AND n.nspname IN ('processing','incidents','public') ORDER BY r.rolname,n.nspname;"
```

Observed non-template databases: `postgres`, `telecom`, both owned by
`telecom_admin`. Application login roles: `processing_app`, `incident_app`;
neither is superuser/CREATEDB/CREATEROLE. Each owns its respective schema with
USAGE/CREATE and has neither privilege on the other application schema. PUBLIC
retains CONNECT/TEMP on `telecom`, and USAGE on public schema. Runtime roles
cannot CREATE in public schema, but can CREATE in their own schemas.

**Full team G0 is not complete.** Revision 3 requires `processing_db`,
`incidents_db`, `keycloak_db`, separated credentials, migrator/runtime separation,
restricted cross-database access and identity integration. None of those three
databases or migrator/Keycloak roles exists in this stack. Compose/init and
`.env.example` still configure `telecom?currentSchema=processing/incidents` and
schema-owner runtime roles. The incident runtime name is singular `incident_app`,
where the target ADR specifies `incidents_app`. `scripts/verify` checks the current
starter only; its PASS does not prove Revision 3 database isolation or real login.

Shared follow-up belongs to M5 Bradu Stanislav (infrastructure/grants) with M2
Moroz Denis (database/identity boundary), per the owner map. Keycloak/proxy and
canonical identity URL/login evidence also remain outside this branch's streaming
acceptance. No Compose/init/credential/migration changes were made in Day 03.

## Limits and next boundary

Inventory is loaded at startup with no hot reload. Source authority validates
declared payload identities; it does not authenticate a network publisher.
Kafka access is readiness metadata only. There is no end-to-end observation flow,
persistence, rejection outbox, KPI finalization, detector or episode processing.

Revision 3 Day 04 is `V001__observation_state.sql` plus `IngestionService`:
receipts, interval buckets and source state in processing_db; atomic receipt and
interval updates; acknowledgment after commit; rejected-output outbox preserving
event IDs/reasons. Prove retries do not double-count, transient DB failures remain
retryable, and malformed input is identifiable. Reserve migration numbers with
Sergiu and hand off a stable persisted-window boundary. None is implemented here.
