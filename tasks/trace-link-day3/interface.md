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

Correction baseline: main `9db3e0e5a61a8062310e560e734a5395758f70b7` contains
processor V001–V016 and incident V001–V010. Preserve those migrations unchanged.
Day 3 independent persisted inputs/state/jobs/outbox use processor
V017__trace_link_detection_runtime.sql after V016. Dedicated detector
evidence/projection/audit use incident V011__trace_link_detection_runtime.sql
after V010, including `detected_at NOT NULL`. Main's V006 read models are retained;
the conflicting branch-only alternate V006 existed only in disposable tests.
No repair/reset/checksum replacement or out-of-order migration is permitted as
an integration workaround. See `corrections-20261014.md` for frozen correction
ownership and the shared migration ledger for allocation history.

Activation defaults false. Four detector workers; per-scope serial processing,
fenced leases, atomic state/evaluation/outbox, ordered per-episode publication.
LIVE scoring freezes the first actual evaluation time on claim and preserves it
across retries; EVENT_TIME_REPLAY is explicit for historical experiments. Original
source observations stay immutable; quiet rule outcomes are durably retained.
Finalized inputs are immutable by stream/window even across revisions.

Receiver read API: `/api/v2/trace-link/incidents`, `/{episodeId}`,
`/{episodeId}/evidence`; list/evidence `{items,total,limit,offset}`;
limit 1..100 (default 50), offset 0..100000 (default 0).
List/detail use `TraceLinkIncidentView`: episodeId, firstObservedAt, detectedAt,
latestWindowEnd, latestSequence, technicalState, severity and latestDetection.
The nested latestDetection and evidence-page items remain strict wire envelopes.
The OpenAPI list is `TraceLinkIncidentPage`; evidence is
`TraceLinkDetectionEvidencePage`.

On contiguous OPEN, firstObservedAt is the validated first-breach anchor and
detectedAt is OPEN context.evaluatedAt. Neither changes on subsequent transitions;
latestWindowEnd follows the latest contiguous window. These technical reads do
not provide analyst assignment/status/investigation/resolution/workflow audit
or connect hash episodes to legacy UUID incidents.

## Acceptance

One durable received episode per domain; exact lifecycle identities; controls,
replay, conflicts, reorder, restart and concurrency; legacy suites and frozen
hashes; measurement report distinguishes real time and accelerated replay.
No receiving-owner approval is implied by implementation or tests. Upstream
60-second windows are a different versioned contract; there is no implicit
60-to-300-second bridge. Connected capacity, upstream compatibility and analyst
workflow acceptance remain open.
