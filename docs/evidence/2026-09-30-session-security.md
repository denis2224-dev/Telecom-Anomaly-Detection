# Acceptance evidence: session security

- Planned date: 2026-09-30
- Actual execution date and timezone: 2026-10-02, Europe/Chisinau (EEST)
- Owner: Denis Moroz
- Branch and tested commit: `fix/session-security-lifecycle`, `c3a79c1` (PR CI)
- Gate status: **PARTIAL**
- Backend/frontend/processor/model/proxy revisions: backend branch above; other components at `origin/main` `7ef9a9f`; no compatible live stack run
- Contract and migration versions: incident API OpenAPI in `7ef9a9f`; no migration changed
- Environment: Java 21.0.12.1, Spring Boot 4.0.8, local Maven; Docker daemon unavailable; real browser, Keycloak, proxy and HTTPS deployment not exercised

## Automated checks

| Command/check | Expected | Actual | Result | Saved report |
| --- | --- | --- | --- | --- |
| `./mvnw -o -DargLine=-javaagent:... -Dtest=SessionSecurityTest,AuthSecurityTest test` | Deadline and auth regressions execute | 15 tests, 0 failures, 0 errors, 0 skipped | PASS | `services/incident-service/target/surefire-reports/` |
| `./mvnw -o -DargLine=-javaagent:... verify` | Unit and integration suites pass | Stopped in Surefire: 105 run, 76 errors caused by Testcontainers finding no Docker environment; Failsafe did not run | BLOCKED | `/private/tmp/incident-security-verify.log` and Surefire reports |
| `.venv/bin/python scripts/check-contracts.py` | Existing contracts validate | All listed v2, explanation, voice and SMS validations passed | PASS | Command output, 2026-10-02 |
| `git diff --check` | No whitespace errors | No errors | PASS | Local check |
| PR backend CI | Tested PR revision passes | 111 Surefire tests and 11 Failsafe tests; 0 failures, errors or skips | PASS | [PR #40 backend job](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37007486152/job/110839071347), downloaded XML reports in `/private/tmp/incident-ci-reports/` |

The added workflow regression checks missing and invalid CSRF on assignment, status and comment routes and asserts no incident or audit change. It compiled but could not run locally because its PostgreSQL Testcontainer needs Docker. The focused suite used the already-installed Mockito 5.23.0 agent because local JVM self-attachment is restricted.

## Live acceptance

| Case | Run/episode/incident or correlation ID | Expected | Actual | Result |
| --- | --- | --- | --- | --- |
| Real Keycloak login, session rotation, old/new CSRF token | None | Old token fails and fresh token authorizes a mutation | No live identity stack available | NOT RUN |
| Anonymous/CSRF/role-denial JSON responses | None | 401/403 without redirect loop | Mock MVC auth/CSRF routes passed; real proxy untested | PARTIAL |
| Idle and absolute expiry | None | 15-minute idle and 30-minute absolute expiry | Deterministic boundary tests passed; deployed timing untested | PARTIAL |
| Local and provider logout, provider outage | None | Local access ends even if provider fails | Mock MVC local invalidation passed; provider navigation/outage untested | PARTIAL |
| Cookie flags and token-free `/api/auth/me` through HTTPS proxy | None | Secure cookie and no leaked tokens | Existing Mock MVC token-free response passed; HTTPS proxy untested | PARTIAL |

## Receiving-owner reproduction

- David: browser login, CSRF rotation, idle/absolute expiry and logout checks pending on a compatible UI commit.
- Sergiu: no receiving-owner check required for this branch.
- Stanislav: HTTPS proxy cookie flags, provider outage and logout check pending.

## Limits and unfinished checks

- Docker Desktop is installed as an application bundle without an executable in this environment, and the Docker daemon socket is absent. PR CI executed the full `./mvnw verify` successfully on a Docker-capable host; local Docker-backed rerun remains unavailable.
- No real OIDC/browser/proxy evidence was collected. David and Stanislav must perform the checks above and record revisions, timestamps and redacted results before merge.
- The earlier [G2 backend evidence](2026-09-29-g2-backend.md) remains a separate partial live acceptance record.
- Do not record credentials, CSRF tokens, cookies or OIDC tokens.

## Merge decision

- Required checks satisfied: **no**; focused tests, contract validation and full backend PR CI passed, while live checks remain.
- Remaining blockers: David's browser check and Stanislav's proxy/cookie check.
- PR and merge commit: [draft PR #40](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/40); no merge commit. Leave it open until blockers close.

## Follow-up verification and merge — 2 October 2026

Docker became available. The complete security-branch backend suite passed locally: 111 Surefire + 11 Failsafe tests, zero failures/errors/skips, using the local Mockito agent. PR #40's backend/model/processor CI passed. At the user's explicit request to commit, merge and push without squashing, PR #40 was merged with regular merge commit `42253e9`. The original commits remain intact. The earlier Docker-unavailable statement describes the earlier attempt; it no longer blocks local testing. Live authenticated/HTTPS release acceptance is tracked in the later integrated G3 record, not inferred from this merge.
