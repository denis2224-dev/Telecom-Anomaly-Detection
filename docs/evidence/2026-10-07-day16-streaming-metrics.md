# Revision 3 Day 16 — processor streaming instrumentation

Status: **DAY 16 COMPLETE — READY FOR REVIEW**. Evidence collected on 2026-10-07 in an isolated worktree using disposable PostgreSQL 16.4 and embedded Kafka. This is local/component evidence, not production observability or deployed alert acceptance.

## Repository and implementation

- Repository: `denis2224-dev/Telecom-Anomaly-Detection`; owner: Zavtoni Ion / `itsjohnyoff`.
- Fresh base: `d00f74ece253a88b103bb12a36a35b15ca6c08af`; PR #63 is merged into that main commit. Main was refreshed again before publication.
- Implementation SHA: `55c6663e0120d41cedb8f10882b0f934aacf3830`. This evidence and the derived-gauge documentation clarification are a subsequent documentation-only commit.
- Branch: `feature/ion-day16-streaming-metrics`, created from current main, independently of the old Day 15 branch.
- The original checkout's unrelated edits and untracked files were preserved. No migration or dependency change is required: Actuator/Micrometer already arrive through `streaming-support`.

## Exact metric names and labels

| Micrometer name | Finite labels |
| --- | --- |
| `telecom.processor.observations` | `outcome=accepted,duplicate,invalid,late` |
| `telecom.processor.finalizer.delay` | none |
| `telecom.processor.finalizer.delay.histogram` | Simple-registry derived gauges: `le=10,30,60,300,900,3600,86400` (seconds) |
| `telecom.processor.sources` | `state=fresh,stale,never_seen` |
| `telecom.processor.outbox.oldest.age` | `queue=delivery,rejection` |
| `telecom.processor.metrics.snapshot.age` | none |

Types, units, truth sources, refresh/empty/failure semantics, interpretation, cardinality policy and proposed alerts are specified in the [metric contract](../observability/streaming-metrics.md). Trace identifiers are log fields only. There are no event, scope, source, window, run, Kafka key, offset or topic labels.

## Observed acceptance evidence

**Cardinality:** [machine-readable result](assets/day16-streaming-metrics/day16-cardinality.json) records 750 real listener invocations: 500 accepted observations over 250 window starts in two authoritative legacy scopes, plus 250 rejected inputs with distinct unknown scope IDs. All 500 corresponding windows finalized. The same 18 meter identities remained before/after: 11 application identities and seven finite derived histogram gauges. Every label value is checked against an explicit finite set. A separate [inventory growth result](assets/day16-streaming-metrics/day16-inventory-growth.json) records 100 catalogue scopes / 200 expected source pairs with the same 18 identities. This is an observability regression, not a throughput benchmark or a claim that unknown scopes were accepted.

**Persisted event-to-feature trace:** [receipt/feature result](assets/day16-streaming-metrics/day16-trace.json) and [structured phases](assets/day16-streaming-metrics/day16-structured-trace.json):

```text
eventId:     879adeeb-7816-3394-80aa-753dbd80f27e
receipt:     app.observation_receipt.event_id = that same UUID
scopeId:     VOLTE-MD-CENTRAL
windowStart: 2026-09-15T08:00:00Z
windowId:    513f5809a908204afaed14fa0d949759c4bf1abde3df4fe7bd18322693e90fcc
feature:     app.feature_outbox.payload.sourceEventIds = [that same UUID]
```

The real listener invokes the ingestion transaction proxy; the acknowledgment callback independently sees the committed receipt. The real finalizer transaction proxy produces the immutable feature for that scope/window. Captured key/value log events connect `kafka_received` → `ingestion_committed` with `outcome=accepted` → `feature_finalized`. A booted processor console test verifies `%kvp` actually renders the trace fields, without payload logging.

**Finalizer delay:** [result](assets/day16-streaming-metrics/day16-finalizer-delay.json): window end `08:01:00Z`, successful proxy return at injected clock `08:01:15Z`, one 15-second sample. This includes lateness, not scheduler invocation duration. Deferred PostgreSQL commit failures produce no sample and roll back both feature and finalization marker; a subsequent successful scheduler retry records exactly one sample. Not-due, not-found, already-finalized and missing-service paths are also checked.

**Source freshness:** [result](assets/day16-streaming-metrics/day16-source-freshness.json): six authoritative expected legacy scope/source pairs; after one real receipt, one fresh / five never-seen pairs. The 90-second policy boundary is still fresh; one nanosecond beyond it is stale. Future activity remains stale. Source/queue values are unknown (`NaN`) before a successful refresh or after refresh failure, with last-success age retained. No interval absence is reclassified as activity staleness.

**Oldest pending outbox:** [result](assets/day16-streaming-metrics/day16-outbox-age.json): delivery `30.100312` seconds, rejection `45.076627` seconds, sampled against PostgreSQL wall time. A published row one day old was excluded. Empty queues and future-created pending rows yield zero. Published rejection/delivery rows and retained finalized feature history are excluded from pending age. Refresh failure yields `NaN` and does not interrupt ingestion.

## Fresh validation

Java `21.0.12.1`, Maven `3.9.16`. Final full reactor verification completed at `2026-10-07T17:41:32+03:00` on implementation SHA above. Counts are extracted from Surefire XML in [validation.json](assets/day16-streaming-metrics/validation.json).

| Module / check | Recorded tests | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| streaming-support | 111 | 111 | 0 | 0 | 0 |
| event-generator | 81 | 81 | 0 | 0 | 0 |
| processor | 405 | 401 | 0 | 0 | 4 |
| **Full relevant reactor** | **597** | **593** | **0** | **0** | **4** |
| `StreamingMetricsTest` (included in processor total) | 20 | 20 | 0 | 0 | 0 |
| Processor `HealthProbeTest` (included in processor total) | 4 | 4 | 0 | 0 | 0 |

The four existing optional skips are the geographic HTTP scorer, packaged live SMS model, packaged live voice model (`ML_SERVICE_URL` absent), and SMS shadow replay (`SMS_SHADOW_REPLAY_DIR` absent). These are not claimed as validated. No Day 16 test skipped.

Commands run from repository root, using the cached Maven executable and repository Python environment:

```powershell
$mvn = 'C:\OrangeSystems\Program\.maven-wrapper-home\wrapper\dists\apache-maven-3.9.16\56ba1f9f\bin\mvn.cmd'
& $mvn '-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2' -pl services/processor -am '-Dtest=StreamingMetricsTest,HealthProbeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
& $mvn '-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2' -pl services/processor -am '-Dtest=StreamingMetricsTest#structuredLogsConnectReceiptCommittedIngestionAndFeature+failedRefreshIsUnknownAndCannotInterruptProcessing' '-Dsurefire.failIfNoSpecifiedTests=false' test
& $mvn '-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2' -pl services/processor -am verify
& 'C:\OrangeSystems\Program\Telecom-Anomaly-Detection\.venv\Scripts\python.exe' scripts/check-contracts.py
git diff --check
```

- Initial regression on uninstrumented main: `StreamingMetricsTest` recorded two tests, one passed and one failed because the committed accepted-outcome meter was absent. This failure was recorded before production instrumentation edits.
- Focused suite: 24 processor tests plus one generator health test passed, zero failures/errors/skips. After the final trace rendering and snapshot-age refinements, both affected tests passed separately, followed by the full final verification above.
- Full final `verify`: **PASS**. Includes replay/finalizer race, geography, ingestion, source freshness, delivery, features and episode regressions. Expected injected PostgreSQL failure logs are regression stimuli, not test failures.
- `scripts/check-contracts.py`: **PASS**, all nine printed groups: 13 observation fixtures; six detection payloads and 12 explanation trajectories; seven voice / 12 SMS parity cases; 62 geographic raw/reference cases; 10 cities / 20 geographic scopes + two legacy; 50 observations / 12 rejected catalogues / 30 coverage cases; eight geographic voice / seven SMS numeric cases; cause DTO shapes. Python/reference checks are not a deployed-pipeline claim.
- Application startup / registry / health: **PASS**, real processor Spring Boot startup; bounded application meters registered; existing Kafka/database readiness and liveness checks pass; `/actuator/metrics` returns HTTP 404 under unchanged health-only exposure.
- `git diff --check`: **PASS**.
- Graphify AST update: **PASS**, using the installed Python module because the launcher points to a missing script. Final graph: 5,895 nodes / 16,363 edges. SQL graph extraction was unavailable because `tree_sitter_sql` is not installed; Java compilation and PostgreSQL tests provide SQL execution evidence. Generated graphs remain local, outside the PR.

## Scope, limitations and Stanislav handoff

Production changes are limited to the processor metrics component, post-transaction listener/scheduler hooks, and rendering structured log fields. Ingestion/finalizer transaction implementations, observation identity, Kafka commit-before-ack ordering, +10-second lateness, feature formulas, baselines, detector decisions, episodes, outbox retry/fairness behavior, geographic authority and Day 15 accounting were not modified. No incident-service, dashboard, NGINX or deployment files changed. No migration was added. Day 17 was not started.

Counters/logs are best-effort process-local telemetry and can be lost on restart or process failure between commit and recording. A postcommit feature metadata-read failure can lose a successful finalizer sample. Aggregates refresh every 10 seconds by default, perform work proportional to authoritative source inventory, and are sampled rather than cross-table atomic database snapshots. The selected private backend/exporter will determine actual histogram series and percentile queries.

**Stas handoff:** use the [metric contract](../observability/streaming-metrics.md) to map outcome rates, p50/p95 finalization delay, aggregate source activity and oldest pending queue age to the chosen private exporter. Add snapshot-age/unknown-state checks and agree thresholds/grace periods with owners before deployment. Dashboards, alerts and production observability were not deployed or claimed. F3 delivery fairness remains a separate Sergiu-owned task. No remaining Day 16 implementation gap was identified; review and monitoring deployment are handoffs.
