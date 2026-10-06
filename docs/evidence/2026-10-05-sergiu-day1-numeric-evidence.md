# Sergiu Day 1 — numerical and evidence deliverables

**Implementation/local verification: PASS. Owner acceptance/shared G1: PENDING.**

This implements the October pack's Rusu Serghei/Sergiu Day 1 assignment. Local results below are separate from external owner reviews and shared geographic activation.

## Base and ownership

Selected current main: `46c72f9b02beabf5b1b8e58ee9bb2bf0df33a6bf`, fetched 5 October and rechecked with ls-remote after verification.
Work is isolated on `feature/sergiu-day1-numeric-evidence` in sibling worktree `Telecom-Anomaly-Detection-sergiu-day1`.
The original checkout's branch/untracked files and earlier September plan are preserved.

GitHub read-only checks confirmed PR #43 merged at 620a48a, #46 at 3ef5191 and Ion's #48 at 0d71c4f.
Ion's merged authority/role/coverage contracts are on this mainline, CONTRACT_ONLY/effectiveFrom=null.
Stanislav's devops proposal at 5bec67d records older c539b67 and blocked runtime checks; it is an audit source, not acceptance of this newer base.
Denis's backend contract proposal at `65be90948fe8ad4540894e3b916955b7bda1c902` was checked for semantic alignment and remains a proposal.

Sergiu owns metric meaning, baseline/detection contracts and Python expectations; Ion owns Java feature building/joining.
Denis owns protected OpenAPI, incident storage/read projections and migrations; David owns UI/generated types;
Stanislav owns combined integration and deployment/rollback. No migration reservation is needed for this Day 1 change.

## Completed technical tasks

| Task | Output / result |
| --- | --- |
| 1. Integration inputs | Exact current-main candidate SHA, merged PR state, published Ion catalogue and ownership recorded |
| 2. Numeric/model freeze | Metric dictionary, comparator table, ordered vectors and hashes of 14 existing invariant files |
| 3. Golden meaning | Eight voice/seven SMS shared cases; independent Java BigDecimal/Python Decimal verification |
| 4. Roles/baselines | Sergiu accepts Ion resolver boundary; opt-in twenty-peer candidate; 3360 city/hour lookups plus legacy slots and negatives |
| 5. Evidence/optional boundary | Seven DTO/wording examples, strict cause schema and separate bounded PLANNED auxiliary candidate |
| 6. Verification/handoff | Full Java/Python/contract/parity results, package build, hash evidence and actual owner dispositions |

Deliverables:

- [Metric dictionary and boundary table](../detection/geography-numeric-contract.md).
- [Role/baseline/ML/outbox decision](../detection/geographic-role-baseline-decision.md).
- [Evidence vocabulary and optional boundary](../detection/geographic-evidence-contract.md).
- [Contract/model/mapping hash manifest](2026-10-05-sergiu-day1-contract-manifest.json).
- [Machine-readable validation evidence](2026-10-05-sergiu-day1-validation.json).

## Fresh verification

| Check | Actual result |
| --- | --- |
| scripts/check-contracts.py | PASS: old V2/policy/baseline/explanation fixtures, candidate baseline; 10 cities/20 city+2 legacy scopes, 50 observations, 12 invalid catalogues, 30 coverage cases; 15 numeric cases, 7 cause DTOs, 1 planned auxiliary shape |
| Root Python suite | 44 passed; 0 failures/errors/skips |
| ML Python suite | 16 passed; 0 failures/errors/skips, including 9 feature tests |
| Maven processor reactor verify | 414 passed: 110 streaming-support +59 event-generator +245 processor; 0 failures/errors/skips; packaged artifacts built |
| New GeographicAggregationCasesTest / GeographicBaselineContractTest | 4 passed in initial focused run; also covered by full verify |
| Fresh legacy voice Java/Python export | 7 persisted payloads matched; integers exact; maximum numeric difference 0 |
| Fresh legacy SMS Java/Python export | 12 persisted payloads matched; integers exact; maximum numeric difference 0 |
| Deviation sign mutation | Inversion failed tests as required; source restored/recompiled; final suite passed |
| git diff --check / invariant bytes | PASS; policy/order/V2 schemas/baseline/default authority/model/calibration unchanged |

Java verify includes existing rule, baseline, configuration, explanation, episode, ML client, replay/outbox/finalization suites and both live packaged-model delivery profiles. Existing no-ML tests prove deterministic fallback. New SMS tests independently cover 3× ratio, absolute recovery, backlog and HIGH/CRITICAL boundaries.

Runtime: Java 21.0.12, Maven 3.9.16, Python 3.13.9; numpy 2.4.6, scikit-learn 1.9.1, joblib 1.5.3, FastAPI 0.141.1, Uvicorn 0.54.0.
The model manifest pins Python 3.13.7; scorer checks compatible major/minor 3.13, exact numerical libraries and artifact hashes. Dependencies were not changed.
Docker Desktop was started for disposable Testcontainers, engine 29.5.2. No user database was reset. ML validation used a loopback-only server on port 18090.
No frontend/authenticated all-city browser run or live geographic generation was performed. City scorer tests reuse golden vectors to prove contract compatibility, not new-city feature parity or geographic model generalization.

## Reproduction and failures resolved

With Python 3.13/repository requirements and Java 21:

```powershell
python scripts/check-contracts.py
python -m unittest discover -s tests -v
python -m unittest discover -s services/ml-service/tests -v
```

Start ML in a separate terminal:

```powershell
python -m uvicorn app.inference.api:app --app-dir services/ml-service --host 127.0.0.1 --port 18090 --limit-concurrency 8 --http h11
```

With Docker running and JAVA_HOME pointing to JDK 21:

```powershell
$env:ML_SERVICE_URL='http://127.0.0.1:18090'
.\mvnw.cmd -pl services/processor -am verify
python scripts/check-voice-parity.py --java-output services/processor/target/voice-parity-java.json
python scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json
```

The actual run used the cached Maven 3.9.16 distribution. An initial unquoted dotted Surefire -D argument failed before tests; quoting corrected it.
The first full verify exposed a local Uvicorn auto-parser problem: the same valid Java POST returned 200 via HTTP/1.1 but 422/missing body with h2c upgrade.
Explicit h11 made both probes return 200 and the full reactor pass with no skips. Protocol selection is a supported [Uvicorn setting](https://www.uvicorn.org/settings/).
This is local runtime evidence, not a deployed-container fix. MlClient's 250ms budget/eight permits remain unchanged.
The sign-mutation experiment initially left stale Python bytecode after restoring same-length source; force-compiling the restored module cleared contamination and the final 44-test run passed.

## Consumer handoff and actual review dispositions

| Owner | Ready artifacts | Disposition |
| --- | --- | --- |
| Ion | Exact counters/fixtures, resolver acceptance, peer baseline/ML decision, atomic finalization/outbox boundary | Sergiu technical review completed; Ion acceptance of new Sergiu outputs PENDING |
| Denis | Weighted/subset/null semantics, cause DTO candidate, historical UNKNOWN labels, version-pinned/idempotent coverage boundary | Published proposal aligned; acceptance of new Sergiu outputs PENDING |
| David | Signed pp, units/samples, attempt estimates, hypothesis/uncertainty wording | New output review PENDING |
| Stanislav | Exact base/hashes, runtime prerequisites, counts, parity, optional readiness boundary | Current combined-tree/shared G1 acceptance PENDING |

No message, PR review or signature was fabricated or sent on behalf of these owners. Ion PR #48 approval is not approval of this new branch. Formal reviews must identify the accepted artifact revision.

**Sergiu technical deliverables: PASS. Shared G1: PENDING OWNER ACCEPTANCE.** The pack additionally requires signed multi-owner contract review, one accepted integration revision with combined frontend/backend/security regression and agreed rollout/rollback.
Day 2 consumes these role/baseline decisions and must prove fresh city-aware feature parity and live delivery. Local reference checks are not live geography.
Optional power/probe runtime remains PLANNED/NOT_IN_SCOPE until G2 publisher, allowlisted authority, consumer, storage/API and controls exist. The sample cannot establish authorized operational evidence or confirm power loss.
