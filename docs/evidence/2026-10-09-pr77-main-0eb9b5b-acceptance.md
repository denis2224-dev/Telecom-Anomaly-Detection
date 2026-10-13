# PR #77 prior-main focused acceptance (2026-10-09)

**Historical focused acceptance: PASSED for integrated main 0eb9b5b.**
This snapshot is not acceptance of later main `957876a87f2414d22977f59d8cf2ddd2002b1542`
or a candidate integrating it. See the [current remediation status](2026-10-09-pr77-review-remediation.md).

**Focused combined-revision technical acceptance: PASSED for the source below.** All three required
connected browser cases passed on source `1c889216dec184120373cfd52355f2b2e8fa4ac6` with retries disabled,
followed by successful owned-resource and credential cleanup. This establishes
the focused compatibility checks requested in review. Human PR approval is
**PENDING** and G5 release approval remains **BLOCKED** until shared final-candidate
gates and required owner sign-offs pass.

## Review findings addressed

- **Archived drivers:** the [historical report](2026-10-08-sergiu-g4-connected-acceptance.md)
  labels its drivers historical and non-executable from their committed paths,
  explains relocation/private-configuration assumptions, and links the
  [supported isolated workflow](../runbooks/pr77-connected-verification.md).
  All **55** sealed historical artifact hashes remain unchanged.
- **Current main:** the tested source integrates main `0eb9b5b417b37343e86038cdd84243ce68e17cbe`, including
  PR #76 session changes and PR #79 generator retry/managed proxy routing changes.
  Fresh production builds and real OIDC, expiry, SSE reconnect and geographic
  verification cover their combined behavior.
- **Evidence size:** runnable material and this compact fresh bundle are separate
  from bulky historical captures. Private logs, secrets, traces and automatic
  error-context captures are excluded from publication.
- **Verified fixes:** the connected run exposed a mobile provenance hash that
  overflowed its detail card. A scoped wrapping rule and regression test cover it.
  The SSE observer now discards historical requests on full document replacement;
  a real HTTP-server regression verifies reload, SPA navigation, reconnect and closure.

## Provenance

| Field | Recorded value |
| --- | --- |
| Directly runtime-tested source | `1c889216dec184120373cfd52355f2b2e8fa4ac6` |
| Integrated main | `0eb9b5b417b37343e86038cdd84243ce68e17cbe` |
| CI actual synthetic PR checkout | `363529482f48c7a848b5358b9c51da7c5cf49992` |
| Equal source/synthetic Git tree | `a6c9b5e247b24a7d60a674073ccfa8d9917492bc` |
| Owned project | `pr77-20261009-r6` |
| Runtime UTC start / finish | `2026-10-09T07:28:01.526Z` / `2026-10-09T08:26:09.001Z` |
| Evidence-only publication | Commit introducing this report; exact SHA and final six CI links recorded in the PR description |

[Runtime provenance](assets/pr77-review-remediation/runtime.json) records image IDs and source labels,
dashboard asset hashes, generated configuration hashes and cleanup outcomes.
[Connected reporter](assets/pr77-review-remediation/verification.json) records all three passing cases.
CI logs identify the full source/main merge and checkout; both trees are identical.
[Application/configuration source objects](assets/pr77-review-remediation/runtime-source-objects.json)
provides the comparison against the later publication head: all root entries
except docs/tasks must remain identical. The publication commit is evidence-only;
its SHA is not claimed as directly runtime-tested. Historical G4 evidence keeps
its original build/source attribution.

## Fresh connected results

| Gate | Result and evidence |
| --- | --- |
| Real OIDC roles; session/CSRF rotation; stale CSRF rejection; token-free storage; analyst scenario restrictions | PASSED - [geographic-security-results.json](assets/pr77-review-remediation/geographic-security-results.json) |
| Owned proxy stop/start; native SSE reconnect and authoritative REST; retained city/filter/range/focus | PASSED - [geographic-security-results.json](assets/pr77-review-remediation/geographic-security-results.json) |
| CHI faults, BAL normal and CAH UNKNOWN/null gap; API/map/queue/detail agreement and recovery | PASSED - [scenarios.json](assets/pr77-review-remediation/scenarios.json) |
| Open inactive dashboard at fifteen-minute idle deadline despite ordinary REST/SSE | PASSED - [idle-expiry-results.json](assets/pr77-review-remediation/idle-expiry-results.json) |
| Original thirty-minute backend absolute deadline despite trusted activity | PASSED - [absolute-expiry-results.json](assets/pr77-review-remediation/absolute-expiry-results.json) |
| Protected 401 and real UI logout close streams/background work; new protected requests rejected | PASSED - [geographic-security-results.json](assets/pr77-review-remediation/geographic-security-results.json) |
| Owned-resource cleanup and guarded credential removal | PASSED / PASSED - [runtime.json](assets/pr77-review-remediation/runtime.json) |

Idle session remained connected at **885.025 s**,
with **117** ordinary background requests
and **1** active SSE connection(s). Deadline
observation was **905.042 s** after last trusted input
`2026-10-09T07:40:00.212Z`. Absolute deadline
`2026-10-09T08:25:31.033450071Z` was unchanged across
**30** trusted activity observations; expiry was
observed at `2026-10-09T08:25:36.047Z`, after
**1804.861 s** from its timing anchor. Both cases
record host/browser monotonic consistency, backend clock anchors, zero active
streams after expiry, a 20-second quiet period and protected statuses 401/401/401.
No accelerated clock or mocked connected response was used.

Fresh scenarios ran from `2026-10-09T07:31:00Z` to
`2026-10-09T07:39:00Z`. Run records retain their original creation-time
status; completed assertions, receipt provenance and recovery are recorded separately.

| Scope / scenario | Run identifier |
| --- | --- |
| VOLTE-MD-CHI / VOLTE_IMS_OVERLOAD | `9181745c-a693-4419-99a4-e87d023408d8` |
| SMS-MD-CHI / SMS_QUEUE_DELAY | `8c7856d2-af71-4ef9-bdb0-3c469ea66b8b` |
| VOLTE-MD-BAL / NORMAL_CONTROL | `4830ad62-7d75-4425-873f-bd5f8363862d` |
| SMS-MD-CAH / TELEMETRY_GAP | `6c288f02-423a-42e6-9e62-92bd35001eb3` |

Incident identifiers: `31f0bc9d-b4b9-4f69-96c4-eb8423dca9de`, `fc7c1711-9ce0-4a6b-93ab-184e6eff77a6`.
The trace verifies **10 immutable detections**, five per fault, against DB/API
canonical identities and **25 distinct authorized durable source receipts**.
Normal/gap controls produced no false incident; recovered incidents retained the
analyst workflow state OPEN. **Nine** explicitly masked desktop/mobile
[captures](assets/pr77-review-remediation/captures/) cover queue, gap, both service
details and recovery. [Bundle index](assets/pr77-review-remediation/index.json) seals the published files;
[historical-seals.json](assets/pr77-review-remediation/historical-seals.json) records the 55 unchanged historical artifacts.

## Candidate CI

All six required candidate jobs passed on the actual synthetic checkout above.
[ci-runtime-candidate.json](assets/pr77-review-remediation/ci-runtime-candidate.json) includes job URLs, checkout evidence and bounded
test summaries. Final publication-head CI is recorded separately in the PR description.

| Required job | Result | Evidence |
| --- | --- | --- |
| build | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37899162469/job/113717416682) |
| backend-verification | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37899162470/job/113717416558) |
| dashboard-verification | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37899162541/job/113717416621) |
| deployment-config | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37899162516/job/113717417001) |
| model-evaluation | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37899162516/job/113717417161) |
| processor-evaluation | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37899162516/job/113719101663) |

Dashboard: **120** unit tests; **131** controlled browser passes and **17** existing
opt-in skips. Backend: **151** unit tests (**1** existing skip), plus **59** integration
tests. Contracts/ML: **59 / 54** tests; both services' HTTP parity passed. Processor
primary reactor: **111** streaming, **95** generator and **825** processor tests
(**4** existing skips), followed by successful selections **2 / 19 / 2 / 179 / 63**
and **30 + 32** persisted Java/Python parity payloads. These are separate step
counts with overlapping selections, not a combined acceptance total.

## Failed and interrupted attempts retained

| Attempt / source | Actual outcome |
| --- | --- |
| r1 / `c2a19fb` | Setup FAILED: activation lacked whole-minute alignment. Owned cleanup and credential cleanup PASSED; alignment corrected. |
| r2 / `7fe593eb` | Mobile-detail overflow and performance-callback binding failures; forcibly interrupted without final runtime/verification reporters. Absolute outcome unavailable. Separate recovery removed 13 owned resources and credentials; original runner teardown is not inferred. |
| r3 / `a4e8b789` | Harness race after native protected 401 removed the next click target; owned browser child interrupted. Idle/absolute not executed. Runner cleanup and credential cleanup PASSED; native-response assertions and action bounds corrected. |
| r4 / `df84b682` | FAILED before runtime startup. Runtime cleanup NOT_STARTED; credential cleanup PASSED. No runtime acceptance inferred. |
| r5 / `df84b682` | Failed historical SSE request-object count after document replacement. Real HTTP diagnostic identified observer accumulation and drove lifecycle correction. Idle interrupted, absolute not executed. Runner cleanup and credential cleanup PASSED. |

The [failed-attempt records](assets/pr77-review-remediation/failed-attempts/) remain
separate from fresh acceptance. This focused run does not repeat every historical
replay/ML-outage audit or supply release-owner approval. G5 remains **BLOCKED**.

## Prior evidence-publication CI

The evidence-only publication head `7f6505a7084db456bc1faa0cb71eb43e9fd6cc05` passed
all six required CI jobs. Every log records synthetic checkout
`e68273f2aa916a21dcccda2224816881429d0ec6`, merging that publication into
main `0eb9b5b417b37343e86038cdd84243ce68e17cbe`. Source and synthetic trees both
are `fd0910384b0e4429a82dfb3837200d9bb8894146`. These checks do not verify
the later dashboard merge.

| Job | Result | Evidence |
| --- | --- | --- |
| deployment-config | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37905068383/job/113736452376) |
| model-evaluation | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37905068383/job/113736452674) |
| processor-evaluation | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37905068383/job/113737729203) |
| dashboard-verification | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37905068401/job/113736452375) |
| build | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37905068390/job/113736451959) |
| backend-verification | SUCCESS | [Job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37905068362/job/113736451821) |

[Compact prior publication CI record](assets/pr77-review-publication-7f6505a7.json)
retains full checkout/tree identities and bounded job log excerpts outside both sealed bundles.
