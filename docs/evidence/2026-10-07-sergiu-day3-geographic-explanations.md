# Sergiu Day 3 — explainable geographic incidents

Branch: `feature/sergiu-day3-geographic-explanations`; base `6800e94`; implementation SHA `a119bec`.
Source: Rusu Serghei Day 3 in the October five-day planning pack. Three workers implemented
episode/replay tests, geographic explanation goldens and queue/display handoffs; the coordinator integrated and verified.

## Result

All twenty scopes have pinned path/evidence, independently calculated identity, delayed timestamp,
serialized replay, interrupted recovery and cross-city isolation coverage. UNKNOWN retains severity
and impact from distinct original windows and receipts. Two PostgreSQL integration cases recreate
a worker after the first breach and prove duplicate ingestion/finalization/evaluation leaves persisted
IDs, payloads, timestamps, jobs and ML calls unchanged while unrelated episodes remain independent.

Two complete city goldens extend the strict fixture collection; the twelve legacy trajectories remain
unchanged. All-city controls cover wrong city/time/event/quality, invalid node/reporter/schema/version,
partial coverage, absent baseline, contradictory access and SMS backlog without service samples.
The offline checker selects authority by saved feature topology and rejects unknown versions, wrong
reporters and wrong saved authority. Neither schema validation nor equality assertions were weakened.

No production detector changes were needed. Policies, feature order, baselines, packaged models,
manifest and calibrations are byte-identical to the base. No new endpoint, migration, retraining,
power correlation or analyst workflow was introduced.

## Verification

| Check | Actual result |
| --- | --- |
| Java reactor suites | 761 reported; 757 executed; zero remaining failures/errors; four existing opt-in skips |
| GeographicExplanationTest | 121 passed |
| GeographicReplayTest | 2 passed |
| ExplanationCasesTest | 64 passed, including 14 complete trajectories |
| Root Python / ML Python | 49 / 54 passed |
| Contracts / Node syntax / whitespace | Passed |
| Live receipt/KPI/coverage/protected API | 80 matched normal points across twenty scopes |
| Authenticated city fault dispatch | Blocked: HTTP 400 INVALID_SCOPE |

The full `clean verify` run found one SMS golden representation mismatch (integer p95 versus decimal
builder output); all other tests passed. After correcting the fixture without changing numeric meaning
or equality checks, ExplanationCasesTest was rerun through reactor `verify`, which passed and packaged
the services. Counts aggregate the final reports; the initial clean command did not pass unchanged.
The four opt-in skips require geographic HTTP scoring, existing live voice/SMS model integrations or
SMS shadow replay prerequisites. Python models passed; skipped methods establish no live model gate.

## Connected evidence and dependencies

The isolated `telecom-day3-validation` project used separate databases/task credentials, backend port
18082 and a task-only proxy, preserving the user `.env` and existing application container. Eighty
normal API points matched actual receipt IDs, evaluated features, published KPI/coverage facts,
versions, exact counters, signed VoLTE deviation and SMS delay ratio. Real OIDC login passed with
zero browser page errors. The [dashboard capture](2026-10-07-sergiu-day3-dashboard.png) was inspected.
Task services were stopped; retained data was not deleted.

Authenticated POST `/api/simulator/scenarios/VOLTE_IMS_OVERLOAD` for `VOLTE-MD-CHI` returned
INVALID_SCOPE. Public ScenarioCommandService and private ScenarioExecutionService both retain
legacy-only targeting. Ion and Denis must integrate city targeting in both layers before fault/control/gap
traces can run. This work does not bypass their ownership or substitute fixtures for live acceptance.

The [handoff](../runbooks/sergiu-day3-explanation-handoff.md) specifies queue/display examples and
identifies missing priority implementation, incorrect retained-impact origin projection and required
SMS ratio display. Denis owns projection/priority; David owns UI. Mixed-service ordering is a proposed
total order requiring Denis review. No messages or fabricated owner sign-offs were sent. Shared G3 is pending.

## Reproduction

With JDK 21 selected and Docker running:

```powershell
./mvnw.cmd -q clean verify
.venv/Scripts/python.exe scripts/check-contracts.py
.venv/Scripts/python.exe -m unittest discover -s tests -v
.venv/Scripts/python.exe -m unittest discover -s services/ml-service/tests -v
node --check scripts/day3-geographic-live.cjs
```

Against a disposable stack: `node scripts/day3-geographic-live.cjs <private-env-file> <output-dir>`.
Supply DAY3_USERNAME/DAY3_PASSWORD privately; optional DAY3_BASE_URL defaults to http://telecom.test:8080.
The runner captures normal history before recording the city-scenario blocker. Completed-scenario
assertions do not independently prove receipt provenance or numeric map/queue/detail parity; owner review is required.

The [manifest](2026-10-07-sergiu-day3-manifest.json) records source/invariant hashes, suite counts,
eighty live rows, versions, references and pending handoffs. Temporary credentials/logs remain
untracked in tmp; public artifacts contain no passwords, session cookies or tokens. Cross-agent
review covered correctness, readability, architecture, security and performance; improvements were applied.

Rollback reverts test/tooling/documentation commits. No new runtime schema or detector behavior
requires rollback. Retain compatible geographic readers while city work remains queued; do not downgrade blindly.
