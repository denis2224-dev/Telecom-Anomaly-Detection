# Backend security and database lifecycle regression

Candidate base: merged `main` at `18d0848b9986d1724d1d1775623ac5d81be5af12`.
Branch: `test/backend-security-lifecycle`.
Tester and date: Codex, 9 October 2026, local disposable PostgreSQL and Compose stack.

## Permission boundaries

| Check | Evidence | Result |
| --- | --- | --- |
| Forged `role=ADMIN` in a scenario body | `PermissionsIT.claimedRoleInRequestCannotStartScenario`: an OIDC `ANALYST` receives 403 and no command row is saved | Pass |
| Issuer plus opaque subject identity | `PermissionsIT.sameSubjectFromAnotherIssuerIsNotTheSameAnalyst`: known pair returns its analyst ID; same subject from another issuer receives 403 | Pass |
| Disabled local analyst profile | `PermissionsIT.disabledAnalystCannotKeepUsingProfile`: the same authenticated session changes from `/api/auth/me` 200 to 403 after disable | Pass |
| CSRF and session lifetime | Existing `AuthSecurityTest` and `SessionSecurityTest`: CSRF failure, PKCE redirect, token-free session response, idle and absolute expiry | Covered by full backend verification |
| Concurrent workflow and recovery gate | Existing `WorkflowTest`: stale version produces 409 without a second audit entry; ongoing and unknown incidents cannot be resolved; a recovered assigned incident can be resolved | Covered by full backend verification |
| Stream session behavior | Existing `IncidentStreamIT` and `SessionSecurityTest` | Covered by full backend verification |

The earlier connected [release workflow](2026-10-09-release-backend-reliability.md) used real Keycloak logins for analyst and supervisor roles, CSRF, assignment, stale-version rejection, recovery-gated resolution, SSE reconnection, and logout. The [dependency drill](backend-failure-drills.md) separately verified existing-session and fresh-login behavior across an identity outage. This branch's new permission test uses an OIDC test principal against the real incident schema; it does not claim a new live browser run.

## Database isolation and migration

| Check | Evidence | Result |
| --- | --- | --- |
| Future migrator-owned table and sequence | `DatabaseIsolationIT`: runtime can insert using the default sequence and can select, update, and delete rows | Pass |
| Runtime cannot own or alter schema | `DatabaseIsolationIT`: `CREATE` in `app`/`public` and `ALTER` of migrator table fail with SQLSTATE `42501` | Pass |
| Runtime cannot cross service databases | `DatabaseIsolationIT`: connections to `processing_db` and `keycloak_db` fail with SQLSTATE `42501` | Pass |
| Fresh Flyway installation and current constraints | Existing `DatabaseMigrationTest` applies four migrations to a new database and verifies runtime DML, immutable evidence, ownership, indexes, constraints, and denied DDL | Covered by full backend verification |
| Populated upgrade path | Existing `DatabaseUpgradeTest` migrates a populated V003 database to V004, validates checksums and row fingerprints, and proves a second migration is a no-op | Covered by full backend verification |
| Active Compose roles and grants | `./scripts/verify` checked database ownership, runtime DML, immutable tables, application readiness, Keycloak discovery, and public auth routing | Pass |

`DatabaseIsolationIT` tests default privileges on objects created *after* role setup, which the existing migration tests did not cover. It uses disposable credentials and a Testcontainers PostgreSQL instance. No production schema or migration was changed.

## Commands and decision

- `./mvnw -q verify -Dit.test=PermissionsIT,DatabaseIsolationIT -Dsurefire.failIfNoSpecifiedTests=false`: pass; `PermissionsIT` 3/3 and `DatabaseIsolationIT` 1/1.
- `.venv/bin/python scripts/check-contracts.py`: pass.
- `./scripts/verify`: pass.
- Full incident-service `./mvnw -q verify`: pass; all Surefire and Failsafe reports have zero failures and errors.

No security or migration invariant failed. The branch is ready for CI and integration.
