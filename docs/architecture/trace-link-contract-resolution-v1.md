# Trace Link receiving contract resolution

Prepared 13 October 2026, Europe/Chisinau. Semantic owner decisions remain **PENDING_REVIEW**.
This implements an isolated, executable integration path. It does not approve deployment,
activate Kafka, release V016 intent, or alter the frozen offline rules or legacy episodes.

The base is PR #101, `3468ed908bebbe0eae7ced93ef46d56e412c9655`, which already contains
merged #98, candidate #99 and repair #100. Refreshed main is
`f7364d35c71ddf865c9f826c41bdf0aac37f473d`. Exact #93–#101 heads, reviews and checks
are archived in `docs/evidence/assets/trace-link-contract-resolution-20261013/pr-*.json`.
Prior documents describe historical boundaries; this document records the additive implementation.

## Executable paths

| Path | Source | Admission and execution | Receiving representation |
| --- | --- | --- | --- |
| Existing VoLTE | `trace-link-primitive-projection-v1`, 300 seconds | Unchanged `TracePrimitiveProjection` and `TracePrimitiveIngestionService` | V008, existing `volte.setup.success` queries; `voice.success_rate` remains a different population |
| Existing SMS/VoLTE V2 | Existing V2 source and feature contracts | Unchanged validators, workers, rules and episodes | Existing APIs and tables |
| Native new domains | `trace-link-domain-primitive-result-v1`, 300 seconds | `TraceLinkMeasurement.wrap/parse`, original domain validator, dictionary and result qualification checks | New measurement v1, V010, native queries; computed values remain insufficient evidence when source metadata is unknown |
| Live synthetic new domains | Unchanged `trace-link-domain-window-v1`, 60 seconds | `TraceLinkMeasurement.wrap/parse` and `TraceLinkLiveContract.inputs`, then `TraceLinkLiveDetector` | New result v1 and measurement v1, V010, native queries; no offline context is manufactured |
| Offline detection | Existing `trace-link-evaluation-v1`, native 300-second evidence | Unchanged `TraceLinkInputs`, seasonal registry, domain rules, cause and recovery policies | Existing offline result and episode identities |

`contracts/kpi/trace-link-domain-mapping-v1.json` is the machine-readable mapping.
It copies the dictionary's exact eleven metric definitions, populations, units, operators,
primitive fields and native limitations. It defines all five domain translations explicitly.
No metric is renamed. `VOLTE`, `TRANSPORT`, `ROAMING`, `MOBILE_DATA`, `SMS` map to
`volte`, `transport`, `roaming`, `mobile-data`, `sms` at the receiving boundary.
Only the three new domains enter the additive table; legacy behavior stays in its existing path.

## Source bytes, scope and arithmetic

The measurement v1 wrapper stores the **unchanged original bytes** as Base64 plus SHA-256.
It carries an explicit source kind, mapping version, manifest hash, generator and seed.
The original event ID is recomputed by the existing validator; revision zero must be finalized.
Native original natural ID/file/row/hash and qualifications remain in the source payload and
stored provenance. Native dataset manifests are checked against explicit administrative source
registrations; live manifest bytes are pinned to the unchanged LF Git blob. No registration is
seeded automatically. Runtime cannot register its own source or scope authority.

Scope hashes use a fixed field order over the complete native scope. Transport retains link and
direction. Roaming retains partner, country, in/out direction and procedure. LTE retains reference
city, explicit contract city, LTE access and procedure. A source/complete-scope registration must
match the payload. Queries reject metric/domain/procedure mismatches. No country substitutes for
a partner, no opposite direction is inferred, and no link is converted to a city.
The live topology remains `trace-link-links-v1`; no equivalence to the offline
`trace-link-md-topology-v1` is asserted or needed for this separate minute path.

Ratios use `100 * numerator / denominator`; zero denominator returns null. Means retain sum/count;
time-weighted metrics retain weightedSum/actual validSeconds; snapshots retain value/snapshotAt.
The new query is native-only and performs no rollup. Existing native rollups and pooled histogram
arithmetic remain unchanged and retain their existing regression suites. Histograms are not among
the eleven metrics admitted by this receiving version. An unsupported operator is rejected.
Native throughput remains a modeled city-slot value with count=1, never a user/session-weighted mean.

Native row presence is not source coverage. V010 stores slot coverage separately from nullable
source quality/coverage. Observed native values retain `INSUFFICIENT_EVIDENCE`; missing slots retain
MISSING and null primitives/value. A measured zero, an absent row, retained stale evidence and
an invalid payload have different outcomes. Invalid inputs throw and are not stored as measurements.
The original source envelope is available in every nonempty query point.

## Minute policy and baseline proposal

`trace-link-live-evaluation-v2` deliberately differs from the prior unimplemented v1 proposal.
It admits one primary population and up to three same-scope, same-source, same-minute observations.
Transport can include its three measured rate/capacity/utilization fields. DNS and activation
are evaluated independently, and roaming procedures/directions cannot be mixed.
Receipt time, evaluation time and source emission remain distinct. The caller supplies an explicit
clock; admission requires source end <= emission <= receipt <= evaluation <= clock.
Archived fixtures use an explicit logical replay clock; this is not wall-clock freshness acceptance.

| Decision | Separate minute demonstration default |
| --- | --- |
| Version/activation | `trace-link-live-synthetic-rules-v1`, isolated synthetic only; owner PENDING_REVIEW |
| Window | Exact aligned 60 seconds; no padding, interpolation, duplication or five-minute adapter |
| Minimum evidence | VALID, coverage=1, computable primitives; full actual 60 valid seconds for time-weighted inputs; at least 100 ratio attempts |
| Freshness | At most 120 seconds from the complete interval end; retained capacity snapshot is within its declared source interval |
| Baseline | Explicit `STATIC_SYNTHETIC_ONLY`, center=99%; `trace-link-live-static-baseline-v1`; otherwise UNAVAILABLE/UNKNOWN |
| Rate trigger/hysteresis | Drop >=5 percentage points breaches; drop <=1 is healthy; intermediate values are GRAY |
| Severity | Drop >=20 percentage points is CRITICAL, otherwise HIGH; active severity does not de-escalate |
| Opening/recovery | Two contiguous breaches open; three actual contiguous healthy observations recover |
| Gaps/partial/missing/stale/insufficient | UNKNOWN interrupts healthy/breach streaks; active episode stays open/UNKNOWN; no timeout recovery |
| Transport | >=85% with matching rate/capacity inputs is CANDIDATE; no outage or cause from utilization alone |
| Attribution | UNDETERMINED: live source v1 supplies no independent impairment/dependency measurements |

These are explicit synthetic demonstration choices for review, not inherited seasonal thresholds or
statistical calibration. The static center is configuration, not an estimator fitted to scenario labels
or future windows. No training, ground-truth field, peer fallback or five-minute baseline can enter the
new input schema. Existing offline policy, baseline versions and thresholds are unchanged.
The native unknown-quality arithmetic is not silently promoted into approved offline evidence.

The result references each original event ID and payload hash and contains the exact admitted input.
Its stable detection key includes policy/baseline, dataset/source, complete scope, primary metric and
source minute/revision. Receipt/evaluation changes do not create another logical ID; changed content
under that ID conflicts. The lifecycle has its own `trace-link-live-episode-v1` identity, based on stream
and first breached minute. State is caller-owned and serializable. It is not a running worker or an
incident-service legacy lifecycle. Consumer admission independently checks result claims against the
versioned policy, actual source values, eligibility and identity before persistence.

## Storage, query and delivery boundaries

V010 adds source registrations, complete scope registrations, immutable measurements and immutable
evaluation results. It preserves V001–V009 and all processor migrations. Runtime has SELECT on authority
and SELECT/INSERT on measurements/results, with no UPDATE/DELETE/TRUNCATE rights. Conflicting finalized
payloads and natural slots fail closed. Concurrent identical retries return one INSERTED and one
IDENTICAL_RETRY. Measurement and result writes are transactional; partial failures roll back.

`TraceDomainQueryService` accepts exact native alignment, at most 30 days and 8640 source points.
It distinguishes an absent point from an explicit missing observation and returns complete source
provenance. It never averages percentages, p95 values or snapshots. The additive authenticated GET
`/api/v2/trace-domain-measurements` uses existing session/role/enabled-analyst protection and is disabled
by default (`TRACE_DOMAIN_READ_MODEL_ENABLED=false`). OpenAPI and dashboard generated types describe it.
Existing series APIs, metric definitions, security, CSRF and legacy UI flows remain compatible.
MockMvc authentication is component evidence, not real login/browser acceptance.

The shared `streaming-support:contracts` classifier contains pure validators and contracts, excluding
Spring runtime configurations. Incident-service excludes transitive Spring 3 dependencies. Its Docker
build and CI install this library before the Spring 4 application. Runtime image verification must
exercise this packaging boundary; a successful compile alone is insufficient.

No Kafka listener/publisher is added. New-domain delivery and the held V016 outbox remain disabled.
This work does not establish a topic/key/partition/ACK agreement, new-domain Kafka restart delivery,
connected incident lifecycle, real authenticated browser acceptance, capacity or release approval.

## Owner decision records and handoffs

PR #102 now retains PR #101's quality-state and modal-focus fixes from
`eab953e9af90ddf5d14cbda57a6935994c2cefb1`. The reviewed proposal below is unchanged;
the [reconciliation evidence](../evidence/2026-10-13-pr102-dashboard-reconciliation.md)
pins the renewed tests and the accepted-history keyboard regression. This adds no source authority,
delivery activation or human approval. Sergiu and Denis should record APPROVED or REJECTED decisions
against this versioned package in Draft PR #102, with any required alternative policy/schema version.

Each record is **PENDING_REVIEW**, with the proposed choice, alternative and consequence below.
References are this contract, the exact source/fixture files and PR #93–#101 review snapshots (no human
approval of these new semantics was present). The final Draft PR requests the actual owners' review;
an agent test or a previous PR merge is not a new semantic approval.

| Owner / decision | Proposed choice | Alternative / consequence | Evidence reference |
| --- | --- | --- | --- |
| Sergiu / minute authority and thresholds | Review the isolated v1 static-synthetic policy above | Supply another versioned policy/baseline; current version must remain unactivated | LiveDetector/LiveContract/LiveRecovery tests and policy JSON |
| Sergiu / recovery and UNKNOWN | Two breaches, three actual healthy minutes; gaps reset streaks | Different counts/timeout handling needs new policy and tests; no missing-data recovery | `TraceLinkLiveContractTest` |
| Sergiu / Transport cause | Candidate only; cause UNDETERMINED for current live primitives | Supply independent measured impairment and versioned dependency binding; do not infer it | High-utilization/opposite controls; existing offline impairment/cause tests |
| Sergiu / native reference eligibility | Keep source quality/coverage unknown and LTE reference qualifications | Supply source-backed quality/procedure evidence; arithmetic alone cannot approve detector eligibility | All eleven native fixture checks |
| Denis / receiving contract and storage | Review measurement/mapping v1 and V010 isolated authority | Alternative additive schema must preserve every dimension, bytes and null state | `TraceDomainIngestionIT`, `DatabaseMigrationTest`, durability tests |
| Denis / API and incident mapping | Review native-only bounded API, default off; retain evaluation read model separately | Extending legacy incidents/rollups requires an approved lifecycle/evidence contract | API tests, OpenAPI, mapping JSON |
| Denis / delivery | Keep disabled until a reviewed topic/key/ACK contract and durable Kafka/restart rehearsal | Activating now would lack the required delivery evidence | No listener/publisher; source manifests and held V016 unchanged |
| Ion / receiving checklist | Confirm original bytes/event ID, complete scope, finalized revision zero, pinned manifest and exact retry | A new source/population/duration needs a separate version; never rewrite these fields | Original #97 JSONL, native fixtures, shared validator tests |
| Stanislav / migration and delivery operations | Review V010 grants, actual PostgreSQL restart/rollback and feature-off operation; keep Kafka disabled | A topic/key/partition/ACK agreement and connected restart rehearsal are required before activation | Migration, durability and immutable replay reports; V016 held-state checks |

Ion should reproduce generation from #97, compare source byte hashes, run the receiving checks,
retain unavailable source metadata, verify replay conflicts and avoid releasing held intent.
Sergiu should run the minute, offline, cause and recovery suites on one source SHA, then record actual
policy/baseline/authority decisions. Denis should run fresh/upgrade migration, runtime-grant, concurrency,
rollback, restart and secured API tests and record the receiving/API/delivery decisions.
Connected integration must wait for those decisions and an approved durable delivery rehearsal.
Stanislav should verify fresh and upgrade schema/grant parity, test restart on disposable infrastructure,
retain the held outbox and prepare the separately reviewed Kafka ACK/commit/restart acceptance procedure.
