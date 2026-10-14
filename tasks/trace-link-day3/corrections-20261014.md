# PR #104 correction interface — 14 October 2026

Original PR head: `f41dfbb017ad4f11e7a1f18b0996a3868889ff75`.
Pinned main: `9db3e0e5a61a8062310e560e734a5395758f70b7`.
Coordinator branch: `feat/trace-link-day3-review-corrections`.
The alternate detector V006 has only been applied to disposable tests.

## Frozen boundaries and ownership

Coordinator owns main merge, shared POM/config, both OpenAPI copies, generated
dashboard types, orchestration, final verification and evidence packaging.
Agent A owns incident V011 and incident migration tests; new processor
fresh/V016-upgrade tests. Main incident V001–V010 and processor V001–V016 must
remain byte-identical. Processor V017 remains unchanged. No repair/reset or
out-of-order migration workaround. Only one Maven process per checkout.
Agent B owns the five-minute receiver parser/service/controller, a new view
record and its focused/unit/integration/Kafka tests. No SQL/OpenAPI/shared edits.
Agent C owns scope/runbook/ledger/checklist corrections and a dated correction
report. Original compressed evidence and manifests are immutable. No invented
test totals, personal approvals or current-source capacity claims.

## Schema and API decisions

Incident V011 creates the detector evidence/incidents/audit tables formerly in
the disposable detector V006. Add `detected_at timestamptz NOT NULL` to the
incident table. Keep evidence payloads, fingerprints and lifecycle IDs unchanged.
On contiguous OPEN, use the validated preceding-breach anchor for
first_observed_at and OPEN context.evaluatedAt for detected_at. Updates never
rewrite either. last_observed_at continues following the latest window end.

List/detail return `TraceLinkIncidentView` with exactly episodeId,
firstObservedAt, detectedAt, latestWindowEnd, latestSequence, technicalState,
severity, latestDetection. Timestamp fields are UTC ISO instants; sequence is
a positive long. latestDetection is the unchanged strict wire envelope.
List is `TraceLinkIncidentPage`; evidence uses `TraceLinkDetectionEvidencePage`
whose items remain strict envelopes. Java may use generic Page<T> internally.
Keep current routes/security/default-off flags and pagination bounds. Do not
feed a read DTO into the wire validator; test exact payload equality through
latestDetection and the evidence endpoint.

## Scope and acceptance

Implement the review's timestamp regression: onset 08:35, OPEN interval starts
08:40, initial decision 08:45:01. Test database/list/detail stability through
update/escalation/unknown/recovery/reorder/replay/restart, with unchanged raw
evidence. Test populated main-to-V011 upgrade, fresh installation, unchanged
checksums/rows/grants, idempotency and processor V016-to-V017.

These endpoints are read-only technical incident/evidence views. No analyst
assignment/status/investigation/resolution/workflow audit exists for hash-ID
episodes; no mapping to legacy UUID incidents is introduced. Keep upstream
60/300-second compatibility, simultaneous capacity and personal sign-offs open.
No long benchmark, remote push, PR merge, deployment or activation is authorized.
