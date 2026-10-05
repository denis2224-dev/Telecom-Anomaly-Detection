# PR #43 reconciliation with current main — 2026-10-05

This records reconciliation verification, separate from the original PR #43 acceptance.
Original head: `8e456f8035308057514929e8839508a81a611f0e`.
Fetched main: `c539b67a40ac776c693e9301fac91b035e4037bc`.
Merge base: `55e373a2a18eac56f9726c8f7b0ea307bde0a9ce`.
Initial divergence: PR branch 5 ahead / 43 behind main. GitHub reported OPEN / CONFLICTING.

The reconciliation used `C:/OrangeSystems/Program/pr43-reconcile`, branch
`reconcile/pr43-main`, because the existing feature checkout was dirty.
The original checkout, its untracked files, other worktrees, and existing stash were preserved.
Backup branch: `backup/pr43-before-main-reconciliation-20261005`.
A normal merge of `origin/main` preserves the five original PR commits and their authorship.
There is no rebase, squash, force push, or PR merge.

## Accepted main work

- Session-bound incident hints and live investigation (`cdac43e`), including explicit
  stream completion at logout (`b376975`), verified enabled analysts (`f827c75`),
  and committed-change publication (`b2b962e`).
- Bounded/cancellable history from PR #46 (`3ef5191` / `23f24c1`), including 24-hour
  ranges, 1,440 windows, 15 KPI pages, 20 incidents/evidence updates per page,
  and 50 exact-value table rows per rendered page.
- Dark operations shell (`c539b67`), including sidebar, responsive navigation,
  range toolbar, chart interaction, compact incident summary, and workflow drawer.
- Serialized episode replay/order reconciliation (`b05e7fc` / `a3c5735`), idle and
  absolute session deadlines, CSRF rotation, workflow/comment permissions,
  and the optional Compose incident-service runtime.

## Conflict decisions

All 14 conflicts were reviewed against the merge-base changes, both branch versions,
current call sites, and tests. Main is authoritative for infrastructure and lifecycle.

| File | Decision | Reason and resulting behavior |
|---|---|---|
| `apps/dashboard/playwright.config.ts` | KEEP MAIN | Preserve current separated live tests; real session tests retain explicit skip guards. |
| `apps/dashboard/src/app/app.component.html` | KEEP MAIN | Preserve dark shell, sidebar, toast, and authentication screen lifecycle. |
| `apps/dashboard/src/app/app.component.ts` | KEEP MAIN | Drop global competing live client; route components own the accepted stream. |
| `apps/dashboard/src/app/core/api/telecom-client.ts` | COMBINE | Main AbortSignal/API behavior plus SMS fixtures with bounded pagination. |
| `apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts` | COMBINE | Main evidence layout and pagination; retained phase attributes and aggregate-subscriber caveat. |
| `apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts` | ADAPT | Main workflow drawer, REST reconciliation, audit timeline, cancellation; clarify SMS fixture history. |
| `apps/dashboard/src/app/features/service-kpi-history/kpi-chart.component.ts` | ADAPT | Main bounded table, gap paths, baseline chart and keyboard navigation; retain eligible-attempt wording. |
| `apps/dashboard/src/app/features/service-kpi-history/service-detail.component.ts` | ADAPT | Keep main history loader and incident pages; add canonical cards, dependencies and supporting baseline charts. |
| `apps/dashboard/src/app/features/service-overview/service-overview.component.html` | ADAPT | Dark rows and statistics plus persisted episode health, baseline delta, and prior-minute comparison. |
| `apps/dashboard/src/styles.css` | ADAPT | Main imports/theme/layout; only retained feature styles appended. |
| `apps/dashboard/tests/e2e/specs/voice-investigation.spec.ts` | ADAPT | Current bounded mocks and stronger scoped chart/evidence assertions match retained content. |
| `services/incident-service/src/main/java/md/utm/telecom/evidence/service/EvidenceService.java` | KEEP MAIN | Episode locks, sequence-gap reconciliation, flush/version handling and committed hints supersede old hook. |
| `services/incident-service/src/main/java/md/utm/telecom/incidents/AuditService.java` | KEEP MAIN | Current actor checks, comment permissions and committed publication remain exact main. |
| `services/incident-service/src/main/java/md/utm/telecom/incidents/IncidentWorkflow.java` | KEEP MAIN | Preserve optimistic version checks, transactional audit and analyst/technical separation. |

## Additional semantic reconciliation

- Removed PR-only `incidents/live/IncidentStream`, `IncidentStreamController`, and
  `StreamSessionConfiguration`, their superseded tests/mock, and frontend
  `core/api/live-updates.ts` and its tests. No duplicate endpoint registration remains.
- The single contract is `GET /api/incidents/stream`, events `ready` and
  `incident-upsert` (id/version hints), with keepalive comments. The main registry,
  after-commit listener, controller, session listener, security configuration,
  deadline filter and frontend stream client remain main's implementation.
- Native EventSource reconnect/open and `ready` request authoritative REST state.
  Errors probe `/api/auth/me`; 401 expires the session. Route teardown/session end
  closes streams, cancels probes/HTTP reads and clears owned refresh timers.
- Overview and service detail each own one stream while mounted. The overview has
  one 30-second current-data timer and coalesces pending hints. Service detail
  updates the current summary and current incident page without replacing a selected
  history range or refetching all history on each notification.
- The older 48-hour sliced loader, full detection cache, and obsolete detail template
  were dropped. Current KPI bounds/cancellation/stale-response checks remain main's.
  Retained metric-chart exact tables now page at 50 rows too.
- Incident impact, confidence, recommended checks and source context appear inside
  the existing episode's expandable evidence. The investigation drawer and timeline
  remain the current main experience; there is no second top-level investigation layout.
- Metric phase bands use the latest detection on the current incident page. Earlier
  phases and other pages are explicitly outside that context; absence is UNKNOWN,
  not assumed NORMAL. A partial incident page cannot claim NORMAL current health.
- Missing/zero-sample metrics remain unavailable, measured zero stays distinct,
  chart paths break at gaps, and SMS p95 is used per window without averaging.
- The dashboard OpenAPI/type addition exposes the already-existing firstObservedAt
  episode anchor; it does not change episode or observation identity.
- The PR generator fix remains useful: loopback-only `127.0.0.1` publication for
  optional host-run incident-service. The Compose app runtime continues using
  `http://event-generator:8081` privately. Documentation no longer claims the
  incident service cannot run in Compose.

## Verification

Commands ran on the reconciled tree. Maven used the cached Maven 3.9.16 executable:

```powershell
$maven = 'C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'
```

| Result | Command / working directory | Exact result |
|---|---|---|
| PASS | `git fetch --all --prune` / original repository | Current remote refs fetched. |
| PASS | `npm.cmd ci --no-audit --no-fund` / `apps/dashboard` | 502 pinned packages installed. |
| PASS | `node scripts/sync-branding.mjs` / root | Generated assets match current branding; no net branding/theme change against main. |
| PASS | `npm.cmd test` / `apps/dashboard` | 79 passed across 17 files; 0 failures/skips. |
| PASS | `npm.cmd run build` / `apps/dashboard` | Production build succeeds. |
| PASS | `$env:E2E_PORT='4313'; npx.cmd playwright test --workers=2 --reporter=list` / `apps/dashboard` | 60 passed, 6 skipped, 0 failures. |
| PASS | `$env:KAFKA_BOOTSTRAP_SERVERS='127.0.0.1:65534'; & $maven --batch-mode --no-transfer-progress verify` / `services/incident-service` | 129 unit + 25 integration tests; 0 failures/errors/skips. |
| PASS | `& $maven --batch-mode --no-transfer-progress -pl services/event-generator,services/processor -am verify` / root | Support 58 passed; generator 59 passed; processor 235 passed, 2 skipped; 0 failures/errors. |
| PASS | `python scripts/check-contracts.py` / root | 13 observation fixtures, 4 policy/baseline/feature/detection payloads, 12 explanation trajectories, 7 voice/12 SMS reference cases. |
| PASS | `python scripts/check-voice-parity.py --java-output services/processor/target/voice-parity-java.json` / root | 7 fresh Java/Python payloads; all fields match; max float difference 0. |
| PASS | `python scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json` / root | 12 fresh Java/Python payloads; all fields match; max float difference 0. |
| FAIL (environment) | Git Bash: `set -a; source ../Telecom-Anomaly-Detection/.env; set +a; ./scripts/verify` / root | Docker/config checks pass; stops at PostgreSQL readiness: postgres not running. Environment loaded opaquely, never printed. No application stack was running. |
| NOT RUN | Real authenticated browser / full scenario stack | Local application stack unavailable; controlled tests are not live acceptance. |
| FAIL (tooling) | `graphify update .` / root | Installed launcher refers to missing `C:/Users/Admin/.local/bin/graphify`. Initial graph query succeeded; no graph refresh claimed. |

The two processor skips are `VoiceDeliveryTest.livePackagedModelEnrichesRecoveredVoiceEpisode`
and `SmsDeliveryTest.livePackagedModelEnrichesRecoveredSmsEpisode` (optional live model endpoint).
The six browser skips are the real analyst investigation, real Keycloak login, and four
real session lifecycle cases. `live-investigation`, `auth-integration`, theme,
voice-first-slice and real scenario specs stay in their separate configurations.

Initial checks exposed Windows-generated branding mismatch, a premature assertion in
the new cancellation test, and two resource assertions expecting no stream on the overview.
Branding was regenerated; the test now waits for the actual replacement response;
resource checks now prove old-stream closure, exactly one replacement stream, and
zero streams after leaving the protected route. No bounds or assertions were relaxed.

## Evidence boundaries

The original October 1–2 evidence files and five Service Assurance screenshots are
historical PR evidence, preserved unchanged. They do not describe acceptance of this
combined tree. In particular, their old event names, global live-state clock,
48-hour history loader and earlier layout are superseded by the decisions above.
The mentor design document is the original proposal, not the current infrastructure contract.

Fresh browser evidence uses controlled REST/stream responses. Desktop, tablet and mobile
dark operations screenshots were generated by the tests; they are local artifacts,
not authenticated live screenshots. Fresh Maven reports use isolated testcontainers.
Fresh parity output comes from this reconciliation's processor run. Full-stack readiness
and authenticated live acceptance remain unavailable, and are not implied by these results.

## Regression verdicts

| Check | Result | Evidence / limit |
|---|---|---|
| One SSE implementation / subscription | PASS | Single controller/registry/client; resource tests prove closed old stream and exactly one active replacement. |
| Bounded history | PASS | 24h/1440/15-page checks, paged incident/evidence and 50-row tables; resource browser tests. |
| Cancellation | PASS | Main AbortSignal contract; rapid range test aborts previous request and rejects its stale response. |
| Dark UI | PASS | Current shell/styles exact main; desktop/tablet/mobile interaction suite. |
| Session cleanup | PASS (controlled/isolated) | Main session/security tests and controlled expiry/route tests; authenticated live run not available. |
| Technical vs analyst status | PASS | Workflow/evidence suites; recovered/open episode and disabled premature resolution in browser. |
| Missing/UNKNOWN | PASS | Contract/model/rendering tests; missing source trajectories and gap paths remain unavailable. |
| Scenario runner connectivity | PASS (configuration/isolated) | Loopback mapping, private Compose address, generator and simulator tests; live host dispatch not verified. |
| Auth/CSRF | PASS (isolated/controlled) | Main security classes unchanged against main; controller/session/security tests. Live Keycloak acceptance skipped. |
| No model/threshold/identity changes | PASS | No net diff in processor, generator, ML, feature/detection/policy/baseline implementations; fresh parity matches. |

## Changed-file manifests

The complete net change relative to main and merge-import file manifest follow.
There is no separate follow-up commit: conflict adaptation and verification repairs
belong to the focused merge resolution. No generator/processor/model/threshold or
observation/episode-identity implementation changes remain relative to main.

### Net files versus main

`	ext
M	.env.example
M	.gitignore
M	apps/dashboard/angular.json
M	apps/dashboard/api/incident-api.yaml
M	apps/dashboard/src/app/core/api/data-source.fixtures.ts
M	apps/dashboard/src/app/core/api/data-source.ts
M	apps/dashboard/src/app/core/api/schema.ts
M	apps/dashboard/src/app/core/api/telecom-client.ts
M	apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts
M	apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts
M	apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts
M	apps/dashboard/src/app/features/incident-investigation/incident-list.component.ts
A	apps/dashboard/src/app/features/incident-investigation/incident-story.component.ts
A	apps/dashboard/src/app/features/service-kpi-history/assurance-model.spec.ts
A	apps/dashboard/src/app/features/service-kpi-history/assurance-model.ts
A	apps/dashboard/src/app/features/service-kpi-history/assurance-rendering.spec.ts
A	apps/dashboard/src/app/features/service-kpi-history/kpi-cards.component.ts
M	apps/dashboard/src/app/features/service-kpi-history/kpi-chart.component.ts
A	apps/dashboard/src/app/features/service-kpi-history/metric-chart.component.ts
A	apps/dashboard/src/app/features/service-kpi-history/service-detail.component.spec.ts
M	apps/dashboard/src/app/features/service-kpi-history/service-detail.component.ts
A	apps/dashboard/src/app/features/service-kpi-history/service-path.component.ts
M	apps/dashboard/src/app/features/service-overview/service-overview.component.html
M	apps/dashboard/src/app/features/service-overview/service-overview.component.spec.ts
M	apps/dashboard/src/app/features/service-overview/service-overview.component.ts
M	apps/dashboard/src/app/features/service-overview/service.store.spec.ts
M	apps/dashboard/src/app/features/service-overview/service.store.ts
M	apps/dashboard/src/fixtures/services.json
A	apps/dashboard/src/fixtures/sms.ts
M	apps/dashboard/src/styles.css
A	apps/dashboard/tests/e2e/specs/assurance-live-updates.spec.ts
M	apps/dashboard/tests/e2e/specs/evidence-timeline.spec.ts
M	apps/dashboard/tests/e2e/specs/login.spec.ts
M	apps/dashboard/tests/e2e/specs/resource-bounds.spec.ts
A	apps/dashboard/tests/e2e/specs/service-assurance.spec.ts
M	apps/dashboard/tests/e2e/specs/service-scenarios.spec.ts
M	apps/dashboard/tests/e2e/specs/voice-investigation.spec.ts
A	apps/dashboard/vitest.config.ts
M	compose.yaml
A	docs/design/volte-sms-future-scenarios.md
A	docs/design/volte-sms-mentor-service-assurance.md
A	docs/evidence/2026-10-01-authenticated-live-verification.md
A	docs/evidence/2026-10-01-volte-sms-service-assurance-audit.md
A	docs/evidence/2026-10-02-final-repository-audit.md
A	docs/evidence/2026-10-02-gemini-authenticated-acceptance.md
A	docs/evidence/2026-10-02-scenario-runner-blockers.md
A	docs/evidence/assets/service-assurance/README.md
A	docs/evidence/assets/service-assurance/dashboard_ui_semantics.png
A	docs/evidence/assets/service-assurance/sms_incident_detail.png
A	docs/evidence/assets/service-assurance/telemetry_gap_dashboard.png
A	docs/evidence/assets/service-assurance/volte_dispatch_success.png
A	docs/evidence/assets/service-assurance/volte_incident_detail.png
M	docs/runbooks/local-dev.md
M	scripts/verify
M	services/incident-service/src/main/resources/application.yaml
`

### Merge resolution and imported main files versus old PR head

`	ext
A	.dockerignore
A	.github/workflows/dashboard.yml
A	.github/workflows/incident-runtime-image.yml
A	.github/workflows/incident-service.yml
M	.github/workflows/integration.yml
A	Guides/Day-13-resilient-SSE-refresh.md
A	Guides/Day-14-g3-analyst-workflow-and-usability.md
A	Guides/Session_Replay_Live_Updates_Completion.md
M	README.md
M	apps/dashboard/playwright.config.ts
A	apps/dashboard/playwright.live.config.ts
M	apps/dashboard/src/app/app.component.html
M	apps/dashboard/src/app/app.component.ts
D	apps/dashboard/src/app/core/api/live-updates.spec.ts
D	apps/dashboard/src/app/core/api/live-updates.ts
M	apps/dashboard/src/app/core/api/telecom-client.spec.ts
M	apps/dashboard/src/app/core/api/telecom-client.ts
A	apps/dashboard/src/app/core/state/incident-stream.spec.ts
A	apps/dashboard/src/app/core/state/incident-stream.ts
M	apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts
M	apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.spec.ts
M	apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts
M	apps/dashboard/src/app/features/incident-investigation/incident-actions.component.spec.ts
M	apps/dashboard/src/app/features/incident-investigation/incident-actions.component.ts
M	apps/dashboard/src/app/features/incident-investigation/incident-detail.component.spec.ts
M	apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts
M	apps/dashboard/src/app/features/incident-investigation/incident-list.component.ts
M	apps/dashboard/src/app/features/scenario-runner/scenario-runner.component.ts
M	apps/dashboard/src/app/features/service-kpi-history/assurance-model.spec.ts
M	apps/dashboard/src/app/features/service-kpi-history/assurance-model.ts
A	apps/dashboard/src/app/features/service-kpi-history/history-range.component.ts
M	apps/dashboard/src/app/features/service-kpi-history/kpi-chart.component.ts
M	apps/dashboard/src/app/features/service-kpi-history/metric-chart.component.ts
D	apps/dashboard/src/app/features/service-kpi-history/service-detail.component.html
M	apps/dashboard/src/app/features/service-kpi-history/service-detail.component.spec.ts
M	apps/dashboard/src/app/features/service-kpi-history/service-detail.component.ts
M	apps/dashboard/src/app/features/service-kpi-history/sms-history.component.ts
M	apps/dashboard/src/app/features/service-kpi-history/sms-quality.component.ts
M	apps/dashboard/src/app/features/service-overview/service-overview.component.html
M	apps/dashboard/src/app/features/service-overview/service-overview.component.spec.ts
M	apps/dashboard/src/app/features/service-overview/service-overview.component.ts
M	apps/dashboard/src/app/features/service-overview/service.store.spec.ts
M	apps/dashboard/src/app/features/service-overview/service.store.ts
A	apps/dashboard/src/app/shared/icon.component.ts
A	apps/dashboard/src/app/shared/toast.service.ts
M	apps/dashboard/src/branding/mark.svg
M	apps/dashboard/src/branding/tokens.css
M	apps/dashboard/src/styles.css
A	apps/dashboard/src/styles/base.css
A	apps/dashboard/src/styles/components.css
A	apps/dashboard/src/styles/features.css
A	apps/dashboard/src/styles/layout.css
M	apps/dashboard/tests/e2e/specs/assurance-live-updates.spec.ts
A	apps/dashboard/tests/e2e/specs/dark-workspace.spec.ts
M	apps/dashboard/tests/e2e/specs/evidence-timeline.spec.ts
A	apps/dashboard/tests/e2e/specs/investigation.spec.ts
M	apps/dashboard/tests/e2e/specs/keycloak-theme.spec.ts
A	apps/dashboard/tests/e2e/specs/live-investigation.spec.ts
M	apps/dashboard/tests/e2e/specs/login.spec.ts
A	apps/dashboard/tests/e2e/specs/reconnect.spec.ts
A	apps/dashboard/tests/e2e/specs/resource-bounds.spec.ts
M	apps/dashboard/tests/e2e/specs/service-assurance.spec.ts
M	apps/dashboard/tests/e2e/specs/service-explanations.spec.ts
M	apps/dashboard/tests/e2e/specs/session-expiry.spec.ts
M	apps/dashboard/tests/e2e/specs/voice-investigation.spec.ts
M	compose.yaml
M	contracts/openapi/incident-api.yaml
M	design/branding.json
M	docs/evidence/2026-09-29-g2-backend.md
A	docs/evidence/2026-09-30-session-security.md
A	docs/evidence/2026-10-01-replay-ordering.md
A	docs/evidence/2026-10-02-g3-backend.md
A	docs/evidence/2026-10-02-g3-ui.md
A	docs/evidence/2026-10-02-proxy-runtime.md
A	docs/evidence/day-15/resource-baseline-synthetic-1.json
A	docs/evidence/day-15/resource-baseline-synthetic-2.json
A	docs/evidence/day-15/resource-baseline-synthetic-3.json
A	docs/evidence/day-15/resource-baseline-synthetic-extended.json
M	docs/runbooks/local-dev.md
M	infra/keycloak/themes/telecom/login/resources/css/telecom.css
M	infra/keycloak/themes/telecom/login/resources/css/tokens.css
M	infra/keycloak/themes/telecom/login/resources/img/mark.svg
M	scripts/check-auth-routing.mjs
M	scripts/sync-branding.mjs
M	scripts/sync-branding.test.mjs
M	scripts/up
M	scripts/verify
A	services/incident-service/Dockerfile
A	services/incident-service/entrypoint.sh
M	services/incident-service/src/main/java/md/utm/telecom/analysts/AnalystController.java
M	services/incident-service/src/main/java/md/utm/telecom/analysts/repository/AnalystRepository.java
A	services/incident-service/src/main/java/md/utm/telecom/analysts/service/AnalystAccess.java
A	services/incident-service/src/main/java/md/utm/telecom/evidence/service/EpisodeLock.java
M	services/incident-service/src/main/java/md/utm/telecom/evidence/service/EvidenceService.java
A	services/incident-service/src/main/java/md/utm/telecom/evidence/service/PendingSequenceReconciler.java
M	services/incident-service/src/main/java/md/utm/telecom/incidents/AuditService.java
M	services/incident-service/src/main/java/md/utm/telecom/incidents/IncidentWorkflow.java
A	services/incident-service/src/main/java/md/utm/telecom/incidents/controller/IncidentStreamController.java
M	services/incident-service/src/main/java/md/utm/telecom/incidents/exception/WorkflowErrors.java
D	services/incident-service/src/main/java/md/utm/telecom/incidents/live/IncidentStream.java
D	services/incident-service/src/main/java/md/utm/telecom/incidents/live/IncidentStreamController.java
D	services/incident-service/src/main/java/md/utm/telecom/incidents/live/StreamSessionConfiguration.java
M	services/incident-service/src/main/java/md/utm/telecom/incidents/security/SecurityConfig.java
M	services/incident-service/src/main/java/md/utm/telecom/incidents/security/SessionDeadlineFilter.java
A	services/incident-service/src/main/java/md/utm/telecom/incidents/stream/IncidentChanged.java
A	services/incident-service/src/main/java/md/utm/telecom/incidents/stream/IncidentCommittedListener.java
A	services/incident-service/src/main/java/md/utm/telecom/incidents/stream/IncidentStreamRegistry.java
A	services/incident-service/src/main/java/md/utm/telecom/incidents/stream/StreamSessionConfiguration.java
M	services/incident-service/src/main/resources/application.yaml
M	services/incident-service/src/test/java/md/utm/telecom/IncidentServiceIntegrationTestSupport.java
A	services/incident-service/src/test/java/md/utm/telecom/analysts/AnalystControllerTest.java
M	services/incident-service/src/test/java/md/utm/telecom/evidence/EvidenceServiceTest.java
M	services/incident-service/src/test/java/md/utm/telecom/incidents/CommentConcurrencyIT.java
M	services/incident-service/src/test/java/md/utm/telecom/incidents/FirstSliceIT.java
A	services/incident-service/src/test/java/md/utm/telecom/incidents/IncidentStreamIT.java
A	services/incident-service/src/test/java/md/utm/telecom/incidents/ReplayOrderingIT.java
M	services/incident-service/src/test/java/md/utm/telecom/incidents/TestcontainersConfiguration.java
M	services/incident-service/src/test/java/md/utm/telecom/incidents/WorkflowTest.java
A	services/incident-service/src/test/java/md/utm/telecom/incidents/controller/IncidentStreamControllerTest.java
D	services/incident-service/src/test/java/md/utm/telecom/incidents/live/IncidentStreamSecurityTest.java
D	services/incident-service/src/test/java/md/utm/telecom/incidents/live/IncidentStreamTest.java
M	services/incident-service/src/test/java/md/utm/telecom/incidents/security/AuthSecurityTest.java
A	services/incident-service/src/test/java/md/utm/telecom/incidents/security/SessionSecurityTest.java
A	services/incident-service/src/test/java/md/utm/telecom/incidents/stream/IncidentStreamTest.java
M	services/incident-service/src/test/java/md/utm/telecom/simulator/ScenarioControllerIT.java
`
