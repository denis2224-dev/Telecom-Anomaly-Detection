# PR #102 dashboard reconciliation

13 October 2026. Overall **PARTIALLY_COMPLETE**: reconciled implementation and isolated verification;
owner decisions and connected delivery remain **PENDING_REVIEW/BLOCKED**. Draft PR:
[#102](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/102).

## Source and preserved boundaries

- Worktree: `C:/OrangeSystems/Program/trace-link-contract-resolution-20261013`.
- Branch: `integration/trace-link-contract-resolution-20261013`.
- Previous published head: `65560c4582f2e2aca061af57dc6980a88cd918ec`.
- Previous integration base: `3468ed908bebbe0eae7ced93ef46d56e412c9655`.
- Current PR #101 base: `eab953e9af90ddf5d14cbda57a6935994c2cefb1`.
- Non-rewriting merge: `eba554e27ebbfff02957c95acec99b73ce2fab86`, preserving both parent histories.
- Tested reconciled code: **`31f093987d898b8589cd8522450703f594cf4cb6`**.
- Initial backend implementation: `8f97fb3c853cdd34adb14016724ffe6e85177ee3`.

The only new executable change after merging the base is the history browser test. PR #101 commits
`046a5549` (INSUFFICIENT_EVIDENCE admission and negative controls) and `eab953e` (modal close focus)
are retained exactly in the component and unit tests. No production detector, receiving, storage,
migration, security, source fixture, policy or baseline changed. Git tree equivalence for `services`,
`contracts`, `scripts`, `tests` and `.github` is recorded in the assets. The previous acceptance record
and its byte-exact artifacts are retained; their historical test counts are not new executions.

## Original failure and demonstrated correction

Inspected [CI run 38439606468](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/38439606468),
its failed-job log and artifact `dashboard-browser-results` (ID `11835585431`). The run reported
171 passes, 24 skips and one failure finding `Expanded persisted metric` after Enter at 1366px.
Although the event head was `65560c4`, checkout actually tested GitHub merge
`bd6df213fb33ac2774ab46d51d2999ce5cdb3d28` into `eab953e`; both base fixes were already present.
Merging alone therefore cannot establish that this intermittent failure is resolved.
The failing CI case saved an accessibility/error-context document, not a trace or screenshot: the
configuration disables automatic capture and the case failed before its final explicit screenshot.
The new controlled reproduction supplies the missing trace without using a real login session.

The unchanged test passed 8 traced repeats, 20 CI trace-off repeats and 4 CPU-throttled diagnostic
repeats. A controlled Linux reproduction forced the permitted ordering: reset-query click, focus
the existing opener, then Angular's scheduled loading render. A gated fixture response proved that
the focused opener was detached, focus became BODY, and Enter failed the original dialog assertion.
The failing reproduction, log and trace are retained. This reproduces the focus/render race;
the unmodified intermittent failure did not recur spontaneously in those targeted attempts.

The test now verifies the rendered bucket timestamp and count after resolution/zoom/reset changes
before keyboard activation. It asserts focus and sends Enter through the current button locator.
Two added gated-response cases at 1366px and 390px prove that loading hides the old opener, accepted
reset history can be expanded by keyboard, INSUFFICIENT_EVIDENCE remains visible, and Escape returns
focus. All original modal, recovery-of-focus, chart, CSV, topology and overflow assertions remain.
No production modal workaround, assertion removal, retry increase, timeout increase or sleep was added.

An initial full Linux browser attempt reported 169 passes, 24 skips and three Chromium
`Page.captureScreenshot: Unable to capture screenshot` protocol failures in service-assurance.
It overlapped disposable database and diagnostic browser work. All 16 assurance cases passed unchanged
on rerun with screenshots retained. An additional byte-exact LF run had 173 passes, 24 skips and one
identical screenshot protocol error in the mocked anonymous-login test; all history cases passed.
The final full suite uses a fresh container with `--init --ipc=host`, following the
[recommended Playwright Docker configuration](https://playwright.dev/docs/docker), and no concurrent
disposable database or diagnostic browser work. No OOM kill was observed; an exact cause of those
protocol errors is not asserted. These initial failures are archived rather than suppressed or counted as passes.

## Renewed verification

| Check | Actual result |
| --- | --- |
| Linux isolated-runner safety | 42 passed, no skips/failures |
| Linux dashboard units, including base quality controls | 162 passed across 30 files |
| Production dashboard build | PASS |
| OpenAPI generation | PASS; generated schema SHA-256 unchanged |
| Corrected history suite, four repeats | 20 passed; not added to full-suite totals |
| Unchanged assurance suite rerun | 16 passed; not added to full-suite totals |
| Final full Linux browser suite, workers=2 | 198 reported, 174 passed, 24 skipped, 0 failures/flaky tests |
| Contract/schema/independent source-byte validation | PASS |
| Repository Python | 160 reported, 159 passed, 1 skipped; unavailable pwsh wrapper regression |
| Focused shared/minute/offline/native/V016 Java | 50 passed, no skips/failures/errors |
| Complete incident-service verify | 273 unique reported, 272 passed, 1 skipped; SMS_SHADOW_REPLAY_DIR absent |
| Incident PostgreSQL integration subset | 100 passed, no skips/failures/errors; included above |
| Fresh/upgrade ownership, grants, immutable/conflicting/concurrent replay, rollback, restart, cross-DB denial | PASS |
| Protected API/session/role/CSRF component checks | PASS; not real login/browser acceptance |
| Full native-domain SQL parity | PASS; 362,880 rows, all eleven metrics across 14 metric/scope/direction cases |
| Held V016 compatibility | PASS; original migration/archive unchanged; no intent released |
| Git whitespace and final source equivalence | PASS |

The 24 browser skips are unchanged opt-in gates: 21 require a real/protected/authenticated stack or
session/rehearsal setup, and three require an explicitly selected fixture server. Their exact test names
and original skip annotations are retained in the verification manifest. None is counted as a pass.

The initial focused Maven attempt lacked sandbox access to the Docker named pipe and produced two
Testcontainers initialization errors. Its log is retained. Sequential rerun with authorized Docker access
passed all 50 cases. Reports select the latest result per class; duplicate Surefire/Failsafe classes
are not counted twice. No new full Java reactor or ML-model execution is claimed: those trees are
identical to the prior verified implementation. The previous 8,640 projections/held outbox entries
remain evidenced by the preserved original acceptance; this follow-up did not release or rebuild them.

The evidence audit also detected CRLF lines in the Windows-copied browser test. Its normalized content
was identical to the committed source. The final full Linux browser run uses the exact LF Git blob,
SHA-256 `2cce72d815955a19dce3be8f6f8fe53c14b553bb59da120eaa92db5ca74f42cf`;
the earlier successful run and its original bytes are distinguished in the saved logs.

Linux environment: `mcr.microsoft.com/playwright:v1.63.0-noble`, image digest
`sha256:eff16c30e6f3f4af0a03fa4b706120d5e9b0891c344a27d64559aff5900a4a27`,
Node 24.20.0, npm 11.19.0, Chromium supplied by that matching Playwright image.
Existing Java 21/Maven 3.9.16/PostgreSQL 16.4-alpine tooling was reused. No shared volumes or demo
databases were deleted. The isolated Linux diagnostic copy is separate from the committed source.

Commands, suite/skip records, original failures, selected JUnit XML, source trees and SHA-256 hashes are
in [the reconciliation assets](assets/trace-link-contract-reconciliation-20261013/verification.json).
The [current changed-path list](assets/trace-link-contract-reconciliation-20261013/changed-paths.txt)
is relative to the refreshed integration base. Asset hashes exclude the hash manifest itself.

## Receiving decisions and safety

The [versioned decision package](../architecture/trace-link-contract-resolution-v1.md) remains review-ready:
Sergiu must decide thresholds/baseline/source authority, UNKNOWN/recovery and causal semantics;
Denis must decide lossless mappings, V010 authority/immutability, qualifications, queries and future
incident/delivery lifecycle. Ion's source identity/replay checklist and Stanislav's migration/delivery
operations decisions remain pending. Actual review requests are not approvals.

Kafka activation **NO**; V016 remains held; feature flag remains default-off. Merge-ready **NO** until
owner decisions and separately required connected acceptance. Fixture-driven Playwright and PostgreSQL
durability do not establish real authenticated browser or durable Kafka ACK/restart acceptance.
Safe rollback remains the existing additive feature-off procedure, preserving V010, identities and history.

Highest-priority remaining task: Sergiu and Denis record APPROVED or REJECTED decisions with references
in Draft PR #102 before any connected-delivery activation work. Remote CI is verified again after push;
the final delivery response identifies the exact remote HEAD and resulting checks.
