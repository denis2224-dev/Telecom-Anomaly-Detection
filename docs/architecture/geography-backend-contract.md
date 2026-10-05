# Geographic backend contract

**Status:** proposed for the shared G1 review. No geographic producer, coverage consumer, database migration, or API controller is activated by this document.

**Base:** `c539b67a40ac776c693e9301fac91b035e4037bc` (`origin/main`, fetched 5 October 2026). Ion's geographic authority candidate is PR #48, updated with the coverage window-identity fix at `1bdd01e`. It is a dependency, not part of this branch. Reconfirm both heads before integration.

## Ownership and boundaries

| Boundary | Owner | Handoff |
| --- | --- | --- |
| Strict observation authority, companion geography, source/role mapping, coverage publication | Ion | Versioned catalogue and `ScopeWindowCoverageV1` facts |
| KPI/baseline/ML compatibility and cause meaning | Rusu | Signed formulas and null/uncertainty rules |
| Incident database projections, duplicate/conflict handling, protected reads and canonical OpenAPI | Denis | Response fixtures and bounded endpoints |
| Dashboard client and generated types | David | Consume canonical OpenAPI and real protected responses |
| Integration SHA, migrations, startup/rollback and combined checks | Stanislav | Gate evidence and release ledger |

The incident service receives durable facts through Kafka and stores its own read model in `incidents_db`. A request never joins `processing_db`. Existing observation, feature, detection and episode identities remain unchanged. The current `/api/services`, incident, simulator and SSE routes remain compatible.

## Catalogue and identity

- PR #48 proposes topology version `2-geography-g1` and companion catalogue version `1-geography-g1`. The catalogue is `CONTRACT_ONLY` with no effective activation time. The current default remains the two legacy scopes.
- The ten city codes are `CHI`, `BAL`, `EDI`, `SOR`, `RIB`, `UNG`, `TIR`, `COM`, `CAH` and `ORH`. Each has one VoLTE and one SMS scope. These are synthetic configured footprints, not real municipal coverage or subscriber counts.
- `VOLTE-MD-CENTRAL` and `SMS-MD-ROUTE-A` stay `LEGACY / UNALLOCATED`. Neither is silently included in city totals.
- Containment (`country → city → aggregation → site → cell`) is distinct from dependency roles (`VOLTE_IMS`, `VOLTE_TRANSPORT`, `SMS_SMSC`, optional `SMS_TRANSPORT`). A dependency edge never adds a second measured traffic partition.
- The initial footprint has one monitored cell chain per city. Aggregation and site levels expose membership and mapped evidence; they do not acquire their own KPI from a copied city total.
- A window or episode is resolved against its captured topology and catalogue version. Activated versions and mappings are retained for history. Unknown or ambiguous versions fail closed.

## Coverage fact accepted by the proposed consumer

The proposed topic is `telecom.coverage.v1`, keyed by `scopeId`. The exact wire fields from PR #48 are `schemaVersion=1`, `coverageId`, `windowId`, `scopeId`, `service`, `windowStart`, `windowEnd`, `topologyVersion`, `catalogueVersion`, `expectedSourceIds`, `receivedSourceIds`, `usableSourceIds`, `sourceIssues` and `synthetic=true`. The topic is separate from strict V2 observations, KPI features and detections.

`coverageId` is lowercase SHA-256 of compact UTF-8 JSON `["scope-window-coverage-v1",scopeId,canonicalWindowStart,topologyVersion,catalogueVersion]`. `windowId` must equal the finalized feature ID for `[scopeId,canonicalWindowStart,2]`, not merely match the 64-character hash shape. Both associations are validated before persistence. The consumer computes a canonical payload SHA-256 locally; that hash is a storage/replay check, not an extra wire field.

`expectedSourceIds` are the required SERVICE reporter plus required dependency reporters in the pinned catalogue. `receivedSourceIds` are accepted SERVICE/NODE receipts for the same scope and UTC minute, including optional reporters when present. `usableSourceIds` are received COMPLETE raw measurements that satisfy their bound role. Heartbeats do not count. Optional SMS transport is excluded from expected minimum coverage. A COMPLETE raw measured zero can be usable while a KPI with a zero denominator is unavailable.

`sourceIssues` supplies one reason for each required unavailable or received-unusable source: `NOT_RECEIVED`, `REPORTED_MISSING`, `INCOMPLETE` or `MEASUREMENT_MISSING`. The incident service validates shape, exact versions, sorted/unique sets, authorized sources, subset relations, ID/window association and reason coverage. It does not claim to reconstruct raw receipt quality from another database. Producer authority and broker access are part of the trusted boundary.

| Import case | Proposed result |
| --- | --- |
| New valid ID and body | Insert immutable fact and update its read projection. |
| Same `coverageId` and same canonical payload hash | No-op; do not add a second row or incident. |
| Same `coverageId` with different hash, or same logical scope/window/version with a different ID | Reject and retain an observable conflict record; never overwrite the first fact. |
| Coverage arrives after KPI | Until arrival, report `UNKNOWN`. Then join by exact scope, service, window ID and captured versions; do not mutate the finalized KPI. |
| KPI absent | Keep coverage fact, but do not invent KPI or health. Reconcile when the matching KPI arrives. |
| Unknown catalogue/version, scope or unauthorized source | Reject or quarantine. Do not map through the current catalogue. |

The consumer/importer is implementation work after G1. The proposed SQL in `docs/architecture/geography-read-model-proposal.sql` is not a deployed migration.

## Field-source and null map

| API field | Source | Missing or null interpretation |
| --- | --- | --- |
| `cityId`, `displayName`, `synthetic`, footprint and dependency path | Pinned companion catalogue | Unknown city is 404; no fallback to another city. |
| `scopeId`, service and approved source/role IDs | Strict authority joined to the companion catalogue | Reject ambiguous or missing mappings before activation. |
| `catalogueVersion`, `topologyVersion` | Captured version on the fact/episode | Never substitute the newest version for an older fact. |
| `generatedAt` | Incident-service response clock | Response creation time, not measurement time. |
| `windowStart`, `windowEnd`, `latestWindowEnd` | Finalized KPI fact | Null if no matching finalized window exists. |
| Expected/received/usable counts and source issues | Matching durable coverage fact | `UNKNOWN` until coverage exists; `sourceEventIds` are only contributors. |
| Observed, numerator, denominator and sample count | Matching finalized KPI payload | Missing and measured zero remain distinct. |
| Baseline and signed `deltaPp` or `delayRatio` | Rusu-approved matching baseline and formula | Null with a reason if baseline or sufficient measurements are absent. |
| `technicalActiveCount` | Distinct ongoing technical episodes | Do not count analyst workflow status as technical activity. |
| `analystOpenCount` | Incidents not analyst-resolved | May remain open after technical recovery. |
| `firstObservedAt`, `detectedAt`, `recoveredAt` | Immutable detection/episode history | Never replace with `generatedAt`. |
| Cause, confidence, evidence and next checks | Persisted detection/evidence projection | Missing telemetry or a failed ping cannot confirm power loss. |

For disjoint compatible VoLTE partitions, calculate technical CSSR from summed numerators and denominators. `90/100 + 999/1000 = 1089/1100 = 99.0%`; averaging percentages is invalid. A full aggregate baseline or impact is unavailable if a required child baseline is missing. SMS p95 is the true nearest-rank minute percentile for the single authoritative city scope; never average p95s across windows or child scopes. Partial measurements may show an explicitly labelled observed subset and coverage, never a full-city healthy verdict.

## Proposed read boundary

The canonical proposed endpoints are `GET /api/geography/cities`, `GET /api/geography/cities/{cityId}`, `GET /api/geography/cities/{cityId}/topology`, `GET /api/geography/cities/{cityId}/kpis` and `GET /api/operations/priority`. Their OpenAPI descriptions are proposals until controllers and integration tests exist. All are protected by the existing enabled-analyst read policy. Existing server-side role, session and CSRF behavior is unchanged.

- City inventory is fixed at ten active synthetic areas; each exposes both services and separates technical-active from analyst-open counts.
- Unknown city is 404. Invalid city syntax, service, parent/filter, range, page or size is 400.
- Topology is paged at default 50, maximum 100 nodes. Dependency references are separate from containment parents.
- History uses UTC half-open `[from,to)` windows, at most 24 hours per request and 100 points per page, ordered by canonical window start and stable ID. A 48-hour client view requires two bounded requests.
- Priority is a view of persisted incidents, not a detector. It pages at default 20, maximum 100 and uses stable ties. Fresh ongoing episodes lead, then uncertain/stale, then recovered; VoLTE percentage points are not compared numerically with SMS milliseconds.
- A blank incident list never proves healthy. Coverage and freshness are separate from incident state.

## Stream reconciliation and migration ledger

On fetched `main`, one `IncidentStreamController` serves `/api/incidents/stream`; `IncidentCommittedListener` broadcasts `IncidentChanged` after commit; the stream emits `ready` and `incident-upsert`, checks enabled analysts and ends with the session. PR #43's current net diff against `main` contains no incident stream or backend event publisher changes. Keep the current implementation; do not add a second controller or event vocabulary. Recheck if PR #43 moves.

Incident-service has applied V001 and V002; V003 is a candidate only after the shared integration base is checked. Processor migrations have a separate sequence through V007. No applied migration is edited. The current `DatabaseMigrationTest` expects two incident migrations and six application tables, so promotion of this draft SQL requires coordinated test updates, clean install, upgrade and repeat-migrate checks on disposable databases. Compatible readers and schema deploy before city producers. Feature-off returns to the old route while retaining migrated data and pending facts.

## G1 review and remaining decisions

| Decision | Current status | Needed reviewer/evidence |
| --- | --- | --- |
| PR #43 backend stream disposition | Reviewed against `origin/pr-43` at `90a7273`; keep `main` | Denis/Stanislav verify combined tree at final integration SHA. |
| PR #48 city/role/coverage candidate | Reviewed at `2f36936`; contract-only and not activated | Ion and Rusu approve role/source and model/baseline compatibility. |
| `windowId` identity validation in PR #48 | Fixed at `1bdd01e`: Java/Python validators now require the exact V2 feature-window hash; valid-shape mutation tests pass. | Recheck PR CI and accepted head before integration. |
| Coverage consumer and payload-hash policy | Proposed here | Ion/Rusu/Denis sign exact outbox/wire/import boundary. |
| OpenAPI response and null meanings | Proposed here | David and Rusu review before client generation. |
| Migration version and deployment order | V003 candidate, no migration committed | Stanislav/Denis reserve on integration SHA. |

**Shared G1 is still partial** until these reviews, the integration SHA, and combined regressions are accepted. Contract-only files are not evidence of a live geographic endpoint.

The local clean-merge and focused regression results are recorded in `docs/evidence/2026-10-05-geography-contract-merge-check.md`; the full processor run still requires a Docker-enabled environment.
