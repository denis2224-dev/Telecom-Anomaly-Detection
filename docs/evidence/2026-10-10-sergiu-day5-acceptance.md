# Rusu Serghei — Day 5 detector acceptance

**Historical candidate evidence, superseded for PR #86 integration.** The results
below apply only to the original implementation SHA. Reconciliation with main is
tracked in `tasks/sergiu-day5-acceptance/plan.md`; regenerated results are recorded
in `2026-10-10-sergiu-pr86-reconciliation.md`. The original manifest is preserved.

Implementation and local verification are recorded separately from shared G5
release acceptance. The final machine-readable results and content hashes are in
`2026-10-10-sergiu-day5-manifest.json`. Raw logs and exports are retained locally
under `output/day5/` and the processor's `target/` directory.

## Pinned candidate and final results

Tested implementation revision:
`49695750ab22dbf64ba315b986ed63f3f25e7495`, on
`codex/sergiu-day5-acceptance`. Subsequent evidence-only commits do not change
this tested implementation. Verification finished on 2026-10-10 UTC.

| Check | Passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: |
| Java processor reactor, including upstream modules | 644 | 0 | 1 |
| Repository Python suite | 46 | 0 | 0 |
| ML-service Python suite | 49 | 0 | 0 |
| Contract validators | PASS | 0 | 0 |
| Persisted Java/Python parity payloads | 199 | 0 | 0 |
| Fresh geographic private-HTTP model inputs | 60 | 0 | 0 |
| Incompatible-baseline HTTP rejection controls | 60 | 0 | 0 |

The sole Java skip is `SmsShadowReplayTest`, which requires the optional
`SMS_SHADOW_REPLAY_DIR` experimental dataset. Both mandatory packaged-model
delivery suites executed with `ML_SERVICE_URL` explicitly set. Parity maximum
absolute numerical difference is zero, with identifiers and provenance compared
exactly. All twelve frozen configuration/model/calibration hashes match the
starting revision.

The runtime was Java 21.0.12.1, Python 3.13.9, Docker 29.5.2, NumPy 2.4.6,
scikit-learn 1.9.1 and joblib 1.5.3. Geographic tests explicitly activate at
`2026-10-05T08:00:00Z`; default deployment settings remain legacy. Existing
deployment controls are `telecom.geography.enabled=true` and
`telecom.geography.effective-from=<whole UTC minute>`. The manifest records the
activation-derived catalogue version/digest, topology, baseline and feature
versions. Test intervals are synthetic and separate from verification time.

## Implementation

- Geography activation selects the existing twenty-peer baseline candidate;
  geography-off retains the legacy defaults. Donor values and `baseline-v2`
  are unchanged. All 168 UTC hour slots remain covered.
- Detector source selection and explanation checks resolve both node and reporter
  through the captured topology. Unchanged legacy scopes can finish pending
  baseline-version work after activation. Unreviewed versions fail closed.
- Python feature generation accepts validated topology/catalogue context and
  preserves the existing legacy calling convention. Both parity scripts support
  `--geographic` and require an explicit persisted Java export in that mode.
- New tests cover all twenty city/service scopes, policy boundaries, unknown and
  nonadjacent windows, historical impact, replay, source authority and ML failure.
  Auxiliary ping/worker messages and scenario labels cannot replace V2 evidence.
- Two existing migration regression assertions were stale: V011 adds two tables
  (16 total), and the current upgrade includes V012/V013. Exact expectations were
  updated; ownership, isolation and forbidden-operation checks remain intact.

## Reproduction

Use Java 21, Docker Desktop and Python 3.13 with `requirements-dev.txt` and
`services/ml-service/requirements-ml.txt`. The workspace's existing `.venv`
contains the pinned numerical libraries. Start the private packaged model:

```powershell
.venv/Scripts/python.exe -m uvicorn app.inference.api:app --app-dir services/ml-service --host 127.0.0.1 --port 18095
```

In another terminal, set `JAVA_HOME` to Java 21 and put its `bin` on `PATH`:

```powershell
$env:ML_SERVICE_URL='http://127.0.0.1:18095'
.venv/Scripts/python.exe scripts/check-contracts.py
.venv/Scripts/python.exe -m unittest discover -s tests -v
.venv/Scripts/python.exe -m unittest discover -s services/ml-service/tests -v
.\mvnw.cmd -pl services/processor -am verify
.venv/Scripts/python.exe scripts/check-voice-parity.py --java-output services/processor/target/voice-parity-java.json
.venv/Scripts/python.exe scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json
.venv/Scripts/python.exe scripts/check-voice-parity.py --geographic --java-output services/processor/target/geographic-parity-java.json
.venv/Scripts/python.exe scripts/check-sms-parity.py --geographic --java-output services/processor/target/geographic-parity-java.json
.venv/Scripts/python.exe tasks/sergiu-day5-acceptance/check_geographic_http.py
```

The database tests use disposable Testcontainers databases and do not reset the
development databases. Packaged delivery tests require `ML_SERVICE_URL`; an
assumption skip is not ML acceptance. The missing-baseline parity variant uses a
test-only catalogue with no matching donor hour; the shipped catalogue is intact.

## Acceptance and evidence interpretation

The manifest records AGG-01/02, DET-01–05, replay, parity, invariant hashes and
the shared-review blockers. Each row identifies its evidence kind, expected and
actual result, synthetic scope/window context and local artifacts. The numeric
aggregation cases remain independent arithmetic oracles; live read-projection
agreement must be reviewed with Denis.

The fresh geographic parity export has nine variants for each of twenty scopes:
normal, fault, zero, low volume, missing node, missing baseline, partial source,
stale node and numerical precision. SMS precision uses fractional p95;
VoLTE precision uses 1089/1100. No p95 scalar averaging is introduced.

The all-city database replay produces one OPEN and one RECOVERY per scope and
preserves its complete outbox after replay with ML unavailable. The packaged HTTP
check separately scores 60 fresh exported city windows and rejects 60 altered
baseline versions. These prove execution/contract compatibility, not geographic
model accuracy. A role-swap mutation fails the regression test; original bytes
were restored and the feature tests passed again.

## Wording and teammate handoff

For David and Denis, the example meanings to review are:

- VoLTE CHI fault: 900/1000 eligible technical attempts gives 90.0% CSSR;
  synthetic peer baseline 99.3%, display delta -9.3pp, estimated 93 extra technical
  failures. High aligned IMS CPU/SIP 503 with healthy access supports a MEDIUM
  confidence capacity hypothesis, not a confirmed diagnosis.
- SMS CHI fault: delivered sample count, measured p95 in milliseconds, queue
  depth 250 and oldest pending age are separate measurements. 100 affected
  delivered messages and 250 pending messages are not unique subscriber counts.
- UNKNOWN retains historical severity/impact with source-window references;
  current impact must remain unavailable/stale. RECOVERY describes technical
  measurements and does not resolve the analyst workflow.
- `uniqueSubscribers` remains null. Anomaly rank and categorical confidence are
  separate; neither is a cause probability. All examples must show synthetic
  provenance and their actual scope/version/measurement time.

For Ion: review the shared feature/export boundary and geographic baseline
activation. For Stanislav: consume the manifest, exact counts, config/model
digests and commands; verify the combined deployment and feature-off path.
No teammate message, human signature or approval is fabricated by this record.

## Remaining shared gate and limitations

Authenticated application readiness and geography API checks were unavailable
on this machine. No authenticated mentor screenshots, current unaffected-city
API comparison, analyst-workflow sign-off or teammate reproduction is claimed.
These remain BLOCKED for the final integrated release candidate, with Denis,
David and Stanislav supplying the protected runtime and review evidence.
Probe results are retained in `output/day5/live-readiness.json`; the manifest
hashes this artifact and keeps shared G5 status BLOCKED. Owner review entries
remain PENDING. This document is a local technical handoff, not a signed release
matrix.

Optional power correlation/DET-06 is NOT_IN_SCOPE because no G2 enablement was
established. Failed ping/worker controls verify rejection at the strict detector
boundary; they do not claim an activated auxiliary publisher/consumer.
Baselines remain fixed synthetic peers. Geographic model generalization is
unvalidated. No retraining, threshold/model-byte change, new public API, strict
V2 field, transport subsystem or subscriber-impact calculation is included.
