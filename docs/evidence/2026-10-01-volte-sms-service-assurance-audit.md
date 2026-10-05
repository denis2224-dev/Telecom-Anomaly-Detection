# VoLTE + SMS Service Assurance audit — 2026-10-01

> Historical audit. Its pending authenticated acceptance and PR blockers are
> superseded by [Gemini's fresh October 2 acceptance](2026-10-02-gemini-authenticated-acceptance.md)
> and [the final repository audit](2026-10-02-final-repository-audit.md).
> Live SSE updates passed; SSE intentional reconnect is **NOT DIRECTLY VERIFIED**.

Audit snapshot: 2026-10-01T18:42:46.9186+00:00. Technical evidence timestamps below are UTC.
Branch: `feature/volte-sms-service-assurance`; base `55e373a2a18eac56f9726c8f7b0ea307bde0a9ce`.

## 1. IMPLEMENTATION COMPLETED

Development and non-browser verification completed by continuing the recovered working tree.
The existing dashboard, canonical KPI cards, charts, dependency context, incident story,
history slicing, notification server/client and refresh lifecycle were preserved and audited.
This session fixed current health being incorrectly restricted to a historical range, made
the SMS history table use the shared scroll container, clarified VoLTE CSSR eligibility and
fixture history text, and added live lifecycle regressions. Authenticated live acceptance is pending.

## 2. FILES CHANGED

Feature source, contract, fixture, test and design files reviewed:

| File | Working tree change |
| --- | --- |
| apps/dashboard/angular.json | modified |
| apps/dashboard/api/incident-api.yaml | modified |
| apps/dashboard/playwright.config.ts | modified |
| apps/dashboard/src/app/app.component.html | modified |
| apps/dashboard/src/app/app.component.ts | modified |
| apps/dashboard/src/app/core/api/data-source.fixtures.ts | modified |
| apps/dashboard/src/app/core/api/data-source.ts | modified |
| apps/dashboard/src/app/core/api/live-updates.spec.ts | new |
| apps/dashboard/src/app/core/api/live-updates.ts | new |
| apps/dashboard/src/app/core/api/schema.ts | modified |
| apps/dashboard/src/app/core/api/telecom-client.ts | modified |
| apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts | modified |
| apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts | modified |
| apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts | modified |
| apps/dashboard/src/app/features/incident-investigation/incident-story.component.ts | new |
| apps/dashboard/src/app/features/service-kpi-history/assurance-model.spec.ts | new |
| apps/dashboard/src/app/features/service-kpi-history/assurance-model.ts | new |
| apps/dashboard/src/app/features/service-kpi-history/assurance-rendering.spec.ts | new |
| apps/dashboard/src/app/features/service-kpi-history/kpi-cards.component.ts | new |
| apps/dashboard/src/app/features/service-kpi-history/kpi-chart.component.ts | modified |
| apps/dashboard/src/app/features/service-kpi-history/metric-chart.component.ts | new |
| apps/dashboard/src/app/features/service-kpi-history/service-detail.component.html | new |
| apps/dashboard/src/app/features/service-kpi-history/service-detail.component.spec.ts | new |
| apps/dashboard/src/app/features/service-kpi-history/service-detail.component.ts | modified |
| apps/dashboard/src/app/features/service-kpi-history/service-path.component.ts | new |
| apps/dashboard/src/app/features/service-kpi-history/sms-history.component.ts | modified |
| apps/dashboard/src/app/features/service-overview/service-overview.component.html | modified |
| apps/dashboard/src/app/features/service-overview/service-overview.component.spec.ts | modified |
| apps/dashboard/src/app/features/service-overview/service-overview.component.ts | modified |
| apps/dashboard/src/app/features/service-overview/service.store.spec.ts | modified |
| apps/dashboard/src/app/features/service-overview/service.store.ts | modified |
| apps/dashboard/src/fixtures/services.json | modified |
| apps/dashboard/src/fixtures/sms.ts | new |
| apps/dashboard/src/styles.css | modified |
| apps/dashboard/tests/e2e/specs/assurance-live-updates.spec.ts | new |
| apps/dashboard/tests/e2e/specs/evidence-timeline.spec.ts | modified |
| apps/dashboard/tests/e2e/specs/login.spec.ts | modified |
| apps/dashboard/tests/e2e/specs/service-assurance.spec.ts | new |
| apps/dashboard/tests/e2e/specs/service-scenarios.spec.ts | modified |
| apps/dashboard/tests/e2e/specs/voice-investigation.spec.ts | modified |
| apps/dashboard/vitest.config.ts | new |
| docs/design/volte-sms-future-scenarios.md | new |
| docs/design/volte-sms-mentor-service-assurance.md | new |
| services/incident-service/src/main/java/md/utm/telecom/evidence/service/EvidenceService.java | modified |
| services/incident-service/src/main/java/md/utm/telecom/incidents/AuditService.java | modified |
| services/incident-service/src/main/java/md/utm/telecom/incidents/IncidentWorkflow.java | modified |
| services/incident-service/src/main/java/md/utm/telecom/incidents/live/IncidentStream.java | new |
| services/incident-service/src/main/java/md/utm/telecom/incidents/live/IncidentStreamController.java | new |
| services/incident-service/src/main/java/md/utm/telecom/incidents/live/StreamSessionConfiguration.java | new |
| services/incident-service/src/test/java/md/utm/telecom/evidence/EvidenceServiceTest.java | modified |
| services/incident-service/src/test/java/md/utm/telecom/incidents/live/IncidentStreamSecurityTest.java | new |
| services/incident-service/src/test/java/md/utm/telecom/incidents/live/IncidentStreamTest.java | new |

This evidence report is also new. Four generated branding files have only line-ending working-tree
differences; `git diff --ignore-space-at-eol` is empty for those files. They were preserved.
Existing `.agents/`, `.codex/`, `AGENTS.md`, `.telemetry*` files and `graphify-out/` are local
tooling/previous environment evidence, distinct from feature source. No existing artifacts were discarded.

## 3. LIVE AUTH RESULT

NOT VERIFIED for `ion-supervisor` and SUPERVISOR role. Browser inventory exposed no tabs/apps.
The Windows computer-use helper reported: native pipe unavailable, OS error 2.
No session material or credentials were retrieved. The host service was found stopped during
readiness verification, rebuilt from the existing feature source, and restored with its unchanged
configuration. An earlier in-memory backend session cannot be asserted to survive that outage.
Anonymous `/api/auth/me`, `/api/services` and `/api/incidents/stream` return 401 as required.

## 4. VOLTE LIVE RESULT

PASS for persisted backend telemetry, historical read model, fault/recovery evidence and null
subscriber impact. Historical processing and incident read models each contain 43,200 distinct
VoLTE minute windows in [2026-09-01T07:02Z, 2026-10-01T07:02Z).
Fault CSSR is 94% against stored baseline 99.3%, IMS CPU 97%, SIP 503 count 55.
The persisted hypothesis cites IMS capacity pressure, with MEDIUM confidence and recommended
checks; RRC/bearer setup and transport measurements remain supporting context.
Authenticated live REST/UI is NOT VERIFIED.

## 5. SMS LIVE RESULT

PASS for persisted backend telemetry, historical read model, fault/recovery evidence and null
subscriber impact. Both historical models contain 43,200 distinct SMS minute windows over the
same range. The live fault supplies P95 delay, delivery success, completed sample volume,
queue depth and oldest pending age. Stored P95 baseline is 2,000 ms. Persisted explanation:
SMSC backlog suggests a delivery bottleneck; verify downstream routing. Confidence: MEDIUM.
Impact supplies affected delivered messages/pending messages and `uniqueSubscribers: null`.
No SMS transport health was inferred. Authenticated live REST/UI is NOT VERIFIED.

## 6. SCENARIO / STATE TRANSITIONS

Supported private generator API used; no direct observation injection or database mutation.
This verifies generator publication and downstream processing, not the authenticated public
SUPERVISOR command API, its durable command ledger or the simulator UI.

| Type | Scope | Run ID | Start UTC | End UTC | Final status |
| --- | --- | --- | --- | --- | --- |
| VOLTE_IMS_OVERLOAD | VOLTE-MD-CENTRAL | 2846e5eb-bb92-4713-a78e-dff84315f960 | 2026-10-01T18:25:00Z | 2026-10-01T18:33:00Z | COMPLETED |
| SMS_QUEUE_DELAY | SMS-MD-ROUTE-A | 97cdab62-bf34-492c-84c2-daf3c9204c0e | 2026-10-01T18:25:00Z | 2026-10-01T18:33:00Z | COMPLETED |
| TELEMETRY_GAP | VOLTE-MD-CENTRAL | e6faf3bc-3f52-460f-b0d1-9645a9364c7d | 2026-10-01T18:33:00Z | 2026-10-01T18:41:00Z | COMPLETED |
| TELEMETRY_GAP | SMS-MD-ROUTE-A | 53590713-f43c-47ac-b0aa-e9cac40b3ecc | 2026-10-01T18:33:00Z | 2026-10-01T18:41:00Z | COMPLETED |

All four runs completed eight scheduled windows. Normal windows: 18:25–18:26; fault:
18:27–18:29; healthy recovery samples: 18:30–18:32. Persisted transitions:

| Service | Window start UTC | Phase | Sequence | Technical state |
| --- | --- | --- | --- | --- |
| SMS | 2026-10-01T18:28:00Z | OPEN | 1 | ONGOING |
| VOLTE | 2026-10-01T18:28:00Z | OPEN | 1 | ONGOING |
| SMS | 2026-10-01T18:29:00Z | UPDATE | 2 | ONGOING |
| VOLTE | 2026-10-01T18:29:00Z | UPDATE | 2 | ONGOING |
| VOLTE | 2026-10-01T18:30:00Z | UPDATE | 3 | ONGOING |
| SMS | 2026-10-01T18:30:00Z | UPDATE | 3 | ONGOING |
| VOLTE | 2026-10-01T18:31:00Z | UPDATE | 4 | ONGOING |
| SMS | 2026-10-01T18:31:00Z | UPDATE | 4 | ONGOING |
| SMS | 2026-10-01T18:32:00Z | RECOVERY | 5 | RECOVERED |
| VOLTE | 2026-10-01T18:32:00Z | RECOVERY | 5 | RECOVERED |

The first and second healthy minutes emit UPDATE/ONGOING; only the third emits RECOVERY.
Analyst workflow remains separate. The gap scenarios withhold all sources at 18:35–18:37:
six MISSING windows, zero receipts and all observed KPI values null. They represent UNKNOWN
service health, not measured zero or healthy data. They do not create a fabricated anomaly
episode after the preceding episode recovered. Normal data resumes during 18:38–18:40;
continuous baseline resumes at 18:41 with three VoLTE and two SMS receipts.
Observed interval: 38 processor windows and 38 matching canonical
read-model windows, identical IDs/KPIs, zero duplicate natural keys and zero rejections.

## 7. REST RESULT

PASS: anonymous protection, Angular shell HTTP 200, OIDC discovery HTTP 200, CSRF discovery
HTTP 200 (response body never inspected), readiness and auth routing.
PASS in integration/controlled tests: canonical serialization, pagination, fixed historical ranges,
rolling tail refresh and stale-request cleanup. Backend 24h/100-row request limits remain unchanged;
48h UI history uses two legal slices. Authenticated live REST refresh is NOT VERIFIED.

## 8. SSE RESULT

PASS in backend tests: session authentication/deadline, transaction-after-commit notifications,
rollback silence, expiry/invalidation/logout cleanup and connection limits.
PASS in unit/controlled browser tests: real browser EventSource consumption of controlled frames,
REST reload after notification/reconnect, event coalescing, one poller, stale callback rejection
and session expiry cleanup. NGINX buffering is disabled and heartbeat keeps its stream active.
Authenticated live event delivery/reconnect is NOT VERIFIED.

## 9. TEST RESULTS

| Check | Result |
| --- | --- |
| Angular unit tests | 72 passed, 17 files |
| Incident-service Maven verify | 114 tests + 11 integration tests, zero failures/errors/skips |
| Focused generator/processor tests | 69 passed, zero failures/errors/skips |
| Controlled browser suite | 29 passed; 1 credential-based real-login test skipped |
| Production Angular build | PASS |
| Contract validation | PASS: observation, detection, explanation and voice/SMS parity fixtures |
| ./scripts/verify and auth routing | PASS after restoring host service |
| scripts/up syntax/interpreter discovery | PASS: usable Python 3 selected as `python` |
| git diff --check | PASS |
| Graphify | AST graph refreshed using installed Python module; missing SQL parser warning remains |

Full raw local logs remain outside the repository under `C:/OrangeSystems/Program/assurance-audit-*.log`.
Only non-sensitive test summaries were used for reporting. Selected runtime evidence is in
`C:/OrangeSystems/Program/assurance-audit-evidence/`.

## 10. UI / BROWSER VERIFICATION

BROWSER LIVE VERIFICATION: NOT VERIFIED.
Controlled desktop/mobile coverage includes VoLTE/SMS normal, degraded, recovery and missing-source
states, exact evidence/cause/confidence/checks, null subscriber disclosure, 48h slicing, scrolling,
incident detail, login expiry and SSE lifecycle. Desktop and mobile screenshots were visually
inspected. These images use explicitly controlled data and do not prove the user's live session.

## 11. scripts/up REVIEW

Classification B: separate infrastructure commit/PR. The fix validates a runnable Python 3
interpreter before use, skips the broken Store alias, considers `python3`, `python`, `py` and
local virtual environments, supports an explicit interpreter path and invokes the existing
Keycloak preparation script through the selected interpreter. This addresses environment startup,
not Service Assurance behavior. Diff reviewed; syntax and actual discovery pass. Preserved unchanged,
unstaged and uncommitted. `./scripts/up` was not rerun because it stops services/bootstrap jobs.

## 12. GIT STATUS

Still on the requested feature branch. All feature modifications and new files remain uncommitted;
index is empty. No commits or pushes performed. The existing infrastructure fix, local tooling,
prior telemetry evidence and line-ending-only branding changes remain in the working tree.

## 13. REMAINING ISSUES

Authenticated live identity/SUPERVISOR role, actual dashboard and incident detail, public simulator
triggering, authenticated REST refresh and live SSE delivery/reconnect still require an attachable
human-authenticated browser. No authentication changes or credential workaround was made.
Graphify's missing optional SQL parser affects repository indexing, not application behavior.
PR preparation must stage only feature files and leave infrastructure/tooling artifacts separate.

## 14. READY FOR PR? NO

Implementation and non-browser checks are complete; the requested authenticated live quality gate
is still unverified. No commit/push is authorized or performed in this audit.
