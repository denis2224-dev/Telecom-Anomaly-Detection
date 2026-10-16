# PR #116 verifier review remediation

Reviewed revision: `6b120a25672972e926c043fff71455560967fe41`.
Repaired verifier/tests: `9dd490d0` on `feat/trace-link-day6-detection-signoff`.
Local verification passed; independent re-review and connected acceptance remain
pending. This document records the author's correction, not reviewer approval.

## Confirmed findings and corrections

| Finding | Reproduction and repair | Regression evidence |
|---|---|---|
| P2: invalid outcomes can pass | Equal scalar `PASS` values and boolean/integer equality were accepted. Validate all required outcome fields in the common manifest and both receipt objects before comparison. Require nonnegative integer counts, unique IDs matching counts, and nonempty named decision maps. | Scalar and partial objects, missing fields at each location, booleans, floats, negative counts, invalid IDs and invalid decisions are rejected. Valid outcomes still match across genuinely different run times/latencies. |
| P3: malformed receipt leaves empty report | Null trace rows and null/nonstring timestamps raised AttributeError. Validate their types plus top-level manifests, scenario, receipts, gates and outcomes explicitly. | Actual CLI subprocesses exit 1 and write parseable INCOMPLETE JSON with a nonempty reason; no traceback or empty report. A valid pair exits 0, retains humanAcceptance PENDING and sharedReleaseApproval false. |

The [runbook](../runbooks/sergiu-day6.md#receipt-format) specifies the six required
outcome fields. Count validation deliberately uses exact integer type checking;
Python boolean equality must not establish deterministic success. Decision maps
still require a reviewer to check complete scenario coverage against raw evidence.

## Verification and retained attempts

The [machine-readable receipt](assets/sergiu-day6/pr116-review-verification.json)
pins eight compressed logs, command exits, original log hashes and file completion
times. Times describe log completion, not event-time or deployed latency.

- Before repair: 23 focused tests, 15 failures and 6 errors. These reproduce the
  reported false PASS and empty-report paths.
- After repair and expanded coverage: 25 focused tests pass. The final full suite
  includes the later refinement retaining the release-expectation binding test.
- Final pinned Python 3.13.9 environment: **208 tests passed, zero skips**,
  including all 25 verifier tests. Do not add these overlapping counts together.
- An in-memory mutation accepting booleans as integers makes the new regression
  test fail as intended; source file bytes remain unchanged.
- The initial sandbox run could not use its temporary folders. The system-Python
  full-suite attempt lacked `openapi_schema_validator` and reported four import
  errors. Both attempts are retained; final verification used the project's
  existing pinned environment without changing dependencies or assertions.

No Java, ML study or Windows model-byte suites were rerun for this offline
verifier-only repair. Earlier results retain their original revision and scope.
INCOMPLETE/PASS lines emitted by verifier unit-test fixtures do not represent new
connected rehearsals or model-byte reproduction.

## New candidate and acceptance boundary

The [new freeze](assets/sergiu-day6/detection-inputs-after-pr116-review.json) pins
307 files at the repair commit. Compared with the preserved
[previous freeze](assets/sergiu-day6/detection-inputs.json), only
`scripts/verify-sergiu-day6.py` and `tests/test_sergiu_day6.py` change. All 307
current files match the new pins. Historical freezes, archives and their manifests
are unchanged; the new correction receipt is a separate evidence inventory and
does not hash itself.

The [fresh connected preflight](assets/sergiu-day6/connected-preflight-after-pr116-review.json)
exits 1 / INCOMPLETE because the common release manifest and both connected runs
are absent. Supply the new detection-input map to the common release coordinator
before freezing and running the candidate. There are no existing connected runs
to invalidate; both required future rehearsals must use the repaired candidate.

Stanislav's manifest/environment, Ion's scenarios, Denis's persisted linkage,
David's display inventory, human Day 5 acceptance, and actual Sergiu attestation
remain pending. SMS shadow and historical V016 external fixtures remain
unsupplied. Technical regression success does not grant final Day 6 sign-off.
