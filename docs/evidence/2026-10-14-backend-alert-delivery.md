# Backend alert delivery and exact export verification

Actual verification date: 14 October 2026, Europe/Chisinau. Behavior implementation completed on a stack above foundation PR #103. Main integration remains blocked by the V009/schema collision; the user selected preservation and stacked draft PRs.

- Backend Surefire: 190 tests, zero failures/errors, one existing skip. Failsafe: 89 tests, zero failures/errors/skips.
- Permanent generator rejection: failed before repair (400 expected, 503 observed); passed afterward with stable retry identity.
- Real PostgreSQL lease recovery and exhaustion checks pass after timestamp parameter casts.
- Dashboard: 131 tests and production build pass.
- Repository Python: 148 tests, zero failures/errors, one existing skip; activate the repository virtual environment.
- Canonical/copied OpenAPI match and contract validation passes.
- Numerical reference oracle: 708498 checks; DOM-shim controls: 23 checks. Not real-browser acceptance.
- Compose configuration validates.

[Verification summary](assets/backend-alert-delivery/verification.json) records source identity, suite counts and local-only log checksums. Raw logs remain outside Git.

V009 is unchanged; V011 reserves scenario constraints. Push and symptom scenario flags remain false. No existing database was migrated, no producer delivery was activated, and no teammate acceptance was invented.
