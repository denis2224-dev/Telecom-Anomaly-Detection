# PR #77 source-provenance correction (2026-10-09)

**Focused technical acceptance: PASSED** for source `fed26a9d529aa5bdfd1810c4c49c902db41f6525`. Human PR approval is **PENDING**; shared G5 release authorization remains **BLOCKED** pending its separate gates and owner sign-offs. All times below are UTC.

## Correction and safety verification

The runner previously excluded untracked files from its source checks even though dashboard and Docker builds consumed the filesystem. The shared integrity guard now rejects tracked modifications and every nonignored untracked file, including unrelated files. It also rejects ignored inputs except the narrowly approved dependency/build-output directories. Git ignore rules alone are not evidence that Docker excludes an input. Checks run before preparation, after builds before startup, and after connected acceptance; the recorded HEAD must remain unchanged.

Approved ignored directories are dashboard `node_modules`, `dist`, `.angular`, `coverage`, `test-results` and `playwright-report`; root `.venv` and `target`; and `target` under incident-service, event-generator, processor and streaming-support. Prefix lookalikes and misplaced directories are rejected. Private/public output paths must resolve outside the checkout; realpath checks also reject junction or symlink aliases into it.

All **42 Windows runner-safety regressions passed with zero skips**, including an actual contaminated CLI candidate rejected before setup, repository/local/global ignored inputs, staged/unstaged/deleted source, leading-whitespace/Unicode filenames, directory boundaries and HEAD changes. The regression was recorded failing before implementation. The same safety suite passed in Linux's automatic `dashboard-verification` CI step. Full connected CI remains optional/manual; the supported pre-merge CLI mechanism remains documented in the [runbook](../runbooks/pr77-connected-verification.md).

## Source and runtime provenance

| Field | Value |
| --- | --- |
| Runtime-tested candidate | `fed26a9d529aa5bdfd1810c4c49c902db41f6525` |
| Integrated main | `652726fc0748bce0fa1ff5c74101e4168bc2fff3` |
| Actual CI synthetic PR checkout | `d03a46e2cbd36009f9ae4da517fdd1dbfd82d691` |
| Equal candidate/synthetic Git tree | `d4d66df7e7e0647c38bceb8a9b06207136a2cf69` |
| Owned project | `pr77-20261009-provenance-r1` |
| Runtime start / finish UTC | 2026-10-09T19:56:38.311Z / 2026-10-09T20:57:28.429Z |
| Browser start / finish UTC | 2026-10-09T20:00:48.210Z / 2026-10-09T20:57:14.848Z |

[Runtime provenance](assets/pr77-provenance-fed26a9/runtime.json) records image IDs, their checked source revisions, dashboard asset hashes and generated configuration hashes. [Checkout equivalence](assets/pr77-provenance-fed26a9/ci-checkout-equivalence.json) identifies the actual synthetic CI checkout separately from the branch candidate. [Source objects](assets/pr77-provenance-fed26a9/runtime-source-objects.json) constrain a later evidence-only publication: source, configuration and verification objects outside docs/tasks must remain identical.

## Fresh connected acceptance

All three real-service cases passed serially, with retries disabled and native clocks/authentication:

| Case | Outcome | Measured duration |
| --- | --- | --- |
| integrated production roles, real SSE reconnect and fresh geographic incidents | PASSED | 620.360 s |
| open inactive dashboard expires at real fifteen-minute idle deadline | PASSED | 931.289 s |
| trusted analyst activity cannot extend real thirty-minute absolute deadline | PASSED | 1831.880 s |

[Geography/security assertions](assets/pr77-provenance-fed26a9/geographic-security-results.json) cover real OIDC roles, session/CSRF behavior, owned proxy outage/native SSE reconnect, four city/service scenarios, durable source trace and masked desktop/mobile presentation. The geographic case started at 2026-10-09T20:00:51.007Z.

[Idle expiry](assets/pr77-provenance-fed26a9/idle-expiry-results.json) retained 117 ordinary background requests and 1 active stream(s) before the deadline. Last trusted input was 2026-10-09T20:11:12.557Z; expiry was observed at 2026-10-09T20:26:17.597Z, after 905.038 s.

[Absolute expiry](assets/pr77-provenance-fed26a9/absolute-expiry-results.json) retained the original backend deadline 2026-10-09T20:56:44.426665103Z despite 30 trusted-activity observations; expiry was observed at 2026-10-09T20:56:49.445Z, after 1804.826 s. Both expiry cases ended with zero active streams, at least a twenty-second quiet period and protected HTTP statuses 401/401/401.

Owned-resource cleanup and guarded credential removal both **PASSED**. The [browser reporter](assets/pr77-provenance-fed26a9/verification.json) and runtime reporter bind acceptance to the same candidate. This fresh provenance run supplements earlier cause-control, ML-fallback and replay evidence; it does not reattribute historical executions.

## Candidate CI and evidence publication

All six required candidate jobs passed; each actual checkout was the synthetic revision above:

| Required job | Result |
| --- | --- |
| backend-verification | [PASSED](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37983397315/job/113999253208) |
| dashboard-verification | [PASSED](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37983397293/job/113999253199) |
| deployment-config | [PASSED](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37983397307/job/113999254051) |
| model-evaluation | [PASSED](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37983397307/job/113999254402) |
| processor-evaluation | [PASSED](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37983397307/job/114001250638) |
| build | [PASSED](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37983397328/job/113999253017) |

[Candidate CI](assets/pr77-provenance-fed26a9/candidate-ci.json) records run/job URLs, branch/base SHAs and actual checkout attribution. [Local verification](assets/pr77-provenance-fed26a9/local-verification.json) records the Windows safety results and prerequisites.

The [new bundle index](assets/pr77-provenance-fed26a9/index.json) seals every included artifact by its exact bytes, including the bundle's `.gitattributes`. Only the masked SMS and VoLTE mobile detail captures are included; all nine capture hashes are in [capture-hashes.json](assets/pr77-provenance-fed26a9/capture-hashes.json). The remaining seven captures and bulky browser output are retained locally with the raw public run output, outside the committed bundle. Private credentials and diagnostics are not published.

All **115 sealed historical artifacts** across the original G4, prior-main remediation and latest-main acceptance bundles were rehashed and remain unchanged; see [historical seals](assets/pr77-provenance-fed26a9/historical-seals.json). Existing failed/interrupted attempts retain their original outcomes.

The commit publishing this report is a later **evidence-only publication**, not a directly runtime-tested revision. Its exact SHA and subsequent CI results must be recorded separately in the PR after publication; application/configuration/verification changes require a new candidate and affected acceptance reruns. Passing PR-level technical acceptance does not approve or merge PR #77 and does not authorize shared G5 release.
