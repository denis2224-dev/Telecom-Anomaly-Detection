# Trace Link Ion inventory and native-import slice

Plan checkpoint: 10 October. Implementation and verification: **12 October 2026**,
Europe/Chisinau. This record covers the first compatible Phase A/B increment,
not the six-day release or runtime activation.

## Source and integration state

The inspected main is `cbb94d37f7593e99737ee4200e91b14e0512f76f`.
Implementation uses `feature/ion-trace-link-inventory-bootstrap` in a separate
worktree. Implementation commit: `5a8e6bec28ab88fbbfe85da2ecca695cdf4aa7a0`.
The complete changed-file list and committed-byte hashes are in
[`implementation-files.json`](assets/trace-link-ion-native/implementation-files.json).
The original checkout remains on `feature/volte-sms-service-assurance`
at `0eb9b5b417b37343e86038cdd84243ce68e17cbe`; its five tracked modifications
and untracked files were preserved. Exact inspection state is in
[`inspection.json`](assets/trace-link-ion-native/inspection.json).

Reviewed dependencies:

- [PR #88](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/88)
  is merged: 43 KPI definitions and 54 arithmetic cases, contract-only.
- [PR #89](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/89)
  remains open at `fec8905d7ba5563a8ebfe0aa541c16b1cb93b75b`, targeting main.
- [PR #90](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/90)
  remains open at `923a9f009ce402b84cc26882466e7c2e3989a136`, targeting #89's
  branch. Its V005–V007 and read-model implementation are not copied here.

The producer code, topology, existing processor migrations, generator scenario
API, frontend API consumers and CI configuration were reviewed before changes.
The original checkout's Graphify query identified the existing validator,
ingestion, finalizer and history boundaries; current source was authoritative.
No processor V014–V016 collision existed across the fetched remote branches.
The [reservation ledger](../architecture/trace-link-migration-ledger.md) records
implemented V014/V015 and unconsumed V016.

| Area | State and source evidence |
| --- | --- |
| Legacy live VoLTE/SMS | IMPLEMENTED: `ObservationValidator`, `IngestionService`, `WindowFinalizer`, `ContinuousTelemetryService`; existing replay/finalizer/outage tests remain gates |
| New KPI definitions/oracle | IMPLEMENTED in merged `contracts/kpi/trace-link-kpi-definitions-v1.json`, `TraceLinkArithmetic` and its 54-case test |
| Reference inventory and domain boundary | IMPLEMENTED here: new geography/topology/schema, `TraceLinkCatalog`, `DomainObservationValidator`, valid and invalid fixtures |
| Native history | PARTIAL: source archive persistence/import implemented and verified; metric projection/rollups are not integrated |
| Backend history | PARTIAL upstream: #90 has immutable read models and a limited ratio series; projector and approved naming handoff are missing |
| Catalogue runtime activation | GATED: new reference catalogue is CONTRACT_ONLY; legacy geography and authority remain in place |
| New-domain runtime/scenarios | MISSING from this slice; observation fixtures are contract examples, not detector/scenario acceptance |
| Authenticated downstream / UI / receiving-owner handoff | NOT VERIFIED / PENDING |

## Contracts and ownership

Versions:

- scope namespace: `trace-link-reference-v1`;
- catalogue: `trace-link-geography-v2`;
- topology: `trace-link-links-v1`;
- domain schema: `trace-link-domain-window-v1`;
- metric policy/dictionary: existing `trace-link-kpi-v1`;
- frozen native source import: `trace-link-native-source-v1`;
- full dataset: `trace-link-md-30d-v1`;
- bounded checked-in archive: `trace-link-bounded-fixture-v1`.

The new reference catalogue contains exactly CHI, BAL, CAH, UNG, ORH, SOR, COM,
EDI, HIN and CAU, with twelve approved links. Each link declares a-to-b and b-to-a;
only a-to-b has supplied measurements. Lowercase reference city IDs bind explicitly
to `contractCityId`; this grants no legacy runtime ownership. Old catalogue bytes
and RIB/TIR historical identities remain unchanged.

Domain validation rejects unknown units, irrelevant fields, truth labels,
unknown links/directions, future times, mismatched reference/contract cities,
incorrect procedures, negative counters, successes above attempts, missing
effective capacity and incompatible histogram metadata/edges. It reuses the
dictionary's population, unit, aggregation and policy rather than redefining V2
CSSR. Missing, insufficient, stale, partial and measured-zero shapes remain
distinct. It validates documents; it does not finalize windows or grant source
authority. A downstream freshness/detection policy still needs its own gate.

Natural observation identity includes dataset/source namespace, domain, complete
scope dimensions, metric, native start/duration and schema/catalogue/topology/
policy versions. Revision belongs to that same identity. Kafka partition keys
use dataset/namespace plus service-city, link-direction or partner-direction-
procedure state. No new Kafka topic is activated by this PR.

Processor V014/V015 use `trace_source_*` tables for immutable source provenance,
not competing incident-service metric read models. Source rows and progress
commit together in bounded batches. Manifest and catalogue conflicts fail; exact
retry returns the same completion; dataset-scoped advisory locks serialize
concurrent retries. Runtime grants exclude DDL and source UPDATE/DELETE/TRUNCATE.

The supplied files have verified counts of 86,400 city, 103,680 link and 172,800
roaming rows. Their 18 scenario-truth records are verified only and never enter
detector-facing storage. Archive quality/coverage were not supplied and stay null.
No five-minute fact is split, no reverse observation is fabricated, and no packet
counter is inferred from a loss percentage. See the
[runbook](../runbooks/trace-link-native-import.md) for exact startup/retry/query commands.

## Verification and limitations

Detailed command outcomes, current counts, hashes and environment limitations are
recorded in `assets/trace-link-ion-native/verification.json`. The persisted full
import result is `assets/trace-link-ion-native/native-import-result.json`.

| Check | Actual result |
| --- | --- |
| Final complete streaming reactor | PASS: 1,183 reported, zero failures/errors, five skips |
| Full supplied native archive | PASS: 362,880 persisted rows, exact retry unchanged; selected explicitly in the initial full run |
| Incident-service verify | PASS: 218 reported, zero failures/errors, one skip |
| Repository / ML Python | PASS: 111 / 64 tests |
| Dashboard API generation / tests / build | PASS: generated Git content unchanged, 26 files / 131 tests, production bundle built |
| Four-language arithmetic | PASS: 54 cases per language; maximum difference zero |
| Supplied engine / DOM shim | PASS: 708,498 / 23 checks; no real-browser claim |
| Windows fixture checkout | PASS: all seven CSV checksums preserved with core.autocrlf=true |
| Compose config | BLOCKED: missing PROCESSING_DB_PASSWORD in this isolated worktree |
| ./scripts/verify / shared release | BLOCKED: no approved configured integrated target; not executed |

The standard reactor skips three packaged ML HTTP checks, one SMS-shadow replay
and the opt-in full-archive test. The full archive was passed separately; the
remaining skips lack `ML_SERVICE_URL` or `SMS_SHADOW_REPLAY_DIR`. Incident-service
also skips its optional SMS-shadow replay. These are not recorded as passed.

Initial attempts exposed sandbox Docker access and missing Python dependencies.
Host-access reruns use disposable PostgreSQL/Kafka and task-local dependency
directories. One initial slice-query test used local Timestamp bounds; explicit
UTC instants corrected the test. These initial results are retained separately
from passing reruns.

The stale-snapshot regression also exposed a producer schema population-length
limit below the existing dictionary's 189-character description. The limit was
corrected and dictionary/schema compatibility now has a contract check. The
existing schema-ownership test was updated from 16 to 24 tables and verifies the
eight new table names; the V013 upgrade test pins its historical target to V013.
The original ownership, runtime DML and cross-database denial assertions remain.

The standalone Day 1 byte-pin checker rejects Windows CRLF conversion of frozen
text files. No frozen source is rewritten to hide this. All committed frozen-file
bytes and external reference pins were verified independently. The unchanged
verifier also passes on an exact Git-blob LF snapshot; see
`assets/trace-link-ion-native/committed-pin-verifier.json`. The working
arithmetic fixture retains identical JSON semantics. Fresh 54-case exports are
compared across Java, Python, JavaScript and SQL. New native fixture bytes are
pinned to LF in `.gitattributes` so their checksums survive Windows checkout.
The same checkout drift initially blocked frontend branding prechecks. Running
the existing branding generator produced zero committed Git content changes;
API generation, frontend tests and build then passed. Original checkout branding
edits were untouched.

No approved full runtime, shared demo import, authentication/browser smoke,
deployment/rollback, load-capacity run, Grafana query or receiving-owner
reproduction is claimed. Source import verification does not prove an authorized
incident-service history read or UI readiness.
Import interruption is tested after a real committed PostgreSQL batch, followed
by a new importer instance. A native-import process/database restart and outage
drill remain pending; legacy outage tests are not relabeled as proof of that gate.

## Receiving-owner handoffs and next gate

- Denis: review the schema/identity/provenance boundary and agree the metric
  projection contract. #90's `voice.success_rate` and dictionary
  `volte.setup.success` have no approved implicit mapping. Reproduce one native
  slice, then define the authenticated projection/read gate.
- Sergiu: use the new domain examples and existing histogram schemas. Imported
  source quality is unassessed; scenario truth is absent from detector storage.
- David: review explicit reference city bindings, directions, units and missing
  states. No frontend completion is claimed by these producer tests.
- Stanislav: review V014–V016 reservations, runtime grants and the isolated
  bootstrap job. Reproduce fresh install/upgrade and a bounded retry from the
  runbook before selecting any shared database.

All receiving-owner reproductions remain **PENDING**. The next implementation is
the agreed versioned producer-to-incident projection and primitive rollup path,
after those upstream interface and acceptance gates are satisfied. The shared
release remains **BLOCKED**.
