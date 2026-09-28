# Day 10 private scenario handoff

- Date: 2026-09-28
- Owner: Ion Zavtoni (streaming, simulator, service KPI processing)
- Branch: `feature/durable-scenario-handoff`
- Base: `8fa0c34857aab61cdfc3aa51492539407ffeb9d7` (`origin/main` after fetch)
- Implementation commit: `1877c15af30c47d7aa1568de95c5c903952c8873`

## Boundary

Contract: `contracts/openapi/generator-internal.yaml`.

| Method | Private route | Result |
| --- | --- | --- |
| PUT | `/internal/scenario-runs/{runId}` | 202; first delivery or exact retry |
| GET | `/internal/scenario-runs/{runId}` | 200 with schedule, status, acknowledged window count and safe failure code; 404 `RUN_NOT_FOUND` |
| POST | `/internal/scenario-runs/{runId}/stop` | 200 `STOPPED`, including repeat; 409 for `COMPLETED` or `FAILED` |

The command body carries `scenarioType`, `scopeId`, nonnegative `seed`,
`scheduledStartAt`, and `scheduledEndAt`. The path UUID is the execution identity.
The generator has no analyst identity, request ID, body hash, CSRF, or
`incidents_db` access. Compose exposes port 8081 only to the internal network;
NGINX has no generator route.

START validates an aligned UTC minute, an end exactly eight minutes later, the
four declared scenario types, and the service/scope pairing from canonical
topology. An unknown new run at or after its scheduled start gets 409
`SCHEDULE_ALREADY_STARTED`. Within one process, the synchronized check/create
operation accepts one body per `runId`. An exact retry returns the current
execution without scheduling or publishing again. A conflicting retry gets
409 `RUN_CONFLICT` without changing the accepted run.

## Profiles and state

VoLTE overload emits two healthy, three overloaded, then three healthy recovery
minutes. During overload it uses the tracked worked-case measurements: 1,000
eligible attempts, 940 technical successes, 60 technical failures, 55 SIP 503
responses, high IMS CPU, and healthy radio/bearer counters. The older G1
`VoiceScenario.generate(start, seed)` retains its missing-minute regression
profile; it is not used for `VOLTE_IMS_OVERLOAD`.

SMS delay reuses the existing seeded `SmsQueueScenario` with a 2/3/3
normal/slow/recovery profile. `NORMAL_CONTROL` uses the same scheduled Kafka
path and healthy profile for either canonical service. `TELEMETRY_GAP` emits
explicit `MISSING` service observations without metrics for minutes 2-4;
healthy node evidence continues. No fake zero service measurements are sent.
Observation IDs remain derived from source, scope, kind, and window start;
neither seed nor run ID enters event identity or payload.

The single-thread task scheduler runs a start transition and one publication
task at each completed minute. Each window has two observations. Kafka sends
use the existing `KafkaTemplate<String, String>`, scope key, and
`telecom.observations.v2` topic. `COMPLETED` requires acknowledgement of all
16 sends. A send failure or timeout sets `FAILED`, records only
`PUBLISH_FAILED`, and cancels later tasks. `publishedWindows` counts fully
acknowledged windows; a failed window may have a partially sent pair.
STOP takes the per-run lock used for publication, waits for any in-flight
send, and prevents future windows. Already acknowledged observations remain.
STOP never creates recovery traffic and cannot relabel `COMPLETED` or `FAILED`.

## Restart reconciliation

Execution memory is deliberately process-local. After restart, GET for a
previously dispatched run returns 404 `RUN_NOT_FOUND`. Denis's durable
`ScenarioCommand` ledger must reconcile that result: if the persisted start
is still in the future, redeliver the identical PUT; if the start has passed,
mark the public run `FAILED` as interrupted. The generator rejects a new PUT
at or after start with 409 `SCHEDULE_ALREADY_STARTED`, so a run that may have
published cannot replay minute zero. This is a single-generator-process MVP;
it is not a multi-replica execution lock or a durable generator ledger.

## Verification

- `mvn test -pl services/event-generator -am` with installed Maven 3.9.16:
  PASS, 92 Java tests (34 event-generator, 58 streaming-support). Focused
  Day 10 tests included 13 execution service, 2 HTTP controller, 3 voice,
  and 12 SMS tests. A focused voice test was rerun after its final assertion.
- `.\.venv\Scripts\python.exe -B scripts/check-contracts.py`: PASS;
  12 observation fixtures, 4 detection payloads, 7 voice and 8 SMS parity cases.
- `.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v`:
  PASS, 17 tests, including private OpenAPI validation.
- `git diff --check` and staged diff check: PASS.
- Graphify code-only refresh: 2,959 graph nodes. Extracted one
  `ScenarioController` and one `ScenarioExecutionService`, with direct
  controller-to-service, service-to-voice/SMS, and service-to-KafkaTemplate
  paths. No generator source dependency on incident-service, processor,
  dashboard, or a database was found.

The prewritten public verification clients had a three-minute timeout, and
the browser fixture used an unknown random scope. The companion verification
commit raises the waits for an eight-minute run and uses
`VOLTE-MD-CENTRAL`. The public dispatcher has not been implemented on this
main branch, so no live public-to-generator integration or G2 claim is made.
The prewritten browser spec also resides outside the dashboard Playwright
`testDir`; it was not executed as a live test.

## Handoff

Denis: dispatch only a persisted command; send the exact server schedule and
execution fields above; retry identical PUT before the scheduled start; map
409 `RUN_CONFLICT` to a conflict; reconcile 404 `RUN_NOT_FOUND` and 409
`SCHEDULE_ALREADY_STARTED` against the durable ledger as described. Project
generator `SCHEDULED`, `RUNNING`, `COMPLETED`, `STOPPED`, and `FAILED` into the
public run status. Preserve `PUBLISH_FAILED` as failure, not completion.

David: the generator returns the server's `scheduledStartAt` and
`scheduledEndAt` unchanged and reports those same values on STATUS and STOP.
The public UI remains owned by the incident service and dashboard.

Status: Day 10 private boundary ready for independent review. Denis's public
dispatch/reconciliation client and live integrated acceptance remain pending.
