# PR 112 merge verification

The merge follow-up changes the keyboard tests and CI coverage; application behavior,
release manifests, recorded evidence pins and delivery settings are unchanged.

## Keyboard verification

The two macOS WebKit failures reproduce when plain Tab skips links and buttons
with full keyboard access disabled. A minimal native page reproduces the same
behavior. The accessibility suite now uses Option+Tab on macOS WebKit and plain
Tab on other platforms and browsers. It still requires native sequential focus,
visible focus outlines, keyboard activation, modal isolation and focus return;
it does not substitute programmatic focus or skip assertions.

The first expanded Linux CI run passed 68 of 69 focused checks. Firefox exposed
another test-path assumption: after skipping into the workspace, forward traversal
searched for a sidebar link that precedes the workspace. The test now returns with
Shift+Tab rather than traversing every chart point until the document wraps.
The final dashboard check must pass all 69 cases before this PR is merged.

All 23 focused WebKit checks pass locally after the correction. The complete
controlled Chromium suite passes 187 checks with 25 environment-dependent skips. Dashboard unit
checks pass (184), runner safety checks pass (43), and production build, branding,
fixture validation and unchanged API generation pass. The existing 501.42 kB
initial bundle warning remains.

Local Firefox cannot launch its temporary profile, including against a minimal
page, so local Firefox execution is not counted as passing. The dashboard GitHub
check now installs all three engines and runs the accessibility, persisted-history,
native-workflow and archive/export acceptance suites across Chromium, Firefox and
WebKit, in addition to the complete controlled Chromium suite. Require this check
and all other PR checks to pass on the final PR revision before merging.

The follow-up also integrates main `74913e5b` into the PR. Both branches added
`docs/runbooks/detection-and-models.md`; the resolution preserves the PR's general
detection, scenario and missing-window guidance together with main's complete
Day 5 platform-specific verification and independent-reproduction procedures.

## Release acceptance

Merging the reviewed implementation does not sign release acceptance. Timed real
session acceptance, human screen-reader and guide review, coordinated capacity,
new-domain delivery activation, receiving-machine installation, full rehearsals
and owner sign-offs retain their pending status. Browser push remains disabled.
Historical results continue to describe only their recorded source revisions.
