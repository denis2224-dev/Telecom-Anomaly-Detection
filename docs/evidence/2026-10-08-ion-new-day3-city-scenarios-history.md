# Ion Day 3 Evidence: Scenarios and History by City

## Base SHA
`a00f61df79d1a0daed8ff9045a2a9347feb437a7` (current `origin/main`)

## Files changed
- `services/event-generator/src/main/java/md/utm/telecom/generator/VoiceScenario.java`
- `services/event-generator/src/main/java/md/utm/telecom/generator/api/ScenarioExecutionService.java`
- `services/event-generator/src/main/java/md/utm/telecom/generator/scenarios/SmsQueueScenario.java`
- `services/event-generator/src/test/java/md/utm/telecom/generator/api/ScenarioExecutionServiceTest.java`
- `services/processor/src/main/java/md/utm/telecom/processing/history/GeographicHistoricalBootstrap.java`
- `services/processor/src/main/java/md/utm/telecom/processing/history/GeographicHistoryProperties.java`
- `services/processor/src/main/java/md/utm/telecom/processing/history/HistoryBootstrapApplication.java`
- `services/processor/src/test/java/md/utm/telecom/processing/history/GeographicHistoricalBootstrapTest.java`
- `services/processor/src/test/java/md/utm/telecom/processing/history/HealthyHistoryTest.java`
- `services/processor/src/test/java/md/utm/telecom/processing/history/HistoryRangeTest.java`
- `docs/evidence/2026-10-08-ion-new-day3-city-scenarios-history.md`

## City scenario matrix
All 10 authoritative cities and 6 scenario types form 60 compatible combinations:
- Cities: `CHI`, `BAL`, `EDI`, `SOR`, `RIB`, `UNG`, `TIR`, `COM`, `CAH`, `ORH`
- VoLTE Scenarios (30 combinations):
  - `NORMAL_CONTROL` (8 normal windows)
  - `TELEMETRY_GAP` (2 normal, 3 gap/omitted observations, 3 recovery)
  - `VOLTE_IMS_OVERLOAD` (2 normal, 3 fault: CPU 97%, technicalFailures 60, 3 recovery)
- SMS Scenarios (30 combinations):
  - `NORMAL_CONTROL` (8 normal windows)
  - `TELEMETRY_GAP` (2 normal, 3 gap/omitted observations, 3 recovery)
  - `SMS_QUEUE_DELAY` (2 normal, 3 fault: queueDepth >= 150, oldestPendingAgeSeconds >= 70, 3 recovery)

Every combination produces exactly 8 aligned windows, validates strictly against `ObservationValidator(geography.authority())`, and matches authoritative city node roles (`IMS-MD-<city>-01`, `SMSC-MD-<city>-01`). Incompatible combinations (`VOLTE_IMS_OVERLOAD` on `SMS-MD-CHI`, `SMS_QUEUE_DELAY` on `VOLTE-MD-CHI`, unknown scope) are rejected with `INVALID_SCOPE`.

## Scope/body/key proof
Central Day 3 invariant:
$$\text{command.scopeId} == \text{payload.scopeId} == \text{Kafka key}$$
Verified deterministically in `ScenarioExecutionServiceTest.cityScheduledExecutionVerifiesTransitionsKafkaKeyAndPayloadScopeMatch`:
- Emitted observations on `telecom.observations.v2` captured via argument captors.
- For all 24 observations across 8 windows:
  - `key` equals `VOLTE-MD-CHI`.
  - JSON payload `scopeId` equals `VOLTE-MD-CHI`.
  - Command target equals `VOLTE-MD-CHI`.
  - Node source IDs derive authority from `GeographyCatalog` (`IMS-MD-CHI-01`, `TRANSPORT-MD-CHI-01`).

## Reservation/idempotency proof
Preserved existing reservation invariants and extended to geographic scopes:
- **Idempotent restart:** identical `(runId, command)` returns the existing run snapshot without rescheduling or altering execution state.
- **Run conflict:** same `runId` with altered command parameters throws `ApiFailure` with code `RUN_CONFLICT`.
- **Per-scope interval reservation:** overlapping schedules on the same scope (`VOLTE-MD-CHI`) throw `ApiFailure` with code `SCOPE_WINDOW_CONFLICT`.
- **Cross-scope independence:** active runs on `VOLTE-MD-CHI` do not block `VOLTE-MD-BAL` or `SMS-MD-CHI`; different cities and different services execute concurrently.
- **Terminal run retention:** stopped runs retain reservations for their scheduled window interval (`reserves(scope, windowStart) == true`), preventing false recovery fabrication.
- **Interrupted start safety:** schedules whose start time has already passed cannot be restarted from minute zero (`SCHEDULE_ALREADY_STARTED`).

## Legacy compatibility
- Legacy scopes `VOLTE-MD-CENTRAL` and `SMS-MD-ROUTE-A` remain fully supported and byte-identical to `legacy-telemetry-baseline.json`.
- Legacy 2-argument and 3-argument scenario methods (`voice.generateWindows(start, seed, profile)`, `sms.generateWindows(start, seed)`) remain available and unmodified.
- Legacy bootstrap job `initial-demo-v1` remains untouched and coexists with geographic bootstrap without interference or reset.

## Geographic bootstrap identity
Geographic bootstrap identity is pinned to:
$$\text{JobConfig} = (\text{jobId}, \text{catalogueVersion}, \text{catalogueDigest}, \text{scopes}, \text{historyStart}, \text{historyEnd}, \text{seed})$$
- Config digest is calculated using SHA-256 over canonical piped representation.
- Stored as `bootstrap_id = jobId + ":" + digest.substring(0, 16)` in `app.historical_bootstrap`.
- Rerun with identical configuration detects completed status and returns `ALREADY_COMPLETE`.
- Changed configuration under the same `jobId` detects configuration divergence and throws `IllegalStateException`.

## Resume proof
- Proven in `GeographicHistoricalBootstrapTest.interruptedDeliveryResumesWithoutDuplicateEvidence`:
  - When broker delivery fails midway, transaction rolls back delivery but commits valid generated receipts and finalized features.
  - Upon restart/resume with broker recovered, bootstrap detects existing finalized features for completed minutes, skips re-ingestion, and completes pending delivery.
  - Zero duplicate receipts or features are created.

## Existing-fault no-overwrite proof
- Proven in `GeographicHistoricalBootstrapTest.preExistingFaultWindowPreservedByteForByteWithoutAdvancingLiveEpisodeState`:
  - A pre-existing fault window (`VOLTE_IMS_OVERLOAD` fault at minute 2) ingested and finalized prior to bootstrap is inspected.
  - Healthy geographic bootstrap covering the interval detects the pre-existing feature, verifies that evidence is retained, and leaves `payload_hash`, `payload`, and `observation_receipt` entries completely byte-identical.
  - Does not evaluate live episodes (`app.voice_episode_state` count remains 0).

## KPI/coverage drain proof
- `GeographicHistoricalBootstrap.deliverPending(bootstrapId)` queries `app.voice_delivery` joined against `app.feature_outbox` by `window_id`:
  - For `telecom.kpis.v2`: joins `d.id = f.window_id`.
  - For `telecom.coverage.v1`: joins `(d.payload->>'windowId') = f.window_id`.
- Scoped strictly to `f.window_start >= h.history_start AND f.window_start < h.history_end` for the specific `bootstrapId`.
- Emits records with header `telecom-history-bootstrap = bootstrapId`.
- Proven in `deliveryDrainDoesNotConsumeUnrelatedLiveOutboxRow` that live delivery rows outside the bootstrap window remain unpublished (`published_at IS NULL`).

## Test totals
- `services/streaming-support`:
  - Tests run: 111, Failures: 0, Errors: 0, Skipped: 0
- `services/event-generator`:
  - Tests run: 86, Failures: 0, Errors: 0, Skipped: 0
- `services/processor`:
  - Tests run: 489, Failures: 0, Errors: 0, Skipped: 4
- Python Parity and Contract Checks:
  - `python scripts/check-contracts.py`: PASS (all 9 contract suites)
  - `python scripts/check-voice-parity.py`: PASS (7/7 suites exact match)
  - `python scripts/check-sms-parity.py`: PASS (12/12 suites exact match)
- Git hygiene:
  - `git diff --check`: clean (0 errors)

### Classification of Skipped Tests in `processor` (4 tests):
1. `GeographicDetectionTest`: Skipped due to missing `[ML_SERVICE_URL]` environment variable (remote ML service integration test).
2. `SmsShadowReplayTest`: Skipped due to missing `[SMS_SHADOW_REPLAY_DIR]` environment variable (shadow replay dataset test).
3. `SmsDeliveryTest`: Skipped due to aborted assumption (requires optional external broker container).
4. `VoiceDeliveryTest`: Skipped due to aborted assumption (requires optional external broker container).

## Limitations
1. Process-local generator execution: generator handles internal scheduled execution; public scenario command acceptance remains in `incident-service`.
2. Public API blocker: `ScenarioCommandService.java` in `incident-service` currently still restricts scope validation to `VOLTE-MD-CENTRAL` and `SMS-MD-ROUTE-A`. City scenarios dispatched via `incident-service` currently return `INVALID_SCOPE`.

## Denis handoff
### Public Command Validation Requirements for `ScenarioCommandService`
In `services/incident-service/src/main/java/md/utm/telecom/incident/scenario/ScenarioCommandService.java`:
Currently, scope validation restricts scopes to legacy values:
```java
if (!scopeId.equals("VOLTE-MD-CENTRAL") && !scopeId.equals("SMS-MD-ROUTE-A")) {
    throw new ScenarioCommandException("INVALID_SCOPE", "Unknown scopeId: " + scopeId);
}
```
Denis must update this validation to be catalogue-aware using `GeographyCatalog` / `TopologyCatalog.Scope`:
- Accept all 10 cities for VoLTE: `VOLTE-MD-<CITY>` (`CHI`, `BAL`, `EDI`, `SOR`, `RIB`, `UNG`, `TIR`, `COM`, `CAH`, `ORH`)
- Accept all 10 cities for SMS: `SMS-MD-<CITY>` (`CHI`, `BAL`, `EDI`, `SOR`, `RIB`, `UNG`, `TIR`, `COM`, `CAH`, `ORH`)
- Accept legacy scopes: `VOLTE-MD-CENTRAL`, `SMS-MD-ROUTE-A`
- Valid pairings:
  - `VOLTE_IMS_OVERLOAD`: only `VOLTE` scopes (`VOLTE-MD-*`, `VOLTE-MD-CENTRAL`)
  - `SMS_QUEUE_DELAY`: only `SMS` scopes (`SMS-MD-*`, `SMS-MD-ROUTE-A`)
  - `NORMAL_CONTROL`: any valid `VOLTE` or `SMS` scope
  - `TELEMETRY_GAP`: any valid `VOLTE` or `SMS` scope
  - Incompatible pairings (e.g. `VOLTE_IMS_OVERLOAD` with `SMS-MD-CHI`) or unknown scopes -> `INVALID_SCOPE`
- Preserve existing public API guarantees:
  - `requestId` idempotency and bodyHash verification
  - Durable command ledger persistence in Postgres
  - Overlapping scope schedule conflict detection (`SCOPE_WINDOW_CONFLICT`)
  - Same saved runId/schedule returned on idempotent retry
  - `SUPERVISOR`/`ADMIN` authorization roles

## Sergiu handoff
- No detector or explanation files were modified (`services/processor/.../detection/`, `services/processor/.../baseline/`, `services/ml-service/` were untouched).
- Sergiu's PR #71 was not merged or altered.
- Historical bootstrap generates healthy baseline data without advancing live episode state (`app.voice_episode_state` untouched) and does not invoke remote ML.

## Stanislav handoff
- Kafka observation records emitted by generator have topic `telecom.observations.v2` and Kafka key matching `command.scopeId` (`key == scopeId`).
- Historical delivery drains both `telecom.kpis.v2` and `telecom.coverage.v1` with header `telecom-history-bootstrap`.
- Bounded 2-day geographic history bootstrap ensures storage and streaming resources remain constrained.
