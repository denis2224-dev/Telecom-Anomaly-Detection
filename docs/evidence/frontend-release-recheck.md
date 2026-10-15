# Frontend release verification attempt

Status: **partial local checks passed; connected rehearsal acceptance blocked**. No owner signature or certification is supplied.

## Requirements covered

The personal assignment's final rehearsal tasks 1–6 require local test gates, two complete demonstrations on one release identity, domain/context investigation, alerts and workflow, exact export/Grafana context, graceful browser-alert fallback, and published evidence. Its earlier browser/session section adds actual idle and absolute expiry, accessibility, reconnect and outage verification. The common contract requires a pinned full archive and one manifest for both repetitions, with separate human and receiving-machine acceptance. Existing historical results cannot certify the current running candidate.

## Current local checks

The verification checkout is `feature/frontend-acceptance` at `96a7bfb7650e04b462a75d9c493b062b6b69155f`. The main checkout is separately identified in the artifact summary. No candidate deployment or merge occurred. Later report wording changes do not alter application behavior.

- Branding: passed.
- Unit tests: 184 passed across 35 files.
- Production and fixture builds: passed after retry outside sandbox process restrictions. Production retains the 501.42 kB initial-bundle warning against a 500 kB budget.
- Four fixture examples validate. Canonical API copy matches. API regeneration leaves generated types unchanged.
- Controlled Chromium/Firefox/WebKit matrix: 102 passed, zero skipped or failed. This includes keyboard/focus, desktop/phone, context/export, reconnect, notification fallback, inventory 503 and native measurement error states; unexpected page exceptions fail the suite.
- Production-build browser performance: five passed; see the actual retained log and resource reports; this measures controlled browser behavior, not connected pipeline capacity.
- Required live configuration: nonzero prerequisite rejection because existing-account credentials are missing. This is not a successful authenticated run.

The first sandboxed build processes terminated before completion and the initial browser server could not bind its local port. The permitted retries above passed. Those environment failures are not represented as product defects.

## Connected prerequisites rechecked

The eight existing services were stopped. Restarting their existing containers restored healthy services without rebuilding images, importing records, or resetting storage. Read-only SQL then returned zero rows in `app.trace_reference_import`, `app.trace_reference_window` and `app.trace_metric_window`; `app.trace_dataset` returned no records. The earlier assertion that the populated archive is available does not match this endpoint's current database. If another populated stack is intended, its exact origin is required.

Neither process configuration nor the two known private environment files provides `SESSION_USERNAME` and `SESSION_PASSWORD`. Safari displays the expired-session login view. Configure an existing enabled account privately; never put its password in evidence or a message.

Automatic approval review rejected the direct release-test launch because the main checkout's test creates temporary identities and edits analyst records. The acceptance branch's separate test uses existing credentials and contains no provisioning/database writes, but the required live preflight still fails without those credentials. No temporary identities were created by this attempt.

The running incident, processor and generator image source labels are `unrecorded`. The served frontend index hash and image IDs are captured separately from candidate production file hashes. A common pinned frontend/backend runtime identity has not been established. Matching a local build hash alone does not establish deployment identity.

## Rehearsal records

Rehearsal 1: **blocked before start**. Rehearsal 2: **blocked before start**. No populated screenshots, scenario run IDs, incident IDs, badge increments, real stream updates or connected CSV comparisons were fabricated. The automated repeated-history case is also not a substitute for either complete demonstration.

Once the prerequisites are satisfied, use the existing-account release case with real login enabled, then perform the entire demo script twice without changing the manifest, viewport, account, dataset or seed. Record UTC start/end, manifest/source/schema/bundle hashes, browser version, actual run and incident IDs, before/after unread counts, one-tab stream behavior and missed-stream REST reconciliation. Compare 24h at 5m versus 1h (288 versus 24 aligned buckets), drill into `chi-hin:a-to-b`, inspect roaming and LTE activation/DNS independently, and compare the downloaded server CSV against the visible query's exact bounds and primitives. Capture the configured Grafana absolute-time variables and actual results. Demonstrate analyst workflow and recovery/resolution separately using the authorized supported scenario. Preserve push disabled until secure-origin acceptance or an explicitly accepted flag-off release decision.

Human guide-only/screen-reader review, receiving-machine offline acceptance and owner reviews must be performed by the appropriate people; this record does not sign on their behalf. Actual timed session checks and coordinated capacity remain distinct from controlled browser passes.

## Artifacts

[Summary, candidate bundle file hashes and runtime identity](assets/frontend-recheck/summary.json), [artifact checksums](assets/frontend-recheck/artifact-index.json), [browser versions](assets/frontend-recheck/browsers.json), [desktop controlled screenshot](assets/frontend-recheck/controlled-history-1366.png), [phone controlled screenshot](assets/frontend-recheck/controlled-history-390.png). The screenshots use explicit controlled responses and are not connected archive evidence. Compressed logs preserve the actual checks and prerequisite failure.

Complete release gates: **NOT PASSED**.
