# Release candidate runbook

This runbook verifies one exact candidate without changing application
architecture or business logic.

## Freeze and inspect

```sh
git status --short --branch
git rev-parse HEAD
git diff --check
```

Record the SHA before every validation cycle. If source or configuration changes,
freeze a new SHA and rerun affected checks.

## Toolchain

Required versions:

- Java 21
- Maven from `./mvnw`
- Node and npm versions supported by `apps/dashboard/package.json`
- Python 3.11 or newer with the project development requirements
- Docker Desktop with a reachable Docker daemon

## Configuration validation

Use a private `.env` with locally managed passwords. Never commit it.

```sh
./scripts/check_geography_alignment.py --env-file .env
docker compose config --quiet
```

For syntax-only validation without secrets, render with placeholders in a
disposable shell. Placeholder credentials must never be used to start a shared
environment.

## Startup and verification

```sh
./scripts/up --with-incident-service
./scripts/verify
```

The documented startup owns PostgreSQL, Kafka, Keycloak, ML, generator,
processor, proxy and incident-service readiness. Do not replace it with an
untracked startup sequence.

## Regression and live acceptance

Run the existing contract, Java, Python and dashboard checks for the candidate.
For live acceptance, use a provisioned analyst or supervisor account through the
approved secure channel. Verify login, protected REST, protected SSE, logout,
role enforcement, normal control, VoLTE overload, SMS delay and telemetry gap.
Do not record passwords, cookies, tokens or CSRF values.

## Stop, restart and resume

```sh
./scripts/stop
./scripts/up --with-incident-service
./scripts/verify
```

Preserve named volumes for the working environment. Use a disposable Compose
project and disposable databases for destructive reset or migration rehearsals.
Confirm incidents, evidence, migrations, authentication and replay identity after
restart.

## Feature-off and rollback

1. Record the candidate SHA and configuration.
2. Disable the new feature/profile using the documented configuration.
3. Confirm the legacy route and existing authentication remain available.
4. Restore the previously approved application revision through normal review.
5. Retain additive tables and records.
6. Run health, API, replay and existing-workflow checks.

Do not claim rollback support until this sequence has been executed on a
disposable environment and recorded in the evidence manifest.

## Mentor reproduction

Give a teammate only this runbook, the approved secure credentials and the
scenario instructions. They should start, verify, authenticate, inspect the
city matrix, run a scenario, inspect evidence, refresh, reconnect and exercise
feature-off without step-by-step coaching. Record unclear steps as blockers.

## Shutdown

```sh
./scripts/stop
```

Do not remove volumes as a normal shutdown action.
