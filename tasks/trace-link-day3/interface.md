# Trace Link Day 3 implementation interface

Base: `f004f765373753e43adaf714b85bbd1501ccf0bd`.
Coordinator branch: `feat/trace-link-day3-integrated-detection`.
Agents: measurements, runtime, receiver in exclusive `tmp/day3-*` worktrees.

## Boundaries

Five-minute finalized synthetic input is `trace-link-persisted-window-v1`:
`schemaVersion`, `finalized=true`, `context` (Day 2 evaluation), `candidates`,
`controls`, `dependencySnapshot`. No expected labels or scenario truth.
Native evidence adds the compatible provenance origin `NATIVE_OBSERVATION`,
requiring the original observation checksum; reference population limitations
continue to apply to REFERENCE_CSV/REFERENCE_ENGINE only. Frozen V2 is unchanged.

`TraceLinkWindowAdapter`: public noarg constructor; `context(JsonNode)` returns
validated context; `evaluate(JsonNode)` returns rule results;
`cause(JsonNode context, JsonNode result, JsonNode persistedWindow)` returns cause.
Trusted dependency snapshots come from packaged versioned synthetic authority,
not a relation object supplied by the input. Baseline artifacts require SHA-256.

Delivery envelope `trace-link-detection-v1` contains exactly `schemaVersion`,
`synthetic=true`, `lifecycle`, `context`, `cause`, `causeEvidence`, `committedAt` (UTC Z).
`causeEvidence` retains saved candidates, controls and dependencySnapshot, separately
from selected cause metadata, so accepted and rejected source evidence is traceable.
Lifecycle is the unchanged Day 2 transition. Escalation is increased-severity
UPDATE, not a new phase. Topic `telecom.detections.trace-link.v1`, key episodeId.
Jackson 2 producer and Jackson 3 receiver independently enforce the same schema
and semantic identity. Canonical JSON sorts object keys recursively.

## Ownership and migration ledger

Coordinator: contracts/resources, POM/config/topic/OpenAPI edits, migrations,
integration fixture/verification, benchmark and evidence. A: adapter, authority,
joiner, narrow input provenance validation. B: new runtime/store/worker/publisher
classes and tests. C: new receiver/parser/service/controller/consumer and tests.

Processor V014 inventory, V015 history, V016 domain windows remain reserved to
the upstream team. Day 3 detector's independent persisted inputs/state/jobs/
outbox use V017__trace_link_detection_runtime.sql. Incident V005 notification
reservation remains untouched; V006__trace_link_read_models.sql owns dedicated
Trace Link evidence/projection/audit. V007 query-index reservation remains.
No existing migration is renamed or edited. Reconcile reservations on merge.

Activation defaults false. Four detector workers; per-scope serial processing,
fenced leases, atomic state/evaluation/outbox, ordered per-episode publication.
LIVE scoring freezes the first actual evaluation time on claim and preserves it
across retries; EVENT_TIME_REPLAY is explicit for historical experiments. Original
source observations stay immutable; quiet rule outcomes are durably retained.
Finalized inputs are immutable by stream/window even across revisions.

Receiver read API: `/api/v2/trace-link/incidents`, `/{episodeId}`,
`/{episodeId}/evidence`; list/evidence `{items,total,limit,offset}`;
limit 1..100 (default 50), offset 0..100000 (default 0).

## Acceptance

One durable received episode per domain; exact lifecycle identities; controls,
replay, conflicts, reorder, restart and concurrency; legacy suites and frozen
hashes; measurement report distinguishes real time and accelerated replay.
No receiving-owner approval is implied by implementation or tests.
