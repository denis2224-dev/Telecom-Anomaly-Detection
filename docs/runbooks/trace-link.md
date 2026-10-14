# Trace Link backend operations

## Data and migrations

Incident service owns incidents_db read models, immutable incident evidence, analyst workflow, notification history, and the command ledger. Processing and identity databases retain separate owners/runtime roles. Runtime roles have intended DML but no DDL or cross-database access. Grafana receives only approved read views; never grant it alert/subscription tables.

V001–V004 are preserved foundations; V005–V007 add notifications and historical queries; V008–V009 add primitive/domain projections; V011 extends scenario type validation. Confirm current checksums and migration versions before deployment. Upgrade a populated V004 database and repeat migration in tests; never modify an applied migration to make validation pass.

Follow `trace-link-native-import.md` and `trace-link-primitive-projection.md` for the registered dataset import/projection. The reference oracle is not a production migration. Dataset trace-link-md-30d-v1 is synthetic and covers the manifest's half-open history interval at native five-minute resolution. Reimport of the same namespace/manifest must preserve row identities.

## Query context and CSV

Use UTC RFC3339 timestamps ending in Z and [from,to). Supported resolutions are 5m, 15m, 1h, and 6h, independent of period. Maximum range is 30 days and maximum effective bucket count is 8,640. A partial edge is explicitly reported. Missing, stale, zero denominator, incomplete, healthy, and impaired remain distinct. UI display timezone is UTC or Europe/Chisinau rather than a fixed offset.

Inventory defaults to the active catalogue; historical evidence uses an explicit archived version. New scenarios require active entities valid for their full schedule. The current metric registry is the available query surface; unavailable metrics must not be advertised as implemented. Ratios use sums of primitives; no percentage averaging, p95 averaging, or snapshot summing is permitted.

CSV reuses the authenticated series query and returns exact DTO buckets with primitives, units, quality, versions, and provenance. No chart sampling is used. Run `scripts/check-trace-link-export.py` against a frozen fixture to compare JSON/CSV before handoff. Keep authenticated cookie files outside the repository.

## Scenarios and isolation

TRACE_LINK_SCENARIOS_ENABLED defaults false until the generator implements the shared catalogue and scope/schedule/replay contract. A saved retry reuses request ID, body hash, run ID, and start/end schedule. A conflicting body returns 409. Permanent generator rejection leaves FAILED; uncertainty does not become success. Stop controls telemetry publication, not technical recovery.

Preserve separation between genuine live one-minute scenario sources and native five-minute archive projections. The new-domain projection currently requires its registered dataset and five-minute persisted evidence. Confirm the source/duration adapter with Ion and Sergiu before claiming an eight-minute new-domain live scenario is end-to-end. Do not reuse the archive dataset namespace for facts outside its history bounds.

## Deployment, restart, and handoff

Run contract checks, backend verify, dashboard generation/test/build, Compose validation, and `./scripts/verify`. Restart incident service without removing volumes; after readiness authenticate and compare REST IDs, audit, unread/read state, and delivery states. SSE is a hint; REST restores missed history. Record projection lag, freshness, pool wait, percentiles, error counts, and achieved protected API throughput with the hardware context.

Give Ion rejected payload/command reasons, Sergiu evidence/cause suppression, David API/examples/flags and async/reconnect behavior, and Stanislav migrations/grants/secrets/capacity/recovery results. A handoff is complete when the receiver reproduces an authenticated read and one expected failure; a message alone is not acceptance.

## Release and offline operation

Pin code, deployed image digests, migration checksums, API, dataset manifest, topology/policy/model, identity configuration hash, and measured limits. Run two rehearsals on this same identity. A change to application code/config/image/data requires a new identity and fresh rehearsals. If real HTTPS browser push is unverified, release with push disabled and record the limitation.

Stanislav prepares an offline bundle of required images, dependency caches, manifests, contracts, fixtures, and runbooks with checksums and without committed secrets. Prove disconnected start from that bundle and an isolated backup/restore. Do not label a warm-cache local build as a clean offline installation. Restore keys through the approved secret procedure; preserve database separation.
