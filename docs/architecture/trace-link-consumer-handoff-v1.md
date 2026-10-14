# Trace Link producer / consumer decision package

Consumer approval: **PENDING_REVIEW**. Denis has not approved this mapping.
This is a proposal based on the [producer agreement](trace-link-projection-v1.md),
not authorization to activate Kafka delivery or change incident-service storage.

Producer source SHA: `bb52660f762ae696c1de37aa400cef589b06e17d`.
The final PR head and its source-tree equivalence are recorded in PR #92 and
the [final verification report](../evidence/2026-10-12-trace-link-final-hardening.md).
PR #92 remains draft, stacked on #91 at
`1e67e61ce3f2c46f26818b42c17fc3d50481aae4`.
Consumer comparison: #89 `fec8905d7ba5563a8ebfe0aa541c16b1cb93b75b`,
#90 `923a9f009ce402b84cc26882466e7c2e3989a136`. Neither branch is modified.

## Exact inputs and proposed mapping

All paths below are repository paths at the producer source SHA.

| Concern | Producer fact / proposed consumer requirement |
| --- | --- |
| Wire schema | `contracts/kpi/trace-link-primitive-projection-v1.schema.json`; `schemaVersion=1`, `messageType=trace-link-primitive-projection-v1` |
| Reproducible fixture | `contracts/fixtures/trace-link/primitive-projection-cases-v1.json`, independently derived from `contracts/fixtures/trace-link/native-slice/city_kpi_5m.csv` and its `manifest.json` |
| Dataset | Full archive `trace-link-md-30d-v1`, manifest `contracts/datasets/trace-link-md-30d-v1.manifest.json`; SHA-256 `e3033f025f6b5866edc0ed34f3078cfa9f9c01cb126eac4577055ef30247ee1b`; synthetic=true; generator `reference-1.0`, seed 42; history `[2026-09-10T09:00:00Z,2026-10-10T09:00:00Z)` |
| Bounded fixture identity | `trace-link-bounded-fixture-v1`, one native slot; its own manifest/hash and projection ID. Do not relabel the bounded fixture as the full dataset or reuse its projection ID |
| Catalogue / topology | `trace-link-geography-v2` / `trace-link-links-v1`; `contracts/geography/trace-link-geography-v2.json` and `contracts/topology/trace-link-links-v1.json`; catalogue activation remains `CONTRACT_ONLY` |
| Dictionary | `contracts/kpi/trace-link-kpi-definitions-v1.json`; `metricId=volte.setup.success`, policy `trace-link-kpi-v1`, unit `PERCENT`, aggregation `RATIO_OF_SUMS`, minimumVolume=null |
| Exact population | Established eligible calls / eligible network setup attempts. V2 technical-success/user-outcome eligibility remains separately versioned. City reference cohorts are disjoint fictional cohorts |
| Primitives | Stored `voice_success` -> numerator; stored `voice_attempts` -> denominator; nonnegative safe integers, numerator <= denominator. `100 * SUM(numerator) / SUM(denominator)`; never average percentages |
| Domain translation | Wire `VOLTE` -> proposed API/read-domain `volte`, explicitly at the consumer boundary. Canonical metric stays `volte.setup.success`; `voice.success_rate` is not silently aliased |
| Version validation | Verify schema, dictionary policy, import policy `trace-link-native-source-v1`, dataset manifest/completion, catalogue and topology agreement, native duration and unit. Reject source topology differing from the registered catalogue, rather than accepting the source shape's topology |
| City scopes | Metric `CITY`, reference city ID and explicit contract city ID: chi/CHI, bal/BAL, cah/CAH, ung/UNG, orh/ORH, sor/SOR, com/COM, edi/EDI, hin/HIN, cau/CAU. No legacy runtime scope authority is implied; observation `CITY_SERVICE` is a separate contract |
| Partner scopes | Unsupported by this producer v1. Reject partner/direction/country/procedure fields on CITY messages. Future roaming `PARTNER_DIRECTION` must retain partner ID, country and in/out direction as distinct dimensions; country is not a partner identity and cannot be substituted for one |
| Time | Whole UTC `[from,to)` native 300-second slots; 5m/15m/1h/6h whole UTC buckets. Requested/effective bounds and partial edge flags must be exposed. Include only native slots wholly contained in requested bounds; no proration or upsampling |
| Slot coverage | `coverage.expectedSlots`, `receivedSlots`, `ratio` measure row presence, independently of source quality. A native message is 1/1; an empty rollup is 0 received with null primitives/value. Partial buckets keep their whole-bucket expected count |
| Source quality | Nullable `sourceQuality` and `sourceCoverage` are retained. Unknown source quality yields `INSUFFICIENT_EVIDENCE` even with a computed value. Raw VALID plus null coverage yields insufficient; raw VALID plus partial coverage yields effective PARTIAL. Known non-MISSING/non-VALID source states retain their meaning, with an unavailable reason if coverage is null, subject to zero-denominator precedence. Effective VALID requires full known source coverage and computability |
| Contradictions | Reject source MISSING with measured primitives, and zero source coverage with a positive observed population except retained STALE evidence. Preserve measured zero, unavailable source metadata and retained stale primitives/provenance. No source-history repair or invented data |
| Null reasons | Native denominator zero -> value=null, `ZERO_DENOMINATOR`; empty rollup -> null primitives/value, `NO_SOURCE_SLOTS`, MISSING. Unknown source quality/coverage reasons remain distinct from these arithmetic/absence reasons. STALE has precedence over insufficient computability |
| Provenance | Retain source natural ID, source CSV filename, row number, row SHA-256, manifest SHA-256, generator version, seed, catalogue version, import policy, dataset/source namespace, topology, duration, synthetic flag and exact projection IDs contributing to rollups |
| Deduplication | Recompute projection ID from the frozen tuple in the producer agreement; revision=0 and finalized=true. Exact retry has the same ID, immutable payload bytes and SHA-256, and one held outbox row. Same finalized identity with differing content fails closed; do not silently prefer a later revision or acknowledge conflicting content |
| Delivery proposal | Topic `telecom.trace-link.metric-projections.v1`; partition key below. No topic is provisioned, configured or published by PR #92. Held V016 intent stays PENDING_REVIEW |

Proposed partition key:

```text
datasetId|trace-link-reference-v1|VOLTE|CITY|referenceCityId|300
```

## Consumer compatibility and required additive changes

The [isolated report](../evidence/assets/trace-link-final-hardening/consumer-compatibility.json)
records exact #90 blob hashes and read-only schema checks. Run:

```powershell
python scripts/check-trace-link-consumer-compatibility.py `
  --consumer-ref 923a9f009ce402b84cc26882466e7c2e3989a136 `
  --output target/consumer-compatibility.json
```

Direct wire-domain insertion fails V006's lowercase enum. Applying the proposed
domain translation still fails its `quality VARCHAR(16)` enum: the producer's
`INSUFFICIENT_EVIDENCE` is 21 characters and is not allowed. CITY, RATIO_OF_SUMS,
PERCENT, 300 seconds and primitive numeric capacity are reusable. The registry
currently requires `voice.success_rate` / `trace-link-md-policy-v1`; both differ.

Denis must choose an additive migration after his applied V006/V007, with the
next available number checked by its owner. Do not edit applied migrations.
It must support the effective quality enum/width; separate nullable source
quality/coverage; explicit slot counts; projection ID and natural identity;
schema/source namespace; immutable payload bytes/hash; source and manifest
provenance; and deduplication constraints that reject conflicting content.
Existing JSONB may retain provenance, but current read DTOs do not expose it.
Dataset, catalogue, city and topology registration must remain incident-owned
and inactive until separately approved. No cross-database access is needed.

The query layer must retain insufficient/stale distinctions, null reasons,
source metadata and contributing IDs; compare topology with the catalogue;
count slots independently of source coverage; respect requested partial edges;
and preserve zero-denominator semantics. Current #90 collapses non-VALID rows
to PARTIAL, can call a null zero-denominator value VALID, and includes slots
outside requested partial edges. These findings are source/contract checks,
not execution of a deployed consumer or authenticated API.

After explicit approval, a separate producer delivery change would need
additive ACK/lease state and the approved topic: publish exact persisted bytes,
mark only after ACK, and replay the same ID after an ACK/mark interruption.
Consumer acknowledgement must follow durable insertion/content comparison.
That change is outside this PR's activation boundary.

## Proposed authenticated API acceptance

These are acceptance examples to run after an approved consumer exists. They
have not been accepted through a connected API. Use the existing incident
session authentication and keep credential values out of evidence. The proposed
API domain translation and canonical metric require Denis's decision.

```http
GET /api/v2/metrics/series?domain=volte&metric=volte.setup.success&scopeType=CITY&scopeId=chi&from=2026-09-10T09:00:00Z&to=2026-09-10T09:05:00Z&resolution=5m&datasetId=trace-link-md-30d-v1
GET /api/v2/metrics/series?domain=volte&metric=volte.setup.success&scopeType=CITY&scopeId=chi&from=2026-09-10T09:00:00Z&to=2026-09-10T10:00:00Z&resolution=5m&datasetId=trace-link-md-30d-v1
GET /api/v2/metrics/series?domain=volte&metric=volte.setup.success&scopeType=CITY&scopeId=chi&from=2026-09-10T12:00:00Z&to=2026-09-10T18:00:00Z&resolution=6h&datasetId=trace-link-md-30d-v1
```

The first returns numerator 7900, denominator 7957, percent approximately
99.2836496166897, slot count 1/1, both source fields null and effective
INSUFFICIENT_EVIDENCE. Verify the actual full-dataset ID and manifest, source
row hash `7fdcd4963c7c76d121a6fed0685a3f299ac82eef69f2457b881308cb6ceff63c`,
five-minute bounds and the matching full-dataset projection ID.
The second returns 12 actual native points with independently matched CSV
primitives and source row/projection provenance, with no one-minute facts.
The third returns numerator 297917, denominator 299992, percent approximately
99.3083148883970, slot count 72/72, null source fields and effective
INSUFFICIENT_EVIDENCE. Compare against independent native SQL sums.

Also require: unauthenticated/expired-session requests denied by existing API
security; wrong city/domain/metric/policy/topology rejected; partial-edge and
missing-bucket behavior; measured zero; zero-denominator null; retained stale
measurements; identical replay without duplicate rows; conflicting retries
rejected; and Kafka send/ACK/mark failure and restart recovery. Mock or local
producer rollups cannot close these connected acceptance gates.

## Smallest next step after the decision

First record Denis's explicit decision on identifiers, population, versions,
quality, provenance, topic/key and additive read model. Approval remains
PENDING_REVIEW until that record exists. The smallest proposed implementation
step after approval is one incident-owned, isolated fixture acceptance test
against an additive read-model migration, covering the native message and
identical/conflicting retry. Keep its catalogue inactive and use no production
topic. This step has not been started. Connected ingestion/delivery and
authenticated acceptance follow as separate approved work.
