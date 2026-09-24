# Day 03 — login, session and Keycloak branding

Open <http://telecom.test:8080/login> with the local stack running. Choose
**Continue to sign in** to open the branded Keycloak username/password form.
After successful login you return to the dashboard.

- [App login and session code](../../../apps/dashboard/src/app/features/login-and-session)
- [Keycloak login theme](../../../infra/keycloak/themes/telecom/login)
- [Login browser tests](../../../apps/dashboard/tests/e2e/specs/login.spec.ts)
- [Theme setup and verification](../../runbooks/login-theme.md)
- [Account and local environment setup](../../runbooks/local-dev.md)
- [Verified login fixes](../../evidence/2026-09-20-keycloak-fixes.md)
- [Current startup instructions](../day-06-protected-voice-investigation/README.md)

Keycloak accounts need an application role and an enabled analyst mapping.
A temporary Keycloak password must be changed on first sign-in. The project has
administrator-managed accounts rather than public registration.
