# Geographic monitoring checkpoint

Geographic absence requires persisted proof that the processor participated in the
whole UTC minute. Receipt timestamps, bucket adjacency and catalogue activation
alone are not that proof. Legacy gap discovery keeps its existing behavior.

## Migration sequence

`V011__geographic_monitoring.sql` is reserved for this checkpoint. PR #52 owns
V008, V009 and V010. None of those files, or any earlier migration, is changed.
V011 references no PR #52 table or index; it uses the existing processor schema
and runtime role. Production rollout must apply the post-PR-52 sequence before
V011. Applying V011 first and adding lower versions later would violate the
normal ordered Flyway rollout; do not enable out-of-order migration or rewrite
history to work around it.

## Participation and continuity

`geographic_monitoring_range` pins catalogue version and digest, topology version
and digest, activation time, lease owner, and the start of a continuity segment.
Its interval is `[monitored_from, monitored_through)`. The exclusive endpoint
only reaches completed UTC minutes established by consecutive live ticks.

The live recorder starts after `ApplicationReadyEvent`. Enrollment begins at
`max(effectiveFrom, ceilToUtcMinute(readyTime))`; an exact eligible minute
boundary is allowed. The range and all authorized nonlegacy scope cursors are
inserted in one transaction before enrollment is sealed. Enrollment timestamps
may naturally be milliseconds after that minute boundary, so SQL does not
require `monitored_from >= created_at`. The standalone history bootstrap imports
no recorder, and the recorder is also excluded from the `history-bootstrap`
profile.

`telecom.monitoring` binds all timing settings in `MonitoringProperties`:

| Property | Default | Constraint |
| --- | --- | --- |
| `tick-ms` | 10000 | 1000..10000 and below continuity gap |
| `max-continuity-gap-sec` | 20 | 2..20 and below lease |
| `lease-sec` | 30 | above continuity gap, at most 120 |

The 30-second lease exceeds the 10-second tick so ordinary renewal occurs before
expiry. The shorter 20-second continuity bound is independent of ownership:
holding a lease does not prove monitoring through a pause. Gaps greater than
20 seconds, lease expiry, or a new owner freeze the old endpoint and start a new
range at the next eligible minute. Expiry at the exact lease boundary permits
takeover. A competing owner cannot renew an unexpired lease. Old closed ranges
cannot be reopened or extended.

A PostgreSQL transaction advisory lock serializes recorder enrollment and
takeover. Endpoint renewal additionally compares range ID, owner, lease validity,
open state and the previous tick. No monitoring transaction holds a window lock
or waits for feature generation. Database failures cannot turn an unpersisted
minute into an eligible missing window.

## Finalization and cursor acknowledgment

Each `geographic_monitoring_cursor.next_window_start` identifies the first
unresolved minute in its pinned range. Every earlier minute has verified output.
Scope membership and pins remain immutable; runtime grants permit only
operational updates and do not permit deletion.

Discovery returns at most one oldest pending completed minute per scope and
rotates the starting scope, including when previous work failed. The finalizer
merges the geographic and legacy paths with alternating priority under the
configured batch bound. Geographic discovery never extends beyond the durable
endpoint, including after a crash.

The existing finalization transaction remains `REQUIRES_NEW`, `READ_COMMITTED`,
and protected by `WindowDecisionLock`. Feature insertion, coverage insertion in
the existing delivery outbox, and the finalized bucket marker commit together.
Missing windows reuse `finalizeMissingWindow`; accepted service evidence can
instead win normal finalization under the same lock. There is no additional
outbox, observation fabrication, cross-database transaction, or KPI zero fill.

After the finalizer proxy returns, a separate transaction acquires the same
window lock and locks the matching cursor. It verifies range participation and
authority, a finalized bucket, the canonical feature identity and matching
coverage ID, topic, key and payload window. Only then does a compare-and-set
advance the cursor by one minute. Missing, failed or mismatched output leaves
the cursor pending. Published coverage still qualifies because the delivery row
is retained. A crash after finalization but before acknowledgment therefore
retries committed output without producing a second feature or coverage fact.

The immutable pins must match the available contract artifact. This version
resolves the process's immutable loaded catalogue and topology; unavailable
historical authority raises an explicit error and leaves old work pending.
Authority changes require a lifecycle handover and cannot reinterpret old work
with the new catalogue.

## Compatibility and verification

Observation, event, feature, coverage and episode identities remain unchanged.
Receipt acceptance, exact closure and late-arrival rules still use the existing
window decision lock. The PostgreSQL tests exercise silence from the first
minute, receipt races, rollback, crash recovery, independent recorder ownership,
continuity bounds, immutable enrollment, and fair bounded discovery. Lifecycle
tests separately check readiness, configuration bounds and bootstrap exclusion.

PR #52 also changes the ingestion integration test's schema assertion. It now
verifies migrator ownership without a fixed table total, permitting V011's two
tables and PR #52's two tables while retaining the
runtime isolation checks. This is the only shared changed file; integration must
retain PR #52's additional runtime privilege assertions when resolving that
small test overlap.
