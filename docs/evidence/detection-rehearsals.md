# Sergiu Day 6 detection rehearsals

Milestone: 15 October 2026. Actual local execution: 15–16 October 2026,
Europe/Chisinau. **Connected rehearsals: NOT RUN.**
The user confirmed that the common release manifest / connected environment is
not available. Local verification cannot substitute for those two rehearsals.

## Candidate and immutable inputs

Branch: `feat/trace-link-day6-detection-signoff`, isolated LF checkout.
Production source base: `d408f7472ab0a7193c235edb84ba2f282bd08c4e`.
GitHub inspection during this task found PR #111 merged at
`74913e5bd4b6ab43ba94307a6aed5b2ba542569c`. Four workflows on the source base
passed: incident-runtime-image (38759114066), incident-service (38759112801),
dashboard (38759113115), service-integration (38759112943). These are base-source
results, not CI for the new Day 6 files. Historical Day 5 documents remain intact.

The [detection input freeze](assets/sergiu-day6/detection-inputs.json) pins 307
inputs at tooling revision `eabc6d03`, with Python and installed library versions.
The production sources remain byte-identical to the base above. Earlier freeze
snapshots are retained. The fragment contributes to the common release manifest
and grants no approval.

## Connected run ledger

| Run | Common manifest | Starting state | Trace evidence | Result |
|---|---|---|---|---|
| 1 | Not supplied | Not restored | Not captured | NOT RUN |
| 2 | Not supplied | Not restored | Not captured | NOT RUN |

No UI value, database row, incident evidence ID or latency is fabricated here.
The [script](../demo/sergiu-script.md) defines the walkthrough and the
[runbook](../runbooks/sergiu-day6.md) defines the raw receipt requirements.
The previously archived producer-only logical-clock runs remain separate.

## Local verification

Fresh execution results and raw logs are retained under `assets/sergiu-day6/`.
The final verification summary identifies the actual commands, versions, failures
and conditional skips. A local regression/study result is not a connected-run
receipt or evidence of deployed latency. Runtime output directories stay distinct
from the immutable historical Day 4/Day 5/producer archives.

The first focused test invocation failed because the verifier did not exist.
The next attempt hit the sandbox's temporary-directory permissions. Running the
unchanged tests outside that restriction passed; no assertion was weakened.
Docker was initially stopped and was started for disposable test infrastructure.

The original driver was interrupted during the processor suite and did not write
a final `verification.json`; it is not a completed full-verifier PASS. Before the
interruption, repository Python passed 196 tests, ML discovery completed 100 tests
with one fresh-Java-export skip, and the explicit Windows byte gate passed.
Four-language arithmetic independently matched all 54 hand-derived cases with
zero maximum absolute error. The 29 frozen legacy/reference pins passed.

The initial repeated held-out scoring failed when a response measured
421.0073 ms against the unchanged 250 ms limit during overlapping local checks.
An isolated unchanged-input repeat on 16 October passed: `results.json`,
`predictions.json` and `thresholds.json` are byte-identical to the first successful
evaluation. The study retains 280 groups / 3,360 windows and the original
validation/test seeds; no model, threshold or expected result was tuned.

On resuming after the overnight interruption, Docker was stopped again. That
attempt reported 1,123 processor tests, zero assertion failures, 369 setup errors
and two skips. Its entire log and JUnit reports are preserved separately. Docker
readiness was then checked before the next processor attempt. Do not sum counts
from these overlapping invocations or present setup errors as detector defects.

Archived execution bundles: [initial run](assets/sergiu-day6/initial-verification.zip),
[first resumed run](assets/sergiu-day6/resume-with-docker-unavailable.zip),
[focused checks](assets/sergiu-day6/local-checks.zip) and
[arithmetic exports](assets/sergiu-day6/arithmetic-exports.zip). Each bundle has a
matching `*-files.json` containing SHA-256 hashes of its members.

The next native run was interrupted during `TraceLinkProjectionIT`. Its completed
`TraceLinkDatasetBootstrapIT` report records 12 tests, zero failures/errors/skips,
including the explicitly selected full archive. The log and completed partial
JUnit reports are preserved in [the interrupted native run](assets/sergiu-day6/interrupted-native-run.zip).
The resumed verification runs the full default processor suite and selects the
remaining archive cases separately; completed large-import evidence is retained.

The final tooling review added three malformed-field regressions: boolean latency,
boolean exit code and a command string in place of an argument array. All three
failed before correction. The corrected verifier passes 16 focused tests, and
repository Python passes 199 tests. Earlier freezes and test logs remain intact.

## Final local verification

The [verification summary](assets/sergiu-day6/verification-summary.json) and
[final execution bundle](assets/sergiu-day6/final-local-verification.zip) retain
the completed commands, JUnit inventories, raw logs and review regressions.

- Final repository Python: **200 tests passed**, including **17** verifier tests.
- ML discovery: **100 reported, 99 passed, one pre-export feature-parity skip**.
  The later fresh-Java feature suite passes **10 tests**, closing that prerequisite.
- Explicit Windows candidate-byte reproduction passed. Four-language arithmetic
  matched **54** hand-calculated cases; all **307** current detection pins match.
- Full Java invocation: streaming support **139/139**, generator **158/158**,
  processor **1,138 reported / 1,133 passed / five conditional skips**; zero
  failures/errors. Native follow-up: **17 tests passed with no skips**. The earlier
  explicitly enabled bootstrap suite separately passed **12 tests with no skips**.
- The three archive-dependent skips in the default run are covered by those
  explicit archive invocations. Two external cases remain unverified: the SMS
  shadow replay dataset and the separate legacy frozen-V016 compatibility directory
  were not supplied. Their exact method names/reasons remain in the JUnit inventory.
- Real local HTTP validation, fresh feature parity, geographic VoLTE/SMS parity,
  legacy evaluation execution and the byte-identical held-out repeat passed.

Do not add counts from overlapping invocations. The earlier interrupted/failed
runs remain archived. No remote CI result is claimed for the new Day 6 branch.
The [current preflight](assets/sergiu-day6/connected-preflight-current.json) remains
INCOMPLETE because the common manifest and two connected receipts are absent.

## Handoffs required

Stanislav supplies the common manifest/environment; Ion supplies approved source
scenarios; Denis supplies persisted evidence linkage; David supplies the displayed
KPI inventory. Freeze expected results and starting state, execute both connected
runs, verify pins/outcomes, review all trace rows, and then record real human
acceptance. Any changed release artifact invalidates the previous rehearsal pair.
