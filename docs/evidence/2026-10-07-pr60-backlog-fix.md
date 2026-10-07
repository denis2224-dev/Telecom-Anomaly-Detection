# PR #60: independent city SMS backlog detection

Follow-up to the [Day 2 evidence](2026-10-06-sergiu-day2-role-aware-parity.md).
Base: `b279c11e48e494d1ee663f2c7e6a6d9401529a44`.

## Behavior and regression coverage

The geographic null-delay-baseline branch previously returned before validating
independent SMSC queue evidence. It now keeps the saved delay baseline unavailable
while continuing the authoritative queue checks. This covers genuinely absent
donor baselines and immutable city windows created before peer baseline activation.
Current peer values are never substituted for a saved null delay baseline.

`GeographicSmsBaselineTest` adds fourteen cases across both baseline conditions.
An aligned COMPLETE queue with depth 250 and age 90 seconds produces a HIGH backlog
breach, and two adjacent queue-only windows open an episode. Wrong scope, interval,
role, quality or event contribution cannot establish a breach; forged reporters and
receipt/KPI disagreements remain rejected. Payloads remain unchanged. Missing
service telemetry cannot prove recovery, while measured zero deliveries with an
empty, young queue retain the existing recovery behavior. Nonzero completed
deliveries cannot borrow a current baseline to prove delay recovery.

The geographic CI step now receives `ML_SERVICE_URL` and explicitly selects this
regression class alongside the geographic detection, worker and parity tests.

## Actual verification on 7 October 2026

| Check | Result |
| --- | --- |
| Regression suite before production fix | 14 cases; 8 assertion failures, no errors or skips |
| Regression suite after fix | 14 passed, no failures/errors/skips |
| Maven reactor `clean verify`, Java 21, real HTTP scorer enabled | BUILD SUCCESS; 586 reported, 585 executed, no failures/errors; one existing opt-in shadow replay skipped |
| Geographic HTTP scorer controls | All twenty city scopes executed and passed; incompatible baseline rejection and deterministic fallback included |
| Root Python suite | 46 passed |
| ML Python suite | 54 passed |
| Contract validation | Passed |
| Fresh persisted Java/Python parity | 30 VoLTE and 32 SMS payloads; all fields matched, integers exact, maximum numeric difference zero |
| Baseline-substitution mutation | Reusing the active delay baseline caused two historical-delay/recovery assertions to fail; isolated source restored |
| Independent code review, CI YAML and diff whitespace | Passed |

The sole skipped test is `SmsShadowReplayTest`, which requires the existing optional
`SMS_SHADOW_REPLAY_DIR`. No geographic scorer test was skipped.

HTTP validation used the independent `telecom-pr60-validation` Docker container at
`http://127.0.0.1:18090`, built from the unchanged Dockerfile and pinned requirements.
Image: `sha256:8f87b0bbd3fca7f98903a35d69887f5fc41ac95472afbfd599473bb7e1ce3238`.
It used the Dockerfile's default command, Uvicorn 0.54.0, H11Protocol and concurrency
32; existing services were preserved. Java retained its 250 ms budget and eight
permits. Logs remain untracked under `tmp/pr60-fix/`; the isolated mutation log is
`tmp/pr60-review/review-baseline-substitution-mutation.log`.

Reproduce with Java 21 and the pinned inference service running:

```powershell
$env:ML_SERVICE_URL = 'http://127.0.0.1:18090'
./mvnw.cmd --batch-mode --no-transfer-progress clean verify
.venv/Scripts/python.exe scripts/check-voice-parity.py --geographic --java-output services/processor/target/geographic-voice-parity-java.json
.venv/Scripts/python.exe scripts/check-sms-parity.py --geographic --java-output services/processor/target/geographic-sms-parity-java.json
.venv/Scripts/python.exe -m unittest discover -s tests -v
.venv/Scripts/python.exe -m unittest discover -s services/ml-service/tests -v
.venv/Scripts/python.exe scripts/check-contracts.py
```

Policy thresholds, baseline numeric meaning, model/calibration bytes, feature
order, ML limits and frozen fixture expectations are unchanged from the base.
Frontend city bindings and shared G2/owner acceptance remain pending; these checks
do not establish geographic model accuracy or complete frontend acceptance.
