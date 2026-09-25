# Scenario verification — 2026-09-25

## Delivered

- `scripts/run-service-scenarios.py` exercises the authenticated public simulator
  API, records the request and run IDs, verifies the durable schedule, retries
  the same request, and polls server state through completion.
- `tests/e2e/specs/service-scenarios.spec.ts` creates a temporary supervisor,
  performs real browser login, verifies schedule duration and exact retry
  behavior, exercises the idempotent stop control, and polls for the
  `RECOVERY` episode phase.

The checks use server responses and Playwright polling intervals rather than
fixed scenario-duration sleeps. The runner writes its saved result to
`target/service-scenarios.json` when a live run is executed.

## Verification

- `python3 -m py_compile scripts/run-service-scenarios.py`
- `python3 scripts/run-service-scenarios.py --help`
- `git diff --check`

The live Compose-backed E2E run remains pending because the local service stack
was not available during implementation. Run it from the repository root with:

```bash
cd apps/dashboard
E2E_REAL_LOGIN=1 npx playwright test tests/e2e/specs/service-scenarios.spec.ts
```

