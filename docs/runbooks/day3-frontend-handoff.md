# Day 03: login frontend and backend handoff

Status: frontend prepared. Real Keycloak login still needs the running backend
and a provisioned test account.

## How it works

1. Opening `/dashboard` or a service URL checks `/api/auth/me` and then
   `/api/auth/csrf`. A loading screen appears while these checks run.
2. An anonymous user goes to `/login`. The sign-in button navigates to
   `/oauth2/authorization/keycloak`; Keycloak collects the credentials.
3. The backend handles the callback and returns to `/dashboard`. The header
   shows the verified analyst name, roles and expiry time.
4. Expiry clears identity and CSRF state, hides protected content and opens
   `/login`. Connection errors offer a retry; access denial explains who to ask.
5. Sign out submits the CSRF-protected form to `/logout`. The backend invalidates
   the session and returns to `/signed-out`.

OAuth tokens are never stored by Angular. Route guards support navigation;
backend authorization must still protect every API endpoint.

## Preview before Denis's backend is ready

From `apps/dashboard`, run `npm ci`, then `npm run start:fixtures`.
Open `http://127.0.0.1:4200/login` and choose **Open sample workspace**.
This build clearly labels synthetic sample data and has no authenticated identity.
It is separate from the connected application; API failures never switch to it.
Run `npm start` for the connected application, including its real connection-error
screen when the backend is unavailable.

## What Denis and the infrastructure owner need to provide

The agreed configuration is in `docs/adr/002-databases-identity.md`:

- Serve Angular and the backend at `http://telecom.test:8080`. Proxy `/api`,
  `/oauth2`, `/login/oauth2` and `/logout` before the frontend HTML fallback.
  Frontend routes such as `/login` and `/signed-out` must serve Angular.
- Use client `telecom-web`, issuer
  `http://telecom.test:8080/auth/realms/telecom`, and callback
  `http://telecom.test:8080/login/oauth2/code/keycloak`.
  Login returns to `/dashboard`; logout returns to `/signed-out`.
- Provision an enabled analyst with a matching Keycloak issuer/subject and an
  `app_roles` claim containing ANALYST, SUPERVISOR or ADMIN.
- Return JSON from `/api/auth/me`: `analystId`, `displayName`, `roles`, `expiresAt`.
  Return JSON from `/api/auth/csrf`: `token`, `headerName: "X-CSRF-TOKEN"`,
  `parameterName: "_csrf"`. Accept `_csrf` in the logout form.
- Use the HttpOnly session cookie defined in the ADR. Anonymous/expired APIs
  must return JSON 401; denied access must return JSON 403. APIs must never
  redirect to an HTML login page.

The frontend already calls these endpoints. No backend data or client secret
needs to be pasted into Angular. Real login must be checked at the canonical
origin because localhost has a different cookie origin.

## Verification commands

From `apps/dashboard`:

- `npm test`: unit tests.
- `npm run build` and `npm run build:fixtures`: connected and sample builds.
- `npx playwright install chromium` once, then `npm run test:e2e`: browser tests
  with simulated API responses for anonymous routing, HTML errors, retry, expiry
  and mobile layout. The real test is explicitly skipped in this mode.

The browser spec is `apps/dashboard/tests/e2e/specs/login.spec.ts`, beside the
dashboard's test dependencies. For a real run, provide `E2E_REAL_LOGIN=1`,
`E2E_BASE_URL=http://telecom.test:8080`, `E2E_USERNAME`, `E2E_PASSWORD`, and
`E2E_DISPLAY_NAME` through your local environment, then run `npm run test:e2e`.
Keep credentials out of source control. This uses the already running stack
and checks anonymous JSON 401, real login, the expected identity, empty browser
storage, and logout. Missing variables fail the real run. Custom Keycloak themes
may need adjustments to the default form-label selectors.

Before declaring Day 03 complete, also expire the server session and verify that
the next protected API request removes the workspace, confirm real service data,
and record the run as evidence. Mocked tests and sample previews do not establish
that the actual backend login works.
