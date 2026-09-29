# G2 Streaming Evidence — 2026-09-29

**G2 STATUS: PARTIAL.** All ten scheduled profiles completed through the private
generator API. Both services reached accepted observations, finalized features,
Kafka KPI publication, and incident-service KPI persistence. Three VoLTE faults
also produced separate live OPEN-to-RECOVERY episodes and incidents. SMS
episode delivery, model invocation, public simulator dispatch, and authenticated
API/dashboard visibility are still unverified or absent as described below.

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
packaged VoLTE/SMS models and a local scorer. Those files have not been merged
into this branch, and current main still has no HTTP inference service or
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
