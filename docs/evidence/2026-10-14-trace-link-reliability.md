# Trace Link Day 5 producer reliability

Milestone date: 14 October 2026. Work executed on 12 October 2026,
Europe/Chisinau. This is producer-owned local/disposable verification. Complete
Trace Link release readiness remains **BLOCKED** on approved transport/consumer,
connected acceptance, receiver reproduction and five-owner sign-off.

Day 4 was published as Draft [PR #95](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/95),
base `feature/ion-tracelink-domain-producers` at
`e9a79c1f2fa2c5a59b366336e74b4e3b2b94bbb8`, head
`feature/ion-tracelink-scoped-scenarios` at
`e3d0a41da141f2a836edbd35258ba878efc335dd`. Remote heads matched, no existing PR
was found, and the stack was exactly three commits. The local authenticated CLI
created it; no token was retrieved. All six Day 4 CI checks passed. A sandbox-only
authentication/network failure was resolved through authorized host execution.

Day 5 uses a separate worktree/branch
`feature/ion-tracelink-day5-reliability` stacked on #95. Tested implementation:
`6a6231113f9655534e14e54af204fb6c51cb4b92`. Documentation/evidence are committed
separately. Original dirty/untracked checkouts and protected owner branches were
preserved. No applied migration, public API, consumer, production topic,
deployment flag, held-outbox release or legacy finalization rule changed.

## Reliability assessment and implemented slice

| Boundary | Previously verified behavior | Day 5 action/result | Ownership or remaining gate |
| --- | --- | --- | --- |
| Native import | Exact retry; duplicate source rejection; checksums/counts; batch rollback; committed-batch interruption/resume; manifest conflict; concurrent import; runtime grants | Reuse those real PostgreSQL tests; add actual unavailable-before-import/recovery drill and retention during unfinished import | Source remains immutable; no regenerated archive rows |
| Native producer | Deterministic primitives/rollups; duplicate inputs; conflicting identity/payload; half-open ranges; no reverse fabrication; no-clobber artifacts | Add corrupt persisted source rejection before output, artifact preservation through actual DB outage, eligible-time and independent-direction controls | Local SELECT-only job; no transport intent |
| Scenario identity/parser | Eight-minute profiles, scope reservation, adjacent windows, directions, pure restart behavior | Fix noncanonical first-use run ID and permissive artifact JSON; two failing-before regressions | Five executable types; 13 unsupported |
| Local restart | Immutable in-memory snapshots; interrupted restore | Add bounded versioned/checksummed disk checkpoint codec, flush/no-clobber, explicit UNKNOWN_NOT_TRACKED publication, stopped/completed identity retention and concurrent exact replay | File history is generation evidence, never ACK proof |
| Missing/stale/late | Validator quality/snapshot checks; native missing/zero/unknown quality; evaluator negative controls | New recovery-minute gap and zero/eligible-source controls; reuse complete primitive/validator/evaluator suites | Detector/incident recovery acceptance PENDING_REVIEW |
| Legacy outboxes/replay | Commit-before-ACK, durable replay, finalizer concurrency, retention fences and actual dependency outage/restoration | Run complete existing suites unchanged | Legacy results do not establish new-domain delivery |
| Retention | Only explicit completed legacy receipts/ACK-marked rejections can expire; active jobs/episodes/history/monitoring fence cleanup | Real enabled cleanup retains all native source/catalogue/checkpoint rows during incomplete and completed import; exact V016 held row/grants survive cleanup | No new retention job; indefinite source/artifact storage has capacity costs |
| Connected delivery/capacity | No approved new-domain Kafka path | **BLOCKED**; future send/ACK/mark acceptance cases documented | Denis/Stanislav approval and compatible consumer/environment required |
| Receiving-owner acceptance | Author fixtures and tests only | Handoffs prepared for Sergiu, Denis, David and Stanislav | Independent reproduction, API/UI/Grafana, deployment/backup and five-owner sign-off NOT VERIFIED |

## Restart and evidence semantics

The new `TraceLinkScenarioCheckpoints` file format preserves command, run ID,
eight expected identities, exact history prefix and generation state. Files are
bounded to 8 MiB/128 retained runs/eight windows/three observations per window,
with strict JSON, checksum verification and no-clobber publication. Changed
parameters, invalid history/status/identity, unknown metadata, future history,
overlap and conflicting existing files fail before restoration/output.

Stop before minute 1, during degradation and during recovery retains the original
reservation and exact history. Restoring unfinished work yields INTERRUPTED and
releases nothing. Completed retries return the original completed identity and
release nothing. Concurrent exact commands share one run; competing exact
artifact writers create one copy. No background thread, uncontrolled executor,
unbounded queue, automatic resume or reservation eviction was introduced.

Every snapshot and checkpoint reports publication `UNKNOWN_NOT_TRACKED`.
Missing publication metadata is rejected. Saving local history is not atomic
with an external send, and a completed local run proves neither delivery nor
acknowledgement. There is no delivery-level exactly-once claim or power-loss/
directory-fsync guarantee. Losing a checkpoint requires external reconciliation.

Recovery gaps in minutes 6-7 remain MISSING with null primitives and zero
coverage; only a fresh measured minute 8 supplies valid evidence. Stops emit no
fictitious recovery. VALID, PARTIAL, MISSING, STALE, INSUFFICIENT_EVIDENCE,
measured zero and zero denominator remain distinct. Archive calculations do not
promote unknown source quality to healthy. Wrong city/time, opposite direction
and stale snapshot controls remain independent of evaluator truth. Actual
detector transition acceptance is still pending.

Native historical windows remain 300 seconds in `trace-link-md-30d-v1`, UTC
`[2026-09-10T09:00:00Z,2026-10-10T09:00:00Z)`. Live simulation remains a separate
60-second `trace-link-live-synthetic-v1` namespace. No source upsampling,
reverse-history invention, legacy rule weakening or truth leakage occurred.

## Failure drills and retention

The new outage drill pauses/unpauses the actual disposable PostgreSQL server.
Before an import, the unavailable database prevents transaction creation and
leaves zero registered dataset rows. A read-only producer fails while unavailable,
creates no absent artifact and preserves a previously created file. After
restoration, bounded import/retry and producer retries preserve source rows,
checksums, payloads and exact artifact bytes. Timings and exact suite results
are in [verification.json](assets/trace-link-reliability/verification.json).
These are local failure/recovery timings, not connected capacity or backlog-drain
measurements.

The existing importer tests retain real transaction rollback before batch commit,
interruption after committed batch, exact resume, conflict/checksum rejection,
source immutability, no DDL and no cross-database access. An enabled retention
poll deletes an eligible old published rejection while retaining every native
table byte-for-byte, first with an incomplete checkpoint and again after resume.
The frozen V016 SQL is separately applied unchanged and its held row remains
unchanged after producer and retention jobs. The retention-specific additions use
a deterministic guard harness with real SQL; the existing `RetentionJobTest`
independently verifies the guard against a real disposable Kafka broker.

No real retention defect was found. Source/checkpoint/projection/outbox tables
and incomplete investigation files have no deletion lifecycle. Retaining them
indefinitely does not establish storage sizing, automatic quotas or a future
safe cleanup policy. Monitor storage; future retention requires owner review,
reference/provenance checks and verified backup/restore.

## Reproducible verification

The final **single clean reactor** reported **1,250 tests: 1,246 passed, four
skipped, zero failures/errors**, in 17m51s. Streaming support: 120; generator:
138; processor: 992 reported, including the four skips. The earlier focused
run reported 30 tests, 29 passed and one archive-input skip; it is not added to
the full totals. All archive/V016 checks ran and passed in the clean reactor.

Repository Python: **119 passed**. Contracts passed. Independent arithmetic:
54 cases per language with maximum difference zero; archive producer parity:
14 PostgreSQL comparisons with maximum difference zero. Legacy persisted parity:
seven VoLTE and twelve SMS payloads matched every field. Scenario arithmetic:
144 measurements passed; twelve evaluator expectations remain PENDING. The
external reference engine passed 708,498 checks and adapter compatibility passed
75 checks. These counts describe separate verifications, not additional reactor
test cases.

The four optional skips are GeographicDetectionTest, VoiceDeliveryTest and
SmsDeliveryTest live HTTP cases without `ML_SERVICE_URL`, plus SmsShadowReplayTest
without `SMS_SHADOW_REPLAY_DIR`. No skipped case is counted as passed. The final
native outage drill measured 12,136 ms while unavailable and 720 ms for restored
availability plus bounded import/retry. Source rows and artifact bytes remained
unchanged. The packaged checkpoint rehearsal generated three windows/nine
observations with byte/timestamp-stable replay and zero windows after restart.

Java 21; cached Maven 3.9.16; PostgreSQL 16.4 Testcontainers with repository
provisioning; approved legacy disposable Kafka tests. No existing demo databases
or volumes were reset. Host/resource details, exact commands, individual suites,
skip reasons and artifact/log hashes are in
[verification.json](assets/trace-link-reliability/verification.json).

```powershell
$maven = 'C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd'
$env:PYTHONPATH='C:/OrangeSystems/Program/ion-trace-link-evidence/python-deps'
python scripts/check-contracts.py
python -m unittest discover -s tests -v
& $maven --batch-mode --no-transfer-progress '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2' -pl services/streaming-support,services/event-generator,services/processor -am '-Dlogging.level.org.springframework.jdbc.core=INFO' '-DtraceLink.referenceRoot=C:/OrangeSystems/task/Trace_Link_Integration_Pack' '-Dtrace.link.archive=C:/OrangeSystems/task/Trace_Link_Integration_Pack/data' '-Dtrace.link.v016.directory=C:/OrangeSystems/Program/ion-domain-producer-evidence/v016' '-Dtrace.link.v016.sha256=bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848' clean verify
node C:/OrangeSystems/task/Trace_Link_Integration_Pack/tests/engine.test.js
python scripts/check-voice-parity.py
python scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json
python scripts/trace_link_scenario_contract.py --java-output services/event-generator/target/trace-link-evaluation/domain-measurements.json
```

The full clean reactor includes ReplayIT, FinalizerRaceIT, DependencyOutageIT,
geographic coverage/replay, native importer/producer/primitive, scenario concurrency,
truth-leakage, retention and legacy VoLTE/SMS gates. Optional archive/V016 tests
are selected explicitly above. No overlapping focused/full totals are added.
Missing `ML_SERVICE_URL` / `SMS_SHADOW_REPLAY_DIR` inputs remain documented skips.
Independent Java/Python/JavaScript/PostgreSQL arithmetic uses 54 goldens per
language; SQL runs only in a labelled network-none disposable parity container.

Initial attempts remain recorded: two expected red product regressions; sandbox
JUnit temporary-directory cleanup errors; Python dependency import errors before
using the cache; and an incorrect new test expectation of DataAccessException
instead of CannotCreateTransactionException at transaction creation. The latter
was a test assertion correction, not a production recovery fix. Graphify's broken
launcher was replaced by its installed `python -m graphify update .` entrypoint;
generated graph files remain untracked.

## Capacity, handoffs and readiness

`scripts/load-profile.json` remains BLOCKED: 120-second warmup, 900-second steady
state, owner-bounded burst, recovery and backlog drain. No approved compatible
new-domain consumer exists. New-domain Kafka offered/ACK counts, persisted/
finalized rates, lag, CPU/memory under connected load, latency, failure rate,
recovery and drain are **NOT MEASURED**. No local test or represented call/message
count is substituted for those measurements.

The [scenario runbook](../runbooks/trace-link-scenarios.md),
[streaming reliability procedure](../runbooks/streaming.md#trace-link-producer-reliability)
and [receiving-owner handoffs](../architecture/trace-link-reliability-handoffs.md)
cover source/schema ownership, reproducible commands, stable statuses, missing/
stale/late controls, credentials, import resume, retention and future ACK/send/
mark failure cases. Kafka is DISABLED / PENDING_REVIEW. Connected incidents,
authenticated API/UI/Grafana, capacity, deployment/backup/restore, receiver
reproduction and the complete five-owner release gate remain BLOCKED or NOT
VERIFIED. Draft publication is reviewable engineering delivery, not release
acceptance.
