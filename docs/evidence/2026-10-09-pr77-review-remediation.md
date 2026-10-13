# PR #77 review remediation - current status (2026-10-09)

**Focused latest-main combined technical acceptance: PASSED.** The supported
connected suite passed all three required cases on
`84477ac96189f1e147898ed11b8c155c99769d06`, integrating main
`613661dd87b2285ba56c6b9df4c0b181dde9e2bf`. All six candidate CI jobs passed.
Human PR approval remains **PENDING** and shared G5 release approval remains
**BLOCKED** until the required shared gates and owner sign-offs pass.

## Current-main result

The [current-main acceptance report](2026-10-09-pr77-main-613661d-acceptance.md)
records the actual runtime/source/image/asset identities, original deadlines,
scenario/source receipts, nine masked captures and successful resource/credential
cleanup. Its [new compact bundle](assets/pr77-review-main-613661d/index.json)
retains the source-bound failed and interrupted attempts separately.

The connected workflow explicitly selects **Show SMS graph** after navigation and
uses current technical-state badges and **Troubleshooting** evidence codes, node
IDs and source IDs. Cause assertions follow current summary/title semantics.
Unsupported customer counts are omitted by the UI; durable API/database checks
still require `uniqueSubscribers=null` and authorized source receipts.

Main's viewport-wide mobile drawers are checked against both viewport edges and
`innerWidth`, retaining workspace, scrolling and focus assertions. The controlled
geography interaction test pins its clock away from minute-boundary fallback.
Its exact read-count assertion remains; omitting the explicit refresh still fails.
The connected suite uses real clocks and real services throughout.

All three required connected cases passed with retries disabled: authentication,
roles, session/CSRF rotation, proxy/SSE reconnect and fresh geographic incidents;
real fifteen-minute idle expiry; and the original thirty-minute absolute deadline
despite trusted activity. Both expiry cases verify protected 401 responses, closed
SSE streams and a quiet period. Owned resources and credentials were cleaned up;
the scoped Windows keep-awake request was released.

Local verification recorded 164 full controlled browser passes with 21 existing
opt-in skips, 131 dashboard unit tests, 16 focused browser tests, production build
and seven runner-safety tests. The report distinguishes checks preceding the
controlled-test-only clock fix. Candidate CI independently passed all six gates,
including 131 dashboard unit and 164 browser tests.

The commit introducing the new report publishes evidence only. Exact publication
SHA, its subsequent six CI results and the final remote-main recheck are recorded
in the PR description after those checks complete. The evidence commit is not
claimed as directly runtime-tested; all source/configuration entries outside
docs/tasks must match the runtime candidate.

## Historical evidence and retained failures

The [prior-main acceptance snapshot](2026-10-09-pr77-main-0eb9b5b-acceptance.md)
retains its three passing connected cases on source
`1c889216dec184120373cfd52355f2b2e8fa4ac6`, integrating main `0eb9b5b`,
and its separately verified publication `7f6505a`. Those results retain their
original attribution. Its [29-artifact bundle](assets/pr77-review-remediation/index.json)
and the original [55-artifact G4 bundle](assets/sergiu-g4-pr77/index.json) are
unchanged; every sealed hash was rechecked.

Attempt r7 on earlier candidate `be05082` remains FAILED after Windows Modern
Standby interrupted absolute expiry across its deadline. Attempt r8 on the same
candidate was interrupted after geography/security passed to fix the controlled
minute-boundary race; idle/absolute completion is not inferred. Both runners
completed resource/credential cleanup. Their public records and diagnosis are
retained in the new bundle without publishing private diagnostics.

The [historical G4 report](2026-10-08-sergiu-g4-connected-acceptance.md) labels its
archived drivers historical/non-executable and links the
[supported isolated workflow](../runbooks/pr77-connected-verification.md).
The fresh focused compatibility run does not repeat every historical G4 replay,
ML-outage or release audit. Earlier incomplete shared-owner gates remain open.
