# PR #77 current-main focused acceptance (2026-10-09)

**Focused combined-revision technical acceptance: PASSED.** All three required
connected cases passed on `84477ac96189f1e147898ed11b8c155c99769d06`, integrating main `613661dd87b2285ba56c6b9df4c0b181dde9e2bf`, with retries
disabled and successful owned-resource and credential cleanup. This addresses
the review's outdated SMS/evidence-panel acceptance workflow. Human PR approval
remains **PENDING** and G5 release approval remains **BLOCKED**.

## Review finding and verified changes

The connected test explicitly selects **Show SMS graph** after dashboard navigation,
then verifies current technical-state badges and **Troubleshooting** evidence codes,
node IDs and source IDs. Cause assertions follow the current summary/title semantics.
Unsupported customer counts are omitted by the UI; the durable API/database checks
still require `uniqueSubscribers=null` for every immutable detection. Unavailable
paths and evidence classification are verified explicitly.

Current main uses `100vw` mobile drawers. The earlier body-width assertion reproduced
two failures (375px expected, 390px actual); the corrected test requires viewport
width and both edges while retaining scrolling, workspace and focus checks.
The controlled geography interaction check pins its clock away from minute
boundaries so its exact explicit-refresh count cannot race a legitimate fallback.
Its count assertion remains unchanged; omitting the refresh still fails. The
connected suite uses real clocks throughout.

## Provenance and connected results

| Field | Recorded value |
| --- | --- |
| Directly runtime-tested source | `84477ac96189f1e147898ed11b8c155c99769d06` |
| Integrated main | `613661dd87b2285ba56c6b9df4c0b181dde9e2bf` |
| Main integration commit | `d638a71de3cbac1ddcb65236c75c4c01c0989f7a` |
| Candidate CI synthetic checkout | `ab902734dd3a8900cbc18b422345c4c12228df4d` |
| Equal candidate/synthetic Git tree | `9d1e55fd0270bfa51c86a379e15fd037f16bb31c` |
| Owned project | `pr77-20261009-r9` |
| Runtime UTC start / finish | `2026-10-09T11:16:37.679Z` / `2026-10-09T12:15:07.912Z` |
| Owned-resource / credential cleanup | PASSED / PASSED |
| Evidence-only publication | Commit introducing this report; exact SHA and its subsequent CI links are recorded in the PR description |

[Runtime provenance](assets/pr77-review-main-613661d/runtime.json) records image/source IDs, production
dashboard asset hashes, generated configuration hashes and cleanup. The
[connected reporter](assets/pr77-review-main-613661d/verification.json) binds all three outcomes to the
candidate. [Source objects](assets/pr77-review-main-613661d/runtime-source-objects.json) identify every
root entry except docs/tasks; publication must preserve all of them. The later
evidence commit is not claimed as directly runtime-tested.

| Required connected case | Result | Actual duration |
| --- | --- | --- |
| integrated production roles, real SSE reconnect and fresh geographic incidents | PASSED | 10.15 min |
| open inactive dashboard expires at real fifteen-minute idle deadline | PASSED | 15.52 min |
| trusted analyst activity cannot extend real thirty-minute absolute deadline | PASSED | 30.52 min |

Real OIDC roles, session/CSRF rotation, protected 401/logout behavior and native
proxy/SSE reconnect passed. Reconnect retained city, service, range and focus and
refreshed authoritative REST data. Chișinău VoLTE/SMS faults agreed across API,
map, queue and details; Bălți normal and Cahul missing/null controls produced no
false incidents. Technical recovery preserved the analyst workflow OPEN.

The [source trace](assets/pr77-review-main-613661d/geographic-security-results.json) verifies **10
immutable detections** and **25 distinct authorized durable receipts**.
[Nine masked desktop/mobile captures](assets/pr77-review-main-613661d/captures/) supplement the API/source
assertions. Creation-time run statuses are retained; completed assertions are
recorded separately in the geographic result and [scenario record](assets/pr77-review-main-613661d/scenarios.json).

| Scope / scenario | Run identifier |
| --- | --- |
| VOLTE-MD-CHI / VOLTE_IMS_OVERLOAD | `b4a7819a-5bac-40b9-9cad-6fe2427bb650` |
| SMS-MD-CHI / SMS_QUEUE_DELAY | `266fa2ee-684a-4a76-a3d6-ee6015eab0a9` |
| VOLTE-MD-BAL / NORMAL_CONTROL | `6c8cb937-4486-4755-8e4e-f1c11fc059c5` |
| SMS-MD-CAH / TELEMETRY_GAP | `c4f6169e-338a-411f-aba2-752cd53460b5` |

[Idle timing](assets/pr77-review-main-613661d/idle-expiry-results.json): still connected at
**885.026s**, with
**125** ordinary background requests and
**1** active SSE connection; expiry observed
at **905.037s** after last trusted input
`2026-10-09T11:28:58.871Z`. [Absolute timing](assets/pr77-review-main-613661d/absolute-expiry-results.json):
the original deadline `2026-10-09T12:14:29.956020719Z` remained unchanged
across **30** trusted activity observations; expiry
was observed at `2026-10-09T12:14:34.969Z`. Both cases
checked host/browser monotonic consistency, zero remaining SSE streams, a 20-second
quiet period and protected statuses 401/401/401. No accelerated clock or mocked
connected response was used.

## Candidate CI and local checks

All six required candidate jobs passed on the synthetic checkout above.
[Candidate CI record](assets/pr77-review-main-613661d/ci-runtime-candidate.json) retains job URLs,
actual checkout evidence and bounded test summaries. Evidence-publication CI
is verified separately and recorded in the PR description.

| Required job | Result | Evidence |
| --- | --- | --- |
| build | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37928851285/job/113814294000) |
| backend-verification | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37928851292/job/113814293150) |
| dashboard-verification | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37928851272/job/113814293015) |
| deployment-config | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37928851303/job/113814303310) |
| model-evaluation | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37928851303/job/113814302908) |
| processor-evaluation | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37928851303/job/113816221818) |

[Local verification](assets/pr77-review-main-613661d/local-verification.json) records 164 full controlled
browser passes with 21 existing opt-in skips, 131 dashboard unit tests, 16 focused
browser tests, the production build and seven runner-safety tests. Unit/build and
focused checks preceded the controlled-test-only clock fix; their relevant source
is unchanged, and candidate CI reruns its own gates independently. The failed
drawer reproduction, initial controlled race and failing explicit-refresh mutation
are retained separately from successful verification.

## Retained failures, sealed history and limits

Attempt `pr77-20261009-r7` used earlier candidate `be05082`. Its geographic/security and idle
cases passed; the absolute case timed out after Windows Modern Standby interrupted
execution across its deadline. It remains **FAILED**, with no full-suite acceptance
inferred. [Failure reporters](assets/pr77-review-main-613661d/failed-attempts/) retain source/timing/results,
the power-event and Docker-health-gap diagnosis, and successful resource/credential
cleanup. Bulky failed-attempt captures and private diagnostics remain outside this bundle.

Attempt `pr77-20261009-r8` used that same earlier candidate and passed geography/security.
Its owned browser process tree was then interrupted after a concurrent controlled
suite exposed the minute-boundary exact-count race. Idle/absolute acceptance was
incomplete; no completion reporter is inferred. Runner resource/credential cleanup
passed and the keep-awake request was released. The two-line controlled clock fix
was committed as the new candidate before a completely fresh connected execution.

The successful retry used a scoped Windows system/display keep-awake request,
released after the runner; no permanent power policy changed. Explicit sleep/lid
closure can override that API ([Microsoft documentation](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate)).

[Prior seals](assets/pr77-review-main-613661d/prior-seals.json) verify all **29** prior focused artifacts and
**55** original G4 artifacts unchanged. Their historical reports and failed attempts
retain their original sources. [Bundle index](assets/pr77-review-main-613661d/index.json) seals this compact
new bundle. This focused compatibility run does not repeat every historical G4
replay/ML-outage audit or supply reviewer/shared-owner release approval.
