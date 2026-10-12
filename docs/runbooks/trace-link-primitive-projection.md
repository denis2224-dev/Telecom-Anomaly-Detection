# Trace Link producer projection

Consumer agreement is **PENDING_REVIEW**. This procedure creates processor-owned
immutable primitive projections and held delivery intent. It does not publish
Kafka messages or provide authenticated incident-service acceptance.

First import the native archive using [the existing import runbook](trace-link-native-import.md).
Retain the manifest identity and completed import; never reset a demo database.
Use a disposable database for the commands below until deployment owners approve
the additive V016 migration. Use the existing opaque runtime/migrator environment
configuration; do not place credential values on command lines or in evidence.

Build from this branch with Java 21 and Maven 3.9.x:

```powershell
& $mavenPath '-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2' -pl services/processor -am package -DskipTests
```

Select only one historical mode. Run one city and no more than one aligned UTC
day at a time; advance adjacent slices to cover the month. Exact retries change
no projection/outbox identity or payload bytes.

```powershell
$env:TRACE_LINK_PROJECTION_MODE='true'
try {
  java -jar services/processor/target/processor-0.3.0-SNAPSHOT.jar `
    --telecom.trace-link.dataset-id=trace-link-md-30d-v1 `
    --telecom.trace-link.city-id=chi `
    --telecom.trace-link.from=2026-09-10T09:00:00Z `
    --telecom.trace-link.to=2026-09-11T09:00:00Z `
    --telecom.trace-link.resolution=15m `
    --telecom.trace-link.output=target/trace-link-producer-result.json
} finally {
  Remove-Item Env:TRACE_LINK_PROJECTION_MODE
}
```

`HISTORY_BOOTSTRAP_MODE` and `TRACE_LINK_BOOTSTRAP_MODE` must be unset/false for
this entrypoint. It excludes Kafka configuration and has no component scanning,
live listeners, finalizer, detector, retention job or delivery scheduler.

V016 leaves V014/V015 and legacy minute constraints intact. Runtime may SELECT
and INSERT the new projection/outbox tables; it cannot UPDATE/DELETE/TRUNCATE
them, create schema objects, or connect to `incidents_db`. No old retention job
targets these tables. Held delivery intent remains available across retries and
process restart; there is no automatic deletion or release into an existing
topic. Future delivery activation and retention need approved additive changes.

The report echoes requested/effective bounds. 5m/15m/1h/6h use whole UTC buckets
and sums of numerator/denominator. A query may have partial edges; only whole
native slots contained in its interval contribute. Missing source/projection
slots become explicit null points and lower slot coverage. Slot coverage 1
still does not establish source quality. Source quality/coverage remain null
for the supplied archive, giving `INSUFFICIENT_EVIDENCE` with computable values.

Focused checks use real disposable PostgreSQL:

```powershell
& $mavenPath '-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2' `
  -pl services/processor -am `
  '-Dtest=TraceLinkProjectionTest,TraceLinkProjectionIT,TraceLinkArithmeticTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

& $mavenPath '-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2' `
  -pl services/processor -am `
  '-Dtest=TraceLinkProjectionIT#fullSuppliedArchiveHasPersistedNativeAndThirtyDayRollupParity' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DtraceLink.projectionReferenceRoot=C:\OrangeSystems\task\Trace_Link_Integration_Pack' test

python scripts/check-contracts.py
python -m unittest discover -s tests -v
```

Without the explicit reference path, the full archive test is **SKIPPED**, not
passed. Its successful output is
`services/processor/target/trace-link-projection-full-result.json`. Tests of
consumer ingestion, Kafka failure/ACK replay, unauthorized API requests and
authenticated imported-source series remain **BLOCKED** on the approved
consumer contract and implementation. Isolated PostgreSQL proof does not close
those gates.

Denis's handoff is [the frozen producer agreement](../architecture/trace-link-projection-v1.md),
the schema, native CSV-derived fixture, and persisted examples in this change's
evidence report. Approval must explicitly cover identifiers, population,
policy, topology, source quality versus slot coverage, dataset/inventory
ingestion, immutable revision/content deduplication, topic/key, and read DTOs.
Then reproduce one actual imported 5m slice, a larger native interval and a
ratio-of-sums rollup through the authenticated incident API.

Use the [exact consumer decision package](../architecture/trace-link-consumer-handoff-v1.md)
for the proposed mapping and the [final hardening evidence](../evidence/2026-10-12-trace-link-final-hardening.md)
for the fresh complete reactor, non-overlapping test totals, artifact hashes and
read-only consumer compatibility checks. These documents do not authorize delivery.
