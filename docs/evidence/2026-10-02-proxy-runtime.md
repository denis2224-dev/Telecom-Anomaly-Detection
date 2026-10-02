# Proxy and runtime integration check

- Planned and actual execution date: 2 October 2026, Europe/Chisinau (EEST)
- Operator: Codex, performing Stanislav's proxy/runtime checks at Denis's request
- Branch: `fix/proxy-runtime-integration`, based on `7ef9a9ff8c8ac94c8b7f13c2160ce49a90f7fec5`
- Gate status: **PARTIAL** for authenticated release acceptance; public anonymous routing and runtime checks passed
- Public origin: `http://telecom.test:8080` through the real local NGINX proxy
- Runtime: Java 21, Docker 29.7.2, PostgreSQL 16.4, Kafka 3.9.1, Keycloak 26.7.4, NGINX 1.28.3, Playwright Chromium 153

## Running revisions

The dashboard production bundle and Compose Java images were built from local `main` at `7ef9a9f`, plus this branch's Dockerfile for the incident service. Initial checks used a host incident service from that checkout; later checks used the new Compose-managed image. The checked route script and database permission assertions are this branch's changes. Container image IDs at execution time were:

| Service | Running image ID |
| --- | --- |
| Event generator | `sha256:2b988dc61666718b08c813b87c42a62e58ff8b1ba737ed0d2e1a8e1a6aa940df` |
| Processor | `sha256:10f9798875b6ba4408bf5dfc224ca3ca2f8430526e69fa305e40519518ad2bb6` |
| ML service | `sha256:e9fe9a759ec63b4dde8707eb31618591448f68329c8ad7e000f4e5a1b9ca57c9` |
| NGINX proxy | `sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236` |
| Managed incident service | `sha256:be4ea81976d694ae5ca520b1959961073f39f576e44983e7e86bf882306792bf` |

## Checks performed

| Check | Actual result | Status |
| --- | --- | --- |
| `npm --prefix apps/dashboard run build` | Production bundle built and served by NGINX | PASS |
| `./scripts/up` | Preserved existing volumes; PostgreSQL, Kafka, Keycloak, proxy, generator, processor and ML service started and became healthy | PASS |
| Initial `docker compose --profile app up -d --wait incident-service` | Created the incident-service container from the new image; it became healthy and served the existing proxy route | PASS |
| `./scripts/up --with-incident-service` | Full startup command completed with the already-created managed backend healthy and no host Maven process | PASS |
| `docker build --file services/incident-service/Dockerfile --tag incident-service:ci .` | Exact new image-CI build command succeeded locally | PASS |
| `docker compose --profile app restart incident-service` then `./scripts/verify` | Managed backend restarted, returned to healthy, public routes recovered, and its Kafka consumers rejoined the existing group with lag 0 on populated partitions | PASS |
| `KEYCLOAK_CLIENT_SECRET= docker compose config --quiet` | First-time Compose validation succeeds before Keycloak client provisioning; the running backend received a nonempty provisioned secret (value not inspected or recorded) | PASS |
| `docker run --rm --network none incident-service:ci` with no secret | Image exited 1 before Java startup with a provisioning message | PASS |
| `./scripts/verify` after this branch's edits | Database isolation; immutable incident evidence, audit and KPI grants; Kafka topics; service readiness; OIDC discovery and PKCE; Angular routes; callback rejection; anonymous API/stream JSON 401; logout protection | PASS |
| `GET /api/auth/csrf` through NGINX | JSON CSRF contract; one `JSESSIONID` with `Path=/`, `HttpOnly`, `SameSite=Lax` and no `Secure` on the configured HTTP development origin. Cookie and token values were not recorded. | PASS for HTTP |
| `npx playwright test --config playwright.auth.config.ts auth-integration.spec.ts` | Real Angular-to-Keycloak handoff and branded provider at 1366px and 390px: 2 passed | PASS |
| Current-main incident-service `./mvnw verify` with the local Mockito Java agent | 103 Surefire and 11 Failsafe tests, 0 failures/errors/skips | PASS |
| Prepared replay/stream integration worktree `./mvnw -Dit.test=ReplayOrderingIT,IncidentStreamIT verify` with the same agent | 128 Surefire and 14 Failsafe tests, 0 failures/errors/skips. This worktree is uncommitted preparation for later sequential branches, not this PR revision. | PASS as local preparation only |
| Real Kafka consumer group after host service restart | Active detection and KPI consumers; populated partitions had lag 0 at 16:43 EEST. No offsets were reset. | PASS |

The first `npm run test:auth` attempt failed before any page opened because the Playwright Chromium binary was missing. `npx playwright install chromium` succeeded; the two anonymous browser cases then passed again with the Compose-managed backend. The remaining session-expiry browser cases need a provisioned real analyst login and were not run. The host incident service had to be restarted after its tool session ended; it rejoined Kafka and caught up without manual offset changes. The managed container now keeps the backend running independently of that terminal session.

## Limits and receiving-owner handoff

- This environment exposes HTTP only. `SESSION_COOKIE_SECURE=false` is correct for this local origin; HTTPS `Secure=true` still needs a real TLS deployment and inspection through that proxy.
- No test analyst credentials were available to this run. Real session rotation, authenticated CSRF mutation/logout, provider outage, idle/absolute browser expiry, and the public supervisor simulator journey remain open. Do not treat the anonymous browser handoff as those checks.
- `main` has no incident stream controller or dashboard `EventSource` consumer yet. The anonymous stream request correctly returned 401 at Spring's security boundary, but live event delivery, reconnect and session closure through NGINX remain open for the later stream branch.
- The replay worktree's acknowledgement test simulates the transaction boundary and uses real PostgreSQL. A disposable-stack process termination before/after commit with an isolated Kafka consumer group was not performed here. Do not claim real crash acceptance from these tests.
- The new incident-image CI job was added after the first PR revision; its result on the updated PR revision must be recorded before merge.
- [The earlier G2 record](2026-09-29-g2-backend.md) remains **PARTIAL** for the authenticated public simulator and UI check.

For the next integrated run, use a provisioned analyst and supervisor account and a compatible UI/backend revision. Save run/episode/incident IDs, Kafka offsets, proxy arrival and stream closure times, and redacted browser evidence. Never record cookies, CSRF values, credentials or OIDC tokens.

## Merge decision

This branch makes proxy/runtime verification repeatable and passed the checks it can execute locally. Authenticated and HTTPS acceptance remain **PARTIAL**. Keep this PR reviewable; do not use it to mark the three backend gates complete or merge their separate branches.
