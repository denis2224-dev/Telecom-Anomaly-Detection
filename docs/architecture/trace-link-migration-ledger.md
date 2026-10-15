# Trace Link migration reservations

Inspected 12 October 2026 from main `cbb94d37f7593e99737ee4200e91b14e0512f76f`
and all fetched remote branches. No processor V014–V016 collision was found.
This ledger records the supplied reservation, not receiving-owner approval.

| Database / owner | Reservation | State |
| --- | --- | --- |
| processing_db / Ion | V014__trace_link_inventory.sql | Reference-only source inventory; implemented here |
| processing_db / Ion | V015__trace_link_dataset_history.sql | Immutable native source rows and checkpointed import; implemented here |
| processing_db / Ion | V016__trace_link_domain_windows.sql | Producer-only immutable primitive projections and held outbox; consumer agreement PENDING_REVIEW |
| incidents_db / Denis | V005__incident_notifications.sql | PR #90; not changed here |
| incidents_db / Denis | V006__trace_link_read_models.sql | PR #90; not changed here |
| incidents_db / Denis | V007__trace_link_query_indexes.sql | PR #90; not changed here |
| incidents_db / Denis | V008__trace_link_primitive_projections.sql | Reserved by independent PR #98; not imported or approved by this repair |
| incidents_db / integration repair | V009__trace_link_scenario_commands.sql | Additive protected command ledger; isolated PostgreSQL verification; flags default off |
| incidents_db / contract resolution | V010__trace_link_domain_measurements.sql | Reserved after refreshed #98/#100/#101 and all remote incident migration paths on 2026-10-13; isolated source/scope authority, immutable measurements and results; no authority rows seeded; owner mapping PENDING_REVIEW |

Processor source tables use `trace_source_*`. They retain the supplied archive,
including unavailable quality/coverage as null. They are not equivalents of
Denis's metric projections, and no service reads another service's database.
Neither reservation authorizes renumbering an applied migration.

The new catalogue lists ten active reference members but is registered as
`CONTRACT_ONLY`. No legacy source authority or deployment flag is changed.
Runtime activation requires a separately versioned source-authority contract and
the shared consumer alignment gate.

## Transactional history increment

The unmerged PR #103 originally reserved V009 on base f7364d3. Current main already owns V009–V011, so PR #103 now adds V012__trace_link_episode_history.sql after V011. Main V001–V011 remain byte-for-byte unchanged. Episode evidence uses trace_domain_episode_detection to avoid main V010’s trace_domain_detection measurement-verdict table. These separate contracts and feature flags remain intact; the evaluated-domain consumer defaults off. The earlier V009 existed only in isolated review databases, which were removed after verification; this change does not authorize repairing any applied checksum or renaming a migration in a persistent environment.
## PR #104 correction allocation — 14 October 2026

Pinned main: `9db3e0e5a61a8062310e560e734a5395758f70b7`. Original PR #104 head:
`f41dfbb017ad4f11e7a1f18b0996a3868889ff75`. Main now contains processor V001–V016
and incident V001–V010. The earlier reservation table is retained as historical
context; it is not an instruction to overwrite an applied migration.

| Database | Correction allocation and sequence | Compatibility requirement |
| --- | --- | --- |
| processing_db | V017__trace_link_detection_runtime.sql after main V016 | Preserve main V001–V016 and existing V017 bytes; fresh V001–V017 and populated V016-to-V017 verification |
| incidents_db | V011__trace_link_detection_runtime.sql after main V010 | Preserve main V001–V010 byte-for-byte; add five-minute detector evidence/incidents/audit and non-null detected_at; verify fresh and populated main-to-V011 upgrade |

Main's V006__trace_link_read_models.sql remains the upstream dataset/catalogue/
metric-window schema. PR #104's conflicting alternate V006 held different
five-minute detector tables and has only been used in disposable tests. Moving
that unshipped branch delta to V011 preserves main's applied migration history.
V011 was free at the pinned main snapshot; recheck allocation if main advances
before deployment.

Do not run Flyway repair, replace applied checksums, reset an existing database
or enable out-of-order migration to bypass this collision. If an alternate V006
is found in a non-disposable environment, that environment needs a separately
reviewed transition before deployment. No such deployment is asserted here.

The [correction report](../evidence/2026-10-14-trace-link-day3-corrections.md)
records actual fresh/upgrade/validate/idempotency, row/checksum and grant results.
Allocation is not activation approval: upstream 60/300-second compatibility,
shared receiving decisions and simultaneous capacity remain separate gates.

## Day 4 processor allocation — 14 October 2026

`V018__trace_link_ml_shadow.sql` follows the existing processor V017. It adds
processor-local shadow jobs and immutable results, with column-limited job
updates for processing_app. Processor V001–V017 and all incident migrations are
preserved. Fresh and populated V017-to-V018 tests cover retained rows/checksums,
ownership, grants, validation and no-op rerun. Activation remains a separate gate.

## PR #107 compatibility allocation — 15 October 2026

Main foundation #103 is merged at 9085cb1. Main V001–V012 remain byte-for-byte unchanged. V013__trace_link_history_compatibility.sql extends scenario validation and supports the original foundation review databases without changing their applied Flyway records. The original V009__trace_link_domain_incidents.sql and V011__trace_link_scenario_types.sql are retained byte-for-byte under db/compatibility/foundation.

Application startup selects those historical resources only when the recorded V009 script is the original foundation script. Normal Flyway checksum validation remains enabled. The historical track excludes the conflicting main V009–V012 resources and converges through V013; the main track applies V013 after V012. No repair, baseline, out-of-order bypass, deleted history row, or rewritten checksum is used. External Flyway callers upgrading a historical foundation database must use TraceLinkMigrations.forDatabase rather than a raw default-location migration. Fresh/main installations continue using the default migration directory.

V013 renames the foundation episode table and its constraint/index names to trace_domain_episode_detection, preserving rows and foreign keys, and creates the missing canonical command, measurement and runtime tables. Main's trace_domain_detection retains its separate measurement-verdict contract. Disposable PostgreSQL tests cover both original foundation endpoints (V009 and V011), populated main V012, identical fresh/upgrade schemas, no-op repeat, preserved full history rows, runtime denial, and rejected altered checksums. Both detector activation flags and optional push remain off by default.
