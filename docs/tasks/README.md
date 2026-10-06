# Project guides

Feature guides link to shared implementations, setup instructions and verification evidence.

## Feature guides

- [Foundations and service overview](foundations-and-service-overview/README.md) — shared contracts, overview screen and earlier backend evidence.
- [Login and session](login-and-session/README.md) — sign-in page, access protection, Keycloak styling and account setup.
- [Voice KPI trend](voice-kpi-trend/README.md) — graph, expected values, attempt counts and one card per incident.
- [Protected voice investigation](protected-voice-investigation/README.md) — complete login-to-evidence flow and exact startup commands.
- [Evidence timeline and cause panel](evidence-timeline-and-cause/README.md) — historical detections, source evidence, impact and cause hypotheses.

## Frontend folders

Under [`apps/dashboard/src/app/features`](../../apps/dashboard/src/app/features):

- `login-and-session/` — app login screen, sign-out, session state and protected-route guard. Previously `session/`.
- `service-overview/` — dashboard service cards and their health states.
- `service-kpi-history/` — KPI chart, time-range filter and service history. Previously `service-detail/`.
- `incident-investigation/` — incident cards and the full evidence timeline. Previously `incidents/`.

The username/password form lives in [`infra/keycloak/themes/telecom/login`](../../infra/keycloak/themes/telecom/login), because Keycloak handles credentials.
Shared API calls and generated API types live in [`core/api`](../../apps/dashboard/src/app/core/api).
Browser tests live in [`tests/e2e/specs`](../../apps/dashboard/tests/e2e/specs).

## Backend and setup folders

- [`services/event-generator`](../../services/event-generator) creates synthetic measurements.
- [`services/processor`](../../services/processor) calculates KPIs and detects voice episodes.
- [`services/incident-service`](../../services/incident-service) stores incidents and provides protected APIs and login sessions.
- [`services/streaming-support`](../../services/streaming-support) validates shared observation contracts.
- [`services/ml-service`](../../services/ml-service) contains independent Python feature calculations.
- [`infra`](../../infra) contains Docker service configuration, database setup, proxy and Keycloak branding.
- [`scripts`](../../scripts) contains startup, verification, account provisioning and scenario commands.
- [`runbooks`](../runbooks) contains detailed setup instructions; [`evidence`](../evidence) records what was verified.

Backend folders already describe their functions. Their names, API URLs and the
assignment's evidence filenames remain stable. Older screenshots that mention
`session`, `service-detail` or `incidents` refer to the renamed frontend folders above.
