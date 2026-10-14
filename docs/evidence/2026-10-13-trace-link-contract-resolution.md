# Trace Link contract resolution acceptance

13 October 2026, Europe/Chisinau. Overall: **PARTIALLY_COMPLETE**.
The isolated engineering path is implemented and verified. Owner semantics and connected delivery
acceptance remain **PENDING_REVIEW/BLOCKED**. Kafka activation: **NO**. Merge-ready: **NO**.

Implementation: `8f97fb3c853cdd34adb14016724ffe6e85177ee3`.
Branch: `integration/trace-link-contract-resolution-20261013`.
Worktree: `C:/OrangeSystems/Program/trace-link-contract-resolution-20261013`.
Base: PR #101, `3468ed908bebbe0eae7ced93ef46d56e412c9655`; refreshed main:
`f7364d35c71ddf865c9f826c41bdf0aac37f473d`. #101 remained open at the same head before delivery.
It already reconciles merged #98 with #99 and #100. No existing owner branch was modified or merged.

Published as [Draft PR #102](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/102),
targeting `reconcile/trace-link-pr98-pr99-20261013`. Actual review requests were recorded for
`SergiuTOP`, `denis2224-dev` and `stasikkk777`; auto-merge is disabled. The initial publication
snapshot is retained in the assets. These requests are not approvals.

The [mapping and owner decision package](../architecture/trace-link-contract-resolution-v1.md)
defines the code paths, policies, units, populations, scope dimensions, storage, API and handoffs.
The [source manifest](assets/trace-link-contract-resolution-20261013/source-manifest.json)
lists every implementation path and its Git-blob SHA-256. Final delivery adds evidence only.
The complete delivered path list, including evidence, is
[final-changed-paths.txt](assets/trace-link-contract-resolution-20261013/final-changed-paths.txt).
The [verification manifest](assets/trace-link-contract-resolution-20261013/verification.json)
records individual Java cases, source/fixture assertion locations, revisions, commands, outcomes and skips.
Compressed original logs, JUnit XML and hashes are retained beside it.

## Implemented corrections

- Added lossless `trace-link-domain-measurement-v1` / mapping v1. It preserves original source bytes,
  IDs, window duration, revision/finalization, metric population, primitives and complete native scope.
- Added strict `trace-link-live-evaluation-v2`, live result v1, separate synthetic-minute policy and
  static baseline v1, and separate serializable minute recovery/episode identity. Native 300-second
  validators, seasonal baselines, VoLTE/SMS rules and historical episode identities are unchanged.
- Added native-domain receiving/storage/read models in additive incident migration V010, after
  merged V008 and #100/#101 V009. Refreshed remote incident migration paths had no V010 collision.
  Runtime cannot register sources/scopes or update/delete finalized measurements/results.
- Added guarded native queries and a default-off authenticated API. All eleven native metrics retain
  units, primitive operators, population, link/direction or partner/country/direction/procedure or
  city/LTE/procedure. Unsupported live metrics are rejected rather than presented as missing telemetry.
  Native qualifications, including unproven LTE eligibility and modeled throughput weighting, are
  stored and exposed directly with provenance. No metric alias or scope collapse was introduced.
- Isolated a `streaming-support:contracts` jar to keep Spring 3 configurations out of Spring 4.
  Updated CI/Docker build order. Preserved decimal JSON and exact source byte identity. Pinned the live
  manifest to LF without changing its Git blob. V001–V009 and processor V001–V016 remain unchanged.

## Compatibility and mandatory controls

PASS means the stated isolated contract/check works; it does not supply owner approval or Kafka acceptance.

| Boundary/control | Result | Executable evidence |
| --- | --- | --- |
| Valid native 300-second offline contexts | PASS | Unchanged Inputs, domain rules, offline integration, cause and recovery suites |
| Raw minute input sent to old offline validator | FAIL, intentionally retained | Initial probe: `Expected aligned five-minute window`; new live path is separate |
| Genuine minute roaming/DNS failure through new path | PASS | `TraceLinkLiveContractTest.genuineMinuteRateFailuresAreDetectedWithOriginalSourceIdentity` |
| Raw new-domain envelope sent to old VoLTE-only consumer | FAIL, intentionally retained | `TraceDomainIngestionIT.oldConsumerStillRejectsNewDomainEnvelope`; explicit new wrapper/service required |
| All eleven native metrics through receiving storage and API | PASS | Shared Measurement test, all-native IngestionIT loop, independent full-archive SQL parity |
| Wrong duration/version/domain/metric/population/unit/operator | PASS: rejected | Measurement and live contract negative controls |
| Wrong scope/partner/country/direction/procedure | PASS: rejected | Measurement tests, complete-scope registration and native query tests |
| Wrong topology/catalogue/dataset/source manifest | PASS: rejected | Shared validators and registration mismatch integration tests |
| Zero denominator | PASS: null/INSUFFICIENT_EVIDENCE | Shared, live, consumer and legacy primitive tests |
| Missing/partial/stale/unavailable evidence cannot recover | PASS | Live UNKNOWN/recovery tests and unchanged offline recovery controls |
| Transport impairment with valid native-duration offline primitives | PASS | Existing transport loss/RTT domain-rule and cause fixtures |
| Live high utilization without impairment | PASS: CANDIDATE, cause UNDETERMINED | Fault/high-utilization/opposite-direction controls; no invented outage |
| Roaming direction and LTE DNS/activation independence | PASS | Live production detector controls; independent original source populations |
| Duplicate replay, changed identity content, concurrent retry | PASS | Recovery replay controls, real PostgreSQL ingestion/durability tests |
| Source truth leakage / forged healthy or cause claims | PASS: rejected | Strict schemas, independent byte/identity checks, result admission controls |
| VoLTE/SMS regressions and frozen reference inputs | PASS with explicit optional skips | Full reactor, incident suite, legacy parity/recovery; 29 frozen pins verified |
| Detection-to-consumer source identity and evidence | PASS | Pinned results re-executed, independent Decimal/source-byte oracle, actual database result ingestion |
| Actual database restart and atomic rollback | PASS | DurabilityIT: committed source bytes survive restart; conflicting support rolls back preceding inserts |
| Human policy/mapping approval and real connected acceptance | PENDING_REVIEW / BLOCKED | Review requests; no approval fabricated |

Native source quality/coverage remain unknown where the archive does not supply them. Arithmetic
availability is not approval to feed those rows into an offline detector. Native RTT snapshots are not
RTT sample means, and a modeled city-slot throughput count is not user/session weighting.
The live source v1 generates six metrics and supports four primary evaluation populations; it does not
supply independent transport impairment/dependency evidence. No additional live populations are invented.

## Verification results

Counts below select the latest result per test class across Surefire/Failsafe. Focused reruns are **not**
added to full-suite totals. All remaining failures/errors are zero.

| Suite | Reported | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: | ---: |
| Streaming support | 130 | 130 | 0 | 0 |
| Event generator | 147 | 147 | 0 | 0 |
| Processor | 1060 | 1055 | 5 | 0 |
| Incident service | 273 | 272 | 1 | 0 |
| **Unique Java total** | **1610** | **1604** | **6** | **0** |
| Repository Python | 160 | 159 | 1 | 0 |
| Pinned ML Python | 64 | 64 | 0 | 0 |

The full root command initially exited 1 for one new fixture assertion comparing Jackson integer and
decimal node classes. Their complete serialized numeric/field values were identical. The assertion now
compares the exact decimal wire parsing; the sequential affected root run passed **50/50**. Root production
trees are identical to the final implementation; the source-equivalence artifact records their hashes.
The final complete incident `verify` command passed on the implementation revision above.

Processor skips: three optional packaged ML HTTP cases lack `ML_SERVICE_URL`; SMS shadow replay lacks
`SMS_SHADOW_REPLAY_DIR`; the standalone full-native import was not selected again. That standalone import
completed in the interrupted run, and full native import was independently repeated by the successful
full projection and native-domain SQL parity checks. Incident SMS shadow replay has the same missing replay
directory. The Python wrapper regression requires the unavailable `pwsh` executable. Skips are not passes.

Additional PASS results:

- Full archive: **362,880** native rows, **8,640** projections and **8,640** held outbox rows; **121** six-hour
  buckets; exact retry and independent SQL numerator/denominator parity. All eleven native domain metrics
  also passed full-archive SQL parity across 14 metric/scope/direction cases.
- Fresh and upgrade migrations, migrator ownership, runtime immutability/DDL restrictions and cross-database
  denial. Real concurrent receipts store one measurement. A real PostgreSQL process/container restart
  preserves the committed bytes and identity. Rollback leaves no partial detection/measurement writes.
- Protected API component tests retain authentication, enabled analyst roles, sessions and CSRF controls.
  The new API defaults off. These are synthetic OIDC MockMvc checks, not genuine browser/login acceptance.
- OpenAPI type generation, dashboard production build, contract validation, 29 frozen reference pins,
  pure-jar configuration exclusion/LF manifest checks, runtime image build and Git whitespace validation.

## Reproduction and environment

Actual versions: Java **21.0.12.1**, Maven **3.9.16**, Python **3.13.7**, Node **24.20.0**,
PostgreSQL **16.4-alpine**, Docker **29.3.1**. Exact version output and image identity are in the assets.
Run builds sequentially; never rebuild a shared module while another process reads its target classes.

```powershell
$maven = 'C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd'
$cache = '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2'
python scripts/check-contracts.py
python -m unittest discover -s tests -v
# Existing pinned ML dependencies; no model/training source was changed.
$previousTracePythonPath=$env:PYTHONPATH
try {
  $env:PYTHONPATH='C:/OrangeSystems/Program/ion-trace-link-evidence/ml-deps'
  python -m unittest discover -s services/ml-service/tests -v
} finally { $env:PYTHONPATH=$previousTracePythonPath }
& $maven -o -B -ntp $cache '-Dlogging.level.org.springframework.jdbc.core=INFO' `
  '-DtraceLink.projectionReferenceRoot=C:/OrangeSystems/task/Trace_Link_Integration_Pack' `
  '-Dtrace.link.v016.directory=C:/OrangeSystems/Program/ion-domain-producer-evidence/v016' `
  '-Dtrace.link.v016.sha256=bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848' install
& $maven -o -B -ntp $cache -pl services/processor -am `
  '-Dtest=TraceLinkMeasurementTest,TraceLinkLiveContractTest,TraceLinkDomainArchiveTest,TraceLinkV016CompatibilityTest,TraceLinkInputsTest,TraceLinkDomainRulesTest,TraceLinkOfflineIntegrationTest,TraceLinkRecoveryPolicyTest,TraceLinkCausePolicyTest,TraceLinkServiceTransportTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtrace.link.archive=C:/OrangeSystems/task/Trace_Link_Integration_Pack/data' `
  '-Dtrace.link.v016.directory=C:/OrangeSystems/Program/ion-domain-producer-evidence/v016' `
  '-Dtrace.link.v016.sha256=bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848' install
& $maven -o -B -ntp $cache -f services/incident-service/pom.xml `
  '-Dspring.kafka.bootstrap-servers=127.0.0.1:65534' verify
npm.cmd run generate:api --prefix apps/dashboard
npm.cmd run build --prefix apps/dashboard
python scripts/verify-trace-link-pins.py --reference-root C:/OrangeSystems/task/Trace_Link_Integration_Pack
docker build --file services/incident-service/Dockerfile --tag trace-link-contract-resolution:8f97fb3 .
git diff --check
```

## Recovered failures and limitations

The pre-shutdown run stopped during projection and had no final exit result. Its completed native import
and incomplete/failed reports are preserved separately. A prior simultaneous rebuild produced truncated
class files; all resumed Maven work was sequential. Earlier consumer startup imported a Spring 3 Kafka
configuration into Spring 4; the contracts-only classifier fixes that boundary. An early Python export
rounded JSON decimals; the canonical results now retain the original decimal text. The Windows live
manifest LF pin preserves the Git blob and makes its hash portable. Missing native fixtures were retained.

The restart harness first failed on immediate connection readiness, then correctly timed out because Docker
Desktop changed its ephemeral host port. It now reads the actual post-restart binding and waits only on
transient connection exceptions, with a 45-second limit. The committed-row/byte assertions still run.
The saved restart artifact records distinct original and restarted ports and the unchanged source identity.

No new-domain Kafka send/ACK/commit/restart rehearsal, real authenticated browser flow, production source
authority, connected incident lifecycle or deployment/capacity acceptance is claimed. PostgreSQL durability
is not Kafka durability. Native quality and eligibility limitations remain explicit.

Safe feature-off behavior: keep `TRACE_DOMAIN_READ_MODEL_ENABLED=false`, retain V010 and all stored history,
and leave producer/new-domain Kafka and V016 held intent disabled. No schema rollback, database reset,
shared-volume deletion, credential change or automatic merge was performed. A pre-V010 binary rollback
requires a separately verified deployment procedure; this report approves only the additive feature-off path.

## Receiving decisions and next action

All decisions remain **PENDING_REVIEW**; the linked architecture package contains proposals, alternatives,
consequences and executable evidence. Sergiu must review minute thresholds/baseline authority, freshness,
UNKNOWN, recovery and causal/source eligibility. Denis must review the exact mappings, V010 registration,
immutable storage, qualifications, native API and future incident lifecycle. Ion must confirm source identity,
replay and 60/300 boundaries. Stanislav must review grants/operations and supply the future approved
topic/key/partition/ACK/restart acceptance contract. Agent results are not their signatures.

Highest-priority next action: **Sergiu and Denis review the versioned live/receiving decision package in the
Draft PR and record APPROVED or REJECTED decisions with references.** Delivery activation remains held.
