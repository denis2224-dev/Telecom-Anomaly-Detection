# Ion Day 3: corrected city scenarios and geographic history

## Current base and PR SHA

PR [#73](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/73), existing branch `feature/ion-new-day3-city-scenarios-history`.
Original head: `43ef2aee990994f96500da243ad468cb3ab181a5`.
Initial base: `a00f61df79d1a0daed8ff9045a2a9347feb437a7`.
Main advanced during this work to `cfd119c02e0d41784a6d24c1bef2801496a31eb5`; it was merged normally, without conflicts, in `675dba9c005d226fb6b660367e49a4620a24ef00`. Teammate changes already in main were preserved. No teammate branch was merged directly. The final fix commit follows this merge; use the GitHub PR head to identify the reviewed revision.

Execution date: 2026-10-08, Europe/Chisinau. Worktree: `C:/OrangeSystems/Program/ion-new-day3-city-scenarios-history`. Raw logs: `C:/OrangeSystems/Program/ion-new-day3-review-evidence-20261008`. The original dirty checkout, all other worktrees, and old G4 processing-freeze evidence remain untouched.

## Confirmed review findings and red evidence

The new regressions were executed before their production fixes. Original code failed:
- P1 configuration: canonical `telecom.geographic-history.enabled` selected execution but did not activate matching authority; the old bean condition used `telecom.history.geographic.enabled`.
- P1 activation: history hard-coded September 1 and omitted the shared active validator. Disabled/missing/malformed runtime geography could start.
- P2 feature-off scenarios: packaged contract bindings authorized city commands under a legacy validator, causing an uncaught validation exception instead of `INVALID_SCOPE`.
- P1 resume: configured `execute()` recomputed the moving end; a persisted September job restarted later failed with “Changed bootstrap configuration”.
- P1 outbox ownership: the original drain published pre-existing healthy KPI/coverage and fault coverage inside the interval. It also marked a row with another publisher's active lease.
- P2 city VoLTE seed: context-aware profiles changed city IDs but retained fixed legacy measurements regardless of city/seed.
- P2 range validation: empty and fractional-minute ranges were accepted until downstream failure.
- P2 migration privileges: repository provisioning defaults granted UPDATE/DELETE on V013 tables despite additive SELECT/INSERT grants. The new real upgrade regression failed before explicit revocation was added.

`regressions-red.log`: generator 2 tests, 1 failure; processor 20 tests, 9 failures and 2 errors, no skips. Maven test-failure-ignore was used ONLY for this deliberate red collection so all defects could be reproduced; its BUILD SUCCESS is not a passing suite. `city-seed-red.log`: 1 failing regression before the city measurement fix.

## Configuration fix and active authority

`HistoryBootstrapApplication` imports the existing `GeographicRuntimeConfiguration`. Geographic selection has exactly one namespace, `telecom.geographic-history.*`. The geographic bootstrap bean requires the injected ACTIVE catalogue. The old switch and `HISTORY_BOOTSTRAP_TARGET` fail clearly; simultaneous legacy/geographic enabled targets also fail. No contract-only fallback or hard-coded activation remains.

Spring context tests verify the same catalogue version/digest, effective-from, topology version, complete scope/source inventory and validator as `GeographyCatalog.activate(configuredEffectiveFrom)`. Changing activation changes version/digest. Missing, malformed or non-minute activation, disabled runtime geography, invalid seed/days/job/minutes and conflicting switches fail startup. The primary geographic topology/validator and legacy fallback beans coexist with unambiguous injection. Default feature-off legacy startup remains available.

## Feature-off authorization fix

Scenario validation uses injected runtime topology. City commands additionally require an ACTIVE geographic bean and a scheduled start at/after effective-from. Feature-off allows `VOLTE-MD-CENTRAL` and `SMS-MD-ROUTE-A`, rejects every city scope before generation/scheduling/reservation, and returns HTTP 400 `INVALID_SCOPE`. Contract-only catalogue injection does not authorize cities. Active contexts accept all 60 compatible combinations, reject unknown scopes and service mismatches.

## City seed correction and legacy compatibility

For city VoLTE profiles only, `GenerationContext.measurementSeed(seed)` and UTC minute seed a deterministic SplittableRandom. It varies eligible load (1000..1100), healthy IMS CPU (30..45), transport loss (0.0005..0.0015) and throughput (100..140). Technical counters retain exact identities. Overload remains CPU 97, technical failures 60, SIP 503 count 55; normal/recovery failures remain 7. The 2-normal / 3-fault / 3-recovery timeline remains. TELEMETRY_GAP emits no records during minutes 2..4.

Legacy overload/control/gap and old builder overloads retain exact bytes, including fixed legacy measurements. Seed never enters event ID/natural identity. `CityVoiceSeedTest` exercises all ten cities and all voice profiles for same-seed equality, different-seed measurement changes, fixed identities, and distinct city measurements. Existing voice/SMS snapshot tests remain unchanged and pass.

## 60-combination matrix and scope/body/Kafka key

Ten cities CHI, BAL, EDI, SOR, RIB, UNG, TIR, COM, CAH, ORH:
- VoLTE: NORMAL_CONTROL, TELEMETRY_GAP, VOLTE_IMS_OVERLOAD (30).
- SMS: NORMAL_CONTROL, TELEMETRY_GAP, SMS_QUEUE_DELAY (30).

The extended test installs all 60 actual generator runs and executes their scheduled callbacks across eight UTC-minute windows each. It captures 1050 KafkaTemplate publications (voice 630, SMS 420), validates each generated JSON record under active authority, compares command/payload/key scope, and rejects cross-city/minute event identity reuse. Gap windows contribute zero observations; overload/queue phases and reproducibility are checked separately over all cities. The source authority validator checks SERVICE reporters and NODE/source roles; no legacy node is substituted into city output.

This is generated-payload/application scheduling evidence with controlled Kafka ACKs, not a live public city API/browser acceptance claim. The history smoke below uses a real broker.

## Reservations and retry

Existing synchronization and half-open per-scope reservations remain. Tests cover identical run retry, changed run command, overlapping same-scope rejection, independent cities/services, adjacent intervals, STOPPED/FAILED reservations, scheduling failure, STOP without recovery fabrication, and restart rejection after scheduled start. Generator execution remains process-local; Denis owns durable public command reconciliation.

## Time-advanced restart and pinned policy

The first configured invocation freezes `[floorUTCMinute(startup)-duration, floorUTCMinute(startup))`. Default duration is days (1..2); optional `minutes` (1..2880) provides an explicit bounded smoke duration. Persisted days AND optional minutes are compared on resume, even when minutes overrides effective duration. Range is nonempty, whole-minute, <=48h and must begin at/after runtime effective-from.

V013 adds immutable `geographic_history_job` metadata linked to the existing `historical_bootstrap` range. The existing range is loaded under the geographic session advisory lock before constructing job identity. Clock advancement never changes it. Full SHA-256 identity includes job ID, catalogue version/digest, sorted scope set, original start/end and seed. Changed explicit range, seed, days/minutes or authority fails; completed rerun does no work. A different job ID is an independent job, while existing completed windows are preserved rather than adopted.

There is no separate mutable cursor: bounded minute-major rescanning skips committed features. Atomic per-window ownership prevents the old finalization/ownership crash gap. Maximum rescan remains 2880 minutes x 20 scopes. Old pre-V013 geographic jobs without a manifest fail closed and are never automatically retagged/adopted; operator/owner review is required. `initial-demo-v1` and its existing range/seed/status are never reset.

## Outbox ownership safety and Kafka behavior

V013 also adds insert-only `geographic_history_delivery` membership. A window lock and one database transaction cover reconciled healthy receipts, finalization, immutable feature/coverage, evaluation marker, KPI and membership. A history-specific finalizer entry joins that transaction; the existing live finalizer retains REQUIRES_NEW semantics. A trigger-induced membership failure proves all window evidence rolls back together.

Pre-existing finalized healthy/fault windows are not adopted, evaluated, rewritten or published by the geographic job. Compatible partial healthy raw receipts are completed without rewriting the original receipt; incompatible unfinalized fault evidence fails closed. There is no time-range-only drain.

The dedicated drain selects only membership rows and uses the normal delivery protocol: committed 30-second claim, SKIP LOCKED, earliest pending window per topic/key, bounded 100-row batches, immutable Kafka payload/key plus history header, ACK before token/expiry-fenced publication mark. Another active claim is preserved. Lost/expired leases cannot falsely mark; retry sends identical content. A send/ACK uncertainty retains output for at-least-once retry. Earlier unrelated stream heads block successors and completion until their own publisher acknowledges them; they are never drained by history.

Offline bootstrap isolation remains mandatory to prevent live workers advancing detector state or delivering owned output without the history header. The history application imports no live listener, delivery scheduler, detection/ML worker or geographic monitoring recorder. Database lease interoperation is a safety guard, not authorization to run seed alongside live processing.

## Geographic history / no overwrite / publication

PostgreSQL regressions verify 20 city scopes, 50 raw receipts and 20 features for one minute; 20 KPI + 20 coverage membership rows. They cover Kafka failure/restart, clock advancement, byte-identical pre-existing fault feature/receipts, same-range unrelated pending healthy output, out-of-range live rows, partial receipts, unchanged legacy bootstrap, leased output, uncertain ACK, stream ordering, simultaneous session locks, and transaction rollback after ownership failure.

Healthy seed never calls remote ML or advances live episodes. It inserts evaluated markers only for newly owned healthy windows. Existing faults and pending live delivery retain their original owner. Missing data is not replaced with zero or healthy evidence.

## Spring startup and real broker proof

`HistoryBootstrapRuntimeTest` starts the actual non-web history application, uses repository-provisioned disposable PostgreSQL 16.4 and Apache Kafka 3.9.1, real producer ACKs and an independent consumer. Runtime SQL executes as `processing_app`; Flyway uses the migrator role.

- Actual `HistoryBootstrapApplication.run(args)`, one-minute configured smoke: 50 receipts, 20 features, 40 membership/publication marks and 40 consumed KPI/coverage messages with correct key/scope/history header; zero live episodes.
- Actual configured startup with unavailable Kafka: committed windows remain; restart after a five-minute injected wall-clock advance reuses the original range/IDs and completes on real Kafka. Completed rerun remains unchanged.
- Changed seed, days, minutes or activation after restart is rejected.
- No live listener/delivery/detection/monitoring worker is present in this context.

These disposable runtime tests do not seed the user's real/demo database. No two-day full seed was run.

## Migration and ownership review

V013 is necessary because V007 range/seed metadata and V006 leases cannot prove output ownership. It adds only two history-owned tables, FKs, checks and SELECT/INSERT runtime grants, and explicitly revokes UPDATE/DELETE/TRUNCATE inherited from provisioning defaults. Existing migrations and evidence are unchanged. Upgrade from V012 preserves legacy history, unowned geographic history and a leased fault output byte-for-byte. Runtime cannot UPDATE/DELETE/TRUNCATE membership/spec tables; missing-job membership is rejected.

The ingestion schema inventory assertion now expects 16 tables (14 + two owned tables). The retention V011->V012 test is explicitly pinned to target 12 so it continues testing that migration; a separate V012->V013 test covers this change.

No incident-service, dashboard, detector, baseline, ML, Compose, deployment script, CI workflow or unrelated runbook was edited by this fix. Changes inherited through the normal main merge retain their owner's code.

## Changed files

Paths below are relative to their module's source root; the complete PR diff is against current main.

| Area / file | Purpose |
| --- | --- |
| event-generator/main: VoiceScenario.java | Context-aware city profiles and seeded city measurements; legacy bytes preserved |
| event-generator/main: scenarios/SmsQueueScenario.java | Existing PR context-aware SMS profile overloads retained |
| event-generator/main: api/ScenarioExecutionService.java | Active runtime authorization and context-aware generation |
| event-generator/test: CityVoiceSeedTest.java | Ten-city seed/identity regression |
| event-generator/test: api/ScenarioAuthorityContextTest.java | Feature-off/on Spring contexts and controlled HTTP rejection |
| event-generator/test: api/ScenarioExecutionServiceTest.java | Actual 60-run emitted payload/key matrix, reservations and phase checks |
| processor/main: history/HistoryBootstrapApplication.java | Shared authority, canonical selection and isolated startup |
| processor/main: history/GeographicHistoryProperties.java | Bounded duration, canonical properties and job validation |
| processor/main: history/GeographicHistoricalBootstrap.java | Pinned restart, atomic membership and lease-fenced delivery |
| processor/main: kpi/WindowFinalizer.java | Bootstrap transaction participation; live semantics preserved |
| processor/resources: db/migration/V013__geographic_history_ownership.sql | Durable job/output membership and explicit immutable runtime grants |
| processor/test: history/GeographicHistoricalBootstrapTest.java | PostgreSQL resume/ownership/fault/lease/ordering/rollback regressions |
| processor/test: history/GeographicHistoryMigrationTest.java | V012 upgrade preservation and runtime privilege verification |
| processor/test: history/HistoryBootstrapApplicationTest.java | Actual configuration, identity and environment-binding tests |
| processor/test: history/HistoryBootstrapRuntimeTest.java | One-minute entrypoint and restart with real PostgreSQL/Kafka |
| processor/test: history/HealthyHistoryTest.java | Existing PR all-city healthy history tests retained |
| processor/test: history/HistoryRangeTest.java | Duration, nonempty range and whole-minute boundary tests |
| processor/test: ingestion/IngestionIntegrationTest.java | Inventory assertion includes the two new owned tables |
| processor/test: outbox/RetentionMigrationTest.java | Preserve the existing test's exact V011->V012 upgrade boundary |
| docs/evidence/2026-10-08-ion-new-day3-city-scenarios-history.md | Correct results, limitations, configuration and owner handoffs |

## Exact verification results

Commands ran in the PR worktree with Java 21 and installed Python dependencies:

```powershell
$Mvn='C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd'
$RepoProperty='-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2'
$Python='C:/OrangeSystems/Program/Telecom-Anomaly-Detection/.venv/Scripts/python.exe'
& $Mvn $RepoProperty --batch-mode --no-transfer-progress -pl services/event-generator -am verify
$env:ML_SERVICE_URL='http://127.0.0.1:18093'
& $Mvn $RepoProperty --batch-mode --no-transfer-progress -pl services/processor -am verify
& $Mvn $RepoProperty --batch-mode --no-transfer-progress -pl services/processor -am '-Dtest=GeographicHistoryMigrationTest,GeographicDetectionTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
& $Python -B scripts/check-contracts.py
& $Python -B -m unittest discover -s tests -v
& $Python -B scripts/check-voice-parity.py
& $Python -B scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json
& $Python -B scripts/check-voice-parity.py --geographic --java-output services/processor/target/geographic-voice-parity-java.json
& $Python -B scripts/check-sms-parity.py --geographic --java-output services/processor/target/geographic-sms-parity-java.json
& $Python -B scripts/check-boundary-parity.py
```

Final local verification is PASS. `final-test-results.json` retains the complete final XML suite/case inventory outside the repository. Repeated focused/full runs below are separate executions, not additive release coverage. Retained runs:
- `regressions-green.log`: generator 29 PASS; processor/history 26 PASS; zero skips.
- `adversarial-startup.log`: 26 processor tests PASS, including real Kafka startup/resume; zero skips.
- `continuation-focused.log`: 52 generator + 41 processor tests PASS; zero skips.
- First full `processor-verify.log`: support 111 and generator 90 PASS; processor 528 total, 523 passed, 3 failures, 1 error, 1 optional skip. Failures: new environment-source simulation, table inventory/retention migration expectations after V013, and unchanged health probe timeout. The two migration assertions and test environment source were corrected; unchanged health probe passed focused rerun. No failed invocation is counted as PASS.
- `generator-verify-final.log`: support 111 + generator 91 PASS, zero skips.
- Second full `processor-verify-final.log`: support 111 and generator 91 PASS; processor 714 total, 711 passed, 2 failures, no errors, 1 optional skip. The new real V013 upgrade test exposed inherited mutation privileges; explicit revocation corrected that migration. An unchanged live geographic model case exceeded its 250 ms budget. Neither its timeout nor assertions were changed.
- `migration-model-focused-final.log`: corrected V013 upgrade (1) and unchanged live geographic model tests (45), all 46 PASS, zero skips.
- `processor-verify-complete.log`: final complete reactor PASS: support 111/111 and generator 91/91 passed, no skips; processor 714 total, 713 passed, zero failures/errors, 1 optional SMS shadow replay skip. Live ML was enabled at port 18093. All 15 geographic history, 8 application configuration, 2 real startup/restart and 1 V013 upgrade tests passed, together with replay/finalizer/outage/delivery regressions.
- `contracts-final.log`: all nine contract suites PASS on current main, including 14 explanation trajectories.
- `python-root-final.log`: 54 tests PASS, zero skips.
- Fresh Java/Python parity: voice 7, SMS 12, geographic voice 30, geographic SMS 32, boundary 6 = 87 PASS; maximum absolute float difference 0, exact full identity/order/null fields. Separate `parity-*-final.log` files retain each command's output.
- `git diff --check` and complete ownership diff review PASS before commit. No production path outside Ion's generator/processor scope was changed.
- Graphify AST update completed; its launcher workaround used the installed module directly. Graph output remains local/untracked.

## Conditional skips and remaining limitations

Only actual skipped testcases are classified in the final totals. Optional SMS shadow replay requires external `SMS_SHADOW_REPLAY_DIR`; default live ML cases were enabled locally using an isolated container at port 18093. Previous PR skip descriptions that called voice/SMS live-model cases “broker skips” were incorrect.

Local generated payloads and disposable history startup do not prove the public API, deployed activation across all services, authenticated UI city membership/history or integrated Shared G3. Full two-day resource/capacity acceptance remains unexecuted.

## Denis handoff

Current `services/incident-service/src/main/java/md/utm/telecom/simulator/service/ScenarioCommandService.java` still validates only legacy scopes. Required owned change: authorize from ACTIVE runtime catalogue/topology, preserve feature-off legacy behavior, reject unknown/service-incompatible city commands before durable reservation, and retain existing request/run retry, interval, RBAC/CSRF, schedule and generator error semantics. Verify all 60 compatible public commands and scope/body/key linkage through generator delivery.

Current `services/incident-service/src/main/java/md/utm/telecom/services/messaging/ServiceKpiWindowConsumer.java` classifies bootstrap history only for exact `initial-demo-v1`. Geographic jobs carry `jobId:fullSHA256`; they currently go through ordinary ingest. Denis must define and verify a trusted geographic history provenance path, preserve immutable IDs/upserts and bounded history, and ensure historical seed cannot manufacture incidents or advance live state. Do not simply accept arbitrary headers or prefixes. Coverage consumer/projection must also verify pinned activation identity.

## Stanislav handoff

Current Compose history-bootstrap passes only `HISTORY_BOOTSTRAP_MODE`, legacy `HISTORICAL_BOOTSTRAP_*`, Kafka and DB settings. It omits geography activation and canonical geographic history settings. Live generator/processor already depend on history-bootstrap successful completion; keep that startup barrier and ensure previously running workers are stopped before seed. Do not run against existing data until operational authorization is supplied.

Required property/environment mapping (verified through a Spring system-environment property source):

| Spring property | Environment variable |
| --- | --- |
| telecom.geography.enabled | TELECOM_GEOGRAPHY_ENABLED=true |
| telecom.geography.effective-from | TELECOM_GEOGRAPHY_EFFECTIVE_FROM=<aligned UTC minute used by all services> |
| telecom.geographic-history.enabled | TELECOM_GEOGRAPHICHISTORY_ENABLED=true |
| telecom.geographic-history.days | TELECOM_GEOGRAPHICHISTORY_DAYS=1 or 2 |
| telecom.geographic-history.seed | TELECOM_GEOGRAPHICHISTORY_SEED=42 |
| telecom.geographic-history.job-id | TELECOM_GEOGRAPHICHISTORY_JOBID=<stable unique job ID> |
| telecom.geographic-history.minutes | TELECOM_GEOGRAPHICHISTORY_MINUTES=1 (optional bounded smoke override) |
| telecom.history.enabled | HISTORICAL_BOOTSTRAP_ENABLED=false (existing YAML mapping) |

Spring environment binding removes hyphens: use GEOGRAPHICHISTORY / JOBID, not GEOGRAPHIC_HISTORY / JOB_ID. Runtime shared geography effective-from is explicitly resolved by the existing shared configuration. Remove obsolete `HISTORY_BOOTSTRAP_TARGET` and `telecom.history.geographic.enabled`.

Exact standalone entrypoint (DB/Kafka secrets supplied opaquely by the authorized environment, and HISTORY_BOOTSTRAP_MODE=true):
```text
java -jar services/processor/target/processor-0.3.0-SNAPSHOT.jar --telecom.history.enabled=false --telecom.geography.enabled=true --telecom.geography.effective-from=<aligned-UTC-minute> --telecom.geographic-history.enabled=true --telecom.geographic-history.days=2 --telecom.geographic-history.seed=42 --telecom.geographic-history.job-id=geographic-demo-v1 --telecom.geographic-history.minutes=1
```

Proposed owned Compose patch: add `<<: *geography-environment` to history-bootstrap and explicit canonical geographic-history variables; make legacy/geographic selection mutually exclusive. For a full day/two-day seed omit minutes. Pin the same effective-from for generator, processor, coverage and incident-service; ensure requested start is not before it. Do not backdate activation merely to admit history. If activation is recent, use a bounded minute duration within the active period. Use existing kafka-init/topic provisioning, migrator/runtime separation and offline process isolation. V013 must be available in the built image. Deployment integration remains NOT VERIFIED until Stanislav implements and tests this wiring.

## Sergiu / David dependencies and verdict boundary

Sergiu owns city baseline/detector/explanation behavior; his changes now in main remain untouched. Preserve mapped authority, null/missing eligibility, fresh SMS queue evidence and episode/model IDs in integrated scenario acceptance. David owns protected city scenario/history UI and session/SSE verification. Neither backend component tests nor shared green CI proves those integrated flows.

Ion local component verification is complete. The same PR is updated through a normal commit/push; re-review readiness also requires green CI at that updated head, verified separately through GitHub checks. Shared G3 remains BLOCKED on Denis public scenario/provenance integration, Stanislav deployment wiring and required authenticated UI acceptance. This document does not approve old G4 or start later days.
