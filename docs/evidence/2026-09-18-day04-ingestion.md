# Revision 3 Day 04: persist observations and reject safely

Implemented for 18 September 2026; integration verification executed on 20 September
2026. See [ingestion design](../streaming/ingestion.md) for SQL identity, transaction,
rejection, hashing and Day 05 handoff details.

## Preflight and merge

- Clean `feature/streaming-contracts` started at `8c87c852ca7ae0627223f6ece80c7cfebf40960a`.
- Fetched actual main: `b247f71bcc1ec994989f70a1f9a45c9dc8005167`.
- Divergence: 27 main-only commits, 4 feature-only commits.
- Merge base: `edcc7aa516a3b7b6c35973c4a438f87e3465ba42`.
- Normal main merge: `963ec6473e57dc216cbf9ade1c1251d6e7d1e159`, real author/committer
  timestamp `2026-09-20T22:54:49+03:00`.
- Resolved `scripts/verify` topic-list conflict and the modify/delete conflict for
  `tests/test_event_contract.py`. EventV1 remains retired; all active V2 topics and
  teammate infrastructure checks remain. The newly merged detection test imports
  existing `test_utils` instead of the retired EventV1 test module.
- Existing generator/topology tests and BaselineRegistry, DetectionPolicy,
  VoiceSetupRule, ML, incident/auth and dashboard implementations are preserved.
  No changes were made to those teammate implementation directories.

## Implementation

Processor V001 was unused. Added `V001__observation_state.sql`, removed `.gitkeep`,
and implemented `IngestionService`, immutable `ObservationDelivery`,
`IngestionResult`, `RejectionReason`, `PayloadCodec`, `ObservationListener` and
`KafkaIngestionConfiguration`. Shared validation now exposes typed categories via
`ObservationValidationException` while retaining the existing rules and messages.

POM additions use Spring Boot 3.5.16 dependency management: JDBC, PostgreSQL,
Flyway core/PostgreSQL support and PostgreSQL Testcontainers. Spring Kafka runtime
already comes from streaming-support; Spring Kafka Test was already present.
The policies/baselines/features/detections resource configuration remains intact.

Runtime uses `processing_app`; Flyway uses `processing_migrator`. Both exclusively
target `processing_db.app`. All four tables and their SQL uniqueness constraints,
window/pending indexes, runtime DML grants and migration-history protection passed
real PostgreSQL testing. Liveness stays process-only; readiness includes DB/Kafka.

Only a successful unique receipt insert increments a bucket and updates source
state. Eight simultaneous identical deliveries produced one ACCEPTED and seven
DUPLICATE results, one receipt, one bucket input and no rejection. Natural-key races
produced one accepted mutation and one durable conflict. Exact retries do not
change accepted state. Rejected deliveries have bounded raw evidence and technical
Kafka identity before ACK. Both later-write failure and deferred commit failure
leave no partial accepted state and no ACK. The real Kafka test proves no skipped
record after the normal framework retry budget, then successful recovery/offset
advancement after DB permission restoration.

## Final automated results

All final test runs had **zero failures, zero errors and zero skips**.

| Exact command | Result |
| --- | --- |
| `.\.venv\Scripts\python.exe scripts/check-contracts.py` | 12 observation fixtures; 4 detection/feature payloads; schemas, baseline and policy pass. |
| `.\.venv\Scripts\python.exe -m unittest discover -s tests -v` | 16 passed. |
| `.\.venv\Scripts\python.exe -m unittest discover -s services/ml-service/tests -v` | 9 passed. |
| `.\mvnw.cmd test` | 164 passed: streaming-support 58, generator 4, processor 102. Processor includes 28 real PostgreSQL/Kafka integration cases and 5 codec/delivery cases. |
| `.\mvnw.cmd package -DskipTests` | All reactor modules packaged successfully; tests intentionally not rerun by this packaging command. |
| `.\.venv\Scripts\python.exe scripts/check-streaming-smoke.py --java "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe"` | 3 checks passed: deterministic packaged preview, generator unavailable-Kafka probes, processor unavailable-Kafka probes. |
| `.\.venv\Scripts\python.exe scripts/check-contracts.py --batch target/streaming-smoke/preview.json` | 10 accepted, 0 duplicate observations. |
| `.\mvnw.cmd -f services/incident-service/pom.xml test` | 58 passed, including real PostgreSQL migration and auth regression. |
| `npm --prefix apps/dashboard ci` | Locked dependencies installed. |
| `npm --prefix apps/dashboard run build` | Production bundle built. |
| `npm --prefix apps/dashboard test` | 22 passed in 5 files. |
| `./scripts/prepare-databases` | Passed against the existing retained PostgreSQL volume. |
| `./scripts/up` | Passed, building and starting both Java containers plus existing infrastructure. |
| `./scripts/verify` | Passed DB/schema/table/role checks, Kafka topics, application readiness, Keycloak readiness and OIDC discovery. |
| `git diff --check` | Passed. |

Total automated test cases: **269** (164 reactor + 58 incident + 22 dashboard +
16 Python contract + 9 ML), plus contract, package, smoke and infrastructure checks.

## Environment and infrastructure evidence

Docker Desktop was installed but stopped; it was started. The existing PostgreSQL
volume lacked the target databases, so the current provisioner created missing
roles/databases without deleting existing state or changing working passwords.
Only missing local `.env` defaults were added; the missing Keycloak bootstrap
password was generated locally and never committed. The repository's existing
prepare-keycloak helper synchronized its generated client secret privately.

Windows Maven wrapper execution used the existing junction:
`MAVEN_USER_HOME=C:\OrangeSystems\Program\.maven-wrapper-home` with Java 21.
The upstream wrapper's PowerShell handling of a non-junction Maven home failed;
no Maven wrapper logic was changed. Linux Docker builds required checking out
`mvnw` with LF, now enforced by `.gitattributes`.

Git Bash startup used a temporary ignored `target/day04-bin/python3` shim that
executes the existing `.venv/Scripts/python.exe`, because Windows' Store python3
alias was not an interpreter. The executed startup command was:

```bash
export PATH="$PWD/target/day04-bin:$PATH"
./scripts/up
```

The OS denied writing the permanent Windows hosts entry. For the verification
process only, `CURL_HOME` pointed at ignored `target/day04-curl`, whose `.curlrc`
contains `resolve = "telecom.test:8080:127.0.0.1"`. This sends the same real HTTP
request to the proxy with the expected hostname; no check or response was mocked.
Normal browser access still needs the documented system hosts entry. Native Windows
curl could not use `/dev/null` as an output filename with MSYS path conversion
disabled; `scripts/verify` now uses shell output redirection for the identical
request. This preserves the full host OIDC check.

Final Compose state: postgres, kafka, keycloak, proxy, event-generator and processor
all running/healthy. Apache Kafka remains 3.9.1. Topic `telecom.observations.v2`
exists; the processor's stable group `telecom-processor-v2` has an active consumer
assigned all three partitions. No new public ports were exposed.

Additional live checks used runtime credentials inside the PostgreSQL container:
all four tables are migrator-owned; inserts across all four tables plus update,
select and delete succeeded in a transaction that was rolled back; CREATE in
`app`/`public`, DROP of an application table and connections to `incidents_db` and
`keycloak_db` were actually attempted and denied. No verification rows remain.
Local ignored `target-day04-*.log` files retain command output.

## Scope and next task

```text
Generator --X--> Kafka (no producer yet)
telecom.observations.v2 -> processor raw consumer -> shared validation/authority
                      -> IngestionService -> processing_db.app
                         observation_receipt / interval_bucket / source_state
                         rejection_outbox
                      -> COMMIT -> ACK
DB ERROR -> ROLLBACK -> NO ACK -> Kafka retry
```

Teammate detection/baseline code is preserved and not invoked by ingestion. There
is no KPI finalization, feature/KPI publication, rejection publisher, ML call,
episode persistence or detection-pipeline integration in this change.

Day 05 remains unimplemented: finalize voice UTC minutes at end + 10 seconds
under the same bucket lock, calculate eligible attempts/CSSR/radio/bearer metrics,
persist one immutable feature/outbox result and prove exact parity plus idempotent
finalization. Do not call ML inside the finalization transaction.

The Day 04 feature commit uses author and committer timestamp
`2026-09-18T18:30:00+03:00`; the main merge keeps its real September 20 timestamp.
Commit/push identity is reported separately after Git records the final commit.
