# Trace Link implementation and verification

The frontend and supporting backend are integrated with GitHub main `82f69d8d34afbadc4a43e70814af170b5ebdfac7`. The verified runtime source is `280bdacf41d9db9cf69c8bfa4085940ece288bc6` on `feature/trace-link-completion`. Later evidence-only commits do not change runtime source. The branch is published; main, shared databases, volumes and delivery settings were not changed.

## Completed implementation

- Ten approved cities and twelve links drive active geography and typed historical inventory. Retired locations remain historical evidence. Reverse directions are declared independently; absent measurements remain gaps.
- The supplied full archive validates against the pinned manifest and imports 362,880 immutable native rows into separate processor-owned and incident-owned storage. Exact retries preserve source identities, hashes and completion. Authenticated history selects recorded inventory without activating a live catalogue.
- Registered metrics cover the five domains and four resolutions using primitive arithmetic, pooled compatible histograms and retained snapshots. Missing, zero-volume, partial, source-quality-unknown and stale states stay separate. Reference DNS eligibility is unavailable and is displayed/exported explicitly; activation counters cannot establish DNS eligibility.
- Shared scope/time connects topology, domain panels, incident filtering, expanded charts, exact server CSV and Grafana links. Period/resolution remain independent. Async dataset, country and partner selectors now restore their actual URL context. Query/session generations reject stale responses.
- Native technical identity, analyst assignment/comments/resolution, private transactional notification history, one tab EventSource, REST reconciliation and bounded cross-tab presentation remain intact. Upstream enabled-identity enforcement, streaming export and capacity-interval support are retained.
- Protected browser-push APIs and bounded delivery remain implemented but disabled. No secure-origin delivery or human approval is asserted.

Published upstream migrations retain their exact bytes. Unpublished extensions were placed after the integrated history as V014–V018. The partial opening queue bucket remains unavailable when its full-bucket opening is outside the selected interval; SQL and backend now agree instead of exporting an invented zero.

## Actual verification

See [the current evidence index](assets/trace-link-published/artifact-index.json) for results and checksums. Modes are explicit:

- Full source: seven files, 362,880 native rows, exact processor import/retry, exact incident-owned import/retry, 288/2,016/8,640 bucket cardinalities, 41 dictionary metrics at four resolutions and 16,933 backend/SQL bucket comparisons at tolerance `1e-6`.
- Full archive dump/restore: actual custom PostgreSQL archive restored into a new isolated database; identities, hashes, completion, Flyway validation and runtime immutability pass.
- Reference pack: 708,498 engine checks and 23 DOM-shim control checks pass. The latter are not browser rendering/accessibility acceptance.
- Actual Grafana/PostgreSQL: fourteen panels, twenty targets, four variables, focused/all contexts and 3,500 non-null cells compared with restricted SQL on the full archive.
- Dashboard: 184 unit checks; 186 complete controlled Chromium checks before the final selector correction, with 25 environment-dependent skips; eleven focused history checks after that correction. The selector defects have retained failing-before evidence. Build, generated API, branding and fixtures pass; the existing initial bundle warning is retained.
- Connected production build: real OIDC/roles/CSRF, full persisted history and exact CSV on desktop/phone, restored roaming selections, proxy/SSE reconciliation, logout/private cleanup, and four concurrent fresh fault/control/gap runs pass. Two HIGH incidents recover; normal/gap controls do not create false incidents. Ten immutable detections match API/database/source receipts. Recovery leaves analyst workflow open. All four browser cases pass; owned stack and credentials are removed afterward.
- Root Python/contracts: 191 checks pass with one platform-runtime skip. Isolated-runner safety: 43 checks pass. Backend suites include preserved foundation/main upgrade histories, private recipients, leases, immutable evidence and full-source checks; exact final counts are in the verification JSON.
- A local offline candidate was assembled from the tested source/images, built frontend, complete archive and public connected evidence, then checksum-verified. Target installation and receiving-person acceptance remain pending.

The original artifacts remain unchanged under `assets/trace-link-completion` and `assets/trace-link-followup`; their results describe their original pins. The initial strict Linux model byte-rebuild failure remains disclosed there. Unchanged model source passed upstream Windows reproduction; that is not a claim about feature-branch CI.

## Remaining acceptance

Complete release gates are **NOT PASSED**. Remaining work is real secure-origin push acceptance or an accepted flag-off release decision; timed session/browser/assistive-technology and guide-only reviews; connected new-domain scenario activation; coordinated capacity; identity-secret restore and actual offline target installation; two complete populated rehearsals on one identity; and five real owner reviews. No signatures or complete rehearsals are fabricated.

The fresh lifecycle passes now. Proving the cause of the older `2026-10-13T18:26:00Z`–`18:34:00Z` missing-window failure still requires retained logs or a database snapshot; the current pass does not identify its historical cause.

Use the [assignment record](trace-link-assignment-status.md), [frontend acceptance](final-ui-signoff.md), [release manifest](../release/trace-link-release-manifest.json), [analyst guide](../user-guide/trace-link-analyst-guide.md) and [operations procedure](../runbooks/trace-link-completion.md).
