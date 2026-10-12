# Trace Link primitive projection v1

Producer interface: **FROZEN v1**. Consumer agreement: **PENDING_REVIEW**.
No Denis approval was recorded in PRs #89/#90 on 12 October 2026; the requester
confirmed that approval is absent. This document does not grant that approval.

## Reviewed dependencies and ownership

| Dependency | Reviewed head | State at inspection |
| --- | --- | --- |
| main / merged KPI PR #88 | `cbb94d37f7593e99737ee4200e91b14e0512f76f` | Dictionary and 54 arithmetic cases merged |
| Ion PR #91 | `1e67e61ce3f2c46f26818b42c17fc3d50481aae4` | Draft; five checks passed, processor check running |
| Denis PR #89 | `fec8905d7ba5563a8ebfe0aa541c16b1cb93b75b` | Open; six checks passed |
| Denis PR #90 | `923a9f009ce402b84cc26882466e7c2e3989a136` | Open, stacked on #89; six checks passed |

No inline comments or approving reviews were present on #89/#90/#91. Their
branches are not changed. This producer branch is stacked on #91.

The processor owns immutable native rows (V014/V015), validated projections,
and durable delivery intent (V016). Incident-service must own Kafka validation,
idempotency, dataset/inventory registration, immutable metric insertion, and
authenticated reads. There are no cross-database reads or writes.

## Message, identity, and provenance

`contracts/kpi/trace-link-primitive-projection-v1.schema.json` defines the producer
message. It is a primitive measurement, **not** a detector evidence envelope.
The schema validates shape. Consumer semantic validation must also recompute
identities, verify the city translation, UTC alignment/duration, primitive
relationships/value, dataset compatibility and the documented quality rules.
The evidence v1 schema requires freshness/baselines/rules and cannot represent
unknown source quality without additional semantics; it is not repurposed.

Only `VOLTE / volte.setup.success / CITY / trace-link-reference-v1` is supported.
Scope ID is the reference ID, e.g. `chi`; `contractCityId=CHI` retains the explicit
catalogue translation. No legacy `VOLTE-MD-CHI` source authority is inferred.
The population, `PERCENT` unit, `RATIO_OF_SUMS`, and `trace-link-kpi-v1` policy
come from the merged dictionary. Numerator is stored `voice_success`;
denominator is stored `voice_attempts`. This is not legacy V2 technical CSSR.

Projection ID is SHA-256 of the pipe-separated tuple:

```text
datasetId|sourceNamespace|VOLTE|volte.setup.success|CITY|referenceCityId|
windowStart|300|trace-link-primitive-projection-v1|trace-link-kpi-v1|
topologyVersion|revision=0
```

Dataset IDs and scope IDs are validated; delimiter injection is rejected.
Revision is 0 and finalized is true, independently. A changed source archive
requires a new dataset namespace. Identical retry preserves the ID, payload,
hash and outbox row; conflicting content under an existing ID fails closed.
V016 stores the exact serialized `payload_text` alongside JSONB, with a database
check binding the two representations; the hash covers those immutable bytes.
The message retains source natural ID, CSV filename, row ordinal and checksum,
manifest checksum, generator version, seed, catalogue and topology versions,
native duration, dataset and `synthetic=true`. It has no retry-time timestamp.

## Quality, coverage, and time

All source intervals are whole UTC-aligned `[start,start+300s)` measurements.
No five-minute measurement becomes a one-minute fact.

`coverage` measures presence: expected native slots, received native slots, and
their ratio. One persisted row contributes one received slot. Missing slots
are query results only, with no invented persisted row. Archive checksums are
integrity evidence; they never establish measured service health.

`sourceQuality` and `sourceCoverage` preserve source-supplied nullable values.
Unknown quality or source coverage produces `INSUFFICIENT_EVIDENCE`, even when
the arithmetic value is computable and slot coverage is 1. Known non-VALID
source quality is retained. Zero denominator produces null value with
`ZERO_DENOMINATOR`; empty buckets have null primitives/value, coverage 0,
`MISSING`, and `NO_SOURCE_SLOTS`. No health or recovery state is inferred.
Historical age does not assert live freshness. The dictionary has no approved
minimum-volume threshold, so none is invented.

The [Day 1 state matrix](../contracts/trace-link-day1.md#evidence-state-consistency)
defines absence, observed zero and retained stale evidence. This primitive
message always has measured numerator/denominator, so `sourceQuality=MISSING`
is rejected; missing slots are represented only in query results. Explicit zero
source coverage with a positive measured population is rejected, except for
legitimately retained `STALE` measurements. No source record is rewritten.

Source metadata and effective quality are separate fields: a supplied `VALID`
label with unavailable coverage remains visible as source metadata but yields
`INSUFFICIENT_EVIDENCE / SOURCE_COVERAGE_UNAVAILABLE`; partial source coverage
yields `PARTIAL / SOURCE_COVERAGE_PARTIAL`. Effective `VALID` requires known
VALID source quality, full source coverage and a computable value. Zero
denominator cannot remain effective VALID or PARTIAL, even in a partial rollup
bucket. Unknown source metadata keeps its unavailable reason; `STALE` takes
precedence and retains primitives, value when computable, and provenance.
These rules do not turn primitive projections into detector evidence envelopes.

Rollups use sums of primitives for 5m, 15m, 1h and 6h. Buckets are whole UTC
aligned buckets. For a non-aligned query, only native slots wholly contained
in `[from,to)` contribute; a partial source interval is excluded, never prorated.
The response echoes requested/effective bounds and edge flags; expected slots
cover the whole bucket, so partial edges reduce coverage. Bounds are at most
30 days and must remain within the dataset's declared history. Mixed policy,
schema, topology, duration or unit is rejected before aggregation.

## Proposed consumer agreement requiring Denis review

| Concern | Producer v1 | Current #90 | Proposed action, not an alias |
| --- | --- | --- | --- |
| Metric | `volte.setup.success` | `voice.success_rate` | Add canonical dictionary metric; retain/reject old identifier explicitly |
| Domain | `VOLTE` | `volte` | Validate uppercase wire domain; explicit API domain translation only after approval |
| Scope | `CITY`, reference ID plus contract ID | `CITY` | Keep city metric scope; do not convert into observation `CITY_SERVICE` or legacy authority |
| Observation scope | Separate domain-window `CITY_SERVICE` | No native projection ingestion | Producer projection is a separate message type, not an observation alias |
| Policy | `trace-link-kpi-v1` | `trace-link-md-policy-v1` | Consumer must validate the dictionary policy; never rewrite silently |
| Topology | `trace-link-links-v1` | Tests use `trace-link-md-topology-v1` | Register the actual versioned producer topology and validate catalogue agreement; do not rename it |
| Quality | Nullable source fields plus reasoned quality object | V006 requires coverage and VARCHAR(16) quality in VALID/PARTIAL/MISSING/STALE | Add separate nullable source quality/coverage and support INSUFFICIENT_EVIDENCE; never coerce unknowns into VALID or PARTIAL |
| Provenance | Full source and manifest identity | Metric payload, dataset and versions | Validate and retain source provenance in the incident-owned immutable read model |

V006/V007 have reusable primitive/read indexes, but their current query model
does not expose this provenance/quality contract. V006 cannot store the
21-character `INSUFFICIENT_EVIDENCE` state or nullable source coverage; its
required `coverage` column must be explicitly defined as slot coverage if reused.
Any consumer adjustment must
be additive to applied migrations. Current #90 takes the source shape's topology
without comparing it with the catalogue topology; the approved consumer must
reject that mismatch. Its zero-denominator test expects quality VALID; this
producer keeps null value/zero-volume meaning independently and cannot infer
adequate evidence from a full slot count.

Candidate topic: `telecom.trace-link.metric-projections.v1`; candidate partition
key: `datasetId|trace-link-reference-v1|VOLTE|CITY|referenceCityId|300`.
**No topic is introduced, configured, or published by this change.** V016 holds
immutable outbox intent in `PENDING_REVIEW`, with no topic or publishing worker.
Existing feature outbox requires a one-minute V2 payload and is incompatible;
the existing voice delivery scheduler auto-sends arbitrary topics, so it cannot
safely hold unapproved work. After approval, an additive delivery mechanism must
publish the exact persisted payload, mark only after Kafka ACK, and replay the
same projection ID after ACK/mark failure. Consumer deduplication must compare
payload content and reject conflicting retries before acknowledging Kafka.

Incident-service session authentication remains authoritative for
`GET /api/v2/metrics/series`; unauthorized requests must be rejected using its
existing security conventions. Producer local rollups are not public APIs.
No authenticated native-source-to-series result, Kafka recovery result, or
end-to-end acceptance is claimed until the agreed consumer exists and connected
verification passes. Denis must approve the table above and reproduce imported
5m, larger native, and rolled-up query examples before this gate closes.
