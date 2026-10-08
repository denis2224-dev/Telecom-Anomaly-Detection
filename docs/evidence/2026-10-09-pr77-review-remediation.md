# PR #77 review remediation (2026-10-09)

**Status: fresh combined-revision acceptance PENDING.**
The historical archive/reproduction contract is corrected. Current main has been
integrated, and all six CI jobs for the intermediate source revision below passed.
Those CI results do not establish acceptance of the next candidate or final
publication SHA. G5 release approval remains **BLOCKED** until shared
final-candidate gates and required owner sign-offs pass.

## Review findings and implementation

- **P2: archived drivers.** The [historical acceptance report](2026-10-08-sergiu-g4-connected-acceptance.md)
  now labels its audit drivers historical and non-executable from committed paths,
  explains root/import relocation and original machine/private-configuration limits,
  and points to the [supported isolated workflow](../runbooks/pr77-connected-verification.md).
  All 55 indexed historical artifact hashes remain unchanged.
- **P2: current-main compatibility.** Main `a52cc85ae46b4d3138dbca505ba80de661fe97d1`,
  including PR #76 dashboard/session changes, is integrated. The supported runner
  builds a committed candidate with fresh isolated identities and owned resources,
  then runs real authentication, expiry, SSE reconnect and geographic browser checks.
  Fresh acceptance is still pending; all required cases must pass on one rebuilt candidate.
- Fresh compact results and sanitized captures belong in a separate evidence bundle;
  do not modify or relabel the sealed historical G4 assets. This focused verification
  does not repeat every historical replay/ML-outage audit or provide release-owner approval.

## Candidate and provenance

| Field | Recorded value |
| --- | --- |
| Integrated main | `a52cc85ae46b4d3138dbca505ba80de661fe97d1` |
| Intermediate runtime/CI source candidate | `7fe593eb0c4eabe4670e2d1c649fc573f3e91a1d` |
| CI actual checkout for that intermediate candidate | `87dc61bef23817ef675bc710970fd8eeda867685` (synthetic PR merge) |
| Fresh final runtime-tested source candidate | **PENDING** |
| Fresh runtime images, asset/configuration hashes and evidence checksums | **PENDING** |
| Fresh acceptance UTC start/end, run/incident identifiers and artifacts | **PENDING** |
| Later evidence-only publication head and final-head CI | **PENDING** |

Each intermediate job's checkout log explicitly says it merged `7fe593eb` into
main `a52cc85`, then records the full synthetic checkout SHA above. Fresh runtime
verification must record its own built source SHA and image identities. Committing
evidence afterward creates a later publication head; do not claim that later SHA was
directly runtime-tested. Any application/configuration fix requires rebuilding and
rerunning affected connected checks, and all candidate CI jobs must be green again.

## Verification recorded so far

The following six jobs passed for intermediate source `7fe593eb`, tested as
synthetic merge `87dc61b`. Their results must not be carried forward as final-head CI.

| Required job | Result | Evidence |
| --- | --- | --- |
| build | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37847468517/job/113551818744) |
| backend-verification | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37847468482/job/113551819174) |
| dashboard-verification | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37847468494/job/113551821662) |
| model-evaluation | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37847468484/job/113552113040) |
| deployment-config | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37847468484/job/113552113192) |
| processor-evaluation | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37847468484/job/113553129534) |

Local checks recorded by the coordinator passed: **59** Python contract tests,
**54** ML tests, **120** dashboard unit tests, and **129** controlled browser tests.
The controlled browser suite retains **17** existing opt-in skips; it does not prove
the required connected acceptance suite. Intermediate processor CI reports **825**
primary Maven tests with zero failures/errors and **4** existing skipped tests;
its downstream replay/parity/focused verification steps also passed. These are
individual run/step counts, not a combined acceptance total.

| Fresh connected gate | Result and evidence |
| --- | --- |
| Real OIDC roles, session/CSRF rotation, token-free storage, logout and protected 401 | **PENDING** |
| Open idle session expires after 15 minutes plus grace despite ordinary REST/SSE | **PENDING** |
| Absolute expiry at original 30-minute deadline despite trusted activity | **PENDING** |
| Owned proxy interruption/recovery: renewed SSE, authoritative REST refresh and retained UI state | **PENDING** |
| Fresh geographic faults/normal/gap controls: API, map, queue and detail agreement; masked desktop/mobile captures | **PENDING** |
| Owned cleanup and guarded credential removal | **PENDING** |

Backend time anchors expiry acceptance. All three required connected cases must
execute and pass with retries disabled. Timing-invalid, failed or interrupted
attempts remain separate records, not successful checks.

## Retained failures and finalization requirements

- **Attempt r1**, source `c2a19fb`: setup failed because geography activation was not
  minute-aligned. This was a harness configuration failure. Its cleanup and
  credential cleanup passed; retain the failed record independently.
- **Attempt r2**, source `7fe593eb`: identified a 390-pixel mobile incident-detail
  overflow and a browser performance-callback binding bug. The required connected
  suite was not fully accepted. Retain this attempt and its eventual timing/cleanup
  outcomes separately; a corrected candidate must execute all three cases fresh.
- **Fresh replacement attempt:** pending. Append actual test outcomes and artifact
  references only after the complete run and owned cleanup finish.

Before reporting ready for re-review, populate pending provenance and every fresh
gate from actual artifacts, verify all 55 historical seals again, recheck remote
main, and record six successful CI jobs for the publication candidate with their
actual checkout SHAs and URLs. Human PR approval/merge and G5 shared release
sign-off remain separate.
