# Trace Link primitive projection consumer implementation

This incident-service implementation accepts the producer's
`trace-link-primitive-projection-v1` message through
`TracePrimitiveIngestionService.ingest(byte[])`. It does not subscribe to a topic
or release the producer's held outbox. The producer/consumer delivery agreement
in `trace-link-consumer-handoff-v1.md` remains pending review.

## Registration and acceptance

The incident service must register a completed synthetic dataset in
`app.trace_dataset`, set `source_completed_at`, and register the active
`trace-link-geography-v2` catalogue with its city inventory before ingesting.
The consumer validates the published JSON schema and recomputes projection
identity, native source identity, 300-second window, ratio and quality. It
requires the registered dataset manifest, generator version, seed, history,
catalogue, topology and city to match the payload. A missing or inactive
registration rejects ingestion.

V008 stores the producer's exact payload bytes and SHA-256 alongside typed
primitives, source quality and coverage, quality reason, slot counts and source
row provenance. Replaying identical bytes returns `IDENTICAL_RETRY`. Reusing a
projection identity or native slot with different bytes is rejected. Runtime
access can insert and read projections but cannot update or delete them.

## Query mapping

The producer's `VOLTE` domain maps explicitly to the existing lower-case
`volte` API domain. Its metric remains `volte.setup.success`, with
`trace-link-kpi-v1`, `PERCENT` and `RATIO_OF_SUMS`; it is not aliased to the
legacy `voice.success_rate` metric. Native and rollup points expose source
quality/coverage, contribution IDs and provenance, expected/received slot
counts, quality and null reasons. Empty buckets return `MISSING` and
`NO_SOURCE_SLOTS`; zero-denominator buckets return a null value and
`INSUFFICIENT_EVIDENCE`. Query bounds expose partial edges, and a projection
whose topology differs from the registered catalogue yields a conflict.

The isolated producer fixture and PostgreSQL integration tests cover insert,
retry, conflicting bytes, inactive catalogue, empty and partial buckets,
zero denominator, source quality, fresh migrations and upgrades.
