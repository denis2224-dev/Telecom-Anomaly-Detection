# Geographic source authority

`GeographyCatalog.load()` loads the versioned geographic contract pair. The bundled catalogue
remains `CONTRACT_ONLY`; deployment activation is explicit. `TopologyCatalog.load()` continues
loading the unchanged `2-baseline` authority and two legacy scopes. Runtime authority selection
belongs to `GeographicRuntimeConfiguration`.

## Artifacts and interfaces

| Artifact | Meaning |
| --- | --- |
| `contracts/topology/geographic-scopes-v2.json` | Strict `2-geography-g1` authority: twenty city scopes plus the exact two baseline scopes; same field set and loader as baseline |
| `contracts/geography/demo-geography-v1.json` | `1-geography-g1` companion, typed containment, footprints and explicit dependency roles |
| `contracts/geography/geography-catalogue-v1.schema.json` | Strict GeographyCatalogueV1 structure; cross-reference semantics additionally validated in Java/Python |
| `contracts/coverage/scope-window-coverage-v1.schema.json` | Strict ScopeWindowCoverageV1; no subscriber fields |
| `GeographyCatalog.resolve(scopeId, Role)` | Returns the exact strict-authority `TopologyCatalog.Node`, including its reporter source; wrong service/absent/ambiguous target fails |
| `GeographyCatalog.expectedSourceIds(scopeId)` | Immutable sorted required measurement source IDs; excludes optional SMS transport |
| `GeographyCatalog.activation()` | Explicit contract status/effective instant; no implicit activation |
| `CoverageContract.coverageId(...)`, `CoverageContract.validate(...)` | Cross-language deterministic identity and semantic payload validation, not receipt collection or delivery |
| `scripts/geography_contract.py` | Offline validator and golden receipt-to-coverage reference; not Rusu's feature reference |

## Equal configured geography

| City ID | Display name | VoLTE scope | SMS scope |
| --- | --- | --- | --- |
| CHI | Chișinău | VOLTE-MD-CHI | SMS-MD-CHI |
| BAL | Bălți | VOLTE-MD-BAL | SMS-MD-BAL |
| EDI | Edineț | VOLTE-MD-EDI | SMS-MD-EDI |
| SOR | Soroca | VOLTE-MD-SOR | SMS-MD-SOR |
| RIB | Rîbnița | VOLTE-MD-RIB | SMS-MD-RIB |
| UNG | Ungheni | VOLTE-MD-UNG | SMS-MD-UNG |
| TIR | Tiraspol | VOLTE-MD-TIR | SMS-MD-TIR |
| COM | Comrat | VOLTE-MD-COM | SMS-MD-COM |
| CAH | Cahul | VOLTE-MD-CAH | SMS-MD-CAH |
| ORH | Orhei | VOLTE-MD-ORH | SMS-MD-ORH |

For each code C, service reporters are `VOLTE-SRC-C` and `SMS-SRC-C`. Containment is
`MD -> CITY-MD-C -> AGG-MD-C-01 -> SITE-MD-C-01 -> CELL-MD-C-01`.
Both service scopes have the same single leaf footprint. Dependency nodes `IMS-MD-C-01`,
`SMSC-MD-C-01`, `TRANSPORT-MD-C-01` are parented to the city for navigation, while their service
dependency edges are defined separately in role bindings. Each dependency's reporter source ID
equals its node ID. IDs meet the existing uppercase/hyphen regex and 64-character bound.

There are 74 unique companion nodes: one country, seven nodes per city, and three unallocated legacy
dependencies. Reuse of the same transport node in two service scopes is intentional; strict authority
prohibits duplicate node IDs/reporters *within* each scope. No city total is a separately measured cell,
site or aggregation KPI. These levels expose membership and dependency context only. All provenance
is synthetic. No coordinates, equipment positions, real network links or subscriber counts are asserted.

Role capabilities and expected raw metrics:

| Role | Scope service | Capability | Required receipt metrics | Required coverage |
| --- | --- | --- | --- | --- |
| VOLTE_IMS | VOLTE | IMS | cpuPct | Yes |
| VOLTE_TRANSPORT | VOLTE | TRANSPORT | packetLossRatio | Yes |
| SMS_SMSC | SMS | SMSC | queueDepth, oldestPendingAgeSeconds | Yes |
| SMS_TRANSPORT | SMS | TRANSPORT | packetLossRatio | No |

The SERVICE source is also required. Thus every city has three VoLTE and two SMS required measurement
receipts/minute: 50 city observations and 20 city KPI windows/minute for the future minimum profile.
Optional SMS transport increases raw traffic to 60 if enabled; it never increases required coverage.
Legacy traffic is counted separately. Heartbeats do not satisfy measurement coverage.

Legacy `VOLTE-MD-CENTRAL` remains `VOLTE-ADAPTER` with `VOLTE_IMS -> IMS-A` and
`VOLTE_TRANSPORT -> TRANSPORT-A`. `SMS-MD-ROUTE-A` remains `SMS-ADAPTER` with
`SMS_SMSC -> SMSC-A` and optional `SMS_TRANSPORT -> TRANSPORT-A`. Both bindings are `legacy=true`,
`cityId=null`, footprint empty, displayed as LEGACY / UNALLOCATED and excluded from city totals.

## Version and activation rules

The supplied candidate has `activation.status=CONTRACT_ONLY` and `effectiveFrom=null`. An ACTIVE
version requires an explicit UTC minute effectiveFrom. Changing authority, role, footprint, display
metadata or activation metadata requires a new immutable catalogue version and a new digest. Authority
changes also require a new topology version. Retain old versioned files for historical resolution;
never reinterpret or re-finalize old windows under a new mapping.

Deploy compatible readers before enabling producers. Drain pending baseline feature and
detection work before switching the default authority, or implement and test a version-aware registry.
`GeographyCatalog` validates an explicitly supplied pair; it is not an automatic version selector or
archive store. Snapshot coverage against the pair effective for that UTC minute. A version change cannot
occur within a minute. Rollback disables geographic production while retaining readers for pending city
facts; do not downgrade to baseline-only authority while such facts remain pending.

The baseline registry explicitly accepts both `2-baseline` and the pinned `2-geography-g1`
feature provenance for the unchanged legacy scopes. Compatibility is checked against the historical
scope authority and the validated geography contract's `legacy=true` bindings; it is not a topology
version prefix or a baseline for city scopes. Features keep the topology version that authorized them.
Pending immutable legacy features from before activation and new legacy features across activation
can therefore complete detection with the same baseline values and episode identities. Unrelated
topology versions remain rejected. City feature construction retains `BASELINE_MISSING` and ML
ineligibility until reviewed city baselines/readers exist.

Geographic publication owns a fixed pool of twenty submission workers and a queue capped at twenty
tasks. At most twenty scope chains are admitted, with one outstanding submission/ACK per scope.
An ACK callback only enqueues the next record; it never invokes potentially blocking `KafkaTemplate.send`.
Each chain owns a +67-second deadline task. Expiry/stop cancels submissions, ACKs and scheduled retries;
stop shuts down the pool without waiting on Kafka. A restart is rejected until the previous pool has
terminated, so an unresponsive send cannot multiply workers. An expired send that ignores interruption
keeps its scope reserved until it returns, preventing a new minute from overtaking it. Retry payload bytes, the three-attempt
limit, 250ms backoff and +70-second processor closure are unchanged. A missed deadline leaves missing
telemetry, not fabricated catch-up. These bounds isolate a slow scope; they do not guarantee broker
delivery under arbitrary stalls. Controlled incomplete-future tests prove 150ms per ACK and all fifty
required receipts across twenty scopes in 450ms of modeled publication time.

## Observation identity and compatibility

Natural identity is exactly `(sourceId, scopeId, kind, windowStart)`. The generator's established
identity is Java `UUID.nameUUIDFromBytes(UTF-8(String.join("|", "telecom-observation-v2", sourceId,
scopeId, kind, canonicalWindowStart)))`: MD5-based UUID v3, with existing namespace text and UUID bits.
The new city golden observations use that algorithm. Existing checked-in legacy UUID fixtures are
preserved verbatim, including their original UUID versions; they are not regenerated.

Seed only changes deterministic measurement values. Same content under the same event/natural
identity is a duplicate; changed content under either identity conflicts. Kafka key remains payload
scopeId. No identity adds nodeId, seed, city discriminator, emission time or topology version. NODE
reporter uniqueness within a scope prevents two nodes sharing a natural identity. No timing, closure
(`windowEnd + 10s`), lateness, thresholds, feature schema/order, model or episode identity changes.

## Coverage fields, semantics and identity

ScopeWindowCoverageV1 fields are exactly: schemaVersion=1, coverageId, windowId, scopeId, service,
windowStart, windowEnd, topologyVersion, catalogueVersion, expectedSourceIds, receivedSourceIds,
usableSourceIds, sourceIssues, synthetic=true. All source lists are sorted, unique source IDs.
`windowId` is the actual finalized ServiceFeatureWindowV2 ID, currently SHA-256 of compact UTF-8 JSON
`[scopeId, canonicalWindowStart, featureVersion=2]`. It is not an observation ID. Producer code must bind
it to the feature written in the same transaction; structural coverage validation alone cannot prove
that database association or actual receipt existence.

- **expected**: required SERVICE reporter plus required dependency reporters from the pinned catalogue.
- **received**: accepted durable SERVICE/NODE receipt for that exact scope/start/end. Authorized optional
  measurements may appear here. HEARTBEAT, rejected, conflicting or late-only input does not count.
- **usable**: received, quality COMPLETE, structurally valid service raw metrics, or the required numeric
  raw metrics for the bound node role. MISSING/INCOMPLETE never counts. A NODE with valid metrics of a
  different role is received but MEASUREMENT_MISSING for its bound role. Completeness of raw measurement
  coverage does not establish health, baseline availability, percentile availability or ML eligibility.
- **zero volume**: a COMPLETE measured zero is received/usable raw telemetry. Zero denominators still
  yield null rates; empty SMS samples still yield null p95 and ineligible ML. No absent receipt becomes zero.
- **sourceIssues**: exactly one sorted reason per required absent or received-unusable source:
  NOT_RECEIVED, REPORTED_MISSING, INCOMPLETE, MEASUREMENT_MISSING. Absent optional transport has no issue.

Feature sourceEventIds contains contributors and must never be used as the full source inventory.
The coverage ID is lowercase SHA-256 hex over compact UTF-8 JSON, with no whitespace:

```text
["scope-window-coverage-v1", scopeId, canonicalWindowStart, topologyVersion, catalogueVersion]
```

WindowStart is a canonical UTC minute with Z. WindowEnd is exactly start+60s. Version inputs make
coverage facts distinct across catalogue snapshots; received/usable sets, seed, windowId, emission time
and synthetic labels are not identity inputs. Within one pinned finalized minute, retries reuse the
immutable payload/ID; changed content is a conflict, not an update to immutable history.

## Frozen future delivery boundary and review handoff

Under existing `WindowDecisionLock` and the finalization transaction: read accepted receipts, finalize
the feature/KPI, snapshot coverage, insert coverage into the existing generic `app.voice_delivery`
outbox, and mark the bucket finalized, atomically. Both measured and inferred-missing finalization paths
must do this. Coverage delivery uses id=coverageId, topic=`telecom.coverage.v1`, key=scopeId, strict V1
payload. The namespace prevents collisions with existing feature/detection delivery IDs. Rollback of
any insert must roll back finalization; broker ACK occurs later through the existing leased delivery
worker. No second delivery system, cross-database transaction or applied-migration rewrite is planned.

The existing generic table and publisher are in Rusu's detection package. Rusu must review/enact the
package-facing change with Ion; Denis owns coverage consumption/idempotent projection/OpenAPI. This
boundary defines the coverage handoff without claiming consumer acceptance.
Coverage insertion precedes detection so that inference latency cannot delay measurement coverage.
The history bootstrap's bounded drain joins delivery ID to feature window ID and sends KPI-only facts;
extend it explicitly for coverage IDs/topics before geographic bootstrap. No migration is needed today.

## Validation and fixtures

Java and Python validate strict structure, pinned versions, exact ten cities/twenty geographic scopes,
both services per city, unique IDs, valid single parents, acyclic containment, no cross-city ownership,
equal depth, one monitored leaf per scope, authorized role targets and capabilities, required roles,
unambiguous mappings/reporters, exact legacy authority and full metadata/authority correspondence.
Coverage validation checks identity, required set, sorted subsets, authorized sources and reason coverage.
ObservationValidator continues to enforce the unchanged V2 schema and strict authority.

`fixtures/geography/complete-city-observations-v1.json` has 50 synthetic measurement receipts;
`invalid-catalogue-cases-v1.json` has twelve named mutations with deterministic rejection reasons.
`fixtures/coverage/coverage-cases-v1.json` has thirty expected receipt/coverage cases: all twenty city
scopes plus zero-volume, absent node, all-absent, reported missing, incomplete, wrong-role measurement,
optional SMS transport, heartbeat-only and two legacy cases. Java/Python consume the same fixtures.
Additional tests reject unauthorized SERVICE/NODE reporters, wrong service/city, changed content under
identity, strict authority duplicates/unknown fields, and inconsistent coverage sets/IDs/versions.
These are offline golden contracts, not new live measurements or finalized city feature outputs.

## Separate auxiliary boundary

Future AuxiliaryEvidence is separate from TelecomObservationV2: evidence ID/type, allowlisted source,
target node, topology version, observed/emitted timestamps, status/result, optional vantage/latency and
synthetic provenance. Publisher, consumer, freshness and storage contracts require shared
integration review. No runtime producer, probe, power-off detector or new V2 field is added today. Missing telemetry
does not imply power-off; ping failure does not imply power-off; injected scenario cause is never input.
