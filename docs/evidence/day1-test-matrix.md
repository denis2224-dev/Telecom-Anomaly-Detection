# Day 1 DevOps Test Matrix

This matrix separates repository/controlled evidence from live runtime
acceptance. A row is `PASS` only when the check was executed and its expected
result was observed.

| Area | Check | Expected evidence | Status | Current evidence |
| --- | --- | --- | --- | --- |
| Git | Branch and revision recorded | Exact branch and SHA | PASS | [Day 1 baseline](day1-devops-baseline.md) |
| Git | Dirty-file inventory | Existing untracked files preserved | PASS | `scripts/failure-drill.py` recorded |
| Startup | Existing startup path | Services start through `scripts/up` | BLOCKED | Docker daemon unavailable |
| Runtime | PostgreSQL/Kafka/Keycloak readiness | Health and readiness pass | BLOCKED | Docker daemon unavailable |
| Runtime | Generator/processor/ML readiness | Application endpoints pass | BLOCKED | Docker daemon unavailable |
| Browser | Authenticated analyst session | Login and protected dashboard access | NOT VERIFIED | Runtime was not available |
| Database | Development backup | Private timestamped cluster dump | BLOCKED | Requires running PostgreSQL |
| Database | Separate disposable restore | Schema and connectivity validated | BLOCKED | Requires Docker and backup |
| Probe | Allowlisted target | Controlled identity and timestamp | NOT VERIFIED | Contract established; adapter deferred |
| Probe | Unknown target | Rejected before network access | NOT VERIFIED | Contract established; adapter deferred |
| Probe | Timeout | Bounded failure and healthy worker | NOT VERIFIED | Contract established; adapter deferred |
| Probe | Stale result | Stale evidence distinguished | NOT VERIFIED | Contract established; adapter deferred |
| Probe | Worker stopped | Worker failure distinguished from target failure | NOT VERIFIED | Contract established; adapter deferred |
| Feature boundary | Default-off integration boundary | Existing path remains fallback | PASS | Proposed boundary documented; not wired |
| Contracts | Static repository contracts | Existing contract checks pass | PASS | `python3 scripts/check-contracts.py` passed |
| Python tests | Root contract test suite | All discovered tests pass | BLOCKED | Three modules could not import `openapi_schema_validator` in the selected shell interpreter |

## Evidence classification

- **Controlled fixture**: deterministic repository test or contract check.
- **Backend/database**: live service and persistence verification.
- **Authenticated live**: browser session and protected API behavior.
- **Documentation boundary**: contract or policy recorded but no runtime adapter
  is claimed.

## Follow-up sequence

1. Start Docker Desktop.
2. Run the startup and `scripts/verify` checks from the runbook.
3. Perform the authenticated browser check.
4. Create a private cluster backup and restore it only into the disposable
   target.
5. Implement and test the bounded probe adapter in the later infrastructure
   task, reusing the contract and safety boundary in
   `docs/architecture/diagnostic-probe-contract.md`.
