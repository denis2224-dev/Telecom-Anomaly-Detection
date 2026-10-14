# Day 3 native domain producer evidence

Producer implementation is isolated on
`feature/ion-tracelink-domain-producers`, stacked directly on PR #91 head
`1e67e61ce3f2c46f26818b42c17fc3d50481aae4`. GitHub inspection confirmed main
`cbb94d37f7593e99737ee4200e91b14e0512f76f`, PR #92
`3f6d485f41a6881fe65e580dfc8854d9ce97a2fe`, PR #89
`fec8905d7ba5563a8ebfe0aa541c16b1cb93b75b` and PR #90
`923a9f009ce402b84cc26882466e7c2e3989a136` before branch creation.

PR #91 supplies all native persistence and domain contracts needed here. No
dependency on the frozen VoLTE-only projection schema is required. The
separate job main class also avoids changing PR #92's mode dispatcher. No
migration or durable new-domain outbox is added, and no reservation is reused.
V016 remains exactly the implementation held in PR #92, outside this branch.
The existing dirty checkout, other worktrees and protected PR branches remain
untouched. **Kafka/consumer delivery: PENDING_REVIEW. Connected capacity:
BLOCKED.** No automatic merge or topic activation is included.

## Implemented source-to-primitive matrix

All native windows are UTC `[start,start+300s)`, with dataset/source namespace,
manifest, native row identity and checksum, generator/seed, policy and topology
versions. `sourceQuality` and `sourceCoverage` stay null. Observation coverage is
explicitly **native slot presence**, independently labelled by `coverageBasis`.
Calculated values retain `INSUFFICIENT_EVIDENCE`; no healthy classification or
causal attribution is inferred from arithmetic.

| Metric | Source and primitive mapping | Unit / operator | Reference status and limit |
| --- | --- | --- | --- |
| `transport.traffic` | `traffic_gbps * 300`, `validSeconds=300` | GBPS / time-weighted mean | APPROXIMATION: fixed native validity assumed; byte deltas absent |
| `transport.effectiveCapacity` | `effective_capacity_gbps`, native timestamp | GBPS / last snapshot | APPROXIMATION: positive gauge, exact validity/source authority absent |
| `transport.link.utilization` | `(100*traffic_gbps/effective_capacity_gbps)*300`, `validSeconds=300` | PERCENT / time-weighted mean | APPROXIMATION: per-slot capacity, fixed validity assumption; distinct from reference peak |
| `transport.rtt.snapshot` | `rtt_ms`, native timestamp | MILLISECONDS / last snapshot | APPROXIMATION: no probe identity or sum/count |
| `transport.jitter` | `jitter_ms`, native timestamp | MILLISECONDS / last snapshot | APPROXIMATION: estimator/probe identity unavailable |
| `roaming.registration.success` | `reg_success / reg_attempts` | PERCENT / ratio of sums | SUPPORTED reference primitives; partner, country, direction and REGISTRATION retained |
| `roaming.data.success` | `data_success / data_attempts` | PERCENT / ratio of sums | SUPPORTED reference primitives; DATA procedure isolated |
| `roaming.voice.success` | `voice_success / voice_attempts` | PERCENT / ratio of sums | SUPPORTED reference primitives; VOICE procedure isolated |
| `mobileData.activation.success` | `data_success / data_attempts` | PERCENT / ratio of sums | APPROXIMATION: generic synthetic activation; EPS procedure eligibility UNPROVEN |
| `mobileData.dns.success` | `dns_success / dns_attempts` | PERCENT / ratio of sums | REQUIRES_ADDITIONAL_PRIMITIVES: reference-only; post-activation eligibility UNPROVEN |
| `mobileData.throughput` | `sum=user_throughput_mbps`, `count=1` modeled city-slot | MBPS / modeled slot mean | APPROXIMATION: no genuine user/session weighting |

Transport source is `link_kpi_5m.csv`, Roaming is `roaming_kpi_5m.csv`, and LTE
data is `city_kpi_5m.csv`, read from immutable `app.trace_source_native_window`
rows after `TraceLinkDatasetBootstrap` completes. Each source is rechecked
against its raw CSV checksum, persisted JSON payload, natural key and scope.
No reverse-direction row, measured packet numerator/denominator, RTT sum/count,
operational/LAG state, finalized roaming SMS cohort or user count is fabricated.
Active roaming users are not added across time and are not emitted by this slice.

## Determinism and controls

The domain observation identity includes source/calculation version, metric,
dataset, native duration, reference scope, partner/direction/procedure and
schema/policy/topology versions. Exact artifact replay retains identical bytes;
conflicting existing content fails without replacement. Mixed scope/direction
rollups and conflicting duplicate event contents fail closed. Rollups retain
primitive sums, source lineage, reference limits, slot coverage and a stable
rollup identity. Snapshots retain timestamp/age instead of being summed.

Transport controls cover normal/degraded capacity, zero/missing capacity,
negative traffic, measured zero versus missing, absent opposite direction and
unavailable packet/RTT primitives. Roaming controls cover normal/degraded,
zero-volume, missing/partial/stale evidence, wrong country/partner/direction and
ratio-of-sums parity (`999/1010*100 = 98.91089108910891`). LTE controls keep DNS
failure with healthy activation, activation failure with healthy DNS, throughput
independent, zero attempts, invalid counters and missing source explicit.
Control generation is deterministic test code with its own dataset namespace;
scenario truth is not included in observations. Day 4 API/scenario changes are
outside this branch.

The versioned [result schema](../../contracts/observations/trace-link-domain-primitive-result-v1.schema.json)
wraps the existing domain schema, with 11 observed native metric examples and
five absent reverse-direction controls in
[the fixture](../../contracts/fixtures/trace-link/domain-primitive-results-v1.json).
Python independently checks source fields, arithmetic, manifest/raw-row hashes,
dictionary limitations and identities. Java checks these examples byte-for-byte.

## Verification record

Tested implementation commit: `2b71f421112f800032a5cf1fbbd06256d2109c53`;
source tree: `0623889c794a70a9a6a88cef6eda4237dc284d02`. The final delivery
commit adds evidence and artifact line-ending metadata only.

| Check | Actual result |
| --- | --- |
| Final full Java reactor | 1,200 reported; 1,195 passed; zero failures/errors; five skips |
| Modules | streaming-support 117; event-generator 95; processor 988 |
| Final focused refinements | 16 tests, zero failures/errors/skips |
| Repository Python | 115 passed; zero failures/errors/skips |
| Contract checks | PASS, including 11 native metric mappings and five missing reverse controls |
| Fresh 30-day import and exact retry | 362,880 native rows; manifest `e3033f025f6b5866edc0ed34f3078cfa9f9c01cb126eac4577055ef30247ee1b` |
| Native producer / PostgreSQL parity | 14 metric/direction rollups, 12 native slots each; maximum difference 0 |
| Four-language arithmetic | 54 cases per Java/Python/JavaScript/PostgreSQL export; maximum difference 0 |
| External reference engine | 708,498 checks passed |
| Reference adapter compatibility | 75 checks passed |
| V016 coexistence | Exact SQL applied/validated; seeded held VoLTE record unchanged after Transport job |
| Packaged launcher | Final jar contains PropertiesLauncher and the exact compiled isolated main class |

The five skips are three optional ML HTTP cases without `ML_SERVICE_URL`, one
SMS shadow replay without `SMS_SHADOW_REPLAY_DIR`, and the existing importer's
optional full-archive test without `traceLink.referenceRoot`. The new archive
producer test separately executes full import/retry and SQL parity with its
explicit `trace.link.archive` property. No skip is counted as passed. See
[native archive parity](assets/trace-link-domain-producers/native-archive-parity.json)
and the verification record for all suite names and reasons.

Exact commands, suite counts, skips and artifact hashes are recorded in
[verification.json](assets/trace-link-domain-producers/verification.json). The functional sequence
was Transport first, then Roaming, then LTE, then isolated entrypoint,
observability and V016 compatibility, followed by the full reactor and reference
checks. No test result substitutes for downstream acceptance.

The frozen V016 SQL is extracted read-only from PR #92's exact head into the
external evidence directory. Its SHA-256 is
`bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848`.
The compatibility test applies/validates V001-V016 in a disposable database and
verifies initially empty projection/outbox tables and the `PENDING_REVIEW`
constraint, then seeds one VoLTE held record and proves it remains unchanged
after the Transport job.
The SQL is neither copied into this branch nor modified.

Initial Python discovery lacked cached OpenAPI dependencies; its failure is
retained separately from the completed rerun. The raw Windows pin check also
reports inherited CRLF conversion for `service-rules-v2.json`; an isolated
snapshot of exact committed Git blobs passes all 29 frozen V2/reference file
pins and the arithmetic fixture hash. This branch changes none of those inputs.

## Operations and handoff

See the [producer runbook](../runbooks/trace-link-domain-producers.md) for the
packaged launcher, source import prerequisite, safe retry and missing-data
diagnosis. The job exposes only fixed domain/outcome counters, native rows read
and calculation duration. Import progress/failure evidence stays with the
existing importer. New delivery intents do not exist; pending count/age is not
invented. The [capacity record](2026-10-12-streaming-capacity.md) identifies real
hardware and all unmeasured connected gates.

Denis's consumer/incident-service ingestion, API/read models and metric mapping
remain `PENDING_REVIEW`; his branches are unchanged. Stanislav's coordinated
load environment/topic and burst bounds remain pending. Sergiu/David can review
the versioned primitives, units and explicit limitations. Receiving-owner
reproduction, authenticated API/UI, Grafana and connected recovery have not
been verified here. No message is sent or approval inferred on their behalf.
