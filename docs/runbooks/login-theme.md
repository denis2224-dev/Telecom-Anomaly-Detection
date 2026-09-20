# Branded organization sign-in

The `telecom` Keycloak login theme matches the dashboard's green, white and soft
grey palette. It inherits Keycloak's forms, error messages, password visibility
control, recovery and required-action pages. Credentials continue to go directly
to Keycloak. No Angular password form or new authentication API is introduced.

The theme follows Keycloak's supported inheritance mechanism:
https://www.keycloak.org/ui-customization/themes

## Enable it

`scripts/up` mounts the theme and selects it after Keycloak becomes ready.
New realm imports also select `telecom` automatically. Existing realms are
updated with `scripts/prepare-login-theme`; importing a realm alone does not
update an existing realm.

For an already running stack, from the repository root:

```bash
docker compose up -d keycloak
bash scripts/prepare-login-theme
docker compose restart proxy
```

Wait for Keycloak to be healthy before running the helper. It uses the existing
local admin credentials and changes only the login theme and realm display name.
It preserves accounts, passwords, roles, client secrets and analyst records.

Visit `http://telecom.test:8080/login`. The proxy redirects this exact route to
`/oauth2/authorization/keycloak`, where Spring creates the OIDC request and sends
the browser to the branded credential form. `/login/oauth2/code/keycloak` still
goes to Spring. Existing sign-in buttons already use the same authorization
endpoint. After authentication the backend returns to `/dashboard`.

The fixture build on port 4200 remains a labelled sample preview; it cannot
authenticate. The real stack must be running to access the new credential page.

## Verify the theme

`npm run test:theme` from `apps/dashboard` targets a separate Keycloak on port
8180 with the tracked realm imported and theme mounted. The tests check desktop
and mobile layouts, the loaded branding, password visibility, and rejected
credentials. Set `E2E_THEME_URL` to change its origin; this isolated test instance
uses the default root context rather than the main stack's `/auth` context.
The dashboard's normal `test:e2e` command continues to test its own routing.
Theme tests do not prove successful real backend login or callback handling.

To create the isolated test instance, run from the repository root:

```bash
docker run -d --name telecom-login-theme-check -p 127.0.0.1:8180:8080 \
  -v "$PWD/infra/keycloak/themes/telecom:/opt/keycloak/themes/telecom:ro" \
  -v "$PWD/infra/keycloak/telecom-realm.json:/opt/keycloak/data/import/telecom-realm.json:ro" \
  quay.io/keycloak/keycloak:26.7.4 start-dev --import-realm
```

Wait for startup, then run the theme tests. This uses disposable container-local
storage, with no application users or project database. Stop the test container
when finished. If the dashboard preview already uses port 4200, run its tests
with `E2E_PORT=4212 npm run test:e2e` to use another port.

Verified on 2026-09-20: 22 unit tests, 4 dashboard browser tests, 2 real-Keycloak
theme tests, production Angular build, NGINX configuration validation and the
`/login` 302 redirect. Desktop/mobile screenshots and the invalid-credentials
state were reviewed. The full successful backend login test was skipped because
the isolated theme instance has no provisioned application test account.
