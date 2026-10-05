# Final repository audit — 2026-10-02

**Final audit result: PASS. Ready for PR: YES.** Fresh authenticated
acceptance is ACCEPTED / PASS. SSE intentional reconnect is **NOT DIRECTLY VERIFIED**.
The implementation was audited and minimally cleaned up, not reimplemented.

## Findings and fixes

Reviewed every scoped modified/new source, contract, test, config, design and
evidence file listed below. No introduced auth weakening, credential workaround,
fabricated runtime KPIs/subscriber counts, fixture fallback in live mode,
production hardcoded observation timestamps or debug endpoints were found.
Fixtures and fixed test clocks are explicitly test-only. Missing observations
remain unavailable and UNKNOWN; zero samples do not produce a success rate.
Technical health uses persisted episodes independently of analyst workflow.
History uses legal paginated 24h slices, bounded evidence loading and stale-view
generation checks. EventSource, retry/coalescing timers and view subscriptions
are cleaned up on session end/destruction; REST reloads after connection.
Backend notifications are transaction-after-commit with rollback silence,
session deadlines, connection caps and timeout/error/logout cleanup. Existing
security routing and CSRF configuration are unchanged.

Final cleanup: service detail now also says "open analyst incidents"; unused
overview selection/formatting methods were removed; a redundant time-filter CSS
declaration was removed. A controlled login-refresh test now mocks CSRF discovery
instead of depending on the running backend. API regeneration confirmed the
firstObservedAt contract change; the existing generated-file formatting was
retained to avoid unrelated churn. Four branding changes were verified as
line-ending-only and left untouched. Local tooling/telemetry artifacts remain
outside the staged PR scope. Scoped secret-pattern and PNG metadata checks passed;
screenshots were individually visually inspected for secret-bearing content.

## Generator connectivity

Minimal change: Compose adds only `127.0.0.1:${GENERATOR_HOST_PORT:-8081}:8081`.
Host incident-service defaults to loopback, honors GENERATOR_HOST_PORT and accepts
an explicit GENERATOR_BASE_URL. `.env.example` supplies the host URL; the runbook
explains migration, custom ports and service restart. `scripts/verify` adds a
bounded host readiness probe. Docker-internal service naming/networking is
unchanged. Final Docker inspection returned `8081/tcp 127.0.0.1:8081`; a probe
from processor to `http://event-generator:8081/actuator/health/readiness` returned UP.
No secret-bearing local environment files are included.

## Acceptance evidence

[Authenticated live acceptance](2026-10-02-authenticated-acceptance.md)
contains all supplied statuses, identity/role, three run IDs, observed KPIs and
missing-data/recovery results. Earlier blocked reports have explicit superseding
links while preserving their historical observations. Five inspected PNGs and
their [manifest](assets/service-assurance/README.md) are repository-owned.
Live SSE updates passed; intentional reconnect is NOT DIRECTLY VERIFIED.
Controlled reconnect tests are recorded separately and do not upgrade that status.

## Final relevant tests

| Supported command / check | Final result |
| --- | --- |
| `npm test` | 72 passed across 17 files |
| `npm run test:e2e` with E2E_PORT=4205 and E2E_REUSE_SERVER=1 | 53 passed; 1 credential-based live-login test skipped |
| Root Maven `verify` | streaming-support: 58; generator: 59; processor: 237 tests, 2 optional ML tests initially skipped; no failures/errors |
| Processor `test -pl services/processor -am -Dtest=VoiceDeliveryTest,SmsDeliveryTest -Dsurefire.failIfNoSpecifiedTests=false`, ML_SERVICE_URL=http://127.0.0.1:8091 | 5 passed; zero skips, including both optional packaged-model tests |
| incident-service Maven `verify` | 112 tests + 11 integration tests; zero failures/errors/skips; executable JAR built |
| `python scripts/check-contracts.py` | 13 observations, 4 detection payloads, 12 explanation trajectories, 7 voice/12 SMS reference cases passed |
| `python scripts/check-voice-parity.py` | 7 newly exported Java/Python cases passed, exact integers and zero max float difference |
| `python scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json` | 12 newly exported Java/Python cases passed, exact integers and zero max float difference |
| `npm run build`, `npm run build:fixtures` | PASS |
| `npm run check:fixtures`, `npm run test:branding` | PASS |
| `scripts/verify` (includes `node scripts/check-auth-routing.mjs`) | PASS: database grants/isolation, topics, readiness, host generator, OIDC, anonymous protection and routing |
| `bash -n scripts/up scripts/verify`, isolated scripts/up interpreter resolution | PASS |
| `git diff --check` | PASS |
| `graphify update .` through installed Graphify Python module | PASS; optional SQL parser unavailable, generated graph kept local |

Maven 3.9.16 was run from the existing wrapper-managed distribution with the
workspace Maven cache. Native wrappers initially failed due to Windows/cache
resolution and restricted download access. Docker was initially stopped; it and
the existing Compose containers were started with preserved volumes, then both
full Maven suites were rerun successfully with Docker access. `scripts/up` and
history bootstrap were not run. The host service was restored after packaging.
The first controlled browser attempt hung during Windows server teardown; final
browser verification used an explicitly managed local server and exited 0.
Raw local logs are outside the repository, not committed acceptance artifacts.
Automated credential-based browser login was not rerun; the recorded authenticated
acceptance is the authoritative live gate.

## scripts/up decision

**B — separate infrastructure PR.** Its runnable-Python-3 probing correctly skips
broken Windows Store aliases, supports an explicit interpreter and finds PATH or
local-venv interpreters before invoking prepare-keycloak. Syntax and isolated
interpreter discovery pass. The file remains unchanged, unstaged and uncommitted;
SHA-256 `E867115F49253AE217F043C61301BD226B39A91BBBE394DD5152B4B2F8523C45`.
Its startup portability behavior is independent of the Service Assurance feature.
No infrastructure portability change is silently bundled into a feature commit.

## Commit grouping

1. Backend SSE notifications, after-commit hooks and security/lifecycle tests.
2. Service Assurance dashboard, API contract/types, fixtures and frontend tests.
3. Host-generator loopback connectivity, readiness verification and runbook.
4. Design documentation and durable acceptance/audit evidence (including assets).

Existing author configuration and branch history are preserved. No squash,
force-push, main merge, PR creation or PR merge is part of this audit. Commit SHAs
and the exact final working-tree snapshot are supplied in the final pre-PR report.
The working tree intentionally retains the excluded infrastructure/tooling changes.

## Files reviewed in PR scope

- `docs/evidence/2026-10-02-final-repository-audit.md` (this final report)
- `.env.example`
- `.gitignore`
- `apps/dashboard/angular.json`
- `apps/dashboard/api/incident-api.yaml`
- `apps/dashboard/playwright.config.ts`
- `apps/dashboard/src/app/app.component.html`
- `apps/dashboard/src/app/app.component.ts`
- `apps/dashboard/src/app/core/api/data-source.fixtures.ts`
- `apps/dashboard/src/app/core/api/data-source.ts`
- `apps/dashboard/src/app/core/api/live-updates.spec.ts`
- `apps/dashboard/src/app/core/api/live-updates.ts`
- `apps/dashboard/src/app/core/api/schema.ts`
- `apps/dashboard/src/app/core/api/telecom-client.ts`
- `apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts`
- `apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts`
- `apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts`
- `apps/dashboard/src/app/features/incident-investigation/incident-story.component.ts`
- `apps/dashboard/src/app/features/service-kpi-history/assurance-model.spec.ts`
- `apps/dashboard/src/app/features/service-kpi-history/assurance-model.ts`
- `apps/dashboard/src/app/features/service-kpi-history/assurance-rendering.spec.ts`
- `apps/dashboard/src/app/features/service-kpi-history/kpi-cards.component.ts`
- `apps/dashboard/src/app/features/service-kpi-history/kpi-chart.component.ts`
- `apps/dashboard/src/app/features/service-kpi-history/metric-chart.component.ts`
- `apps/dashboard/src/app/features/service-kpi-history/service-detail.component.html`
- `apps/dashboard/src/app/features/service-kpi-history/service-detail.component.spec.ts`
- `apps/dashboard/src/app/features/service-kpi-history/service-detail.component.ts`
- `apps/dashboard/src/app/features/service-kpi-history/service-path.component.ts`
- `apps/dashboard/src/app/features/service-kpi-history/sms-history.component.ts`
- `apps/dashboard/src/app/features/service-overview/service-overview.component.html`
- `apps/dashboard/src/app/features/service-overview/service-overview.component.spec.ts`
- `apps/dashboard/src/app/features/service-overview/service-overview.component.ts`
- `apps/dashboard/src/app/features/service-overview/service.store.spec.ts`
- `apps/dashboard/src/app/features/service-overview/service.store.ts`
- `apps/dashboard/src/fixtures/services.json`
- `apps/dashboard/src/fixtures/sms.ts`
- `apps/dashboard/src/styles.css`
- `apps/dashboard/tests/e2e/specs/assurance-live-updates.spec.ts`
- `apps/dashboard/tests/e2e/specs/evidence-timeline.spec.ts`
- `apps/dashboard/tests/e2e/specs/login.spec.ts`
- `apps/dashboard/tests/e2e/specs/service-assurance.spec.ts`
- `apps/dashboard/tests/e2e/specs/service-scenarios.spec.ts`
- `apps/dashboard/tests/e2e/specs/voice-investigation.spec.ts`
- `apps/dashboard/vitest.config.ts`
- `compose.yaml`
- `docs/design/volte-sms-future-scenarios.md`
- `docs/design/volte-sms-mentor-service-assurance.md`
- `docs/evidence/2026-10-01-authenticated-live-verification.md`
- `docs/evidence/2026-10-01-volte-sms-service-assurance-audit.md`
- `docs/evidence/2026-10-02-authenticated-acceptance.md`
- `docs/evidence/2026-10-02-scenario-runner-blockers.md`
- `docs/evidence/assets/service-assurance/README.md`
- `docs/evidence/assets/service-assurance/dashboard_ui_semantics.png`
- `docs/evidence/assets/service-assurance/sms_incident_detail.png`
- `docs/evidence/assets/service-assurance/telemetry_gap_dashboard.png`
- `docs/evidence/assets/service-assurance/volte_dispatch_success.png`
- `docs/evidence/assets/service-assurance/volte_incident_detail.png`
- `docs/runbooks/local-dev.md`
- `scripts/verify`
- `services/incident-service/src/main/java/md/utm/telecom/evidence/service/EvidenceService.java`
- `services/incident-service/src/main/java/md/utm/telecom/incidents/AuditService.java`
- `services/incident-service/src/main/java/md/utm/telecom/incidents/IncidentWorkflow.java`
- `services/incident-service/src/main/java/md/utm/telecom/incidents/live/IncidentStream.java`
- `services/incident-service/src/main/java/md/utm/telecom/incidents/live/IncidentStreamController.java`
- `services/incident-service/src/main/java/md/utm/telecom/incidents/live/StreamSessionConfiguration.java`
- `services/incident-service/src/main/resources/application.yaml`
- `services/incident-service/src/test/java/md/utm/telecom/evidence/EvidenceServiceTest.java`
- `services/incident-service/src/test/java/md/utm/telecom/incidents/live/IncidentStreamSecurityTest.java`
- `services/incident-service/src/test/java/md/utm/telecom/incidents/live/IncidentStreamTest.java`

Also separately reviewed: `scripts/up`, `AGENTS.md`, and the four line-ending-only
branding copies listed in the October 1 audit. Local instructions, telemetry audit files
and analysis output were classified as existing local tooling/scratch artifacts;
their contents are not feature inputs and are not staged or included in the PR.
