# Trace Link Day 4 producer scenario evidence

Implementation and local verification ran **12 October 2026, Europe/Chisinau**.
This artifact uses the requested Day 4 plan date, **13 October**. It does not
assert a future rehearsal or downstream acceptance.

Source commit: `85552dbc4d07dc1d0f29012147a4cd6e2db97213`; tree:
`e96b50349a5cfc5461e5b32fd23072ab067aa3b0`; branch:
`feature/ion-tracelink-scoped-scenarios`. Subsequent evidence commits change no
implementation. Base is PR #93's reviewed
`e9a79c1f2fa2c5a59b366336e74b4e3b2b94bbb8`, branch
`feature/ion-tracelink-domain-producers`, stacked on #91. No dependency on #92
was introduced. #89/#90/#91/#92/#93 branches were not modified.

## Baseline and implementation boundary

Read `AGENTS.md`, `C:/OrangeSystems/task/trace-link-2026-10-10/00-common.md`,
`01-zavtoni-ion.md`, the Day 1 domain contracts, producer runbook, current
validator/schema/catalogues, legacy Voice/SMS generators, GenerationContext,
ScenarioExecutionService and the native primitive builder. Queried the existing
Graphify graph before source review. The original checkout's modified branding,
scripts and untracked work were preserved; the new worktree started clean.

PR #93's HEAD matched the requested SHA. Initially three workflows passed and
service-integration was running; before publication all four completed
successfully. Exact protected branch heads are recorded in
[verification.json](assets/trace-link-scenarios/verification.json).

The current domain schema already supports both 60 and 300 seconds. Reused
`DomainObservationValidator` and `TraceLinkCatalog` without weakening or editing
their historical contracts. The native builder deliberately remains separate:
it requires actual 300-second archive source rows. Legacy runtime topology and
GenerationContext retain V2 authority and geographic activation rules; the new
synthetic source resolver grants neither. No public API, Kafka transport,
consumer, migration, projection table, held outbox or production topic changed.

## Implemented behavior and limits

The catalogue defines all eighteen requested public symptom types with versions,
domain/scope sets, parameter bounds, timing, expected primitive changes and
control semantics. An accepted command binds seed, exact scope/topology, start,
parameters and definition into its deterministic identity. **Only five execute**:
TRANSPORT_LINK_OVERLOAD, ROAMING_REGISTRATION_FAILURE, MOBILE_DATA_DNS_FAILURE,
NORMAL_CONTROL and TELEMETRY_GAP. All other thirteen types explicitly fail as
UNSUPPORTED; their intended primitive requirements are documented in the
catalogue. Legacy VoLTE/SMS types and callers retain their existing behavior.

The [runbook](../runbooks/trace-link-scenarios.md) includes the supported matrix,
all unsupported types, CLI, parameters and restart procedure. Default seed **42**
and UTC start `2026-10-13T09:00:00Z` produce eight aligned half-open one-minute
windows: two normal, three degraded, three recovery. Control profiles and explicit
missing-minute masks do not manufacture measured recovery.

Dataset `trace-link-live-synthetic-v1`, source
`trace-link-minute-generator-v1-seed-42`, **60 seconds**, is distinct from native
dataset `trace-link-md-30d-v1` and source `trace-link-native-primitives-v1`,
**300 seconds**. No history is read, divided, overwritten or relabeled as live.
Scope namespace `trace-link-reference-v1` identifies the reference inventory,
not activation of the legacy geographic model. Both independently generated
link directions are possible here; this does not invent reverse archive rows.

Transport generates carried traffic, constant positive effective capacity and
valid-time utilization. It supplies no packet counts, RTT/jitter, member-state
timeline or customer-outage label. Roaming generates separate partner/country/
direction registration populations; it does not merge partners or infer users.
LTE generates independent DNS and EPS bearer activation populations. It does
not infer throughput, cross-population user linkage or real operator eligibility.
Zero attempts yield INSUFFICIENT_EVIDENCE and a null computed success rate;
missing yields null primitives and zero coverage. Measured zero remains distinct.

## Expected and actual measurements

All 144 checked-in domain measurements and fresh Java exports pass independent
Python arithmetic and schema/identity validation. Exact primitives, actuals,
units, quality and event IDs are in
[measured-values.json](assets/trace-link-scenarios/measured-values.json).

| Scenario / metric | Expected model | Actual minute 1 / 3 / 6 |
| --- | --- | --- |
| Transport traffic | capacity 20 Gbit/s times generated utilization | 9.938 / 19.568 / 9.960 Gbit/s |
| Transport capacity | unchanged positive valid-time capacity | 20 / 20 / 20 Gbit/s |
| Transport utilization | NORMAL/RECOVERY 49.5..50%; DEGRADED 97.5..98%; weighted sum / 60 | 49.69 / 97.84 / 49.80% |
| Roaming registration | floor(attempts * 0.99), then *0.70, then *0.99 | 1001/1012; 721/1031; 1014/1025 |
| LTE DNS | independent floor(attempts * 0.99 / 0.70 / 0.99) | 1031/1042; 703/1005; 1007/1018 |
| LTE activation | independent floor(attempts * 0.99) throughout | 1018/1029; 1003/1014; 1017/1028 |

Counters are generated one-minute synthetic primitives, not inferred by
subdividing historical percentages. Numerators never exceed their denominators.
Rate arithmetic uses sums/denominators, not an average of percentages.

Controls verify healthy/high-utilization Transport without an outage label,
unaffected opposite direction, missing recovery, independent roaming in/out,
wrong partner rejection, zero attempts, DNS/activation byte independence, other
city identities, and missing fault/recovery measurements. Wrong-city,
nonoverlapping and stale link evidence fail the evaluator's comparability
checks. No positive comparability result is claimed to prove a VoLTE cause.

## Scheduling, replay and evaluator isolation

`TraceLinkScenarioEngine` is a synchronized bounded pure scheduler using an
injected clock, without background threads/queues. Two concurrent distinct
scopes retain independently reproducible output. A racing same-scope start
accepts one command and rejects the incompatible overlap. Adjacent half-open
intervals and opposite directions remain independent. Complete/stopped/
interrupted reservations are retained. Default runId is a SHA-256 command
fingerprint; exact replay is idempotent and parameter reuse conflicts.

Each snapshot retains all eight reproducible window identities. `advance()`
releases a completed minute exactly once. Stop preserves history and emits no
recovery. Restored unfinished executions and unknown past starts become
INTERRUPTED and release nothing. Checkpoint storage belongs to the caller;
crash-safe publication/acknowledgement tracking is not claimed by this pure
engine. At 128 retained runs it fails closed instead of evicting history.

The packaged CLI produced 24 Transport observations, then returned EXACT_REPLAY
with identical bytes. RunId:
`e89e10e4fa375aaf4a7692753cfba5fb1d7182cc41d5ed70f228dae5381b6ded`.
Artifact SHA-256:
`6445a3d8769d5bf79459681448a4564d36994529f0ada288ec0f00c2b971f151`.
Its default jar Start-Class remains `EventGeneratorApplication`; the isolated
compiler is selected explicitly with PropertiesLauncher. Atomic no-clobber
artifact creation rejects changed content without replacement.

Evaluator measurements and expected transitions live separately under
`tests/evaluation/trace-link/`. The fixture covers OPEN candidate, equal-severity
update, escalation, UNKNOWN, technical recovery, duplicate replay and a later
new-episode candidate. A severe minute is composed only inside the evaluator
fixture; it is not an overlapping runnable scenario command. All twelve
transition expectations remain **PENDING**. No actual new-domain detector,
incident, notification or accuracy result is invented. Technical RECOVERED is
distinct from analyst RESOLVED.

Leakage tests reject expected labels, injected causes, future outcomes, truth
IDs/timelines, evaluation results, run IDs and feature/projection wrappers at the
observation and primitive boundaries, before creating an artifact. Publishable
JSONL contains only strict domain observations. No new persisted projection
message or detector feature path exists; PR #92's held outbox is outside this
branch and unchanged.

## Verification results

Exact commands, suite names, skip reasons, source/artifact identities and external
log hashes are in [verification.json](assets/trace-link-scenarios/verification.json).
Cached Maven 3.9.16/Java 21 were used, with quoted local-repository properties.
Docker tests used disposable resources; existing demos/data/volumes were not reset.

| Check | Actual result |
| --- | --- |
| Pre-edit baseline | PASS: streaming-support 117 + event-generator 95; no skips/failures/errors |
| Transport first | PASS: 3 tests |
| Transport + Roaming | PASS: 6 tests |
| Three domain slices | PASS: 9 tests |
| Final new scenario/source tests | PASS: 32 tests, included in affected-module run |
| Complete Java reactor | PASS: 1,231 reported; 1,224 passed; 7 skipped; no failures/errors |
| Final affected modules after validation/fixture refinements | PASS: streaming-support 120 + event-generator 124 = 244; no skips/failures/errors |
| Final distinct suite reports | 1,232 reported; 1,225 passed; 7 skipped; combines the final affected reports with unchanged processor's 988 |
| Python contracts and fixtures | PASS: 119 tests, no skips/failures/errors |
| Independent scenario arithmetic | PASS: 144 minute measurements; 12 evaluator expectations remain PENDING |
| Legacy persisted VoLTE/SMS parity | PASS: 7 + 12 cases, all fields, maximum difference 0 |
| Trace Link Java/Python/JavaScript/PostgreSQL arithmetic | PASS: 54 cases per language, maximum difference 0 |
| External reference engine | PASS: 708,498 checks |
| Reference adapter compatibility | PASS: 75 checks |
| Packaged CLI + exact artifact replay | PASS: 8 windows / 24 observations; default legacy entrypoint retained |
| Diff checks and AST Graphify update | PASS; graph retained locally, not added to PR |

The full reactor preceded final command/scope validation and fixture assertions;
the final affected-module verify/packages run covers every changed Java module at
the source SHA above. Processor production/test sources remain identical to the
reviewed base. The final distinct suite total is not an additional single reactor
run. Existing ReplayIT, FinalizerRaceIT, DependencyOutageIT, geographic controls,
legacy scenario runner and VoLTE/SMS tests passed as part of those regressions.

Seven inherited optional skips: three packaged ML HTTP tests without
`ML_SERVICE_URL`; SMS shadow replay without `SMS_SHADOW_REPLAY_DIR`; full archive
bootstrap without `traceLink.referenceRoot`; native producer full-archive parity
without `trace.link.archive`; frozen V016 coexistence without
`trace.link.v016.directory`. Full archive/V016 verification is recorded in PR #93;
it was not repeated or counted as passed here. This branch adds no V016 dependency.

Failed attempts are retained separately: sandboxed Python discovery could not
read cached dependencies (6 failures/12 import errors), sandboxed JUnit could
not clean temporary folders (7 cleanup errors), and a new fixture assertion
initially compared Jackson integer/long node types directly (1 failure). Host
reruns and the corrected wire-JSON assertion passed. The Graphify executable's
update launcher failed; its installed Python module completed AST update without
API calls. These failures are not hidden or counted as passing attempts.

## Prepared handoffs and remaining gates

| Receiver | Prepared information | Remaining acceptance |
| --- | --- | --- |
| Sergiu | Separate measurement/truth fixtures, primitives, independent controls, UNKNOWN/recovery/escalation expectations | New-domain deterministic detector/transition acceptance PENDING; no accuracy claim |
| Denis | Existing domain envelope, isolated source resolver, namespace/identity rules, six metric populations | Consumer mapping and compatible transport PENDING_REVIEW; no activation or #89/#90 changes |
| David | Eighteen-type catalogue, five implemented choices, strict scope/parameter bounds, runId/status/stop rules | UI/public scenario integration NOT VERIFIED |
| Stanislav | Explicit offline launcher, bounded pure scheduler, safe replay/restart, source descriptor, no-clobber artifacts | Kafka/runtime activation and connected operational checks PENDING_REVIEW |

These handoffs are documentation only. No messages were sent and no receiving
owner reproduction or approval is claimed. Producer scenario implementation is
verified; connected incident generation is **BLOCKED** on approved integration,
authenticated API/UI is **NOT VERIFIED**, Kafka is **DISABLED/PENDING_REVIEW**,
and release acceptance remains **BLOCKED**. No merge, auto-merge, history rewrite,
production topic creation or frozen outbox activation is part of this milestone.
