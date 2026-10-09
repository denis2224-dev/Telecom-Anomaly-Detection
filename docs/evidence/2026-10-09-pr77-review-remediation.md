# PR #77 review remediation - current status (2026-10-09)

**Latest-main combined acceptance: PENDING.** The completed focused acceptance
against main `0eb9b5b417b37343e86038cdd84243ce68e17cbe` remains valid for its
recorded source. Main advanced during publication CI to
`957876a87f2414d22977f59d8cf2ddd2002b1542` (dashboard traffic/incident layout
merge). Its combined behavior requires fresh verification before current-main
compatibility can be claimed. Human PR approval remains **PENDING** and G5 release
approval remains **BLOCKED** until shared final-candidate gates and owner sign-offs pass.

## Completed baseline retained

The [prior-main acceptance snapshot](2026-10-09-pr77-main-0eb9b5b-acceptance.md)
records three passing connected cases on source
`1c889216dec184120373cfd52355f2b2e8fa4ac6`, successful owned cleanup/credential
removal, nine masked captures, ten immutable detections and 25 source receipts.
All six CI jobs also passed for its evidence-only publication
`7f6505a7084db456bc1faa0cb71eb43e9fd6cc05`; actual checkout and job URLs are in
that snapshot. These are prior-main results, not acceptance of the new merge.

Its [29-artifact bundle](assets/pr77-review-remediation/index.json) remains sealed
and unchanged. The original [55-artifact historical G4 bundle](assets/sergiu-g4-pr77/index.json)
also remains unchanged. Neither bundle is relabelled or overwritten.

The archived-driver P2 is addressed: the [historical report](2026-10-08-sergiu-g4-connected-acceptance.md)
labels its drivers historical/non-executable from committed paths and links the
[supported isolated workflow](../runbooks/pr77-connected-verification.md).

## Latest-main verification required

The new merge changes incident-stream state, incident list/detail/evidence,
connected overview, login presentation, service dashboards and controlled browser
coverage (139 files, 6,543 insertions and 284 deletions against the accepted main).
It changes production UI paths exercised by the requested gates; previous passes
cannot establish compatibility by source equivalence.

1. Main `957876a87f2414d22977f59d8cf2ddd2002b1542` is integrated by merge
   `480592804b79b8a4a505a8f5ce6fa49ff22a6099` without rewriting PR history.
   Review overlapping behavior and verify the combined candidate.
2. Adapt the supported browser workflow to real current UI behavior where needed,
   retaining all assertions for authentication/roles, session/CSRF rotation,
   protected 401, fifteen-minute idle expiry and original thirty-minute absolute expiry.
3. Rebuild isolated production images/assets and execute all three required cases
   with retries disabled, including real SSE/proxy reconnect, retained UI state,
   fresh geographic incidents, controls and masked desktop/mobile captures.
4. Record new source/main/image/asset identities, actual timing, scenario/incident
   receipts, guarded cleanup and six required CI checks. Any failed attempt remains
   a separate source-bound record.
5. Publish the new compact bundle under
   `docs/evidence/assets/pr77-review-main-957876a/`, preserving both prior
   sealed bundles. Record the evidence-publication SHA and subsequent six CI results
   in the PR description; do not claim that SHA was directly runtime-tested.

Current pending technical fields are the frozen runtime source SHA, rebuilt runtime,
three-case acceptance, cleanup results and candidate/publication CI. Populate them
from actual completed artifacts. Recheck remote main before reporting ready for
re-review. The prior r1-r5 failures and interruptions remain documented in the
accepted snapshot and sealed failed-attempt records.
