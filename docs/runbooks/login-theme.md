# Branded organization sign-in

The authentication experience has two deliberately separate pages:

1. Angular owns the `/login` handoff page and explains where the user is going.
2. Keycloak owns the credential form, validation, recovery and required actions.

The Angular button opens `/oauth2/authorization/keycloak`. Spring creates the
authorization request and PKCE values, Keycloak collects the credentials, and
the callback returns through `/login/oauth2/code/keycloak`. A successful login
returns to `/dashboard`.

Credentials must never be collected by Angular or sent to a custom application
authentication endpoint.

## Theme structure

The `telecom` login theme extends the Keycloak theme rather than replacing its
forms:

```properties
parent=keycloak
import=common/keycloak
styles=css/login.css css/telecom.css
darkMode=false
```

Loading `telecom.css` after Keycloak's `login.css` preserves the provider's
form markup, accessibility, messages, password visibility control and other
authentication states. The custom theme currently adds CSS, generated branding
assets, message-bundle entries and a supported `footer.ftl`; it does not override
the login form or shared page templates.

Keycloak is pinned to `26.7.4`. If a future structural change requires a
FreeMarker override, copy only the smallest relevant template from that exact
Keycloak version. Compare every overridden template with upstream before each
Keycloak upgrade.

## Shared branding

`design/branding.json` is the source of truth for the shared colors, typography,
radius and card shadow. The generator writes tracked Angular and Keycloak files:

- `apps/dashboard/src/branding/tokens.css`
- `apps/dashboard/src/branding/mark.svg`
- `infra/keycloak/themes/telecom/login/resources/css/tokens.css`
- `infra/keycloak/themes/telecom/login/resources/img/mark.svg`

After changing the source values, regenerate the outputs from the repository
root:

```bash
node scripts/sync-branding.mjs
```

Check without modifying files:

```bash
node scripts/sync-branding.mjs --check
```

Dashboard start, build and unit-test commands reject stale generated branding.

## Routing ownership

NGINX uses these boundaries:

| Route | Owner |
| --- | --- |
| `/login` | Angular handoff page |
| `/oauth2/**` | Spring Security |
| `/login/oauth2/**` | Spring Security callback |
| `/api/**`, `/logout`, `/actuator/**` | Spring application |
| `/auth/**` | Keycloak |
| Other frontend routes | Angular |

Refreshing or directly opening `/login` must return the Angular shell. The OIDC
callback must never fall through to the Angular shell.

## Enable the theme

`scripts/up` mounts the theme and selects it after Keycloak becomes ready. New
realm imports also select `telecom`. Importing the realm does not update an
existing realm, so use the helper for an already running installation:

```bash
docker compose up -d keycloak
bash scripts/prepare-login-theme
docker compose restart proxy
```

The helper changes only the realm login theme and display name. It preserves
accounts, passwords, roles, client secrets and analyst records.

The fixture build on port 4200 remains a labelled sample preview and cannot
perform a real login.

## Theme development

The production configuration should keep theme caching enabled. For local theme
editing only, Keycloak supports these development flags:

```text
--spi-theme--static-max-age=-1
--spi-theme--cache-themes=false
--spi-theme--cache-templates=false
```

`npm run test:theme` applies those flags to a disposable Keycloak container. Do
not add them to the production Compose service.

## Verification

From `apps/dashboard`, run:

```bash
npm test
npm run build
npm run test:e2e
npm run test:branding
npm run test:theme
```

The theme test starts a disposable Keycloak `26.7.4` container and checks the
default login, invalid credentials, password visibility, keyboard focus, mobile
layout, password-recovery page and provider-error page. It does not use real
accounts.

With the complete NGINX, Spring and Keycloak stack running, verify route
ownership without credentials:

```bash
npm run test:auth-routing
npm run test:auth
```

The repository-level `./scripts/verify` command also runs the authentication
routing check after infrastructure readiness checks.

For a full credential login and logout test, provide a dedicated non-production
test account through the documented `E2E_*` environment variables and run the
normal Playwright suite with `E2E_REAL_LOGIN` enabled. Do not record traces,
videos, screenshots, cookies, passwords, callback URLs or tokens from this run.

If a real test account is unavailable, report successful anonymous routing and
theme verification separately; do not claim that the authenticated callback and
logout flow were exercised.

## Upgrade checklist

When changing Keycloak versions:

1. Update the pinned image used by Compose and the disposable theme-test runner.
2. Run the branding, unit, production-build, frontend and theme test suites.
3. Exercise recovery, required actions, MFA and identity-provider pages enabled
   by the realm.
4. Run the full-stack routing and credential smoke tests.
5. Review selectors in `telecom.css` against the new default theme markup.
6. Compare any future FreeMarker overrides with the new upstream templates.
