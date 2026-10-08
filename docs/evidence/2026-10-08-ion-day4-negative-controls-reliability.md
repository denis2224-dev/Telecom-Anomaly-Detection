# Ion Day 4: negative controls and streaming reliability

> **Historical evidence.** The original body below records its original source SHA, UTC times, counts and owner findings. It is preserved as an archive, not a current Git/acceptance status report. PR #73 and #74 are now merged into `1b675c64938474e74c79efcecb6e051c4d460943`. The old reconciliation ancestry was not imported. Current main already enforces disabled geography and scheduled-start activation in the public command path; the historical public activation reproduction is superseded at source level. Authenticated final-candidate acceptance remains unverified. Fresh selective integration results are recorded separately in [the post-merge integration report](2026-10-08-ion-day4-day5-post-merge-integration.md).

Execution: 2026-10-08, Europe/Chisinau. **ION DAY 4: PARTIAL; SHARED G4: BLOCKED.** Independent Ion component verification is complete: the full affected reactor passed 734 executed cases with four optional skips, and the explicit missing-window gate passed nine cases. The authoritative plan's integrated daily acceptance remains blocked by Shared G3 and owner-controlled acceptance. This report does not approve a release or a later day.

Branch: `feature/ion-day4-negative-controls-reliability`. Worktree: `C:/OrangeSystems/Program/ion-day4-negative-controls`. Validated source commit: `6b830fd616c0a5da1a644bd9a14a0cd157c95a92`, based directly on reconciliation `d7bbaece74e9019841c615115a6178a2bee7716f`. The report-only commit follows the source commit. Raw local evidence and sanitized executed-test inventories: `C:/OrangeSystems/Program/ion-day4-evidence-20261008`.

## Authority, current heads and preservation

Read the authoritative `C:/OrangeSystems/task/pdf/01_Zavtoni_Ion_5_Day_Tasks_FINAL.pdf`, page 5, and `00_Shared_Project_Redesign_5_Day_Plan_FINAL.pdf`, pages 8-11 and 12-14. Their SHA-256 digests are respectively `4a3cb17d02e90d6d72b5d591fe33eb0cfeaae855cafba15e50a4577530d00a3f` and `9c5eff1603816e1a6f0adab0c628ed7fb067bd836b7307bbf9987dcae00f680f`. Fresh page-delimited extractions are retained outside the repository. The plans require source truth, failure isolation, stable retries and explicit fixture-versus-live limits. They prohibit treating an isolated component as the integrated daily gate.

The reconciliation report and previous Ion Day 3 report were read before test changes. Reconciliation's previous 715 passes and Day 3's other results were treated as historical. Read-only GitHub/remote queries before and after implementation confirmed:

| Reference | Current identity | State |
| --- | --- | --- |
| [Ion PR #73](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/73) | `05f4f088244d3728e15cc18c233b67f3b45def5d` | OPEN; six successful checks |
| [Denis PR #74](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/74) | `cd0f98d24bb97d08be838bfa6d4fa5ea86b4dd28` | OPEN; six successful checks |
| Main / both PR bases | `cfd119c02e0d41784a6d24c1bef2801496a31eb5` | Not integrated into this branch |

Current-main changes after the common processing base affect Sergiu's explanation/replay tests, not continuous-producer production code. Existing producer and delivery fairness fixes are already present in the reconciliation base. No later main changes were transplanted. Individual PR checks do not establish candidate CI or combined-tree acceptance.

The main checkout was dirty. Before branch creation, 24 existing worktrees were captured with HEAD, branch, complete status/untracked lists and SHA-256 of dirty tracked files. The suggested Day 4 names were unused, and this isolated branch was created directly from the verified reconciliation. Final preservation verification is recorded below. No remote write, PR creation, merge, history rewrite, database-volume deletion or shared-runtime mutation was performed.

## Coverage audit and gap decisions

Test names were checked against actual assertions, transaction proxies, Kafka consumers and failure mechanisms. PASS below is scoped to the stated component boundary. Production ownership in these rows is Ion unless explicitly assigned otherwise.

| Day 4 requirement | Existing mechanism and executed tests | Level | Gap and decision | Current result |
| --- | --- | --- | --- | --- |
| Ten-city/service authority | `GenerationContext`, catalogue role resolution; `ScenarioExecutionServiceTest`, `CityVoiceSeedTest`, `GeographicCoverageTest`, `GeographicWorkerTest` | Unit; real PostgreSQL | Reuse; add simultaneous mixed fault/gap transport control | PASS |
| Orhei fault / CHI and BAL controls | Existing deterministic city builders; generator publication assertions | Unit | Add real Kafka/PG mixed control in existing `GeographicLoadAccountingTest` | PASS |
| SERVICE absence and independent NODE | `buildMissing`, monitored participation, transactional coverage; `GeographicCoverageTest`, `MissingWindowHandoffIT` | Real PostgreSQL | Add both-service received/usable/source-activity matrix | PASS |
| Reported missing, absent, heartbeat, stale, measured zero | `SourceFreshness`, `CoverageSnapshot`; 14 existing freshness tests | Real PostgreSQL | Add ten integrated matrix cases in existing coverage class | PASS |
| Missing/stale IMS, transport, SMSC | Role-based exact-window joins; `EvidenceJoinerTest`, `WindowFinalizerTest` | Unit; real PostgreSQL | Add six geographic degraded-service dependency controls | PASS |
| Optional SMS transport | Required source set excludes optional dependency; existing coverage test | Real PostgreSQL | NO CHANGE | PASS |
| One slow city / bounded ordering | 20 workers, bounded queue, per-scope chains; existing `GeographicPublicationTest` | Controlled unit futures/clock; actual worker threads | Reuse existing delayed/blocking/ordering tests | PASS, controlled |
| Synchronous continuous send failure | Existing exception/accounting/retry path | Controlled unit sender; actual workers | Add one-scope terminal failure and next-minute release test | PASS, controlled |
| Uncertain continuous ACK | Original bytes retained; existing load test suppresses a successful real broker ACK | Real Kafka + PostgreSQL; simulated client ACK loss | NO CHANGE; rerun existing test | PASS |
| Deadline / downtime restart | Existing lifecycle, expiry and UTC alignment; continuous/publication tests | Controlled unit clock | Add geographic mid-minute restart after downtime | PASS, controlled |
| Scenario reservation / no premature recovery | Existing private execution and continuous suppression tests | Unit, real private execution service; mocked transport | NO CHANGE; retain failed/stopped reservations and end boundary | PASS at tested boundary |
| Exact duplicate and content/natural conflict | Receipt uniqueness, canonical hash, window lock; `IngestionIntegrationTest`, `LateInputIT`, `ReplayIT` | Real PostgreSQL; real Kafka where used | NO CHANGE | PASS |
| Before/at/after closure | Versioned allowed lateness; `LateInputIT`, `FinalizerRaceIT` | Real PostgreSQL and transaction gates | NO CHANGE | PASS |
| Commit/input ACK/output ACK interruption | Listener ACK follows transaction return; durable delivery/leases; `ReplayIT` | Real Kafka/PostgreSQL; SQL trigger/deferred-commit and ACK fault injection | NO CHANGE | PASS |
| Actual dependency interruption/restoration | Existing `DependencyOutageIT` | Real PostgreSQL pause/unpause and isolated Kafka stop/restart | NO CHANGE; full reactor reuses five existing drills | PASS |
| History resume / producer recovery | Existing V013 ownership, committed window checkpoints and lease fencing; history tests | Real PostgreSQL, real non-web Spring startup and disposable Kafka; some injected failures | NO CHANGE; do not duplicate reconciliation's tests | PASS |
| Optional auxiliary runtime | Explicit G2 go/no-go; planned schema and sample metadata | Contract/docs | No approved runtime chain evidenced; NO IMPLEMENTATION | NOT_IN_SCOPE |
| Public activation agreement | Denis's public catalogue/command validation | Prior exact-head reproductions; fresh unchanged-head verification | Denis ownership; NO CHANGE | BLOCKED |
| Persisted incident, API/UI, cause and release acceptance | Owner-controlled projections and same-SHA deployment | Integrated acceptance | Denis/Sergiu/David/Stanislav handoffs; not proved here | BLOCKED |

No Ion production defect was reproduced. The audit found missing test combinations, so no production correction or new architecture was justified. Existing ingestion, finalizer, outbox, history, activation and detector behavior is unchanged.

## Exact changes relative to reconciliation

Only three test files and this report are added/changed, with 19 new executed test cases:

| File | Necessary change |
| --- | --- |
| `services/event-generator/src/test/java/md/utm/telecom/generator/continuous/GeographicPublicationTest.java` | Two tests: synchronous Orhei send failure without starving other scopes; restart ten seconds into a minute after downtime without backfill |
| `services/processor/src/test/java/md/utm/telecom/processing/GeographicLoadAccountingTest.java` | One real Kafka/PostgreSQL simultaneous mixed fault/gap test; reuse existing consumer/ACK/receipt helpers; existing monitored-participation bean and disposable cleanup allow truthful missing finalization |
| `services/processor/src/test/java/md/utm/telecom/processing/kpi/GeographicCoverageTest.java` | Ten SERVICE-state cases plus six required-NODE cases; existing generation/ingestion helpers accept the event's actual minute to admit genuinely old evidence before testing staleness |
| `docs/evidence/2026-10-08-ion-day4-negative-controls-reliability.md` | Reproducible scope, audit, results, limits and owner handoffs |

All production files match reconciliation, including Denis's complete `ScenarioExecutionService.java` and `SmsQueueScenario.java`. No incident-service, detection/baseline/correlation, dashboard, ML, contract, infrastructure, security, CI or migration source was changed. Existing geographic-history production and failure tests are retained byte-for-byte.

## Mixed-city negative controls

Seed `42`; active catalogue from `2026-10-07T08:00:00Z`; mixed window `[2026-10-07T08:02:00Z,08:03:00Z)`. All CHI, BAL, EDI, SOR, RIB, UNG, TIR, COM, CAH and ORH VoLTE/SMS scopes participate. The existing Orhei VoLTE overload profile supplies its fault minute. Only Edineț's SERVICE receipt is withheld; its SMSC receipt is independently present. There is no transport-simulator or detector addition.

Actual broker keys equal payload scopes. The consumer verifies immutable bytes, unique event IDs and natural keys `(sourceId, scopeId, kind, windowStart)` before invoking the production listener and committing real offsets. All unaffected city/service payloads equal their original healthy bytes. Current role/source sets come from the catalogue. At closure, 49 logical receipts / accepted increments produce 20 features and 20 matching coverage outputs, with zero rejections.

| Control | Observed result |
| --- | --- |
| Orhei VoLTE | CSSR `94.42896935933148%`; source identities `IMS-MD-ORH-01`, `TRANSPORT-MD-ORH-01`, `VOLTE-SRC-ORH` |
| Chișinău VoLTE | CSSR `99.20983318700614%`; own independent IMS/transport/service identities; unchanged healthy bytes |
| Bălți VoLTE | CSSR `99.40119760479043%`; own independent identities; unchanged healthy bytes |
| Orhei SMS | Delivery SR `98.37837837837837%`; own service/SMSC identities; unchanged healthy bytes |
| Edineț SMS gap | `quality=MISSING`, service p95/SR/delivered values null, `mlEligible=false`; sole contributor `SMSC-MD-EDI-01`; SERVICE issue `NOT_RECEIVED`, expected 2 / received 1 / usable 1 |

These are observations, finalized KPIs and coverage facts. No deployed incident-creation, public scenario authentication or API/UI acceptance is claimed.

## Source truth and negative dependency controls

The PostgreSQL SERVICE matrix executes both VoLTE and SMS at `2026-09-15T08:00:00Z`; the stale case uses current minute `08:03:00Z` with the earlier SERVICE receipt at `08:00:00Z`. Old evidence is genuinely ingested in its open minute, not inserted as a fake current observation. At `08:04:10Z` its activity age is 190 seconds, beyond the existing 90-second threshold. All current independent NODE measurements remain usable.

| SERVICE case | Activity / interval state | Received / usable measurement sources | SERVICE KPI truth |
| --- | --- | --- | --- |
| Absent source | NEVER_SEEN / MISSING after closure | VoLTE 2/2, SMS 1/1; SERVICE NOT_RECEIVED | Null, not zero; finalized MISSING |
| Explicit MISSING | FRESH / REPORTED_MISSING | VoLTE 3/2, SMS 2/1; SERVICE REPORTED_MISSING | Null; real missing receipt retained |
| Heartbeat only | FRESH / MISSING | VoLTE 2/2, SMS 1/1; heartbeat excluded from measurement coverage | Null; no invented SERVICE contributor |
| Stale prior SERVICE | STALE / current MISSING | VoLTE 2/2, SMS 1/1 | Prior success does not fill the current minute |
| Valid COMPLETE zero volume | FRESH / COMPLETE | VoLTE 3/3, SMS 2/2; no source issues | Real zero counts preserved; zero-denominator ratios and empty-sample p95 remain null |

Required-NODE controls retain measured service degradation while withholding the current IMS, VoLTE transport or SMSC receipt. Each is exercised both never-seen and with a genuinely stale prior NODE. Current coverage names exactly the absent role source as NOT_RECEIVED. Required node KPIs remain null; no other city/role/prior minute is substituted. The SERVICE's COMPLETE quality remains its measured receipt quality, not a healthy-dependency conclusion. Optional SMS transport remains optional in the reused regression.

The reused missing-window handoff opens a real processor episode from two breaches, then closes an absent SERVICE minute with a fresh heartbeat as UNKNOWN evidence without manufactured observed KPIs or recovery. Valid independent SMSC backlog evidence remains available to existing supported rules. Wrong-city, wrong-window, unapproved, missing and incomplete NODE joins are separately rejected/ignored by existing authority tests. Strict schemas and tested features contain no injected scenario/cause labels or POWER_OFF conclusion. Auxiliary alarm correlation itself is not executed.

## Continuous producer fairness, retry and restart

Authoritative constants remain: 60-second UTC windows; default publication at window end +1 second; producer cutoff +7 seconds; finalization closure +10 seconds; 20 daemon submission workers with queue capacity 20; one ordered chain per scope; at most three attempts with 250ms backoff; at most two retained accounting minutes. ACK callbacks enqueue work rather than calling the next blocking Kafka send themselves.

Existing delayed/blocking tests prove that one stalled scope leaves the other 19 scopes eligible, same-scope sends retain order, old blocked calls cannot be overtaken after expiry, and restart cannot multiply an unresponsive executor. The test named `allTwentyGeographicScopesFitPublicationDeadlineWithReal150msAckLatency` uses manually completed incomplete futures and an injected clock: its three 150ms waves/450ms source-time result are **controlled**, not a live latency/capacity measurement.

The new synchronous Orhei failure test offers 50 observations once, ACKs all 47 unrelated observations, records three failed technical attempts for the identical first Orhei payload, and terminates with one failed logical record plus two cancelled successors. No ACK or extra logical offer is fabricated. Only the next minute remains scheduled. Its following complete UTC minute ACKs 50 observations, proving failed-chain release.

The restart test finishes one geographic minute, stops the old executor, restarts at `2026-10-01T09:40:10Z`, and publishes only the next fully observed minute `[09:41:00Z,09:42:00Z)` at `09:42:01Z`. No intervening or partial minute is generated. Existing deadline callbacks and failed/stopped scenario reservations continue to suppress premature publication/recovery until their reserved end; public/private deployment restart reconciliation is not claimed.

The reused real Kafka/PostgreSQL accounting tests retain the following boundaries:

| Case | Expected / offered / ACK | Failure accounting | Wire / durable receipts / features |
| --- | --- | --- | --- |
| Normal 20-scope minute, `2026-10-07T08:00Z` | 50 / 50 / 50 | 0 terminal, 0 expired/cancelled; 50 attempts | 50 / 50 / 20 |
| Successful broker persistence, simulated lost client ACK, `08:01Z` | Initially 50 / 50 / 49; after retry 50 / 50 / 50 | One timeout/failed technical attempt, 51 attempts; no terminal failure or cancelled logical record | 51 / 50 / 20 |

The repeated Kafka record has identical bytes/key/event identity and a different offset. Real ingestion returns DUPLICATE, persists no extra receipt or accepted increment, and the listener safely commits its offset. ACK visibility and PostgreSQL persistence are distinct. Transport is at-least-once; no exactly-once Kafka wire claim is made. JVM/wall samples in the existing artifact include consumer/database/finalization work with a controlled source clock; they are not a deployed throughput or deadline budget. Sustained resource/fairness acceptance remains Stanislav's gate.

## Duplicate, closure, race and interruption evidence

`IngestionIntegrationTest` verifies exact/reordered retries, event-body and natural-key conflicts, simultaneous receipt contenders, committed-state-before-listener-ACK, write failure and deferred commit-time rollback. `LateInputIT` admits SERVICE/NODE arrivals one PostgreSQL-representable microsecond before closure and rejects at/after closure; conflict/duplicate checks retain their precedence and immutable finalized rows. `FinalizerRaceIT` uses real PostgreSQL advisory/trigger gates and independent observers for normal/missing/timely/late contention; one logical finalization wins without acknowledged-input loss.

`ReplayIT` uses actual broker records and persisted consumer-group offsets. Its write-before-commit and deferred-commit faults roll back without ACK; a simulated lost input ACK after commit survives a fresh consumer session; actual output broker ACK followed by SQL publication-mark failure retries identical KPI/detection identity. Full state comparisons cover receipts, increments, source state, features, evaluated windows, episode state and detection outbox rows. This proves processor logical output safety, not incident-service persisted incident idempotency or a deployed process crash.

`RejectionPublisherTest` supplies configuration/batch constructor checks. Real/mocked rejection-send behavior is in `LateInputIT`; its name alone is not treated as an outage proof. Existing dependency-outage and history runtime cases retain their real-versus-injected failure descriptions from the reconciliation and drill reports.

## Optional evidence decision

**G4-OPT-01: NOT_IN_SCOPE / PLANNED.** The shared FINAL plan page 9 requires publisher, idempotent consumer, correlation owner, authority tests and storage/API path by G2. `docs/detection/geographic-evidence-contract.md`, `docs/evidence/geographic-investigation-checks.md` and `evidence-projection-hardening-checks.md` explicitly record the missing approved runtime chain. A schema/sample is not operational authority or G2 approval. No signed go decision was found, and no enablement is inferred.

No auxiliary producer, probe, table, endpoint, cause extension or V2 field is added. Core missing/stale/city/window controls are verified at observation/KPI boundaries. Failed ping, stale/wrong-city auxiliary alarms and fresh-power/no-power read-projection outputs require Sergiu/Denis's owned integrated evidence controls; no runtime alarm comparison is claimed from contract fixtures. Optional alarm acceptance is excluded, while mandatory source-truth and no-fabricated-cause acceptance remains part of Shared G4.

## Commands, runtime and actual results

Java `21.0.12.1`, Spring Boot `3.5.16`, Maven `3.9.16`, Docker server `29.3.1`. Disposable PostgreSQL `16.4-alpine` uses the existing migrator/runtime fixture and real transaction proxies. Load/replay use embedded real Kafka; existing history startup/outage uses disposable `apache/kafka:3.9.1`. No shared Compose deployment is modified. Optional external ML/shadow replay configuration is not enabled by this task.

Commands run in the Day 4 worktree. No full reactor test filter/exclusion was added. Focused `surefire.failIfNoSpecifiedTests=false` allows upstream modules without the named classes; executed class counts are checked explicitly. The processor's default Surefire list omits the two missing-window IT classes, so they are run explicitly as a separate gate.

```powershell
$D4_MVN='C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd'
$D4_REPO='-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2'
$D4_PY='C:/OrangeSystems/Program/Telecom-Anomaly-Detection/.venv/Scripts/python.exe'
$D4_FOCUS='IngestionIntegrationTest,LateInputIT,ReplayIT,FinalizerRaceIT,RejectionPublisherTest,MissingWindowDecisionIT,MissingWindowHandoffIT,ContinuousTelemetryServiceTest,GeographicPublicationTest,GeographicLoadAccountingTest,SourceFreshnessTest,WindowFinalizerTest,GeographicCoverageTest,EvidenceJoinerTest,GeographicHistoricalBootstrapTest,HistoryBootstrapRuntimeTest'
& $D4_MVN $D4_REPO --batch-mode --no-transfer-progress -pl services/event-generator -am '-Dtest=ContinuousTelemetryServiceTest,GeographicPublicationTest,ScenarioExecutionServiceTest,CityVoiceSeedTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
& $D4_MVN $D4_REPO --batch-mode --no-transfer-progress -pl services/processor -am '-Dtest=GeographicCoverageTest,GeographicLoadAccountingTest,SourceFreshnessTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
& $D4_MVN $D4_REPO --batch-mode --no-transfer-progress -pl services/processor -am '-Dtest=GeographicPublicationTest,GeographicCoverageTest,GeographicLoadAccountingTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
& $D4_MVN $D4_REPO --batch-mode --no-transfer-progress -pl services/processor -am '-Dtest=GeographicLoadAccountingTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
& $D4_MVN $D4_REPO --batch-mode --no-transfer-progress -pl services/processor -am "-Dtest=$D4_FOCUS" '-Dsurefire.failIfNoSpecifiedTests=false' verify
& $D4_MVN $D4_REPO --batch-mode --no-transfer-progress -pl services/event-generator -am verify
& $D4_MVN $D4_REPO --batch-mode --no-transfer-progress -pl services/processor -am verify
& $D4_MVN $D4_REPO --batch-mode --no-transfer-progress -pl services/processor -am '-Dtest=MissingWindowDecisionIT,MissingWindowHandoffIT' '-Dsurefire.failIfNoSpecifiedTests=false' verify
& $D4_PY -B scripts/check-contracts.py
& $D4_PY -B -m unittest discover -s tests -v
& $D4_PY -B scripts/check-voice-parity.py --java-output services/processor/target/voice-parity-java.json
& $D4_PY -B scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json
& $D4_PY -B scripts/check-voice-parity.py --geographic --java-output services/processor/target/geographic-voice-parity-java.json
& $D4_PY -B scripts/check-sms-parity.py --geographic --java-output services/processor/target/geographic-sms-parity-java.json
```

| Invocation / artifact | Actual result |
| --- | --- |
| `generator-baseline.log` | PASS: 60 generator cases; zero failures/errors/skips |
| `processor-baseline.log` | PASS: 23 processor cases; zero failures/errors/skips |
| `added-controls.log` | Initial FAIL: generator 16 PASS; processor 26 cases, 25 PASS / 1 failed test assumption / 0 errors or skips |
| `mixed-city-corrected.log` | PASS: three load cases after correcting the healthy-SMS test assumption |
| `focused-final.log` | PASS: generator 32 + processor 173; zero failures/errors/skips; executed before final simultaneous gap extension |
| `mixed-city-gap-final.log` | PASS: three load cases including final mixed fault/gap, on the final source content |
| `generator-full.log` | PASS: support 111 + generator 95; zero failures/errors/skips |
| `processor-full.log` | PASS at source SHA `6b830fd...`: support 111/111, generator 95/95; processor 532 reported / 528 executed PASS / 4 skips / 0 failures/errors. One reactor: 738 reported / 734 executed PASS / 4 skips. Finished `2026-10-08T14:21:39+03:00` (`11:21:39Z`), 8m48s |
| `missing-it-final.log` | PASS at the same source SHA: MissingWindowDecisionIT 8 + MissingWindowHandoffIT 1; zero failures/errors/skips. Finished `2026-10-08T14:23:52+03:00` (`11:23:52Z`); separate invocation |
| `contracts.log` | PASS: all nine existing contract/reference checks; geographic reference cases are not fresh Java parity |
| `python-root.log` | PASS: 51 tests; zero failures/errors/skips |
| `parity-voice.log`, `parity-sms.log`, `parity-geographic-voice.log`, `parity-geographic-sms.log` | PASS: 7 legacy voice, 12 legacy SMS, 30 geographic voice, 32 geographic SMS payloads; all fields compared; maximum absolute float difference 0 in all 81 comparisons |
| `graphify-update.log` | PASS: AST graph rebuilt using installed `python -m graphify update .`; graph remains untracked; optional SQL parser warning does not affect Java verification |
| `git diff --check`, owner diff, preservation | PASS: whitespace clean, test/evidence-only diff; all 24 original worktrees retain HEAD/branch/status/untracked lists and dirty tracked-file hashes |

These are separate invocations, not additive suite totals. Executed-test inventories filter by classes actually reported in each invocation to avoid counting stale Surefire XML from earlier focused runs. Only sanitized suite/case identity, counts and skip reasons are archived. Earlier unfiltered report-directory inventories are not authoritative suite totals.

All 19 added cases pass in the full reactor. The final source also executes all five real dependency-outage cases and all 37 geographic history/configuration/range/migration cases without skips. The explicit missing-window run executes the nine required IT cases that the default reactor does not discover. The four full-reactor skips are existing conditional cases:

* `GeographicDetectionTest.pinnedHttpScorerAcceptsCityVectorsAndRejectsChangedBaselineWithoutBlockingRules(String)`: `ML_SERVICE_URL` absent.
* `SmsDeliveryTest.livePackagedModelEnrichesRecoveredSmsEpisode`: the existing live-model assumption requires `ML_SERVICE_URL`.
* `VoiceDeliveryTest.livePackagedModelEnrichesRecoveredVoiceEpisode`: the existing live-model assumption requires `ML_SERVICE_URL`.
* `SmsShadowReplayTest.freshObservationsReachDurableShadowStorageWithoutChangingRuleIncidents`: `SMS_SHADOW_REPLAY_DIR` absent.

No test was disabled or silently excluded. These skips do not establish live ML acceptance. Java parity exports were freshly produced by this full reactor, archived under `parity-exports`, and compared directly; this is more than running Python reference fixtures. `verification-manifest.json` outside the repository pins source SHA, configuration-file hashes, export hashes and exact executed test/skip inventories.

The initial mixed-control failure assumed every healthy SMS SR exceeded 98%; the deterministic existing envelope is `attempts - 0..4` successes for 180..220 attempts. The corrected oracle retains exact transported bytes/counters and numeric KPI comparison and applies the fault/control CSSR separation to VoLTE. No existing assertion, production envelope or deadline was relaxed. No baseline-red production failure exists to claim: production required no fix. Sandbox Docker/network restrictions were resolved using approved execution; tooling/encoding/Graphify launcher issues are not counted as test failures.

## Acceptance and remaining owner gates

| Criterion | Verdict and precise boundary |
| --- | --- |
| G4-NC-01 city independence | PASS: real broker/PG mixed fault/gap and all 20 authoritative scope/source sets |
| G4-NC-02 missing source truth | PASS: processor missing/null, preserved independent nodes and reused UNKNOWN/no-recovery handoff; integrated cause/UI state separate |
| G4-NC-03 coverage correctness | PASS: expected/received/usable truth for five SERVICE states and required/optional NODE cases |
| G4-PR-01 producer fairness | PASS: controlled worker/future/clock fairness and scope release; sustained deployed performance separate |
| G4-PR-02 ACK uncertainty | PASS: real persistence plus deliberately suppressed ACK, identical retry, 51 wire / 50 logical receipts |
| G4-RP-01 duplicate/replay safety | PASS: immutable processor receipts/KPIs/episode/detection output; persisted incident-service boundary BLOCKED pending owner integration |
| G4-RP-02 conflict safety | PASS: unchanged canonical event/natural-key rejection and finalized snapshot comparisons |
| G4-RP-03 finalization boundary | PASS: real PG before/at/after closure and finalizer/ingestion contention |
| G4-COMPAT-01 legacy compatibility | PASS: unchanged exact legacy snapshots, private feature-off/on authority and seed-independent identities; see final parity |
| G4-OWN-01 ownership | PASS: test-only Ion surfaces and evidence; no production changes |
| G4-EVID-01 reproducible evidence | PASS: source SHA/configuration/window/seed, filtered executed-test inventories, original failed invocation and fresh export/authority hashes retained |
| G4-OPT-01 auxiliary evidence | NOT_IN_SCOPE: no approved G2 runtime chain |
| Shared G3/G4 integration | BLOCKED: public activation mismatch, final-main candidate CI and required integrated acceptance |

**P1 / Denis:** PR #74's public catalogue still permits cities when geography is disabled and public command validation permits a scheduled minute before future activation. The reconciliation's exact-head disabled matrix and future-activation reproduction remain applicable because the head is unchanged. Preserve the existing ledger, authorization, exact retry/overlap and private fail-closed semantics; do not map a permanently invalid command into an unavailable-generator loop. Owning regressions and actual private-generator interaction remain necessary. No public validator or bypass is added here.

**P2 / coordinated integration:** PR #73/#74 reconciliation is still local and excludes final main. Synchronize deliberately, review ownership and run candidate CI/startup on the agreed combined SHA. Denis must verify geographic history provenance through the required KPI/coverage consumer/projections, without prefix-only trust, and persisted incident replay. Sergiu/Rusu owns paired cause/explanation controls, including mandatory absence/stale/wrong-city/time and no false recovery; no detector code was altered here. David owns truthful unknown/missing/coverage/history display and authenticated SSE/expiry/reconnect acceptance. Stanislav owns deployment/configuration, sustained fairness/resource measurement, same-SHA release/rollback rehearsal and optional go/no-go. Local timing, fixtures, green individual PR checks and component outboxes do not discharge these gates.

**P3:** no new Ion production defect or additional release blocker was found. Corrected test/tooling assumptions are retained in the evidence, not promoted to product defects.

Day 4 changes are ready for local review and are not ready for coordinated merge/release until the owner gates above pass. They require no additional Ion production implementation. Personal integrated Day 4 remains PARTIAL because the FINAL plan's Shared G3/projection/cause/release prerequisites are unresolved; independent Ion component coverage is complete. Shared G4 remains BLOCKED. No branch was pushed, no PR was opened and neither PR #73 nor #74 was merged.
