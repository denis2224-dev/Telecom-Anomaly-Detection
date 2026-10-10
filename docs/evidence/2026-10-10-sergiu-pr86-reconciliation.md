# PR #86 — Day 5 review reconciliation

Status: **LOCAL_VERIFICATION_PASS_SHARED_G5_BLOCKED**. Shared G5 remains **BLOCKED**.

Tested code: `1cac338b9c4667b4d37db75e10f7e766170a5568`.
Pinned main: `9e8501b627a00d59f995e410e35d08ceb0ada149`.
Original PR head: `76945124ae93ad6a6fd853887f8adc2732bc08fe`, preserved locally
as `backup/pr86-before-reconciliation-7694512`.
Branch: `fix/geographic-detector-acceptance`.

The original Day 5 report/manifest remain historical evidence for `4969575`.
Their counts are superseded for this integrated candidate. The new machine-readable
manifest is `2026-10-10-sergiu-pr86-reconciliation-manifest.json`; it records exact
suite counts, skip reasons, runtime/configuration hashes, artifact hashes and case
references. Evidence-only commits after the tested SHA do not change executable code.

## Review findings and resolutions

| Finding | Resolution | Verification |
| --- | --- | --- |
| P0: divergent branch and overlapping implementations | Rebased unique Day 5 additions onto pinned main. Preserved DetectionAuthority, worker leases/locks/order/atomic completion and historical handling. Removed superseded DetectionEvidence; dropped obsolete migration assertion and Python compatibility commits. | Production Java/Python implementations are identical to pinned main. Main's V011 migration target remains explicitly isolated. |
| P1: historical missing baselines after peer activation | Added GeographicHistoricalBaselineReplayTest. Persist forty real geographic windows through the finalizer with a legacy baseline registry, then construct a detector with active reviewed peers. Run both measured-SMS and inferred-absence scenarios across all twenty scopes. | Original payloads/hashes unchanged; old jobs complete; later peer-backed windows progress; SMS backlog opens independently; restart and duplicate replay preserve IDs/state/jobs and do not rescore ML. Removing either historical guard causes its expected baseline rejection. |
| P1: Python FeatureContext and missing-service compatibility | Kept main FeatureContext, build_features(context=...), build_missing_features and SCOPES export. Preserved reference_cases(service). Added distinct persisted_cases helper and --geographic-persisted mode. | Canonical geographic parity includes inferred absence; expanded parity covers 180 fresh DB windows. All fields compare, provenance exact, numerical tolerance 1e-9. |
| P2: CONTRACT_ONLY startup must fail closed | Kept main BaselineRegistry activation checks; added explicit injected CONTRACT_ONLY Spring context regression. | Startup rejected with the activation-authority error. Existing absent/disabled and ACTIVE startup tests retained. |
| P2: strict worker receipt boundary | Preserved window_end, COMPLETE, reporter/node and contributing event checks. Added GeographicWorkerReceiptBoundaryTest for both services. | Malformed end rejected at ingestion; incomplete and noncontributing stored receipts never reach the episode as evidence. Existing wrong-city, source, time, version and contradictory-evidence suites retained. |
| Fresh evidence and reproducibility | Clean Java reactor verification with explicit private ML endpoint; regenerate contracts, both Python suites, both parity matrices and HTTP checks. Rename expanded Java exporter Day5GeographicParityTest to avoid main's canonical class collision. | Fresh counts and hashes recorded below and in the integrated manifest; old candidate totals are not reused. |
| Shared G5 runtime and owner review | Local verification scope only, as selected by the user. No protected API/browser run or human signature claimed. | BLOCKED pending final-runtime owner acceptance. Optional power correlation remains NOT_IN_SCOPE; negative cause controls remain mandatory. |

## Results

| Check | Passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: |
| Clean processor reactor, including upstream modules | 1181 | 0 | 1 |
| Processor module alone | 975 | 0 | 1 |
| Repository Python suite | 61 | 0 | 0 |
| ML-service Python suite | 54 | 0 | 0 |
| Contract validators | PASS | 0 | 0 |
| Legacy voice/SMS Java/Python parity | 19 | 0 | 0 |
| Canonical geographic Java/Python parity | 62 | 0 | 0 |
| Expanded Day 5 persisted Java/Python parity | 180 | 0 | 0 |
| Fresh geographic private-model HTTP scores | 60 | 0 | 0 |
| Incompatible-baseline HTTP rejection controls | 60 | 0 | 0 |
| Historical-guard mutation controls caught | 2 | 0 | 0 |
| Frozen policy/topology/baseline/model hash comparisons against main | 12 | 0 | 0 |

Both live packaged-model delivery profiles executed with ML_SERVICE_URL set and
no skips. The sole skip is SmsShadowReplayTest, which requires the optional external
SMS_SHADOW_REPLAY_DIR dataset. Shared authenticated runtime/API/UI checks were not
executed and remain BLOCKED. The clean reactor completed at 2026-10-10T13:49:40Z.

Runtime: Temurin 21.0.12.1+1, Python 3.13.9, NumPy 2.4.6, scikit-learn 1.9.1,
joblib 1.5.3, FastAPI 0.141.1, Uvicorn 0.54.0, Docker 29.5.2. Dependency consistency
check passed. The manifest records 242 parity case rows, episode references and 119
artifact checksums; raw logs and DB exports remain under output/day5-reconciliation/
and services/processor/target/. No local test result is a human release signature.

## Reproduction

Use Java 21 and Python 3.13 with requirements-dev.txt and requirements-ml.txt.
Start Docker for disposable PostgreSQL/Kafka test containers. Start the private
packaged inference application and explicitly set its URL; the example below uses
loopback port 18095. Geographic test activations are explicit UTC fixture values:
2026-09-15T08:00:00Z for canonical/history cases and 2026-10-05T08:00:00Z for Day 5.

In a separate terminal at the repository root:

```powershell
$env:PYTHONPATH="$PWD/services/ml-service"
python -m uvicorn app.inference.api:app --host 127.0.0.1 --port 18095 --limit-concurrency 32
```

```powershell
$env:ML_SERVICE_URL='http://127.0.0.1:18095'
python scripts/check-contracts.py
python -m unittest discover -s tests -v
python -m unittest discover -s services/ml-service/tests -v
.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/processor -am clean verify
python scripts/check-voice-parity.py --java-output services/processor/target/voice-parity-java.json
python scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json
python scripts/check-voice-parity.py --geographic --java-output services/processor/target/geographic-voice-parity-java.json
python scripts/check-sms-parity.py --geographic --java-output services/processor/target/geographic-sms-parity-java.json
python scripts/check-voice-parity.py --geographic-persisted --java-output services/processor/target/day5-geographic-parity-java.json
python scripts/check-sms-parity.py --geographic-persisted --java-output services/processor/target/day5-geographic-parity-java.json
python tasks/sergiu-day5-acceptance/check_geographic_http.py
```

The clean reactor includes both packaged delivery profiles when ML_SERVICE_URL is
set. Its failure/fallback suites use controlled endpoints for unavailable, timeout,
malformed and 422 responses; those controlled responses are distinct from actual
packaged-model HTTP acceptance. Geographic CLI modes require explicit Java exports
and are mutually exclusive. Legacy defaults remain unchanged.

Mutation reproduction: temporarily disable the VoiceSetupRule
legacyWindowWithoutBaseline branch, run GeographicHistoricalBaselineReplayTest,
and observe CSSR baseline rejection. Restore it; separately disable SmsDeliveryRule's
saved-null geographic p95 branch and observe SMS p95 baseline rejection. Restore the
source exactly and run the clean full verification. Local mutation logs and the
automatic byte-restoring script are retained under output/day5-reconciliation/.

## Evidence boundaries and handoff

Unit/configuration, disposable database/replay, controlled failure HTTP and actual
private packaged-model HTTP evidence are identified separately. The CI workflow
runs the new regressions, both geographic parity modes and the explicitly configured
HTTP helper, and uploads exports, Day 5 snapshots and Surefire reports.

Shared acceptance still needs Stanislav's final release SHA/configuration and the
runtime owners' authenticated API/UI review: city/scope/version, observed/baseline,
deviation sign, denominator/sample count, estimated impact, timestamps, receipts and
confidence wording. Verify an affected city and two unaffected cities using their
own measurements, with screenshots showing synthetic provenance. Owner signatures
and teammate communications have not been performed by this implementation task.

Frozen synthetic peers do not establish geographic model generalization. Rank is
not probability, unique subscriber impact stays null, and optional power correlation
has no enablement sign-off. No public payload, policy threshold, model bytes,
calibration, feature order, 250ms ML budget or eight-permit limit changed.
