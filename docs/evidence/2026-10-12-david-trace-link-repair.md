# David Trace Link integration repair — 12–13 October 2026

Outcome: **PARTIALLY COMPLETE — BLOCKED**. This is a reviewable local repair,
not a connected release approval or receiving-owner signature.

Starting source: `94950a35ea514672027242a19871f413a2120b10` (draft PR #99).
Branch: `repair/david-trace-link-e2e-20261012`.
Worktree: `C:/OrangeSystems/Program/david-trace-link-repair-20261012`.
Latest remote recheck: main `f7364d35c71ddf865c9f826c41bdf0aac37f473d`,
#99 still draft/open at the starting SHA; #95 last checked at
`e3d0a41da141f2a836edbd35258ba878efc335dd`. Independent #98
`6152d13fb5147c8d141d88133e2b7e45e6df4484` merged as that new main commit
during finalization. It was fetched and inspected, not imported into this
candidate. Its V008, explicit `volte.setup.success` definition, fifth quality
state and required provenance fields need reconciliation before publication.
Its implementation still does not subscribe to Kafka or release held outboxes.
The original dirty checkout, teammate branches, shared demo and volumes were
preserved. No push, merge, deployment, credential change or held-outbox release
was performed.

Verified implementation commit: `d85eb378d2b7b5c5009b0e668f9a5801ff91f32f`;
tree `6ad580b65e0ed57374aba5b748c27d8ccdd22f39`. It contains 56 intended
source/test/document changes (+4,373 / −37). Six generated branding files had
only checkout line-ending differences and therefore did not enter the commit.
The continuation snapshot preserves all 864 uncommitted files (including the
separate local Graphify output) at
`C:/OrangeSystems/Program/david-trace-repair-evidence/continuation-uncommitted-20261013.zip`.
Its SHA-256 is in the manifest. Graphify output remains untracked.
The subsequent evidence commit also pins evidence bytes in `.gitattributes` so
Windows checkout conversion cannot invalidate checkpoint/artifact hashes.

## Baseline defect inventory and disposition

The supplied review was treated as hypotheses. Source at the starting SHA,
current remote heads and executable checks determined the following results.

| Severity / owner | Exact source boundary | Actual baseline / expected behavior | Reproduction and regression | Compatible repair / status |
| --- | --- | --- | --- | --- |
| P0 / David + Denis | `simulator/controller/ScenarioController.java:start`, canonical `ScenarioRun` | Legacy enum/three-field command and UUID routes cannot carry Trace Link commands; legacy must remain compatible | Trace Link type/body cannot bind to the legacy route; separate PostgreSQL/MockMvc API tests plus unchanged legacy ScenarioControllerIT | Add protected `/api/trace-link/v1/scenarios` with durable public UUID → fingerprint mapping; **FIXED** locally |
| P0 / David + Ion | `generator/tracelink/TraceLinkScenarioEngine.java`, `TraceLinkScenarioApplication.java` | Pure scheduler/CLI lacks runtime service and durable service execution | Runtime test injected clock before release, duplicate ticks, stop at 0/3/6 minutes, restart at 0/3/6, completed replay and corrupt checkpoint | Add disabled-by-default sibling `generator/tracecontrol` adapter; retain pure package and its no-Spring/no-Kafka boundary test; **FIXED** locally |
| P0 / David + Ion | `TraceLinkScenarioCatalogue.validate`, `TraceLinkScenarioCommandCodec.read` | Five executable scenarios require rich versioned tuples; thirteen must reject forged commands | Actual producer validation iterates all eighteen definitions; existing boundary/scenario tests cover invalid scopes, versions, populations and parameters | API uses authoritative producer preflight; UI renders only five executable definitions and three domain-specific scope controls; **FIXED** locally |
| P0 / David | `features/scenario-runner/run.store.ts:start/refresh/stop/restore` | Late asynchronous results can cross session changes; browser cache can be displayed as authoritative run state | `david-trace-session-red.log`: one failing stale-session test before repair; same test passes afterward | Session/operation guards, session-end private-signal clearing and server revalidation of cached locators; **FIXED** |
| P0 / David | New `TraceRunStore.load/mutate`, component initialization effect | Browser test found history reload overwriting a just-accepted run | First desktop Chromium run lost the interrupted run after initially showing it; added pending-history vs accepted-command unit regression | Untracked initialization and mutation generation guard; terminal polling and independent history retained; **FIXED** |
| P1 / David | `TraceScenarioRunnerComponent` dynamic option rendering | Recreated form displayed the first option despite retaining a different scenario in state | Screenshot showed transport label with DNS fields; Chromium now asserts selected type after acceptance | Bind selected options to signal state; **FIXED** |
| P1 / David + Stanislav | Generated branding + Windows checkout | Raw CRLF checkout fails the existing byte-for-byte branding pretest | Initial npm test stopped at stale generated branding before any test executed | Run supported sync and pin generated asset/API bytes to LF, without changing branding content; **FIXED** |
| P0 / Sergiu | `processing/detection/TraceLinkInputs.java`, `docs/architecture/trace-link-live-evaluation-v1.md` | Approved offline policy expects aligned 300-second contexts; live 60-second policy, baselines and recovery decisions remain unapproved | Existing offline boundary/recovery/cause tests in complete reactor; current decision document explicitly PENDING_REVIEW | No timestamps, baselines, validators, thresholds or ground truth changed; **BLOCKED** on reviewed live policy and adapter |
| P0 / Denis + David | `V006__trace_link_read_models.sql:quality`, `MetricDefinitionRegistry`, primitive consumer PR #98 | This base candidate has VARCHAR(16)/four-state legacy quality and only `voice.success_rate`; producer populations/provenance differ | Newly merged #98 adds V008 immutable primitive ingestion, distinct `volte.setup.success`, fifth quality and required provenance fields; fetched source reviewed, not incorporated | Preserve applied migrations and explicit population identities; V009 remains separate; **BLOCKED** on candidate reconciliation and receiving/delivery acceptance |
| P0 / Ion + Denis + Stanislav | V016 held outbox, new-domain topic/key/source and consumers | No approved connected transport or idempotent receipt/ACK agreement for this repair | No new-domain Kafka activation attempted; runtime has no Kafka dependency and reports delivery disabled | Complete local generation/code checks, keep delivery off; **BLOCKED** on isolated approved delivery gates |
| P1 / David | `core/state/trace-query.store.ts`, `features/trace-link/` | No native Trace Link investigation context or API-backed active map | Store tests cover independent resolution, UTC, zoom/reset, URL and scope reset; controlled Chromium checks ten cities, twelve links, exact directional endpoints and stale response cancellation | Add protected native history route, backend inventory, shared immutable context and accessible topology/table; **FIXED** for approved read contract |
| P1 / David + Denis | `MetricSeriesController`, `TraceMetricChartComponent` | Planned CSV route lacked an implementation; native buckets need exact zero/null/quality rendering | PostgreSQL API test compares CSV primitives to JSON; browser downloads exact controlled server bytes and checks gaps, stale text, brush/reset and expanded view | Add server CSV using the same query/registry; retain exact table and independent visible bounds; **FIXED** for `voice.success_rate`, other mappings **BLOCKED** |
| P1 / David + Denis | `notifications/api/NotificationController.java`, `features/notifications/` | Durable recipient rows lacked list/read UI and implemented protected routes | Real PostgreSQL actor isolation, idempotent read and CSRF tests; unit startup/session/read-failure/bounds tests; browser keyboard/mobile/two-tab checks | Add recipient-private REST and bounded in-app history; **FIXED** for existing rows; automatic production remains **BLOCKED** |
| P1 / David | `core/state/incident-stream.ts` | Multiple consumers create duplicate transports; late consumers can miss interrupted state | Failing-before two-subscriber regression; final unit/browser checks sharing, reconnect, route continuity, late-state replay and session cleanup | One subscriber fanout per authenticated tab/session; **FIXED** |
| P1 / David | `NotificationStore` newest-page retention | Browsing more than 500 old records can evict a recent opening and toast it again | `david-trace-notification-retention-red.log`: one failure before repair; final unit suite passes | Keep separately bounded newest-page IDs alongside bounded history IDs; **FIXED** |
| P1 / David | `accessibility.spec.ts:keyboard` | Keyboard test can focus the loading hero before asynchronous KPI rows arrive, then inspect an empty tooltip | Completed full run recorded 171 passed / 1 failed / 24 skipped; existing expected tooltip values retained | Wait for a measured SVG path before keyboard inspection; focused seven and final full browser checks pass; **FIXED** test synchronization |
| P1 / Denis + Sergiu + Stanislav | Multi-domain mapping, automatic notification writer, Grafana, push, release rehearsal | Approved receiving fields, detector decisions, push config/dispatcher and verified Grafana deployment are absent from this candidate | No synthetic mapping, recipient eligibility, secure-origin or connected acceptance invented | **BLOCKED / NOT VERIFIED**; named receiving and infrastructure gates below |

## Implemented behavior

The public API preserves existing authentication and CSRF, limits commands to
enabled SUPERVISOR/ADMIN profiles, keeps run history private to the issuing
analyst, canonicalizes intentional retry bodies, rejects conflicting request
reuse and overlapping scope intervals, commits command and stop intent before
private execution, validates upstream identity/status/counts, and distinguishes
400/401/403/404/409/503.

The runtime uses the actual deterministic engine, an injected clock, the existing
scenario scheduler and exclusive durable checkpoint directory. Restarted
nonterminal runs become INTERRUPTED. There is no automatic resume. History,
payload bytes and fingerprints survive completion, stop and restart. Capacity is
128 retained runs; there is no pruning API or distributed execution claim.

The Angular runner renders link direction/endpoints, country/partner/direction
and LTE DNS controls from the runtime catalogue, uses producer-owned defaults,
converts decimal percentages exactly, validates missing recovery indices and
retains uncertain retry identity. It keeps concurrent independent runs and
terminal history, uses server lifecycle state, and labels local generation,
delivery disabled, unavailable persistence/acceptance, planned recovery and
technical recovery distinctly. The thirteen unsupported definitions cannot be
selected for execution. Legacy controls remain separately labelled.

The new protected `/trace-link` history route reads the versioned ten-city,
twelve-link inventory and current metric registry. Its UTC context preserves
period/resolution independence, visible-only brush and keyboard zoom/pan/reset,
expanded view, timezone and stable URL selections. Requests abort and reject
stale session/context replies. The exact bucket table preserves zero, null,
quality, coverage, primitives, partial edges and source identity; drawing gaps
remain gaps. CSV is produced by the same server query, with exact decimal text.
The approved registry currently supplies only `voice.success_rate` for CITY.
Other domain selections explicitly explain unavailable metric mappings. Existing
legacy service geography remains labelled and its historical contracts are retained.

Notifications read existing recipient-scoped V005 rows, with idempotent,
CSRF-protected mark-read. The bounded component store reconciles REST on stream
hints, suppresses startup/medium/recovery toasts, and coordinates high/critical
opening/escalation hints across same-origin tabs. One shared EventSource serves
notification and incident subscribers; session or final-consumer cleanup closes
it. Automatic durable notification production is not implemented by this repair.
Browser push remains unavailable without permission prompts or a service worker.

API version: `/api/trace-link/v1/scenarios`.
Migration: incidents-owned **V009**; V008 remains reserved by PR #98.
Generator/source versions: unchanged `trace-link-scenarios-v1`,
`trace-link-links-v1`, `trace-link-live-synthetic-v1` and original generator
descriptor. Detector version: unchanged offline policy, no live adapter enabled.
Flags: `TRACE_LINK_SCENARIOS_ENABLED=false`, `TRACE_LINK_RUNTIME_ENABLED=false`,
new-domain delivery disabled, held outbox unchanged.

## Verification evidence

Machine-readable final counts, skipped-test reasons, file hashes, command logs,
source identity and changed-file inventory are in
[`assets/david-trace-link-repair/verification.json`](assets/david-trace-link-repair/verification.json).
Counts from separate focused retries are not added to complete-suite totals.
The final code commit is recorded there; the subsequent evidence commit is the
tip of the local branch.

The complete root Java reactor completed 1,316 tests: 1,308 passed and eight
optional checks skipped. Incident-service separately completed 254: 253 passed
and one optional check skipped. The supplied full archive was then
explicitly selected: all 30 supplemental tests passed, including the four
previously skipped archive/frozen-migration gates. Supplemental totals overlap
the reactor and are not added to it. Import verified 362,880 native 300-second
rows and exact retry; projection verified 8,640 held CITY projections, 8,640
outbox intents, 8,640 native buckets and 121 UTC six-hour buckets, including
partial edges. Fourteen domain arithmetic cases matched PostgreSQL. None of
these checks released an outbox, subscribed Kafka or proved authenticated reads.

| Check | Actual final result | Acceptance boundary |
| --- | --- | --- |
| Root Maven `verify` | 1,308 passed, 8 skipped, 0 failures/errors | Streaming support 120, generator 147, processor 1,049 selected tests; mixed unit/component/PostgreSQL checks |
| Incident-service Maven `verify` | Surefire 172 passed / 1 skipped; Failsafe 81 passed | Actual disposable PostgreSQL, migrations/security; private generator gateway double |
| Explicit full archive / frozen V016 | 30 passed, 0 skipped | Real disposable PostgreSQL, original archive read-only; overlaps root suite |
| Contract checker and reference pins | PASS; 29 frozen inputs unchanged | Contract/arithmetic/identity checks, no connected admission |
| Python discovery | 158 passed / 1 skipped | `pwsh` wrapper check unavailable on this Windows PowerShell host |
| Dashboard unit suite | 156 passed in 29 files | Includes session races, query context, notification bounds and stream sharing |
| Production build / API generation / branding | PASS; 492.47 kB initial bundle; 1 branding test | Generated types deterministic, canonical/dashboard API bytes identical |
| Complete controlled Chromium suite | 172 passed / 24 skipped / 0 failed / 0 flaky; 217.780 s | Real browser, controlled HTTP/session/SSE; environment-dependent checks skipped |
| Actual local runtime artifact | PASS; three windows / nine observations retained after restart | Injected clock; INTERRUPTED, no replay, delivery disabled |
| Compose | Structure-only PASS | Generic process-only placeholders; no environment deployment |
| Connected end-to-end release | **BLOCKED / NOT VERIFIED** | Delivery, live policy, receiving and authenticated gates still required |

The final complete browser report, including each skipped case and its reason,
is [`browser-results.json`](assets/david-trace-link-repair/browser-results.json).
The earlier failed browser result and unchanged expectation are retained in
[`browser-regressions.json`](assets/david-trace-link-repair/browser-regressions.json).
That restricted run also stalled during runner cleanup; only its verified
disposable Angular server was stopped. The final run used normal test
permissions and exited successfully without that intervention. Some legacy
controlled fixtures do not stub the new optional notification read and log
connection-refused proxy errors; those views retain an unavailable/retry state.
This is not evidence that a real notification backend was connected.

Actual measured resource artifacts are `controlled-resource-baseline.json` and
`controlled-load-{overview,service,investigation}.json` in the same asset folder.
They retain request/read counts, frame gaps, long tasks, heap and action timing
for controlled loads. They do not certify coordinated live pipeline capacity.
The retained-history test measured median UI readiness 141.653 ms and p95
236.269 ms; its maximum-range sample measured 333.607 ms and 11,787,408 heap
bytes. These figures describe that controlled test configuration only.

The host uses Java 21 and cached Maven 3.9.16:

```powershell
$maven = 'C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd'
$cache = '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2'
& $maven -B -ntp $cache verify
& $maven -B -ntp $cache -f services/incident-service/pom.xml verify
python scripts/check-contracts.py
python -m unittest discover -s tests -v
npm.cmd --prefix apps/dashboard run generate:api
npm.cmd --prefix apps/dashboard test
npm.cmd --prefix apps/dashboard run build
npm.cmd --prefix apps/dashboard run test:branding
$env:E2E_PORT = '4291'
npm.cmd --prefix apps/dashboard run test:e2e -- --workers=2
docker compose config --quiet
```

The initial sandbox JUnit temp-directory errors were rerun with normal authorized
test permissions; they were not counted as passes. A pure-generator package
boundary test rejected placing Spring adapter classes in that package; the
adapter was moved to a sibling package without weakening the boundary test.
OpenAPI fragment-generation errors were corrected while preserving all original
path/schema definitions, then API generation and Python validation were rerun.
The additive migration inventory/upgrade expectations were updated and tested.

Compose without environment configuration failed for missing required variables.
A separate structure check used process-only disposable placeholders and passed;
it did not start containers or validate a deployed environment. Graphify's broken
launcher was bypassed with its installed Python module; AST update completed
(11,013 nodes, 27,966 edges). SQL extraction was unavailable because the optional
`tree_sitter_sql` dependency is absent; no complete SQL graph claim is made.

Chromium screenshots use controlled HTTP responses at 1366×900 and 390×900.
They are real-browser UI evidence, **not real OIDC login, connected telemetry or
incident acceptance**. The PostgreSQL API tests exercise real transactions and
security filters but use a controlled private-generator contract double. Actual
runtime tests independently exercise the engine/checkpoint path. The CLI sample
is unchanged local synthetic producer output, never Kafka delivery.

Screenshots: [scenario desktop](assets/david-trace-link-repair/trace-scenarios-1366.png),
[scenario mobile](assets/david-trace-link-repair/trace-scenarios-390.png),
[history desktop](assets/david-trace-link-repair/trace-history-1366.png) and
[history mobile](assets/david-trace-link-repair/trace-history-390.png). These were
visually inspected for layout and explicit unavailable states. Exact bytes are
hashed in the manifest. The runtime artifact includes three immutable checkpoint
generations, retained restart response and `RuntimeEvidence.java`; it uses an
injected clock and never opens Kafka or a database connection. A first artifact
attempt submitted exactly at the scheduled start correctly retained an empty
INTERRUPTED history; the verified demonstration submits one second before start.

## Connected integration and handoff

| Layer | Result and practical limit |
| --- | --- |
| Angular scenario controls | UNIT / CONTROLLED BROWSER verification; private API commands typed from canonical OpenAPI |
| Angular native inventory/history/CSV | UNIT / CONTROLLED BROWSER verification; approved CITY read contract only; no fabricated new-domain panels |
| Notification REST / UI / shared SSE | PostgreSQL recipient/read security and controlled browser verification; automatic notification writer remains a separate gate |
| Protected incident-service API / ledger | PostgreSQL/security component integration; public UUID/fingerprint and stop/retry tested |
| Generator runtime | Actual local engine/checkpoint component tests; enabled only in isolated tests |
| Kafka delivery | **BLOCKED / DISABLED**, no held intent released |
| Processor live admission | **BLOCKED**, no approved minute-domain consumer path |
| Detector | Offline regressions only; **live policy PENDING_REVIEW** |
| PostgreSQL new-domain metric/incident read models | Separate receiving consumer work remains unaccepted in this candidate |
| Authenticated connected API/browser rehearsal | **NOT VERIFIED / BLOCKED**; no deployment or owner sign-off |
| Grafana, capacity, push and two release rehearsals | **NOT VERIFIED** by this repair |

Next required actions: Sergiu records an approved native-minute policy/baseline,
identity and recovery contract; Denis validates the current consumer mapping and
read semantics without population aliases; Ion/Stanislav verify approved
topic/key/source, durable idempotent receipts and rollback in an isolated
connected environment. Denis must also provide approved automatic notification
production, recipient eligibility and push configuration/subscription/dispatcher
contracts. Stanislav must supply a verified Grafana destination/context and
secure-origin test environment. David can then bind the remaining multi-domain
and new-incident fields, implement approved optional push/Grafana behavior and
run authenticated browser acceptance on one frozen source manifest. Human
accessibility, capacity and two independent rehearsals remain unverified; no
owner signature is implied.

Rollback and operator interpretation are documented in
[`trace-link-scenario-runtime.md`](../runbooks/trace-link-scenario-runtime.md).
Disable the two new flags while retaining V009 rows and checkpoint generations.
Never delete history, downgrade applied migrations or reset shared volumes.
