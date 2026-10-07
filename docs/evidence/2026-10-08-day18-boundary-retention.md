# Revision 3 Day 18: boundary and retention evidence

DAY 18 COMPLETE — READY FOR REVIEW. G4 was not started; review by Denis and Sergiu remains the handoff before any processing freeze.

Requested evidence filename: 2026-10-08. Actual execution: **2026-10-07, Europe/Chisinau**. All database cleanup used disposable PostgreSQL/Kafka; no shared or historical production data was deleted.

## Repository and scope

- Base SHA: `cebdbfb8ff7d6db070a99e17743f9a82dca97d58`. Fresh `origin/main` remained at this SHA on the publication refresh. PR #68 was verified MERGED with this merge SHA.
- Implementation SHA: `992cff88a629ac2530d28c935340cc767a12ccd6`. The subsequent evidence commit adds this report and saved snapshots; it does not change implementation.
- Branch: `feature/ion-day18-boundary-retention`; worktree: `C:/OrangeSystems/Program/ion-day18-boundary-retention`.
- Existing checkout edits/worktrees were preserved. No automatic merge, detector/episode formula change, incident-service, dashboard, or deployment edit.
- `V012__processor_retention.sql` was allocated after refreshing main and verifying V011 was latest. The existing geographic migration regression is pinned to V011; the new retention migration regression covers V011→V012 separately.

## Existing coverage reused

`WindowFinalizerTest` supplies exact due-time discovery, immutable feature identity, lock/commit failures and seven shared persisted voice cases. `LateInputIT` retains the SERVICE/NODE/HEARTBEAT microsecond closure matrix, conflicting identity precedence and rejection ACK-before-mark retry. `FinalizerRaceIT` and `ReplayIT` retain real PostgreSQL concurrency, raw Kafka deletion and 49-hour logical-clock replay. `IngestionIntegrationTest`, `ObservationValidatorTest`, `EvidenceJoinerTest` and SMS feature tests retain source authorization, validation bounds and aligned dependency provenance.

The new release acceptance adds adjacent full-path minute identities, a **one-nanosecond** closure workflow, zero eligible attempts, absent-node persistence, and the maximum SMS sample through ingestion/finalization. It does not change production boundary or feature code.

## Exact boundary results

- Intervals remain `[start,start+60s)`, aligned to UTC minutes. Adjacent minutes have different SHA-256 V2 window identities and one accepted service input each.
- Malformed/nonaligned/59s/61s timestamp cases reject as `SCHEMA_INVALID`; aligned empty and 120s intervals reject as `SEMANTIC_INVALID`. No accepted receipt, bucket or feature is created.
- Receipt captured at `windowEnd+10s−1ns`: ACCEPTED. Finalizer at that instant: NOT_DUE. Finalizer exactly at `windowEnd+10s`: FINALIZED. New NODE input at closure or `+1ns`: LATE_OBSERVATION.
- Exact event replay: DUPLICATE. Changed content with same eventId: EVENT_ID_CONFLICT. Different eventId with same natural key: NATURAL_KEY_CONFLICT. The saved feature remains identical; accepted count stays 1 after all four rejected/conflicting inputs.
- PostgreSQL rounds stored timestamps to microseconds; admission uses the original sampled application instant. Retention cutoffs are rounded down to avoid shortening the horizon.

## Zero attempts, missing nodes and SMS

Both 0 total attempts and 10 attempts entirely accounted for by user outcomes produce eligibleAttempts=0, numerator/denominator=0, null CSSR/SIP ratios, COMPLETE input quality, empty ML feature arrays and mlEligible=false. No NaN/Infinity or invented healthy ratio.

SERVICE without nodes preserves null IMS CPU/packet-loss KPIs, COMPLETE quality and ML ineligibility; sourceEventIds contains only the real SERVICE ID. Stale IMS and foreign SMSC receipts are not borrowed. Node-only input yields NO_SERVICE without a fabricated SERVICE receipt or feature.

The contract maximum remains **10,000** SMS samples. Descending decimal samples `10000.125 … 1.125` persist one SERVICE receipt plus one real SMSC receipt, accepted count=2 and exactly two contributing IDs. Nearest-rank p95 is **9500.125**. The 10,001-sample case rejects as SCHEMA_INVALID, creates no receipt/bucket/feature, and keeps bounded rejection evidence. All six new persisted boundary payloads match the unchanged Python reference exactly.

## Schema and lifecycle audit

All processor migrations V001–V012 were inspected. No cleanup touches retained evidence or active/pending state.

| Table | Primary key / relationships | Pending or lifecycle marker | Day 18 cleanup |
| --- | --- | --- | --- |
| observation_receipt | event_id; unique source/scope/kind/minute | received_at; bucket and source-state references | Only verified-topic expired completed safe candidates |
| interval_bucket | scope_id/window_start; feature FK points here | finalized/finalized_at | Retain |
| source_state | scope_id/source_id; last_event_id FK to receipt | latest window/emission; current event | Retain; referenced receipt protected |
| feature_outbox | window_id; unique scope/minute/version; bucket FK | immutable canonical feature evidence | Retain indefinitely |
| rejection_outbox | outbox_id; unique topic/partition/offset | published_at NULL = retryable | Only old ACK-marked rows |
| voice_delivery | id | published_at; claim_token/lease_until pair | Retain all topics and states |
| voice_evaluated_window | window_id | committed evaluation marker | Retain |
| voice_episode_state | scope_id | state.active and historical evidence | Retain |
| detection_job | window_id FK to feature, ON DELETE CASCADE | completed_at; claim_token/lease_until | Retain |
| sms_shadow_job | window_id/model_version | completed_at; claim_token/lease_until | Retain |
| sms_shadow_result | evidence_id; unique window/model; FK to shadow job | immutable saved payload | Retain |
| geographic_monitoring_range | range_id; one open range | enrollment, lease, closed_at, authority pins | Retain |
| geographic_monitoring_cursor | range_id/scope_id; range FK | verified next_window_start | Retain |
| historical_bootstrap | bootstrap_id | saved range/seed/completed_at | Retain; all overlapping receipts protected |

## Retention policy and permissions

Default receipt and rejection horizons are **48h**, batch size **100**, poll interval **60s**. Startup rejects horizons below 48h, malformed/overflowing durations, raw horizon at least the receipt horizon, batch sizes outside 1..1000 and nonpositive poll intervals. Disabled cleanup still validates configuration.

Before each receipt batch, a bounded live Kafka topic-config read must prove positive finite retention.ms below the receipt horizon and cleanup.policy containing delete; otherwise receipts are retained. Only receipts from that verified topic are candidates. Tests alter live retention to unlimited, 48h and 72h and prove the declared 24h default does not permit deletion. Compact-only topics retain receipts; compact+delete permits expiry. An unavailable topic retains receipts while eligible ACK-marked rejections can still be cleaned.

Safe receipt cleanup additionally requires finalized_at outside the horizon, saved feature/evaluation/published unclaimed KPI, no current source reference, no pending detector/shadow job, no pending or claimed output/rejection in the scope, no active/unclassified episode, no unresolved monitoring cursor and no historical-bootstrap overlap. If any proof is absent, retain.

Each poll examines at most batchSize old receipts and deletes at most batchSize from each category. Protected oldest receipt candidates consume that budget. Both categories use separate single-statement autocommit transactions, two-second JDBC query timeouts and SKIP LOCKED. Same-instance overlap is guarded; independent workers skip locked rows. Failure rolls back the entire category, leaving pending work intact; the next poll retries safely.

Tests run the job as **processing_app**. Migrator credentials are used only for Flyway and disposable fixture setup/fault injection. V012 adds three indexes, grants no privileges, and revokes inherited DELETE on voice_delivery, voice_episode_state, voice_evaluated_window and historical_bootstrap. The upgrade preserves all 14 populated tables, validates checksums and is a no-op on rerun. Runtime retains necessary INSERT/UPDATE but cannot CREATE schema objects or DELETE retained evidence covered by the permission matrix.

## Cleanup snapshots

The [saved JSON](2026-10-08-day18-retention-snapshots.json) contains independent committed before/after rows, suite counts, permissions, protected-state comparisons and local log checksums.

| Case | Before | After |
| --- | --- | --- |
| completed expired receipt + pending next minute | receipts 2; features/deliveries/evaluations 1 each | receipts 1; other rows unchanged |
| old published + pending + recent ACK + exact horizon rejection | rejections 4 | 3 retained; only old published removed |
| batchSize=2, five completed receipts and five old published rejections | receipts 5; rejections 5; saved feature/delivery/evaluation 5 each | first poll 3/3; drain 1/0; evidence tables remain 5 each |
| pending/claimed delivery, active episode, pending detector/shadow, monitoring, bootstrap, pending rejection | populated protected rows | byte-for-byte normalized snapshots unchanged |

The last source receipt, young/exact-cutoff receipts, unfinalized intervals and unevaluated features remain retained. Repeating a drained poll changes nothing. Tests inject a whole-statement DELETE failure and a permission failure after the other category commits; retries lose no pending state. Exact replay inside the 48h guarantee stays DUPLICATE and preserves accepted counts and saved features.

## Fresh validation

The initial regression ran on current main with real runtime-role ingestion/finalization and failed because RetentionProperties/RetentionJob did not exist (`ClassNotFoundException`). Final focused safety checks and full verification passed after implementation. The first full run caught the V011 migration-test pin; the fresh final run below includes that repair.

| Suite | Cases | Skipped | Result |
| --- | ---: | ---: | --- |
| `WindowBoundaryTest` | 14 | 0 | PASS |
| `RetentionJobTest` | 31 | 0 | PASS |
| `RetentionConfigurationTest` | 16 | 0 | PASS |
| `RetentionMigrationTest` | 1 | 0 | PASS |
| `GeographicMonitoringMigrationTest` | 1 | 0 | PASS |
| `ReplayIT` | 7 | 0 | PASS |
| `FinalizerRaceIT` | 11 | 0 | PASS |
| `LateInputIT` | 20 | 0 | PASS |
| `DependencyOutageIT` | 5 | 0 | PASS |
| `DetectionReplayIT` | 16 | 0 | PASS |
| `StreamingMetricsTest` | 20 | 0 | PASS |
| `ServiceFeatureBuilderTest` | 7 | 0 | PASS |
| `WindowFinalizerTest` | 18 | 0 | PASS |
| `GeographicParityTest` | 1 | 0 | PASS |

Full `mvn -pl services/processor -am verify`: streaming-support **111**, event-generator **81**, processor **481** cases; zero failures/errors. Processor skips: **4** conditional live-model cases. These are NOT VERIFIED in this local run:

- GeographicDetectionTest: pinnedHttpScorerAcceptsCityVectorsAndRejectsChangedBaselineWithoutBlockingRules(String)
- SmsDeliveryTest: livePackagedModelEnrichesRecoveredSmsEpisode
- SmsShadowReplayTest: freshObservationsReachDurableShadowStorageWithoutChangingRuleIncidents
- VoiceDeliveryTest: livePackagedModelEnrichesRecoveredVoiceEpisode

Explicit parity passed: legacy VoLTE 7, legacy SMS 12, geographic VoLTE/SMS 62 total, and Day 18 boundary 6; maximum absolute float difference=0. All compared identities, counts, nulls, sourceEventIds, KPI fields and eligibility agree with the unchanged Python reference.

`python scripts/check-contracts.py`: PASS, including 20 city scopes, 50 observations, 12 rejected catalogues and 30 coverage cases. `git diff --check`: PASS. Graphify AST update: PASS via the installed Python module after the launcher failed; SQL AST extraction is unavailable because tree_sitter_sql is absent. SQL was inspected and tested directly against PostgreSQL.

Exact commands (Maven 3.9.16, Java 21, repository .venv Python; cached Maven repository `.tools/m2`):

```text
mvn -pl services/processor -am -Dtest=RetentionJobTest -Dsurefire.failIfNoSpecifiedTests=false test  # initial red
mvn -pl services/processor -am -Dtest=WindowBoundaryTest,RetentionJobTest,RetentionConfigurationTest,RetentionMigrationTest,GeographicMonitoringMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl services/processor -am -Dtest=WindowBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test  # final exact rejection categories
mvn -pl services/processor -am verify
python scripts/check-contracts.py
python scripts/check-voice-parity.py
python scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json
python scripts/check-voice-parity.py --geographic --java-output services/processor/target/geographic-voice-parity-java.json
python scripts/check-sms-parity.py --geographic --java-output services/processor/target/geographic-sms-parity-java.json
python scripts/check-boundary-parity.py
git diff --check
python -m graphify update .
```

## Limitations and Denis/Sergiu handoff

- Denis: review the conservative policy, raw-topic configuration coordination, capacity impact and index migration before any G4 freeze. Retained evidence/bootstrap ranges remain unbounded; protected oldest candidate cohorts can defer later cleanup. This task does not promise bounded total storage.
- Sergiu: confirm saved feature/evaluation/node-evidence dependencies and conditional live-model gates on the shared release configuration. No thresholds, episode transition, identity, Kafka key, lateness, geographic authority, historical bootstrap, F3 fairness, Day 15 accounting or Day 16 metrics formulas changed.
- Live Kafka config verification and SQL deletion are not an atomic cross-system fence. Coordinate topic retention changes so raw retention remains shorter than the processor horizon.
- Receipt deduplication is guaranteed inside the configured horizon. An expired eligible receipt can later classify as LATE_OBSERVATION instead of DUPLICATE; finalized buckets, feature history and episode evidence remain immutable. Unknown-topic and bootstrap receipts are intentionally retained.
- This is local/component evidence. No shared deployment, physical 49-hour soak, completed human review, or G4 acceptance is claimed. No G4 work or automatic PR merge was performed.

DAY 18 COMPLETE — READY FOR REVIEW
