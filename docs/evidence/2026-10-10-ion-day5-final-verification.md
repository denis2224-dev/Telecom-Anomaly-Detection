# Ion Day 5 final streaming verification

**ION DAY 5 BLOCKED. Shared G5: BLOCKED.** Fresh executable local technical verification passed at `4044327d8af813d728627eba52d4688790b8f7fa`. Required connected-runtime, deployment rollback and independent owner acceptance remain unavailable. No Ion production defect was confirmed; production, migrations, frozen models/policies, backend, dashboard and infrastructure were preserved.

Evaluated base/tested SHA: `4044327d8af813d728627eba52d4688790b8f7fa`; source tree: `d69891ce81db0dae1f6734e60e57e9087eb91a7c`. Branch: `verify/ion-day5-20261010`. Evidence/runbook changes are local and uncommitted; no push, merge, deployment or shared-environment mutation occurred. The dirty primary checkout and 29 original worktrees were preserved across the recorded audit checkpoint; untracked contents were not hashed. The [manifest](2026-10-10-ion-day5-final-manifest.json) records exact commands, cwd, per-execution UTC intervals, class/method inventories and SHA-256 evidence.

## Authority and prior work

Read the three supplied FINAL PDFs: audit all six pages, Ion Day 5 pp. 6-7 and shared acceptance/ownership/rollback pp. 11-16, with supporting source contract pages. Their SHA-256 values are in the configuration ledger. Requirements are distinguished from executed proof. Historical G4 failures were not assumed current: Denis's geographic API sign-off records later authenticated work at `5475867`; that remains supporting evidence for its own revision, not a new run at this SHA.

Fresh GitHub metadata confirms merged PR #73 (city scenarios and immutable history), #78 (negative controls/three-minute reconciliation and corrected numeric assertions), #77 (cause/ML/fallback/replay), and #86 (geographic detector, historical-baseline replay and expanded persisted parity). Their implementations/tests were reused. No resolved safeguards were overwritten or recreated. Four main workflows are successful at the evaluated SHA; [captured CI jobs and links](assets/ion-day5-final-20261010/ci.json) include successful processor outage/replay, packaged-model and geographic parity steps. CI remains a separate evidence class.

## Fresh verification

Runtime: Java 21.0.12.1+1, Maven 3.9.16, Python 3.13.7, Docker 29.3.1, Testcontainers 1.21.4, embedded Kafka 3.9.2, disposable PostgreSQL 16.4 Alpine and Kafka container 3.9.1. Pinned Python dependencies and `pip check` passed. The Windows wrapper failed before Maven startup with `Cannot index into a null array`; installed supported Maven 3.9.16 ran identical project goals. Exact executable paths are execution metadata, not reproduction prerequisites.

Counts below are **reported / passed / failures / errors / skips** for separate invocations. They overlap and must not be added together.

| Execution | Result | UTC interval |
| --- | --- | --- |
| load | 4 / 0 / 0 / 4 / 0 | `2026-10-10T20:29:55.168571+00:00` to `2026-10-10T20:31:31.816557+00:00` |
| load-retry | 4 / 4 / 0 / 0 / 0 | `2026-10-10T20:35:29.020575+00:00` to `2026-10-10T20:36:34.952057+00:00` |
| reactor | 1163 / 1159 / 0 / 0 / 4 | `2026-10-10T20:39:53.580370+00:00` to `2026-10-10T20:55:41.020897+00:00` |
| missing | 9 / 9 / 0 / 0 / 0 | `2026-10-10T20:57:23.715199+00:00` to `2026-10-10T20:57:58.031849+00:00` |
| contracts | PASS | `2026-10-10T20:30:04.729321+00:00` to `2026-10-10T20:30:14.536300+00:00` |
| python-root | 61 / 61 / 0 / 0 / 0 | `2026-10-10T20:30:14.579510+00:00` to `2026-10-10T20:30:35.594069+00:00` |
| python-ml | 54 / 54 / 0 / 0 / 0 | `2026-10-10T20:30:35.610892+00:00` to `2026-10-10T20:33:52.756295+00:00` |
| pip-check | PASS | `2026-10-10T20:33:52.782123+00:00` to `2026-10-10T20:33:56.206200+00:00` |
| legacy-voice-parity | PASS | `2026-10-10T20:54:33.500357+00:00` to `2026-10-10T20:54:33.910662+00:00` |
| geographic-voice-parity | PASS | `2026-10-10T20:54:33.921606+00:00` to `2026-10-10T20:54:35.006203+00:00` |
| persisted-voice-parity | PASS | `2026-10-10T20:54:35.015303+00:00` to `2026-10-10T20:54:36.393862+00:00` |
| legacy-sms-parity | PASS | `2026-10-10T20:54:36.409917+00:00` to `2026-10-10T20:54:37.170302+00:00` |
| geographic-sms-parity | PASS | `2026-10-10T20:54:37.189043+00:00` to `2026-10-10T20:54:38.346398+00:00` |
| persisted-sms-parity | PASS | `2026-10-10T20:54:38.357537+00:00` to `2026-10-10T20:54:39.653592+00:00` |

Full reactor: **1159 passed, 0 failures, 0 errors, 4 skipped**. Explicit missing-window discovery: **8 decision + 1 handoff passed**. Repository Python: **61 passed**; ML-service Python: **54 passed**. Contract validators passed. Six parity commands compare fresh Java exports: legacy 7 VoLTE/12 SMS, canonical geographic 30 VoLTE/32 SMS, expanded persisted Day 5 90 VoLTE/90 SMS, 261 payload comparisons; see actual command output for comparison details. The optional absent-endpoint tests are retained as skips rather than converted to local HTTP acceptance.

- `md.utm.telecom.processing.GeographicDetectionTest.pinnedHttpScorerAcceptsCityVectorsAndRejectsChangedBaselineWithoutBlockingRules(String)`: Environment variable [ML_SERVICE_URL] does not exist
- `md.utm.telecom.processing.kpi.SmsDeliveryTest.livePackagedModelEnrichesRecoveredSmsEpisode`: ML_SERVICE_URL absent; source assumption requires an approved compatible private endpoint. Surefire message was empty.
- `md.utm.telecom.processing.kpi.SmsShadowReplayTest.freshObservationsReachDurableShadowStorageWithoutChangingRuleIncidents`: Environment variable [SMS_SHADOW_REPLAY_DIR] does not exist
- `md.utm.telecom.processing.kpi.VoiceDeliveryTest.livePackagedModelEnrichesRecoveredVoiceEpisode`: ML_SERVICE_URL absent; source assumption requires an approved compatible private endpoint. Surefire message was empty.

The historical PR86 report had 1,182 reported cases with ML configured. Fresh discovery reports 1,163: `GeographicDetectionTest` contributes 26 rather than 45 because its twenty-scope HTTP parameterized method is disabled at method level when `ML_SERVICE_URL` is absent, producing one skipped template. Every other suite's reported count matches the historical inventory. The two legacy live-model methods also skip; shadow replay remains optional. The 19-case reported-count difference and 22 fewer passes are environment-driven discovery differences, not added/removed tests. The compared test source is unchanged between `1cac338` and this SHA.

The first sandbox load invocation had 4 context errors caused by denied Docker access. Its log/XML digests remain separate; the authorized unchanged-source rerun passed 4/4. No test suppression, threshold/deadline adjustment or source fix was used to turn that environment failure green.

## Streaming reconciliation and safety

[Fresh reconciliation](assets/ion-day5-final-20261010/reconciliation.json): one continuous producer, seed 42, ten cities/twenty scopes, fixture interval `[2026-10-07T08:00:00Z,08:03:00Z)`. Each minute has 50 offers/ACKs/wire observations/logical receipts, 20 finalized KPIs and 20 matching coverage facts; total **150/60/60**, zero legacy receipts. The test validates key/body identity, unique logical IDs, authoritative sources and exact expected/received/usable source sets under the same canonical feature/coverage window. It uses controlled scheduling/logical time and real Kafka/PostgreSQL, not fresh live wall-clock minutes or incident-service/API/browser acceptance.

Uncertain ACK retry preserves 50 logical observations despite 51 physical records. Mixed ORH degradation/EDI service gap keeps CHI/BAL and all other cities independently sourced; the gap retains independent SMSC evidence and null service metrics. Complete measured zero, absent/reported-missing/heartbeat/stale service and required-node controls remain distinct. Replay/conflict/late/race, failed-transaction no-ACK, fairness and dependency-outage/restoration suites pass at their tested boundaries; actual outages remain separate from injected failure controls.

History tests preserve `initial-demo-v1`, pin geographic job/authority/spec, reject changes, resume committed windows and durable owned outputs without rewriting evidence or advancing live episodes. Actual non-web Spring/Kafka/PostgreSQL smoke asserts **50 receipts, 20 features, 40 owned deliveries**, and immutable retry. No full two-day backfill or capacity claim is made. Twelve frozen artifact comparisons match evaluated main and preceding main `9e8501b`; model/calibration checksums match the manifest, threshold 0.99 and six-feature order stay fixed. Configuration hashes distinguish Windows raw bytes from Git-normalized source content and fixture activations from approved deployment settings.

## Acceptance matrix

`status` below covers the stated acceptance boundary; `componentStatus` records fresh Ion technical proof. Every required `finalReleaseStatus` is BLOCKED because no approved common release candidate/runtime or sign-off exists. Remaining owner rows and optional DET-06 are explicitly recorded in all 38 manifest cases.

| Case | Ion technical evidence | Case status | Remaining boundary / limit |
| --- | --- | --- | --- |
| GEO-01 | PASS | BLOCKED | Protected ten-city catalogue representation needs Denis on the approved runtime. |
| GEO-02 | PASS | PASS | Source authority/identity/closure verified; final release still requires common-candidate approval. |
| GEO-03 | PASS | BLOCKED | Independent Kafka/PG measurements pass; authenticated status/navigation and unaffected-city API/UI controls are unexecuted. |
| STR-01 | PASS | PASS | Source authority/identity/closure verified; final release still requires common-candidate approval. |
| STR-02 | PASS | BLOCKED | Processor/detector replay passes; final-runtime incident-service projection/count reconciliation remains unexecuted. |
| STR-03 | PASS | PASS | Source authority/identity/closure verified; final release still requires common-candidate approval. |
| STR-04 | PASS | PASS | Controlled restart/fairness and actual disposable dependency interruption/recovery; no deployed process/resource claim or Stanislav sign-off. |
| STR-05 | PASS | BLOCKED | Private reservations/restart/terminal-state behavior passes; approved authenticated public-to-private path remains unexecuted. |
| STR-06 | PASS | BLOCKED | 150 receipts/60 KPIs/60 coverage in controlled integration; no three fresh live UTC minutes through incident-service/API/browser. |
| HIS-01 | PASS | PASS | Bounded disposable history/resume only; consumer projection/startup deployment and two-day capacity are separate owner dependencies. |
| COV-01 | PASS | BLOCKED | Processor truth/coverage passes; protected missing-projection UNKNOWN and authenticated display proof unexecuted. |

## Changes, blockers and handoff

Changes are limited to this new report, machine-readable manifest, [gap matrix](2026-10-10-ion-day5-gap-matrix.md), [blocker register](2026-10-10-ion-day5-blockers.md), sanitized fresh assets and the [updated runbook](../runbooks/ion-day5-release-verification.md). No new test or production fix was warranted. Existing regression behavior was exercised without weakening it.

Stanislav receives the manifest, configuration/catalogue/frozen artifact ledger, fresh Java exports/golden window identities, exact replay/outage inventories and the prepared safe feature-off procedure. Denis/David still need protected same-window projection/API/UI and session verification; Sergiu retains independent detector/ML approval. Stanislav must select/approve final configuration, address Compose's geographic-history forwarding limitation, obtain authorized backup/restore/feature-off evidence and coordinate two mentor reproductions. Keep compatible geographic readers while queued records drain; preserve additive migrations, offsets, receipts, incidents and history. No rollback was executed, and no teammate acceptance is claimed.

Fixed synthetic peer baselines and packaged models do not establish geographic real-world generalization. Anomaly rank is not probability; subscriber impact remains unavailable. Optional power/probe correlation remains NOT_IN_SCOPE without a documented G2 enablement decision.

Reproduce using the portable commands at the top of the runbook. The [asset guide](assets/ion-day5-final-20261010/README.md) distinguishes portable packaged evidence from optional original-machine provenance and explains byte-preserving hash verification. The fresh technical package is reviewable, but **required live and release gates remain BLOCKED**.
