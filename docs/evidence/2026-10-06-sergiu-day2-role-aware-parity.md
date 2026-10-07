# Sergiu Day 2: role-aware detection and parity

Implemented on `feature/sergiu-day2-role-aware-detection` in the existing checkout,
after pulling main to `cd5b013d979e51e27ff1d4b1c1fff3693a2f4e79`.
Requirements: `source/02_Rusu_Serghei_5_Day_Tasks_FINAL.md`, Day 2, in the October planning ZIP.

## Result

DetectionWorker, VoiceSetupRule and SmsDeliveryRule resolve the published roles
against the feature's saved topology version. Selection requires the exact scope,
minute, node, reporter, COMPLETE quality and contributing event ID. Evidence names
the actual city node. Unknown versions fail closed; unchanged legacy authority
remains readable across activation. Existing leases, episode ordering and atomic
KPI/detection completion remain in place.

With activated geographic authority, BaselineRegistry loads the reviewed twenty
same-service peers. The default remains legacy-only. DIRECT/PEER/BASELINE_MISSING
and donor scope are explicit in registry results; absent donor hours have no substitute.
All twenty city scopes resolve every UTC hour: 20 × 168 = 3,360 peer resolutions.
Baseline-v2 values, policy, feature order and model/calibration bytes match the pulled base.

The Python reference takes an explicit `FeatureContext(topology, catalogue)` and
uses the same published resolver for IMS, transport and SMSC roles. Legacy calls
and the exported SCOPES remain compatible. `build_missing_features` calculates
inferred absence without creating a SERVICE observation or event ID; it does not
establish runtime monitoring eligibility. The processor's persisted monitoring proof
continues to authorize inferred missing windows.

## Verification

| Check | Actual result |
| --- | --- |
| Maven reactor clean verify | 549 tests reported; 548 executed, no failures/errors, one existing opt-in shadow replay skipped |
| Supplemental city controls | Three missing/recovery and queue-only cases passed |
| Real pinned HTTP scorer | Twenty city cases passed; changed baseline rejected in all twenty; deterministic two-breach opening still passed |
| Current Java reports after supplements | 572 tests reported; 571 executed, no failures/errors, one opt-in shadow replay skipped |
| Root Python suite | 46 passed |
| ML Python suite | 54 passed, including six new geographic tests |
| Contracts | Passed, including all 62 new raw-input/reference cases |
| Fresh persisted Java/Python parity | 30 VoLTE + 32 SMS cases, all fields compared, maximum absolute numeric difference 0; integer counters exact |
| Pinned Python scorer | 32 eligible fresh geographic reference cases accepted; changed baseline rejected for each |
| Dashboard production build | Passed; existing 502.30 kB initial-bundle budget warning |

The existing `SmsShadowReplayTest` requires `SMS_SHADOW_REPLAY_DIR`; shadow-model
evaluation is outside Day 2. The real HTTP checks used the pinned local inference
service with Uvicorn `--http h11`, retaining the Java client's 250 ms budget/eight
permits. They do not assert that a successful score is stored for every live normal window.

The 62 frozen cases cover all twenty scopes plus CHI/BAL controls for zero/low volume,
missing baseline/node/service/all sources, incomplete input, breaches and stale nodes;
VoLTE unequal volume, SMS fractional p95 and queue-only SMS are included. Java
exports come from real PostgreSQL ingestion/finalization, including monitoring
checkpoints for inferred absence. They are compared independently with Python,
including windowId, topologyVersion, nulls, feature order and sourceEventIds.

City controls verify two adjacent breaches open and three healthy minutes recover;
UNKNOWN does not count as recovery. Wrong-city/time/noncontributing evidence cannot
establish the cause. Replay leaves committed delivery facts unchanged.
Unique subscribers stays null. No schema migration, policy/model change or power
correlation runtime was introduced.

## Three live minutes

The disposable Compose project `telecom-day2-validation` ran the built generator,
processor, pinned ML service, incident service, Kafka, PostgreSQL, Keycloak and proxy.
Geographic authority was activated using one aligned effectiveFrom shared by the
services. Existing user `.env`, running Compose projects and their databases were preserved.
The first whole generated city intervals were:

| UTC window start | City scopes | City receipts | Finalized KPI / coverage windows | Protected API points |
| --- | ---: | ---: | ---: | ---: |
| 2026-10-06 19:45 | 20 | 50 | 20 / 20 | 20 |
| 2026-10-06 19:46 | 20 | 50 | 20 / 20 | 20 |
| 2026-10-06 19:47 | 20 | 50 | 20 / 20 | 20 |

All sixty stored features were COMPLETE and ML-eligible, evaluated once and published
with their coverage facts. There were zero ingestion rejections. Each VoLTE window
had three exact sources; each SMS window had two. All sourceEventIds matched the
accepted receipts. Coverage and protected API points matched the exact window ID,
scope, interval and catalogue/topology versions. Observed values were non-null;
VoLTE baseline was 99.3%, SMS baseline was 2,000 ms. Denominators/sample counts,
deviation and ratio are recorded per window in the manifest.

These are synthetic teaching measurements and same-service peer baselines. They do
not establish city-specific model accuracy or operator production performance.

## UI and owner disposition

Real OIDC login, ten city searches and ALL/VOLTE/SMS filters worked without browser
runtime errors. The existing dashboard's live city `scopeIds` are empty: it still
shows **Mapping pending** and unavailable values for every city. This was observed
and recorded, not counted as city-value UI acceptance. The protected geography APIs
do contain the sixty meaningful city points. Frontend consumption of the reviewed
catalogue/API is David's remaining integration dependency.

Ion/Denis review of role/baseline/parity outputs and David/Stanislav UI/integration
review remain pending. Local implementation checks pass; the shared G2 gate is not
declared accepted. No review messages or external sign-offs were sent or fabricated.

## Reproduction and artifacts

Run the existing root/ML unittest suites and `scripts/check-contracts.py`. With Java
21 and a running pinned ML service configured through ML_SERVICE_URL:

```powershell
./mvnw.cmd -q -pl services/processor -am clean verify
.venv/Scripts/python.exe scripts/check-voice-parity.py --geographic --java-output services/processor/target/geographic-voice-parity-java.json
.venv/Scripts/python.exe scripts/check-sms-parity.py --geographic --java-output services/processor/target/geographic-sms-parity-java.json
```

`GeographicParityTest` regenerates the fresh persisted exports and registry contexts
under processor/target. A contract/reference check alone is not fresh Java parity.

The [manifest](2026-10-06-sergiu-day2-manifest.json) contains exact file/export hashes,
test counts, scorer results, sixty joined live rows, source IDs and pending reviews.
Manifest SHA256: `0d4b02ee6c43931efb01e630f8be97f2da151c078664addd62a4e72346da090e`.
The [dashboard capture](2026-10-06-sergiu-day2-dashboard.png) shows the pending live
city bindings. Temporary credentials/logs remain untracked in tmp; the isolated
validation services were stopped after evidence capture.

Self-review covered correctness, readability, architecture, security and performance.
The implementation reuses canonical authority rather than adding another role map;
SQL values are bound parameters, external inputs retain validation, and snapshots
are loaded once in the runtime bean. No thresholds, tests or quality gates were
weakened. Team ownership and the pending shared acceptance remain explicit above.

Rollback: stop city producers first; let compatible readers drain their pending facts
before disabling geographic authority or replacing the processor. The feature flag
does not authorize a downgrade while city windows are queued.
