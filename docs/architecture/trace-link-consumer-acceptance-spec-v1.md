# Trace Link consumer fixture and migration acceptance specification v1

Decision: **PENDING_REVIEW by Denis**. No consumer, Kafka publisher, topic or
incident-owned migration is implemented by this specification. V016 remains
unchanged and its outbox stays PENDING_REVIEW.

Current consumer source is main `ae9a53626ac7ee62efec40a19ebb20c6e2cf7049`,
which contains merged #89 `fec8905d7ba5563a8ebfe0aa541c16b1cb93b75b` and
#90 `923a9f009ce402b84cc26882466e7c2e3989a136`.
The parallel producer projection is #92
`b987aa809101eedc1d40d52c95dff94a1e9e27b8`; native new-domain producer is #93
`e9a79c1f2fa2c5a59b366336e74b4e3b2b94bbb8`.
The assembled verification tools are at
`f572ebd27c71cb809eb43fa51f07f40740ae181c`, including the Windows LF packaging
attribute and corrected archived gap extraction. Java, SQL Git blobs and
contracts are identical to the combined merge snapshot.

## Exact compatibility comparison

The fresh `consumer-compatibility-current.json` report compares actual Git blobs at
current main. The five inspected consumer blobs are byte-identical to #90.
The existing [VoLTE agreement](trace-link-consumer-handoff-v1.md) remains a
separate proposal and supplies the detailed population and primitive rules.

| Dimension | #92 VoLTE / #93 new-domain producer | Merged consumer | Required decision |
| --- | --- | --- | --- |
| Wire message | #92 schemaVersion 1, messageType trace-link-primitive-projection-v1; #93 trace-link-domain-primitive-result-v1 wraps an observation and availability/provenance | No approved ingestion wire interface | Keep these different shapes explicit; approve separate versioned mapping(s) |
| Domain | VOLTE; TRANSPORT, ROAMING, MOBILE_DATA | SQL allows lowercase volte, transport, roaming, sms, mobile-data | Explicit boundary translation; direct VOLTE insertion rejects |
| Metric/population | volte.setup.success: eligible established calls / eligible network setup attempts; #93 IDs below | Registry exposes only voice.success_rate, described as VoLTE call attempts in the selected city | No alias or population equivalence assumed; explicitly register canonical metric(s) after agreement |
| Policy | trace-link-kpi-v1 plus native import trace-link-native-source-v1 | trace-link-md-policy-v1 | Preserve both purposes; select compatible versions, do not relabel source |
| Topology/catalogue | trace-link-links-v1 / trace-link-geography-v2 | Fixture/reference topology trace-link-md-topology-v1; incident-owned catalogue table | Explicit versioned registration/binding; current query source shape is not compared against catalogue topology |
| Scope | #92 CITY with chi and contract CHI; #93 LINK_DIRECTION with link/direction, ROAMING_PROCEDURE with partner/country/direction/procedure, CITY_SERVICE with city/LTE/procedure | SQL CITY, LINK_DIRECTION, PARTNER_DIRECTION; current query requires CITY; direction, partner_id, procedure are separate columns | Reject lossy scope conversion; register the synthetic partner and preserve country/LTE dimensions |
| Effective quality | VALID, PARTIAL, MISSING, STALE, INSUFFICIENT_EVIDENCE | quality VARCHAR(16), only VALID/PARTIAL/MISSING/STALE | INSUFFICIENT_EVIDENCE has 21 characters and fails width and enum; additive owner migration required |
| Source metadata | Nullable sourceQuality and sourceCoverage; slot presence independently counted | One non-null coverage value and one effective quality column | Preserve unknown source metadata separately; no replacement with slot presence or healthy values |
| Null semantics | Zero denominator, no source slot, unavailable quality/coverage and retained stale evidence remain distinct | DTO lacks nullReason; zero denominator can have null value with VALID quality; non-VALID rows collapse to PARTIAL | Retain arithmetic/absence reasons and effective quality; no healthy recovery from missing/insufficient evidence |
| Time | Native UTC 300s; complete source slots within requested bounds; effective UTC bucket edges separately disclosed | SQL enforces 300s aligned windows; ratio query reads effective rather than requested bounds | Native duration reusable; approve precise partial-edge behavior; live 60s input remains a separate incompatible interface |
| Provenance | Original source natural ID, CSV/ordinal/row hash, manifest, generator, seed, source namespace, versions and projection IDs | payload JSONB exists; DTO omits projectionId/sourceNamespace/sourceQuality/sourceCoverage/nullReason/provenance/projectionIds/expectedSlots/receivedSlots | Add immutable storage and reads for agreed provenance; JSONB alone does not demonstrate read acceptance |
| Deduplication | #92 finalized revision 0, projection ID and natural-slot uniqueness; exact payload_text bytes/hash immutable; changed finalized content fails | PK includes dataset/domain/metric/scope/dimensions/time/policy/topology/revision; query prefers latest revision | Latest-revision selection is not conflicting-retry validation; explicit event/content uniqueness and durable comparison required |
| Primitive SQL | #92 nonnegative safe integers; numerator <= denominator | NUMERIC(22,6), nonnegative numerator/denominator; no numerator <= denominator constraint in V006 | Capacity fits current counters; enforce agreed ratio constraint at admission and storage |
| Delivery | #92 held SQL intent, no publisher; #93 read-only local artifacts, no delivery intents | No approved connected new-domain consumer | Keep Kafka disabled; no release of held intent without separate authorization |

#93 produces eleven native metrics: transport.traffic and
transport.link.utilization (TIME_WEIGHTED_MEAN); transport.effectiveCapacity,
transport.rtt.snapshot and transport.jitter (SNAPSHOT_LAST);
roaming.registration.success, roaming.data.success, roaming.voice.success,
mobileData.activation.success and mobileData.dns.success (RATIO_OF_SUMS);
mobileData.throughput (WEIGHTED_MEAN).

Native reference observations have INSUFFICIENT_EVIDENCE and nullable source
quality/coverage. RTT/jitter/capacity are snapshots, not sample means; throughput
is a modeled city-slot scalar with count 1, not user/session weighting. DNS is
reference-only and historical activation eligibility is unproven. No reverse
link measurement, packet counts, RTT counts, finalized SMS population or active
subscriber count may be inferred. Missing reverse-direction controls are
MISSING with null primitives. These qualifications must survive consumer reads.

## Isolated acceptance harness after the decision

Denis owns the harness and next migration number. Current incident migrations
end at V007; refresh the owner ledger before reserving a subsequent additive
number. Never edit applied V006/V007 or processor V016. Use a disposable
PostgreSQL database initialized with the repository's actual roles, an inactive
catalogue and no Kafka connection. No cross-database access is required.

Inputs are `contracts/fixtures/trace-link/primitive-projection-cases-v1.json`,
the eight-file native-slice archive, and
`contracts/fixtures/trace-link/domain-primitive-results-v1.json`, all pinned by
Git blob hashes in the integration manifest. Keep the bounded dataset ID
`trace-link-bounded-fixture-v1`; do not relabel it as the full archive.

The first #92 chi fixture must retain numerator 7900, denominator 7957,
approximately 99.2836496166897 percent, one present/expected slot, null source
metadata, INSUFFICIENT_EVIDENCE, and row hash
`7fdcd4963c7c76d121a6fed0685a3f299ac82eef69f2457b881308cb6ceff63c`.

| Case | Isolated operation and required assertion | Evidence |
| --- | --- | --- |
| Fresh/upgrade | Apply additive migration fresh and after exact V007; compare columns, constraints, ownership and runtime grants; migrate again applies zero | Flyway history/checksums, schema/grant inventory and SQLSTATEs |
| Valid fixture | Insert via approved mapping; select through read DTO; compare every identity/version/dimension/primitive/source/null/provenance field | Input/output hashes and one row |
| Exact replay | Repeat original ID and exact UTF-8 bytes, including concurrent attempts | One logical row; unchanged payload_text/hash and aggregate |
| Conflicting replay | Same event ID or finalized natural slot, changed content; changed digest cannot bypass natural uniqueness | Explicit conflict, rollback and unchanged original row |
| Populations | Ratio of sums 990/1000 and 9/10 yields 98.9108910891 percent; positive-denominator zero stays zero; zero attempts stay null | SQL-independent oracle and null reason |
| Quality | Unknown metadata, partial presence, partial source coverage, stale retained primitives, full missing bucket and contradictory source states | Separate source/effective fields; no fabricated healthy values |
| Bounds | 5m/15m/1h/6h plus requested partial edges and empty buckets; reject 1m native-history query and incompatible duration | Requested/effective bounds, contributing IDs and independent slot counts |
| Native new domains | Admit only approved metric/operator/scope mappings; retain approximation and availability qualifiers; reject mixed partner/direction/procedure/version | Per-metric fixtures and negative records |
| Roles | Runtime cannot DDL, mutate/truncate finalized history, release producer intent or connect to processing_db/keycloak_db | Expected SQLSTATE 42501 and unchanged rows |
| Authenticated component reads | Existing session fixture: unauthenticated 401, authorized scope reads, invalid metric/time/scope 400 | MockMvc statuses, not a real-browser login claim |
| Connected commit/ACK | After separate transport approval: crash before commit, after commit before offset ACK, send before ACK, uncertain ACK and ACK before publication mark | Durable SQL identity/hash, actual offsets and one logical result |
| Real session/API/UI | After approved deployment: normal login, expiry, role isolation, authorized series and denied unauthorized access on same release | Sanitized real HTTP/browser evidence, never cookies/tokens |

Current executable comparison is:

```powershell
python scripts/check-trace-link-consumer-compatibility.py `
  --consumer-ref ae9a53626ac7ee62efec40a19ebb20c6e2cf7049 `
  --output target/consumer-compatibility.json
```

Expected result: the checker exits 0 because it **reproduces incompatibility**;
the report says INCOMPATIBLE and PENDING_REVIEW. It does not mean consumer
acceptance passed. The proposed fixture ingestion and additive migration tests
remain BLOCKED until Denis records the mapping decision. No production consumer
branch, consumer source or consumer database was changed.
