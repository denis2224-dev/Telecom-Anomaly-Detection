# Sergiu Day 4 — ML shadow and fallback hardening

Review correction addendum: [four independently reproduced findings and fixes](2026-10-14-sergiu-day4-review-corrections.md).
The original counts, hashes and acceptance statements below describe the original
implementation, not approval of the reviewed head. The addendum records the
replacement candidate pins and fresh correction checks; independent re-review is required.

Implemented the 13 October assignment on 14 October 2026, based on main
`f9934b5`, on `feat/trace-link-day4-ml-shadow-hardening`. Shared activation remains
disabled. This report records local verification, not deployment or owner sign-off.

Verified implementation: `525f01ed3a554531b4a83d0b4dd730c07a336981` (feature,
candidate/inference and durable-worker commits). The source manifest records both
verified workspace hashes and Git-blob hashes for each implementation file.

## Delivered behavior

- Versioned three-domain feature order, strict private request/response/result
  contracts, independent Java/Python primitive builders and ten hand-calculated
  fixtures. Missing values retain availability bits and raw quality/provenance.
- Separate checksum-pinned synthetic Isolation Forest candidate, training-only
  imputation/scaling and preceding seasonal baseline fitting. Model artifacts,
  manifest, calibration, baseline and split hashes are retained separately from V2.
- `/internal/inference/trace-link`, capability health, eight inference permits,
  a 250 ms client budget, response identity/freshness validation and explicit
  disabled/ineligible/fallback outcomes without accepted scores.
- V018 processor-local jobs/results, fenced claims, immutable evidence, an
  independent bounded executor and metrics. HTTP occurs outside transactions.
  Deterministic lifecycle/outbox code and the strict detection envelope are unchanged.
- Default-off configuration, container packaging, a full local verifier and
  [the operating runbook](../runbooks/trace-link-ml-shadow.md).

## Fresh verification

Raw logs, actual-run JUnit results, inputs, exports, failure evidence and manifests
are in [the evidence bundle](assets/trace-link-day4-20261014/verification.json).

| Gate | Result |
| --- | --- |
| Full processor/dependency Maven verification | BUILD SUCCESS; processor 1,105 tests, zero failures/errors, eight existing opt-in skips; streaming-support 139 and event-generator 158, zero failures/errors/skips |
| Affected Java suites before full rerun | 69 tests, zero failures/errors, two existing archive-dependent skips |
| ML-service Python | 86 tests, zero failures/errors/skips, including strict clean-process artifact rebuild and held-out seed isolation |
| Repository Python | 174 tests, zero failures/errors/skips |
| Feature parity | 34 fresh Java/Python vectors; integers exact and finite floats within 1e-9; all audit fields compared |
| KPI arithmetic | 54 independent cases agree in Java, Python, PostgreSQL and JavaScript; maximum error zero |
| Legacy geographic parity | 30 voice and 32 SMS payloads; all fields agree, maximum error zero |
| Frozen inputs | 29 V2/reference files and the independent fixture hash unchanged |
| Contracts/configuration | Complete contract validator, Compose validation and whitespace checks pass |
| Packaged inference | Final Docker image scores all three domains identically to offline scoring over real localhost HTTP; missing candidate leaves legacy readiness UP, returns explicit FALLBACK and no score |
| Migration/lifecycle isolation | Fresh and populated V017-to-V018 install, validation/no-op rerun, ownership/grants, rollback, lease fencing, restart and immutable results pass; ML failures leave exact detections/state unchanged; blocked HTTP does not stop deterministic OPEN |

The full initial Maven run exposed three stale schema/version/count assertions
and incomplete cleanup in the new isolation test. Exact expectations and test
cleanup were corrected; existing row, checksum, isolation and grant assertions
remain. The full final run is independently green. A later packaging-only refresh
copied LF-normalized contracts and final pins; it is not counted as another test
run. The processor JAR's feature-contract bytes match the final source exactly.

The eight existing skips require external live ML configuration, supplied SMS
shadow replay, the full native archive, or a frozen V016 directory. Their actual
names/reasons are in verification.json and compressed JUnit reports. No new Day 4
test is skipped. Historical `LegacyMlTimingHarness` XML left in target is excluded
from this run's evidence; only classes named in the fresh Maven log are counted.

## Reproduced corrections and review

1. The initial new worker compared the stored window key to the measurement
   window ID. These are different identities. Completion now verifies the saved
   payload hash and full rebuilt source features; stale claims and wrong-source
   requests cannot commit. Failed/passing worker logs and raw windows are archived.
2. The initial feature builder could accept a snapshot in place of a rate/mean
   population. A focused test failed before the correction. Both builders now
   require the metric's declared primitive fields and valid population/time.
   The real regression also kills an isolated missing-guard mutation; repository
   source was not modified during that mutation experiment.
3. Warm-process rebuilding after other model tests produced identical complete
   estimator/tree/preprocessing state and calibration/baseline hashes, but pickle
   string memoization changed model files by sixteen bytes. Strict byte reproduction
   now tests the documented fresh CLI process; it still compares every artifact byte.
   Separate in-process counter-seed fitting proves held-out changes cannot affect
   model, calibration or baseline hashes. Diagnostic manifests and state comparison
   are preserved; no scores, seeds or expected fixtures were changed to improve results.
4. Generated pinned JSON is written as LF UTF-8 and protected by Git attributes.
   Candidate source hashes declare LF normalization; artifact hashes remain strict
   raw hashes. Fitted baseline MAD values are recorded from the actual preceding
   samples rather than supplied as zero placeholders.

Final review covered correctness, readability, boundaries, security and resource
bounds. Model checksums and manifest pins are checked before deserialization;
SQL values are parameterized; raw scope/window/revision is retained; optional
inference has no write path into deterministic episode/delivery state. All prior
processor and incident migrations remain unchanged.

## Limits and Day 5 handoff

The candidate dataset is `trace-link-day4-native-synthetic-v1`, with twelve
five-minute windows per day for thirty days, not continuous thirty-day coverage.
Per domain: fourteen preceding baseline days, 96 training rows, 48 calibration
rows and 48 held-out rows. Held-out inputs are generated/hashed but never scored
here. Baseline slots and weekday/weekend classes use Europe/Chisinau.

The candidate supports only its manifest-declared synthetic demo scopes:
chi-hin:a-to-b, ro-partner:out DATA, and CHI mobile data. Other scopes are explicitly
ineligible. Broader model quality, held-out metrics, cause accuracy, calibration
as a probability, and connected capacity have not been established. HTTP figures
are three functional samples, not a throughput or latency benchmark.

Verification used Python 3.13.9 and manifest-pinned ML dependencies, PostgreSQL
16.4-alpine and Java 25.0.4.1 compiling for Java 21. Linux-container inference was
verified; byte-identical training reproduction was verified in the pinned Windows
environment. A separate Java 21 runtime campaign is not asserted.

Reproduce with `scripts/verify-trace-link-day4.ps1`; Docker and localhost socket
access are required. Use the runbook to trace a result to its saved primitives,
baseline/policy versions and manifest before Day 5 evaluation or any activation.
