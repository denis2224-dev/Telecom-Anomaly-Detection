# PR #77 review remediation - current status (2026-10-09)

**Latest-main combined acceptance: PENDING.** The completed focused acceptance
against main `0eb9b5b417b37343e86038cdd84243ce68e17cbe` remains valid for its
recorded source. Main advanced during publication CI to the dashboard traffic/incident
layout merge and is now `613661dd87b2285ba56c6b9df4c0b181dde9e2bf`, including
viewport-wide mobile drawers. Its combined behavior requires fresh verification before current-main
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

1. Main `613661dd87b2285ba56c6b9df4c0b181dde9e2bf` is integrated by merge
   `d638a71` without rewriting PR history. The prior dashboard integration
   `480592804b79b8a4a505a8f5ce6fa49ff22a6099` is retained.
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
   `docs/evidence/assets/pr77-review-main-613661d/`, preserving both prior
   sealed bundles. Record the evidence-publication SHA and subsequent six CI results
   in the PR description; do not claim that SHA was directly runtime-tested.

Current pending technical fields are the frozen runtime source SHA, rebuilt runtime,
three-case acceptance, cleanup results and candidate/publication CI. Populate them
from actual completed artifacts. Recheck remote main before reporting ready for
re-review. The prior r1-r5 failures and interruptions remain documented in the
accepted snapshot and sealed failed-attempt records.

## Current UI adaptations and local verification

Commit `95e99fc` explicitly selects **Show SMS graph** after navigation, then
asserts current queue state and **Troubleshooting** evidence codes, node IDs and
source event IDs. Cause details follow the current summary/title presentation;
unsupported paths/classification remain unavailable, and unsupported customer
counts are omitted. The durable API/database assertions still require
`uniqueSubscribers=null` and authoritative source receipts for every detection.

Main's `100vw` mobile drawer replaces the earlier `100%` sizing. The local
`8968972` body-width assertion reproduced two failures (375px expected versus
390px actual) after integration. The updated test requires viewport width and
both viewport edges, while retaining workspace, scrolling and focus checks.
All 16 focused service-hero/evidence-timeline browser tests, 131 dashboard unit
tests, the production build and seven isolated-runner safety tests pass locally.
All 29 prior focused and 55 original historical artifact hashes match their seals.
These local checks do not substitute for the pending three-case connected run.

## Current failed and interrupted attempts

Attempt r7 (`be05082`) passed geography/security and real idle expiry, then timed
out in absolute expiry after Windows Modern Standby interrupted execution from
10:16 to 10:47 UTC. The attempt remains FAILED. Owned-resource and credential
cleanup passed. A scoped system/display keep-awake request is used for retries;
no permanent power settings were changed.

Attempt r8 (same source) passed geography/security before its owned browser child
was interrupted to address an inherited controlled-test timing race. Its runner
completed resource/credential cleanup and released the temporary keep-awake request.
Idle/absolute acceptance was incomplete and is not inferred.

The concurrent full controlled suite recorded 163 passes, one failure and 21 existing
opt-in skips. A catalogue interaction test expected exactly two reads but correctly
received a third from minute-boundary fallback. Only that controlled interaction
test now pins Date.now away from the boundary; all exact assertions remain. The
three viewport cases pass. Omitting the explicit refresh in an isolated mutation
still fails (two reads expected, one received). The real connected clocks and
expiry assertions are unchanged. A complete controlled-suite rerun and fresh
committed-candidate three-case acceptance are required before publication.

The complete controlled rerun passed **164** tests with **21 existing opt-in
skips** and no failures. The required real connected suite remains separate,
with all three cases mandatory and retries disabled.
