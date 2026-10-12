# Trace Link native domain producers

This read-only job calculates versioned Transport, Roaming and LTE mobile-data
primitives from a **completed V015 native import** in processing_db. It creates
a local UTF-8 JSONL artifact. Delivery integration is **PENDING_REVIEW**. It has
no Kafka auto-configuration, component scan, web server, scheduler, detector,
Flyway execution, incident-service access or new tables. The normal processor
entrypoint and VoLTE/SMS paths are unchanged.

Use the [native import runbook](trace-link-native-import.md) first if the dataset
is absent. Never reset an initialized environment or rerun provisioning simply
to calculate primitives. The reader requires the existing import completion;
it rejects incomplete datasets, checksum/payload disagreement, wrong identities,
source scope mismatch and unexpected stale/source-quality metadata.

Build with Java 21 from the repository root:

```powershell
./mvnw.cmd --batch-mode --no-transfer-progress -pl services/processor -am package '-DskipTests'
```

The cached Maven 3.9.16 installation can replace `mvnw.cmd`. Quote PowerShell
`-D` arguments. Use the provisioned `PROCESSING_DB_URL`, `PROCESSING_DB_USER`
and `PROCESSING_DB_PASSWORD` environment variables without printing their values.
This job does not require migrator credentials. Invoke the separate main class
using the packaged Spring Boot launcher:

```powershell
java '-Dloader.main=md.utm.telecom.processing.history.TraceLinkDomainProducerApplication' `
  -cp services/processor/target/processor-0.3.0-SNAPSHOT.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher `
  --telecom.trace-link.producer.dataset=trace-link-md-30d-v1 `
  --telecom.trace-link.producer.kind=LINK `
  --telecom.trace-link.producer.scope=chi-hin `
  --telecom.trace-link.producer.direction=a-to-b `
  --telecom.trace-link.producer.from=2026-09-10T09:00:00Z `
  --telecom.trace-link.producer.to=2026-09-10T10:00:00Z `
  --telecom.trace-link.producer.output=tmp/trace-link/transport.jsonl
```

For Roaming change `kind=ROAMING`, `scope=RO-DEMO-01`, `country=RO`, and
`direction=out` (or `in`); choose a different output filename. Country is a
required partner binding, never a replacement scope. Inbound and outbound
populations are separate. For mobile data use `kind=CITY`, `scope=chi`, and omit
direction/country. Ten reference cities including `hin`/`cau` are valid; `rib`
and `tir` are not members of this catalogue. Inputs cover whole native UTC
five-minute half-open intervals within the imported manifest, at most 30 days.

The job writes 5 Transport, 3 Roaming, or 3 mobile-data records per requested
native slot. Each result wraps a validated `trace-link-domain-window-v1`
observation in the separate `trace-link-domain-primitive-result-v1` schema. This
is an isolated producer contract, not the frozen VoLTE-only v1 projection or a
consumer transport contract. `emittedAt` is the deterministic native window end,
not wall-clock delivery time. Source namespace, versions, manifest hash,
generator/seed, native identity, file/ordinal and raw row checksum are retained.

`observation.coverage` describes native slot presence (1 or 0); its
`coverageBasis` explicitly distinguishes it from nullable `sourceCoverage`.
Observed rows remain `INSUFFICIENT_EVIDENCE` because the archive supplies neither
source quality nor source coverage. A calculated number is not a healthy state.
Absent rows produce null primitives, null values and `MISSING/NO_SOURCE_SLOT`;
zero attempts produce null ratios with `ZERO_DENOMINATOR`; measured zero with
positive attempts remains numeric zero. Reverse link inventory yields missing
measurements. No five-minute row is split into invented one-minute facts.

Transport uses `weightedSum/validSeconds` with a labelled 300-second validity
assumption. Effective capacity must be positive. Capacity, RTT and jitter remain
snapshots. Packet-loss counts, RTT sum/count and operational/LAG member state
are unavailable. Capacity reduction does not establish LAG failure. Roaming
ratios use attempts/successes, never active-user snapshots or finalized SMS
delivery claims. LTE activation eligibility is unproven; DNS is reference-only,
and throughput's count of one represents a modeled city-slot scalar, never a
measured user/session. Activation, DNS and throughput are independent outputs.

`TraceLinkDomainPrimitiveBuilder.rollup` deduplicates exact replay, rejects
conflicting duplicate content and mixed partner/direction/metric/version
contexts, sums ratio/weighted primitives and retains the last snapshot with its
timestamp/age. `presentSlots/expectedSlots` disclose gaps. Supply explicit
missing results for a completely empty range. It does not average stored
percentages, sum snapshots across time or create a new consumer read model.

Artifacts are published with an atomic no-clobber hard link from a same-directory
temporary file. NTFS and the disposable verification filesystem support this.
An exact retry leaves identical bytes and counts duplicate outputs; conflicting
existing bytes fail without replacement. Unsupported hard-link filesystems fail
closed. Use a new filename for an intentionally different request. Retain the
original artifact for investigation; do not overwrite evidence to force retry.
Source data is never updated and there are no new delivery intents to drain.

The job prints process-local Micrometer metrics and safe row counts. Labels are
only three fixed domains and fixed outcomes. `produced` counts rows after new
artifact creation; `duplicate` counts exact artifact replay; `invalid` records
calculation/validation or content-conflict failures. Source rows read and
calculation duration are separate. Existing import logs provide committed batch
progress and checksum-failure exceptions. No pending-intent gauge is invented
for this path, and these counters do not claim Kafka acknowledgements.

Focused verification:

```powershell
./mvnw.cmd -pl services/processor -am '-Dtest=TraceLink*PrimitiveTest,TraceLinkDomainProducerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
python scripts/check-contracts.py
python -m unittest discover -s tests -v
```

The full archive test is explicitly enabled by `-Dtrace.link.archive=<reference
data directory>`. Frozen V016 compatibility is enabled with
`-Dtrace.link.v016.directory=<directory containing only exact V016 SQL>` and
`-Dtrace.link.v016.sha256=<pinned SHA-256>`. These optional tests report skips
when inputs are absent. See the [Day 3 evidence](../evidence/2026-10-12-domain-producers.md)
for the exact tested inputs and results.

Future integration can merge this additive branch with PR #92 after review;
the job entrypoint does not change its dispatcher, V016 or held outbox. Denis
owns consumer/incident-service mapping, Stanislav owns environment/topic
coordination. Kafka topic activation, authenticated reads, connected capacity
and receiving-owner reproduction remain separate handoffs. No approval is
inferred from local fixture or PostgreSQL parity.
