# Trace Link Day 3 implementation and detection capacity

Assignment date: **12 October 2026**. Actual execution: **13 October 2026**,
Europe/Chisinau (UTC+3). Branch: `feat/trace-link-day3-integrated-detection`.

**Historical measurement record.** The verification totals, measured source
commits and archived assets below describe the 13 October implementation, not
the corrected 14 October source. The colleague's later review demonstrated a
main migration collision, shifted episode onset and an unsupported workflow
claim. Those corrections and their fresh verification are tracked in the
[14 October correction report](2026-10-14-trace-link-day3-corrections.md).
Original raw artifacts and manifests remain unchanged.

The three synthetic domains now produce durable, queryable incidents with
immutable primitive evidence. Replay, restart, ordering and control regressions
pass. The measured persisted-window processing boundary passes its correctness
gate; **full source-to-incident capacity acceptance remains incomplete**.
Collection/finalization throughput was not measured, and the actual receiver
reconciliation ran subsequently. Activation remains disabled.

## Delivered implementation

Three implementation agents owned measurement authority, durable runtime and
receiving in separate worktrees; the coordinator integrated shared contracts,
migrations, configuration, verification and evidence. See the [interface and
migration ledger](../../tasks/trace-link-day3/interface.md), [cross-review
resolutions](../../tasks/trace-link-day3/review.md), and [runbook](../runbooks/trace-link-detection-day3.md).

The adapter checks finalized aligned 300-second windows, complete scope,
independent primitive populations, quality, inclusive source age of at most
600 seconds, trusted topology and checksum-verified learned baseline artifacts.
First actual LIVE claim time is frozen across retries; historical experiments
require explicit replay mode. Rejected cause diagnostics cannot erase a valid
symptom. Existing Day 2 rules, recovery transitions and IDs are reused.

Dedicated PostgreSQL input/job/state/evaluation/outbox tables commit accepted
input fingerprints, quiet rule results and detections atomically. Fenced leases
serialize complete lifecycle keys, while four configurable workers process
independent scopes. Exact replay is a no-op; conflicting committed inputs and
late history rewrites are rejected. The publisher preserves episode sequence.

The additive `trace-link-detection-v1` topic contract has independent producer
and receiver validation. Receiver evidence is immutable; sequence gaps remain
pending until projection can advance contiguously. Authenticated bounded
`/api/v2/trace-link/incidents` list/detail/evidence routes provide read-only
technical incidents and immutable evidence. Analyst assignment, analyst status,
investigation, resolution and workflow audit are unavailable for these hash-ID
episodes; no mapping to legacy UUID incidents exists. Technical recovery updates
only technical state. Analyst workflow needs a separately approved contract.

The original branch used processor V017 and an alternate incident V006. The
14 October correction preserves pinned main's processor V001–V016 and incident
V001–V010, keeps processor V017, and moves the detector tables into incident V011.
It adds stable first-breach/initial-detection fields to a dedicated incident-view
DTO while retaining strict envelopes as nested latestDetection and evidence
items. These corrections do not change the recorded 13 October measurements.

## Verification

| Check | Retained result |
|---|---|
| Root Python | 147 passed |
| ML Python | 74 passed |
| Full Maven reactor | 1,238 tests; zero failures/errors; four environment-dependent skips |
| Subsequent shared/runtime/HTTP regression | 79 passed; zero skips |
| Separate incident-service suite | 166 unit tests, one opt-in skip; 73 integration tests, zero skips |
| Final receiver policy guard regression | 12 passed |
| Frozen V2/reference artifacts | 29 pinned files and original golden fixture verified |
| Dashboard generated API/build; compose config; PowerShell syntax; diff whitespace | Passed |

The full reactor preceded the final narrow baseline/policy guard changes; those
changes were exercised in the subsequent 79-test and 12-test runs. Three skipped
reactor cases required `ML_SERVICE_URL` and subsequently passed against the
actual packaged HTTP service. The remaining processor and receiver SMS shadow
cases require opt-in replay input unavailable in this run. They remain reported
as skipped, not passed. No test was removed or disabled to obtain a pass.

Coverage includes exact OPEN/equal UPDATE/escalation UPDATE/UNKNOWN/RECOVERY
identities, scope independence, duplicate and reordered delivery, conflicting
input, expired leases, restart and transaction/publication recovery. Missing,
stale, partial and low-volume inputs, absent baselines, high healthy utilization,
wrong mappings and ambiguous causes are controls. Recovery needs three adjacent
eligible healthy windows and required backlog drainage; missing data does not
advance it. Fresh-install and upgrade migration assertions retain exact counts
and immutable-table privilege checks.

Logs and raw results are retained as deterministic gzip files under
[assets/trace-link-day3](assets/trace-link-day3/packed-artifacts.json).
The packing manifest records compressed and original SHA-256 hashes. The
[verification ledger](assets/trace-link-day3/verification.json) records suite
boundaries and implementation provenance separately from measured source bits.
Directory-local Git attributes preserve evidence bytes across checkout newline
settings; the artifact manifest excludes itself from its own hash list.

## Measured profile and boundary

The real-time run started **05:36:49 UTC** and ended **06:20:00 UTC**
(08:36:49–09:20:00 local). It waited through eight genuine five-minute native
intervals, 05:40–06:20 UTC, for transport, roaming and LTE data. No accelerated
event-time wait is represented as real elapsed time in these results.

| Phase | Declared duration | Declared rate | Accepted windows | Observed rate |
|---|---:|---:|---:|---:|
| Warmup | 120 s | 4/s | 480 | 3.99945/s |
| Steady | 900 s | 4/s | 3,600 | 3.99987/s |
| Bounded burst | 60 s | 8/s | 480 | 7.99765/s |
| Native five-minute lifecycle | Eight intervals | Three scopes | 24 | Separate lifecycle workload |

The [executed profile](assets/trace-link-day3/executed-profile.json) records the
four/eight-rate harness selection, pending bound 1,000 and drain timeout 300 s.
It is reconstructed from the retained measured harness source and invocation;
its packaging timestamp is not claimed to be a pre-run freeze timestamp. Default
tooling profiles and this actual selection are distinguished. There is no
personal Ion/Stanislav input-manifest or load-profile acceptance in this record.

Hardware: ASUS TUF Gaming A15 FA507NV, Ryzen 7 7735HS, 16 logical CPUs,
16,369,233,920 bytes physical RAM; Windows detector JVM, embedded Kafka and
Docker PostgreSQL. JVM maximum heap was 4,093,640,704 bytes; 3,146 samples recorded
peak used heap 162,523,736 bytes. Four workers were configured; observed maximum
was three. Initial verification workloads shared the host, so this is an
achieved load under that contention, not an isolated maximum-capacity claim.
See [hardware](assets/trace-link-day3/hardware.json).

All **4,584** offered windows were accepted, finalized and evaluated. There were
**2,301** committed detections, Kafka acknowledgements and observed outputs,
with zero duplicate episodes or lost committed outputs. Pending work started
at zero, peaked at four and drained to zero in **0.3699002 s**, within the
declared bound and timeout. The smaller capacity-only drain is a separate raw
field and is not substituted for this final detector/outbox drain.

Input begins at finalized persisted windows. Synthetic producers preserve actual
independent primitive populations and immutable source checksums; 4,588 source
observations were checksum-verified, with recorded age approximately 0.091–1.594 s.
This demonstrates freshness and traceability at the injection boundary. It does
not establish collection or finalization throughput from upstream raw sources.

## Stage and lifecycle measurements

Percentiles use the report's nearest-rank calculation; units below are ms.
Stages have different sample populations and must not be summed as percentiles.

| Stage | Samples | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| Detector queue wait, database timestamps | 4,584 | 81.275 | 202.455 | 258.711 |
| Rule execution | 4,584 | 2.0423 | 3.4737 | 5.0832 |
| Dependency joining | 2,301 | 4.0910 | 8.0933 | 11.4971 |
| Evaluation through database commit | 4,584 | 86.920 | 178.769 | 254.587 |
| Committed outbox to Kafka acknowledgement | 2,301 | 108.320 | 177.153 | 289.196 |
| Upstream collection/finalization wait | 0 | Unmeasured | Unmeasured | Unmeasured |
| Original outbox to incident receipt | 0 | Unmeasured | Unmeasured | Unmeasured |

Publication samples are unique detection IDs, not duplicate per-window spans.
The [summary](assets/trace-link-day3/capacity-summary.json) includes extrema,
phase timestamps, resource samples and machine-readable gate reasons; the
compressed full report retains detailed traces and source-file hashes.

Each of the three native episodes followed OPEN, equal UPDATE, severity
escalation UPDATE, UNKNOWN, two healthy UPDATEs, then RECOVERY. The timings below
include five-minute policy waiting where specified and are not rule compute time.

| Lifecycle interval (seconds) | Samples | p50 | p95 / p99 |
|---|---:|---:|---:|
| First bad window start to OPEN commit | 3 | 600.375835 | 600.412396 |
| First bad window end to OPEN commit | 3 | 300.375835 | 300.412396 |
| Escalation window start to increased-severity UPDATE commit | 3 | 300.316377 | 300.431928 |
| Final eligible healthy window end to RECOVERY commit | 3 | 0.401937 | 0.514809 |

## Actual receiving reconciliation

The exact produced artifact was subsequently sent to the actual Kafka receiver
at **11:15:48–11:18:13 UTC**. Its first/last database receipt timestamps were
11:15:54.906645–11:17:08.156318 UTC. There were **4,602** reversed and duplicate
deliveries, **2,301** immutable evidence and audit rows and **2,283** episode
projections: three native episodes recovered and 2,280 capacity episodes ongoing.
Identity mismatches and pending gaps were zero. The receipt binds the exact
producer-file hash and labels itself `SEPARATE_SUBSEQUENT_KAFKA_RECEIVER_RUN`.

The hours between these two runs are a handoff wait. They are not live incident
latency. This receiving run proves reconciliation and receiver behavior against
the actual produced output; simultaneous pipeline capacity remains unmeasured.

## Eligible legacy ML measured separately

The existing pinned ML container used Uvicorn `--limit-concurrency 8`. A separate
64-call Java MlClient sample configured and observed concurrency eight: **23 OK,
41 UNAVAILABLE**, zero timeouts. Container logs reconcile exactly to 23 HTTP 200,
41 HTTP 503 and 41 concurrency-limit warnings. SMS had 10 OK/22 unavailable;
VoLTE had 13 OK/19 unavailable. This was a concurrency-control sample, not the
900-second detector load run or an ML throughput SLA.

| HTTP timing, ms | Samples | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| All outcomes | 64 | 74.2429 | 122.7424 | 125.4099 |
| Successful inference | 23 | 100.7319 | 125.4092 | 125.4099 |

New-domain ML is **NOT_ELIGIBLE_DAY3**. Model `isoforest-v2-synthetic-1` and
`baseline-v2` were unchanged. Image, model, calibration and timing hashes are
retained in [ML provenance](assets/trace-link-day3/legacy-ml-provenance.json).
UNAVAILABLE outcomes are reported as fallback, not successful inference.

## Provenance, reproduction and remaining acceptance

Integrated implementation commit: `27f596f55d1f406c84f141987866b84aced19449`;
the [integrated source manifest](assets/trace-link-day3/integrated-source.json)
records filesystem hashes and canonical Git blob identities for the changed
implementation files. Measured source commit:
`59827b1d199516e9ddc10a4df6a8479dc370870b`.
Measured source-tree SHA-256:
`6c292a81f26ee4225646f0d59994495e0f2f0bde02c009cd60e5637a45aa4b18`
(272 measured source-file entries, retained in raw evidence).
Producer raw SHA-256:
`08da19134241ed3154d0cbaefbee0ae28d8d10f7a54a8e8db73abae15d7f3842`.
Receiver receipt SHA-256:
`7055691fc2ecc4f8a2337bf1aa760f32e7bf43a60322c535fd2af0495bcb61e4`.
These identify measured working-file bytes; they are distinct from the later
integrated implementation commit and canonical Git blob identifiers. Final
trust guards, receipt reconciliation and reporting fixes followed the long run
and have their own regression evidence.

The [reproduction entrypoint](../../scripts/verify-trace-link-day3.ps1) records
commands/logs and checks pins, Python, the reactor and the separate receiver.
`-MeasureRealtime` requires an actual hardware manifest and waits through genuine
five-minute windows. The runbook documents prerequisites and the converter/report
pipeline. Archived JSON files are ordinary gzip and can be decompressed into a
temporary directory; their original hashes are in the packing manifest.

Correctness gate: **PASS**. Full real-time capacity gate: **INCOMPLETE**, with
`SOURCE_COLLECTION_AND_FINALIZATION_CAPACITY_UNMEASURED` and
`SEPARATE_RECEIVER_REPLAY_NOT_SIMULTANEOUS`. No SLA is inferred. A coordinated run
including upstream collection/finalization and concurrent receiving is still
needed for full acceptance. The prepared [handoffs](../../tasks/trace-link-day3/handoffs.md)
separately record missing Ion matching-window/receiving decision, Denis contract
acceptance, David wording handoff and Stanislav hardware/rate acceptance. Agent
test results are not personal sign-offs. The historical correctness PASS applies
to the measured boundary, not integration acceptance against current main: the
14 October review demonstrated defects requiring the separately documented
migration and timestamp corrections. Live activation remains off until the
upstream, connected-capacity, personal-acceptance and deployment gates pass.
