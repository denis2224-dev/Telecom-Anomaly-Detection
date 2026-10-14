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

Processor source tables use `trace_source_*`. They retain the supplied archive,
including unavailable quality/coverage as null. They are not equivalents of
Denis's metric projections, and no service reads another service's database.
Neither reservation authorizes renumbering an applied migration.

The new catalogue lists ten active reference members but is registered as
`CONTRACT_ONLY`. No legacy source authority or deployment flag is changed.
Runtime activation requires a separately versioned source-authority contract and
the shared consumer alignment gate.

## Transactional history increment

Incident-service V009__trace_link_domain_incidents.sql is reserved for immutable five-minute evaluated-domain detections and their contiguous technical episode projection on base f7364d3. V008 remains the merged primitive projection migration; V001–V008 are unchanged. Producer delivery acceptance remains pending, so the new consumer defaults off.

## Alert delivery and exact export increment

V011__trace_link_scenario_types.sql is reserved for additive legacy-command validation. Current V009__trace_link_domain_incidents.sql is preserved. Main currently owns a different V009__trace_link_scenario_commands.sql and V010__trace_link_domain_measurements.sql; their version/table collision must be resolved through a separately reviewed upgrade before this stack merges into main. This branch does not apply or rename a consumed migration and does not activate producer delivery.
