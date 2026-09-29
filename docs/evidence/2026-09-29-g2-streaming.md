# G2 Streaming Evidence — 2026-09-29

**G2 STATUS: PARTIAL.** In the original Day 11 run, all ten scheduled profiles completed through the private
generator API. Both services reached accepted observations, finalized features,
Kafka KPI publication, and incident-service KPI persistence. Three VoLTE faults
also produced separate live OPEN-to-RECOVERY episodes and incidents. SMS
episode delivery, model invocation, public simulator dispatch, and authenticated
API/dashboard visibility were unverified or absent. Later merged private-path
verification is recorded separately at the end of this document.

The [machine-readable live results](2026-09-29-g2-live-results.json) contain
every run ID, observation event ID, finalized window ID and value, KPI window
ID, detection ID, episode ID, incident ID, and observed finalization delay.
Counts in this document come from the live Kafka topic and PostgreSQL tables,
not from local generator expectations.

## Repository

- Branch: `feature/durable-scenario-handoff`.
- Starting Day 11 commit: `a508584a2373f14647ba05d5a66ed74a656645e1`.
- SMS KPI delivery code/test commit: `72f6c95e6e904c0e13cee1a2e4dfff271862c03e`.
- The live run used `origin/main` at
  `908f4319e24772a9091eeaa7bdec543af35ef8be`. At final handoff review,
  `origin/main` had advanced to `a6da77cdab401e9468cae1988127e7f364d67a06`
  (PR #27 merged). Ion's three implementation/evidence commits were then three
  ahead and seven behind; no mainline merge or rerun against the new base was done.
- No mainline merge was attempted in final review. The pre-sync stash remains
  untouched. Pre-existing untracked `.agents/`, `.codex/`, `AGENTS.md`, and
  `graphify-out/` were excluded.
- Graphify's code graph was refreshed after the processor changes (3,153 nodes,
  7,801 edges; SQL extraction is unavailable without `tree_sitter_sql`).

## Runtime

| Component | Observed state / boundary |
| --- | --- |
| Docker Desktop / Compose | Engine 29.3.1; Kafka, PostgreSQL, Keycloak, proxy, event-generator, and processor healthy. |
| Kafka | `telecom.observations.v2` and `telecom.kpis.v2` used; external broker `127.0.0.1:9094`. Processor observation group caught up to offset 257, lag 0. |
| PostgreSQL | Healthy on `127.0.0.1:5432`; processing and incident databases queried directly. Processor Flyway reached V005. |
| Generator | Healthy private HTTP service on container port 8081. All ten process-local run statuses were `COMPLETED`, with eight published logical windows each. |
| Processor | Healthy after the SMS KPI publication rebuild; container port 8083. |
| Incident service | Host Java process on `localhost:8082`; actuator health returned 200. A process-local Java hosts file under ignored `target/` resolved the documented Keycloak issuer. |
| Proxy / Keycloak | Proxy `127.0.0.1:8080`, Keycloak healthy behind `/auth`; dashboard HTML returned 200. Anonymous incident/auth API requests returned 401. No authenticated analyst session was exercised. |
| ML | The tested Ion checkout has the Python feature builder but no model artifacts or inference endpoint. The later `origin/main` includes PR #27's packaged models and local scorer, but still lacks live HTTP/processor inference integration. |

The documented `scripts/up` reached the initial services but Git Bash resolved
`python` to a Windows Store alias. The existing venv Keycloak helper, login
theme helper, Kafka initialization, and documented Compose build/up steps were
then run directly. The generator was kept running throughout all scheduled
windows. The processor alone was rebuilt and recreated after the SMS delivery
fix; persisted Kafka and PostgreSQL state preserved the already emitted data.

## G2 run matrix

All times are UTC on 2026-09-29. `Generated` means unique event IDs observed
on the Kafka observation topic; `accepted` means persisted
`app.observation_receipt` rows. The 40 replay deliveries are excluded from
generated counts and did not add accepted rows. `Feature/KPI` means distinct
processor `feature_outbox` rows / incident `service_kpi_windows` rows. Each
run's feature window IDs exactly match its incident KPI window IDs. Rejected
counts were zero for every run and the processor rejection outbox remained
empty. `—` means no runtime detection or episode was produced.

| Scenario | Seed | Scope | Run ID | Start–end | Generated / accepted / rejected | Feature / KPI | Episode | OPEN / RECOVERY window | ML status / rank | Run result |
| --- | ---: | --- | --- | --- | ---: | ---: | --- | --- | --- | --- |
| VOLTE_IMS_OVERLOAD | 29092026 | VOLTE-MD-CENTRAL | `bca33f35-0909-4f3e-b97e-75abde07ea95` | 11:04–11:12 | 24 / 24 / 0 | 8 / 8 | `98b54c7eef5f801ee455efb18fe4bb2cd43d223202c10b26547ed02083993e5d` | 11:07 / 11:11 | UNAVAILABLE / null | PARTIAL: rule episode and incident pass; ML/public path absent |
| SMS_QUEUE_DELAY | 29092026 | SMS-MD-ROUTE-A | `4c155379-4115-4744-9bd6-02517e44c3da` | 11:04–11:12 | 16 / 16 / 0 | 8 / 8 | — | — | not invoked / null | PARTIAL: no SMS episode worker |
| VOLTE_IMS_OVERLOAD | 29092027 | VOLTE-MD-CENTRAL | `d446cd9c-1c57-4ffb-932e-a9b4aacbe71e` | 11:12–11:20 | 24 / 24 / 0 | 8 / 8 | `28f990e74b64b521f1638004f6bfe75d3aeda094c56bd649510ae6349477cc03` | 11:15 / 11:19 | UNAVAILABLE / null | PARTIAL: rule episode and incident pass; ML/public path absent |
| SMS_QUEUE_DELAY | 29092027 | SMS-MD-ROUTE-A | `78d8aa9e-32e2-44e4-98b8-532952c7eb41` | 11:12–11:20 | 16 / 16 / 0 | 8 / 8 | — | — | not invoked / null | PARTIAL: no SMS episode worker |
| VOLTE_IMS_OVERLOAD | 29092028 | VOLTE-MD-CENTRAL | `379ebd89-7bee-471f-b913-760e7f9dbb7c` | 11:20–11:28 | 24 / 24 / 0 | 8 / 8 | `471fe6cf125c1377795efaf74280785492f585d6bb7b0d3b9f8138b7b0bae232` | 11:23 / 11:27 | UNAVAILABLE / null | PARTIAL: rule episode and incident pass; ML/public path absent |
| SMS_QUEUE_DELAY | 29092028 | SMS-MD-ROUTE-A | `ddc103c6-633a-4811-af96-1332a3945bc1` | 11:20–11:28 | 16 / 16 / 0 | 8 / 8 | — | — | not invoked / null | PARTIAL: no SMS episode worker |
| NORMAL_CONTROL | 29092029 | VOLTE-MD-CENTRAL | `0f06b7ff-d585-43a4-957f-b3eab37a2e2a` | 11:28–11:36 | 24 / 24 / 0 | 8 / 8 | — | — | not invoked / null | PASS: no false VoLTE episode |
| NORMAL_CONTROL | 29092029 | SMS-MD-ROUTE-A | `ef792535-c538-4c7f-87b6-f69bfd5c8612` | 11:28–11:36 | 16 / 16 / 0 | 8 / 8 | — | — | not invoked / null | PARTIAL: KPI control verified; SMS detector absent |
| TELEMETRY_GAP | 29092030 | VOLTE-MD-CENTRAL | `c296dab5-7b0f-4d8e-9241-8b4782d8659c` | 11:36–11:44 | 15 / 15 / 0 | 8 / 8 | — | — | not invoked / null | PASS: three missing windows, no false VoLTE episode |
| TELEMETRY_GAP | 29092030 | SMS-MD-ROUTE-A | `62bf6855-78d3-425f-9366-dcade34cb9e2` | 11:36–11:44 | 10 / 10 / 0 | 8 / 8 | — | — | not invoked / null | PARTIAL: missing KPIs verified; SMS detector absent |

All eight measured windows in each degradation/control run were `COMPLETE`
and `mlEligible=true`. In each telemetry-gap run, five windows were `COMPLETE`
and eligible; the three gap windows were `MISSING` and `mlEligible=false`.
`app.interval_bucket` showed 40/40 finalized intervals in each scope, with
111 VoLTE and 74 SMS accepted inputs and three zero-input gap intervals per
scope (185 accepted inputs total).
Actual feature persistence lag after the logical window end was 10.005–11.333
seconds across the 80 windows, consistent with the configured 10-second
allowed lateness. The 15 VoLTE detections were stamped 10.586–12.186 seconds
after their window ends. These are feature/detection timings, not full
dashboard latency. There was no authenticated API latency measurement.

## VoLTE

The three degraded seeds each produced five persisted `ServiceDetectionV2`
messages (OPEN, three UPDATEs, RECOVERY), one distinct episode, and one
incident. Each incident retained workflow `status=OPEN` while its final
`technical_state=RECOVERED` and `latest_sequence=5`; it was not manually
closed. Exact detection IDs and windows:

| Seed | OPEN detection ID / sequence / window | RECOVERY detection ID / sequence / window | Incident ID |
| ---: | --- | --- | --- |
| 29092026 | `96e74c2bf8bd9b6b94c9cb2ac97cb7ae362dfd125e251ec21524b1d8a418a745` / 1 / 11:07 | `e185aef45d28e70bd241b61423a0d84f710c7acf72242e3e219bd4b542e2531a` / 5 / 11:11 | `a7e1d56d-4a6a-4ba7-a3b6-068d10896449` |
| 29092027 | `3b755b4aa08cfd51991f0cf8128f5dbca2af5e6f25fb74a972dea614ec895700` / 1 / 11:15 | `d97d74f5a9838f3fa9db1865761779af306695eb9f6700c4fe98abf45317df89` / 5 / 11:19 | `267ccf39-dce8-41ac-80ef-54ba275e8296` |
| 29092028 | `35aa3b6936b7609202b1762c978aaf4373094afea9a537e8830e8d888f73463b` / 1 / 11:23 | `35b96b50debaabfdffddf2c5c31692c8a9ce564db235269f1538751d7b15ce1e` / 5 / 11:27 | `90fb7336-8628-4a09-a3ac-7a3f17f55403` |

The first fault minute of each VoLTE run had observed CSSR 94.0%, SIP 503
count 55, and IMS CPU 97%. The live detector used the deterministic voice
rule. Its payload recorded `mlStatus=UNAVAILABLE`, `modelVersion=null`, and
`anomalyRank=null`.

## SMS

All three SMS faults reached eight persisted incident KPI windows per run.
Their first fault windows showed actual p95 delivery delays of 58,942 ms,
59,143 ms, and 58,912 ms for seeds 29092026–29092028; aligned SMSC queue
depths were 164, 250, and 203, and oldest pending ages were 71, 105, and
120 seconds. These are observed feature values, not runtime rule decisions.

`SmsDeliveryRule.evaluate` implements deterministic breach/health evaluation
and checks the aligned SMSC receipt, but no runtime component calls it for SMS
features. There were zero SMS detections, episodes, or incidents in the live
database. Calling these faults a detector pass would fabricate downstream
evidence.

### SMS episode audit

1. There is no generic detector worker consuming both services' finalized
   `ServiceFeatureWindowV2` rows. `VoiceDeliveryService` and its scheduler
   explicitly filter to `VOLTE`.
2. `SmsDeliveryRule` produces an `Evaluation`, including breach, healthy,
   severity, impact, evidence, and `mlStatus`; it does not produce an episode or
   `ServiceDetectionV2` payload.
3. `RecoveryPolicy` is service-neutral, but there is no production
   `EpisodeState` class; `EpisodeStateTest` tests `RecoveryPolicy`. The
   `VoiceEpisode` wrapper hard-codes `VoiceSetupRule`, `VOLTE`, and
   `VOLTE_SETUP_DEGRADATION` plus voice-specific detection assembly.
4. There is no generic `detection_outbox`. The existing `voice_delivery`
   table/publisher has generic topic/key columns but is populated only by the
   VoLTE evaluator. SMS also needs a durable per-scope episode state, a join to
   the aligned SMSC receipt, detection construction, and publication.
5. The detection JSON schema permits `SMS_DELIVERY_DELAY` and `SMS`, and the
   incident detection consumer is service-neutral. The missing work is
   **MISSING TEAMMATE SUBSYSTEM**, not a small service dispatch switch.

No new SMS episode implementation was added on Ion's branch.

## Normal control

The VoLTE control persisted 24 accepted observations, eight complete/eligible
feature and KPI windows, and zero detections or incidents. Its first window
showed CSSR 99.3% and two SIP 503s. The SMS control persisted 16 accepted
observations and eight complete/eligible feature and KPI windows; its first
window had p95 delivery 3,357 ms and queue depth zero. It also had zero SMS
detections/incidents, but this cannot validate SMS detector specificity while
the worker is absent.

## Telemetry gap

Each gap covers 11:38, 11:39, and 11:40 UTC. The generator emitted no service
or node observation for those minutes: actual Kafka unique counts were 15
VoLTE and 10 SMS over the full eight-minute runs, matching persisted accepted
receipts. Processor and incident-service each persisted eight windows per
service. The three gap windows per service have quality `MISSING`, null
observed KPIs, empty feature vectors, and `mlEligible=false`. Their exact
window IDs and values are in the machine-readable results. No zero or healthy
measurement was invented.

Neither gap run began with an active episode, and no detection or incident was
persisted. Therefore these live runs do not directly prove active-episode
recovery behavior. The `EpisodeStateTest` and `VoiceEpisodeTest` regressions
prove separately that UNKNOWN evidence resets the healthy recovery streak and
cannot cause a false recovery of an active VoLTE episode.

## Replay/idempotency

The first degraded VoLTE and SMS inputs were captured from Kafka and replayed
exactly once: 24 VoLTE plus 16 SMS records with the original keys, payloads,
and event IDs. The processor group then reached zero lag. Kafka contained 48
deliveries for the first VoLTE run and 32 for the first SMS run, but only 24
and 16 distinct event IDs respectively. Every distinct ID matched a persisted
receipt. The live before/after slice counts were unchanged:

| Persisted item (11:04–11:12 UTC) | Before | After |
| --- | ---: | ---: |
| Accepted observation receipts | 40 | 40 |
| Finalized feature windows | 16 | 16 |
| Incident-service KPI windows | 16 | 16 |
| Detection evidence messages | 5 | 5 |
| Incidents | 1 | 1 |
| Rejection outbox rows | 0 | 0 |

Receipt event IDs and source/scope/minute natural keys, feature window IDs,
detection IDs, and episode IDs are unique in the database. The incident KPI
consumer verifies exact same-content window replays before deduplicating.
The private generator's same-`runId` PUT retry also returned the existing run
without rescheduling it before execution; the Kafka replay is the stronger
downstream deduplication proof.

## SMS KPI delivery fix

Live reproduction found persisted SMS `feature_outbox` rows but zero SMS
`service_kpi_windows` rows for the first slot. The VoLTE delivery evaluator
intentionally filters SMS rows and had been the only KPI publisher. The new
`SmsKpiDeliveryScheduler` selects only finalized SMS rows, publishes their
stored payload to their intended Kafka topic with the stored scope key, waits
for acknowledgment, and then inserts an immutable V005
`app.sms_kpi_delivery` marker. Failure before acknowledgment leaves the row
pending for retry. A crash after acknowledgment can resend the same window ID
and payload; incident-service exact replay handling preserves one logical KPI
row. As with the existing voice publisher, concurrent processor replicas may
send the same raw record, but the consumer's stable window ID prevents a
duplicate persisted KPI. No SMS episode logic was added to this publisher.

The V005 table has a feature-window foreign key and runtime SELECT/INSERT only.
It applied successfully to the existing processing database. After rebuilding
only the processor, 16 previously stranded SMS KPI rows from the first two
slots appeared in incident-service. The final live state has **40 SMS feature
rows, 40 SMS delivery markers, and 40 SMS incident KPI rows** for the ten-run
range. The focused PostgreSQL tests cover successful publication, failure
retry, and exclusion of VoLTE features.

## ML

The tested Ion branch's `services/ml-service` contains a Python feature builder
and nine feature tests, but no model artifacts, manifest, HTTP inference
service, processor ML client, model version persistence, or rank producer.
After the live run, PR #27 merged into `origin/main` at `a6da77c`, adding
packaged VoLTE/SMS models and a local scorer. At the original handoff review,
those files had not yet been merged into this branch, and main had no HTTP inference service or
processor ML client. Sergiu's separate `feat/sergiu-days-11-12` branch contains
candidate runtime wiring; it is not merged or live-verified here. The threshold
remains frozen at 0.99 and was not changed. VoLTE live detections truthfully
report `UNAVAILABLE` and null rank/version. SMS has no runtime detections from
which to report an ML status.

## Public/private scenario path

The exercised boundary was `PUT/GET /internal/scenario-runs/{runId}` on the
event-generator container. It schedules deterministic eight-minute windows
and publishes schema-version-2 JSON observations to
`telecom.observations.v2`, keyed by scope. Its run map is process-local, and
the supplied run ID is not embedded in observation payloads; live correlation
here uses scope plus non-overlapping scheduled windows. The incident service
has a `ScenarioCommand` model/repository and OpenAPI declares
`/api/simulator/...`, but no executable public controller/dispatcher or durable
handoff is present. The public scenario runner was not exercised.

The proxy served dashboard HTML (200) and correctly protected anonymous
`/api/incidents` and `/api/auth/me` (401). No dedicated analyst credentials
were supplied or provisioned for this run, so authenticated incident/API/dashboard
visibility remains unverified despite direct PostgreSQL incident persistence.

## Verification commands

`$MAVEN` is the cached Maven 3.9.16 executable under
`C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16\...\bin\mvn.cmd`.
Java tests used Java 21 and bounded Surefire heap/metaspace where noted.

| Command | Final result |
| --- | --- |
| `.\.venv\Scripts\python.exe -B scripts/check-contracts.py` | PASS: 13 observation fixtures, four detection payloads, seven VoLTE and 12 SMS parity cases |
| `.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v` | PASS: 17 tests |
| `.\.venv\Scripts\python.exe -B -m unittest discover -s services/ml-service/tests -v` | PASS: nine feature tests; no inference tests exist |
| `& $MAVEN -q -pl services/event-generator -am '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` | PASS: 58 streaming-support + 44 generator = 102 tests |
| `& $MAVEN -q -pl services/processor -am '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` | PASS: 169 processor tests, zero failures/errors/skips |
| `& $MAVEN -q -pl services/processor -am '-Dtest=MissingWindowDecisionIT,MissingWindowHandoffIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` | PASS: nine explicit PostgreSQL integration tests |
| `& $MAVEN -q '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` from `services/incident-service` | PASS: 99 default tests |
| `& $MAVEN -q '-Dtest=FirstSliceIT,CommentConcurrencyIT' '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` from `services/incident-service` | PASS: two explicit integration tests |
| `.\.venv\Scripts\python.exe -B scripts/check-voice-parity.py` | PASS: seven fresh Java/Python payload comparisons |
| `.\.venv\Scripts\python.exe -B scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json` | PASS: 12 fresh Java/Python payload comparisons |
| `docker compose ps`, Kafka group offsets, PostgreSQL queries | PASS: required Compose services healthy; processor observation lag zero; ten run records and downstream IDs measured |
| `git diff --check` | PASS |

The first post-resume full processor attempt had one test error in a transient
Kafka offset check: a receipt was visible just before the broker reported a
committed offset, and the test dereferenced the temporarily absent position.
Its Awaitility assertion now waits for a non-null position and still requires
that the committed offset exceed the produced offset. The subsequent complete
169-test run passed. This test adjustment does not change runtime behavior.

## Remaining limitations

| Owner | Exact remaining dependency |
| --- | --- |
| Ion | No remaining observed streaming/feature/KPI delivery defect in these ten runs; validate again when teammate runtime changes land. |
| Sergiu | Integrate and live-verify the SMS episode/evidence worker with aligned SMSC input, durable state and detection publication, plus real VoLTE/SMS ML inference without changing the 0.99 threshold. His unmerged candidate branch requires coordination with Ion's SMS KPI publisher and the VoLTE-only scheduler/service filters. |
| Denis | Implement the public authenticated simulator controller, durable run ledger, and dispatch handoff to the generator. |
| Environment | Docker, Kafka, PostgreSQL, Keycloak and proxy were healthy during the run. PR #27 model packaging is now on main but unmerged into this branch; no authenticated test analyst session was available for dashboard/API verification. |

The gate remains **PARTIAL** until live SMS episodes, real model ranks,
public durable dispatch, and authenticated API/dashboard visibility are
exercised. Local component tests and the private live run do not substitute
for those missing boundaries.

## Post-merge compatibility check

This check is separate from the original 11:04–11:44 UTC live run. The feature
branch was `8aeee4c721598079ee54a935b59fc6e722eb32e1` before the merge;
fetched `origin/main` was `a6da77cdab401e9468cae1988127e7f364d67a06`;
the conflict-free merge commit was `ed0fc38dd77bf60e9842375502d60b5cd20f1fdf`.
Main changed ML packaging and a dashboard sample-volume component, with no
overlap in Ion's generator or processor implementation. Processor migration
V005 remains unique; main ends at V004.

PR #27 packages both VoLTE and SMS Isolation Forest artifacts, calibration
files, a manifest with `isoforest-v2-synthetic-1` and SHA-256 checksums, and a
local Python scorer. The 13 ML tests passed, including artifact integrity and
finite ranks for eligible VoLTE and SMS vectors. This proves local model load
and score only. There is still no HTTP inference service, processor ML client,
runtime invocation for either service, anomaly-rank propagation into
`ServiceDetectionV2`, or timeout/unavailable inference handoff. The VoLTE
episode assembler still writes null model version and rank. Main did not add an
SMS episode worker, SMS detection outbox, or SMS detection publication.

Post-merge commands and results (Java 21, Maven 3.9.16, bounded Surefire heap):

| Command | Result |
| --- | --- |
| `.\.venv\Scripts\python.exe -B scripts/check-contracts.py` | PASS: 13 observation fixtures, four detection payloads, seven voice and 12 SMS parity cases |
| `.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v` | PASS: 17 tests |
| `.\.venv\Scripts\python.exe -B -m unittest discover -s services/ml-service/tests -v` | PASS: 13 tests, including four new packaging/scoring tests |
| `.\.venv\Scripts\python.exe -B scripts/check-voice-parity.py` | PASS: seven comparisons |
| `.\.venv\Scripts\python.exe -B scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json` | PASS: 12 comparisons |
| `mvn -q -pl services/event-generator -am '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` | PASS: 58 streaming-support and 44 generator tests |
| `mvn -q -pl services/processor -am '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` | PASS: 58 streaming-support and 169 processor tests, including SMS KPI delivery, finalization, feature builder, rules, and episode tests |
| `mvn -q '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` in incident-service | INCOMPLETE: 93 tests ran, 71 context errors and zero assertion failures; Testcontainers could not find a Docker engine |
| `git diff --check` | PASS |

The local Docker pipe was absent during the incident-service run, so its
database-backed regression suite needs a repeat when Docker is available.
This environment limitation does not alter the prior live run's measured
events, windows, detections, or incidents. No 40-minute scenario replay was
needed: that intermediate merge made no generator or processor runtime changes.
G2 remained **PARTIAL** at this stage, before Sergiu's detector/ML merge.

## Final post-Sergiu merge verification

This is new evidence for the merged runtime, not a rewrite of the original
11:04–11:44 UTC measurements. Previous Ion HEAD was `7a8809f77bf4640cb2eeae93ee3b0239472a4c06`;
fetched main was `66383bf171ed637d1c49e3120a46e027f5de053c`;
the semantically resolved merge commit was `5e8e8c75760fc477a23d867b2f29d991159eb2c4`.

### Delivery architecture

The merge initially exposed two SMS KPI publishers: Ion's direct
`SmsKpiDeliveryScheduler` and Sergiu's generalized `VoiceDeliveryService` outbox.
The automatic merge also retained VoLTE-only discovery filters in both the
delivery scheduler and service. The final implementation removes the direct
SMS scheduler, allows both `VOLTE` and `SMS` through the shared scheduler and
service, and enqueues KPI and any detection in one database transaction.
`voice_evaluated_window` prevents reevaluation; `voice_delivery` retries failed
Kafka sends with stable IDs. Processor tests cover one publication per service,
failed SMS send and retry, and deterministic SMS detection when ML is unavailable.
V005 and its table remain unchanged because that migration applied to the
original Day 11 database; the table is no longer written by this runtime path.

### Private ML and episode verification

The Compose ML service reached `/health/ready`. Canonical HTTP normal/fault
ranks were VoLTE `0.9776785714285714` / `1.0` and SMS
`0.8973214285714286` / `0.9890873015873016`, all finite and versioned
`isoforest-v2-synthetic-1`. The frozen `0.99` threshold was unchanged; SMS
deterministic episodes do not depend on an ML candidate. Both focused Java
replays passed through ingestion, feature finalization, real HTTP inference,
the service rules, durable episode state and detection outbox. Their five-run
fixtures per service include three faults, a normal control, and a telemetry
gap; they are test replays, not public simulator runs. The separate unavailable
ML test still opens and recovers an SMS episode with null rank/version. The
client has a 250 ms request budget, eight permits, no inline retry, and maps
timeout, unavailable and ineligible inputs to distinct statuses.

### Targeted merged live runs

One private generator run per service was scheduled in parallel for
18:31–18:39 UTC on 29 September 2026 with seed `29092031`. Both returned
`COMPLETED` with eight published windows. The merged Compose generator,
processor and ML service, host incident consumer, Kafka and PostgreSQL were
running. Counts below were read from processing and incident databases, not
from generator expectations. VoLTE used scope `VOLTE-MD-CENTRAL`; SMS used
scope `SMS-MD-ROUTE-A`.

| Service / run ID | Accepted / feature / incident KPI | Episode ID | OPEN detection ID / rank | RECOVERY detection ID / rank | Incident ID |
| --- | --- | --- | --- | --- | --- |
| VoLTE `0437f0c9-0cbf-4873-8988-8cb4faf8c923` | 24 / 8 / 8 | `97fa89e5a6c18ca21d7761ee3b32218aec226accc831ac2425f727f88cc6be0e` | `e093e69dc6f3f1ee8e602bc9713ead4eecc609830f0b53e6b6704cb963e72567` / `1.0` | `14d32468882643422f16238e4acf0cd066c9f5866cdabe1f942b4188c8a300b5` / `0.4548611111111111` | `bd8a4b63-a092-41d6-b9a3-91f48d8b5600` |
| SMS `be919e74-8faf-4394-87d6-28e031e7d19c` | 16 / 8 / 8 | `332c85c6c11a0eca4ef9b11dcc411efb43eb0b64bae9edeaa6abf286e08639c6` | `d8eb3efd382547a0c5e52015cfe9ff904f1c2befbb1cf98d2853b50ea614f940` / `0.9950396825396826` | `5537ec6af27f516ea26122db79161bef03c837611af327ea26be13ec6801ddc4` / `0.9821428571428571` | `5908428b-b1b3-44c5-b230-ccd43bb77433` |

Each service persisted five detections in one episode: OPEN, three UPDATEs,
RECOVERY. All ten detections have `mlStatus=OK`, model version
`isoforest-v2-synthetic-1`, and a finite rank. Both incidents finished with
`technical_state=RECOVERED` and `latest_sequence=5`. The processor outbox has
16 acknowledged KPI messages and 10 acknowledged detections. All 16 incident
KPI window IDs are unique and match the 16 features; V005 SMS marker rows for
this interval are zero. Rejection rows are zero. Exact read-only results are
in ignored `target/final-g2-live/results.json` locally. No second three-seed
live matrix was needed after these two merged end-to-end runs passed.

### Final regression and remaining gate

| Command | Result |
| --- | --- |
| `.\.venv\Scripts\python.exe -B scripts/check-contracts.py` | PASS: 13 observation fixtures, four detection payloads, seven voice and 12 SMS parity cases |
| `.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v` | PASS: 17 tests |
| `.\.venv\Scripts\python.exe -B -m unittest discover -s services/ml-service/tests -v` | PASS: 15 tests, including HTTP API and model evaluation |
| `.\.venv\Scripts\python.exe -B scripts/check-voice-parity.py` / `check-sms-parity.py --java-output services/processor/target/sms-parity-java.json` | PASS: seven / 12 comparisons |
| `mvn -q -pl services/event-generator -am '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` | PASS: 58 streaming-support and 44 generator tests |
| `mvn -q -pl services/processor -am '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` | PASS: 58 streaming-support and 175 processor tests; two HTTP replay methods skipped until a service URL was set |
| `mvn -q -pl services/processor -am '-Dtest=VoiceDeliveryTest#livePackagedModelEnrichesRecoveredVoiceEpisode,SmsDeliveryTest#livePackagedModelEnrichesRecoveredSmsEpisode' '-Dsurefire.failIfNoSpecifiedTests=false' test` with `ML_SERVICE_URL=http://127.0.0.1:8090` | PASS: two real HTTP replay methods |
| `mvn -q -pl services/processor -am '-Dtest=MissingWindowDecisionIT,MissingWindowHandoffIT' '-Dsurefire.failIfNoSpecifiedTests=false' test` | PASS: nine PostgreSQL integration tests |
| `mvn -q test` in incident-service | PASS: 99 tests with Docker restored |
| `mvn -q '-Dtest=FirstSliceIT,CommentConcurrencyIT' test` in incident-service | PASS: two explicit PostgreSQL integration tests |
| `git diff --check` and `docker compose config --quiet` | PASS |

The Docker-backed incident-service suite no longer has the earlier 71 context
errors. Two Maven runs attempted concurrently during the live window exceeded
the workstation paging file; their sequential reruns above passed. The public
`/api/simulator/...` paths remain declared in OpenAPI, and the incident service
has a scenario command model and repository, but current production source has
no public controller or dispatch worker. Dashboard HTML returned 200 and an
anonymous incident API request returned 401. No authenticated analyst session
was available for a live API/dashboard check.

**Ion streaming: PASS. Sergiu detector/ML private integration: PASS. Combined
private generator-to-incident pipeline: PASS for the targeted VoLTE/SMS pair.
Shared public G2: PARTIAL** until Denis's durable authenticated simulator
dispatch and authenticated API/dashboard acceptance are exercised.
