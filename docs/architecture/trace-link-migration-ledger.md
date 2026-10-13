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
