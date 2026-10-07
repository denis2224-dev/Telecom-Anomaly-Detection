# Service integration and acceptance report

## 1. Test baseline

- Repository: `denis2224-dev/Telecom-Anomaly-Detection`
- Branch: `fix/pr60-geography-transition`
- Revision: `6cec04f7fbc2f057f12a17273ae293ecb771a8e5`
- Configuration: Compose configuration validated with disposable non-secret values; no `.env` or credentials committed.
- Profile: local development configuration; live containers were not available.
- Database: PostgreSQL configuration inspected; runtime database checks blocked by Docker daemon availability.
- Synthetic-data configuration: repository fixtures and contract matrices; no live scenario command was dispatched.
- Environment: macOS, Python 3.13 virtual environment, Java/Maven build available, Docker CLI present but Docker daemon unavailable.

## 2. Runtime status

- Database: BLOCKED — Docker daemon unavailable.
- Kafka: BLOCKED — Docker daemon unavailable.
- Generator: BLOCKED — no running Compose environment.
- Processor: BLOCKED for live runtime; compilation passed.
- Incident API: BLOCKED — no running authenticated service.
- Dashboard: NOT VERIFIED live — no browser session was available.
- Probe worker: PASS for static failure-drill coverage; live worker execution not verified.
- Overall runtime status: BLOCKED for connected acceptance; controlled validation passed.

## 3. Scenario acceptance

### NORMAL_CONTROL

- Expected: healthy input produces populated current metrics and no false incident.
- Actual: live execution was not performed.
- Evidence: `scripts/check-contracts.py` passed contract and fixture validation.
- Status: NOT VERIFIED

### VOLTE_IMS_OVERLOAD

- Expected: IMS degradation propagates through Kafka, processor, detection, API, and dashboard.
- Actual: no authenticated live scenario was dispatched.
- Evidence: live runner requires a real browser session cookie; no cookie was supplied.
- Status: BLOCKED

### SMS_QUEUE_DELAY

- Expected: queue delay produces the expected SMS degradation and recovery behavior.
- Actual: no authenticated live scenario was dispatched.
- Evidence: live runner was not executed without authenticated session material.
- Status: BLOCKED

### TELEMETRY_GAP

- Expected: missing telemetry becomes UNKNOWN/MISSING without fabricated power evidence.
- Actual: no connected live run was performed.
- Evidence: contract and controlled fixture checks passed; this is not live evidence.
- Status: NOT VERIFIED

### ORHEI_POWER

- Expected: explicit power evidence is distinguishable from a telemetry gap.
- Actual: no live Orhei run was performed.
- Evidence: no live scenario output or database record was created.
- Status: BLOCKED

### ORHEI_WITHOUT_POWER_EVIDENCE

- Expected: missing telemetry is not promoted to a power-loss root cause.
- Actual: no live run was performed.
- Evidence: no live scenario output or database record was created.
- Status: BLOCKED

## 4. Cross-service consistency

- Generator → Kafka: NOT VERIFIED live.
- Kafka → Processor: NOT VERIFIED live.
- Processor → Detection: PASS at compile/contract level; live propagation NOT VERIFIED.
- Detection → API: NOT VERIFIED live.
- API → Database: NOT VERIFIED live.
- API → Dashboard: NOT VERIFIED live.
- Map/Incident agreement: NOT VERIFIED.
- Chart/Incident agreement: NOT VERIFIED.
- Unaffected-city behavior: PASS for controlled geographic contract/reference matrices; live independence NOT VERIFIED.

## 5. Incident semantics

- `firstObservedAt`: implementation preserves the first breached window in the episode state; live value NOT VERIFIED.
- `detectedAt`: implementation records processing time separately from the observed window; live value NOT VERIFIED.
- `recoveredAt`: live recovery timestamp NOT VERIFIED.
- Technical state: controlled implementation supports UNKNOWN, ONGOING, and RECOVERED; live transitions NOT VERIFIED.
- Analyst state: live workflow NOT VERIFIED.
- Severity: controlled contract paths validated; live result NOT VERIFIED.
- Priority: live result NOT VERIFIED.
- Evidence: controlled evidence validation PASS; live evidence NOT VERIFIED.
- Probable cause: live result NOT VERIFIED.

## 6. Scenario lifecycle

- Start: live authenticated start BLOCKED.
- Stop: live stop BLOCKED.
- Retry: live retry BLOCKED.
- Duplicate replay: static durability drill PASS; live replay NOT VERIFIED.
- Duplicate incident check: static identity/durability checks PASS; live database check BLOCKED.

## 7. Authenticated REST

- Authenticated request: BLOCKED — no authenticated live session.
- Unauthenticated request: NOT VERIFIED in this run.
- Expired session: static session-invalidation drill PASS; live expiry NOT VERIFIED.
- API range limit: NOT VERIFIED live.
- Authorization behavior: static security invariants inspected; live authorization requests NOT VERIFIED.

## 8. SSE

- Connection: BLOCKED — no running API or authenticated browser.
- Event delivery: NOT VERIFIED live.
- Disconnect: NOT VERIFIED live.
- Reconnect: NOT VERIFIED live.
- Authoritative reload: NOT VERIFIED live.
- Duplicate event/incident check: static identity checks PASS; live stream check NOT VERIFIED.

## 9. Feature-off fallback

- Feature state: Compose defaults geography to disabled and the shared generator/processor configuration is validated.
- Existing workflow: controlled configuration and contract checks PASS.
- Result: live fallback behavior NOT VERIFIED because the runtime was unavailable.

## 10. Rollback rehearsal

- Environment: controlled repository only; no running deployment.
- Starting revision: `6cec04f7fbc2f057f12a17273ae293ecb771a8e5`.
- New feature: shared geography startup alignment and acceptance tooling corrections.
- Rollback action: not performed against a live environment.
- Old revision: not deployed.
- Existing data preserved: NOT VERIFIED live.
- Additive data preserved: NOT VERIFIED live.
- Existing workflow after rollback: NOT VERIFIED.
- Result: BLOCKED — destructive or live rollback was not attempted without a disposable runtime.

## 11. Resource / latency measurements

- Hardware: macOS host; Docker daemon unavailable.
- Workload: static contract checks, Python tests, Maven compilation, and static failure drills.
- Duration: Python suite 42.43 seconds; Maven package 4.38 seconds; static failure drill completed in under one second.
- CPU: not measured for a connected workload.
- Memory: not measured for a connected workload.
- Request rate: not measured.
- Latency: no live API or SSE endpoint available.
- Errors: live runtime unavailable; no secrets or session material exposed.
- Notes: these measurements are local validation timings, not production performance results.

## 12. Acceptance matrix

- PASS: contract validation; Compose syntax/configuration; geography alignment tests; static failure drill (5 components, 15 checks); Python tests (105 tests and 236 subtests); processor compilation/package; whitespace validation.
- FAIL: none observed in the executed controlled checks.
- BLOCKED: connected service scenarios; live database/Kafka/generator/processor/API checks; authenticated REST/SSE; rollback rehearsal.
- NOT VERIFIED: live dashboard agreement, live timestamps, live unaffected-city behavior, live resource/latency measurements, and live scenario recovery.

## 13. Defects found

1. Owner: Infrastructure/release verification
   - Severity: Environment blocker
   - Revision: `6cec04f7fbc2f057f12a17273ae293ecb771a8e5`
   - Scenario: all connected acceptance scenarios
   - Seed: none
   - Expected: disposable Compose environment available for runtime validation.
   - Actual: Docker CLI could not connect to `unix:///Users/bradustanislav/.docker/run/docker.sock`.
   - Reproduction: run `docker info`.
   - Evidence: local command output; no application data was changed.

2. Owner: Authentication/integration handoff
   - Severity: Acceptance blocker
   - Revision: `6cec04f7fbc2f057f12a17273ae293ecb771a8e5`
   - Scenario: authenticated scenario runner
   - Seed: none
   - Expected: real logged-in browser session for protected scenario commands.
   - Actual: no authenticated browser session or cookie was available.
   - Reproduction: `scripts/run-service-scenarios.py --help` confirms the required real session input.
   - Evidence: runner source and help output; no cookie was printed or stored.

## 14. Files changed

- `scripts/failure-drill.py` — corrected repository paths and current outbox/episode identity markers so static drills reflect the current tree.
- `docs/evidence/2026-10-07-integration-acceptance.md` — recorded the controlled results and explicit runtime blockers.

## 15. Files preserved

- `.env` and all credentials — not read into evidence or committed.
- `scripts/failure-drill.py` output — temporary report remained outside the repository.
- `scripts/failure-drill.py` was corrected in place; no unrelated application code or migrations were changed.
- Existing untracked files outside this acceptance scope were preserved.

## 16. Evidence created

- `docs/evidence/2026-10-07-integration-acceptance.md` — acceptance matrix, validation results, blockers, and handoff.

## 17. Known limitations

- Connected runtime acceptance requires Docker Desktop/daemon to be started.
- Authenticated REST, SSE, dashboard, scenario lifecycle, rollback, and resource measurements require a running environment and a real browser login.
- Static fixture and contract results do not substitute for connected evidence.

## 18. Team handoff

- Revision: `6cec04f7fbc2f057f12a17273ae293ecb771a8e5`
- Environment: local macOS repository; Docker daemon currently unavailable.
- Configuration: Compose geography alignment is wired and validated before startup.
- Failing tests: none in the executed controlled suites.
- Blocking defects: unavailable Docker runtime and missing authenticated browser session.
- Required owner actions: start the disposable runtime, log in through the real dashboard, execute the scenario sequence, and attach fresh REST/SSE/dashboard evidence.
- Infrastructure notes: no reset, cleanup, volume deletion, credential exposure, or live rollback was performed.

## 19. Final status

PARTIAL — controlled integration and failure-boundary checks passed; connected acceptance remains blocked by environment and authentication prerequisites.
