# Trace Link completion and acceptance

This procedure accompanies the completion branch. It does not authorize deployment, shared delivery, a merge, or shared-volume resets. Earlier evidence reports describe their original revisions. Current results and unresolved gates are recorded in [completion status](../evidence/trace-link-completion-status.md).

## Inputs and activation

Provide the full seven-file `trace-link-md-30d-v1` archive matching the committed manifest, the approved reference dashboard/oracle pack, and an approved HTTPS origin/browser for push acceptance. The archive interval is `[2026-09-10T09:00:00Z,2026-10-10T09:00:00Z)`. No generator output or bounded slice can replace it.

Apply additive migrations through V015 using the existing migration owner. Existing migrations and historical scope identities remain unchanged. Run the explicit incident-owned import described in [native import](trace-link-native-import.md), preserving the processing import separately. Verify CITY 86,400; LINK 103,680; ROAMING 172,800; total 362,880 rows. Repeat the import and compare row hashes and completion timestamp. Catalogue activation is a separate authorized operational action; do not infer authorization from a completed import.

Keep `app.notifications.push-enabled=false`. The protected subscription API and bounded worker are implemented, but secure-origin delivery acceptance remains pending. No credentials, encryption keys, VAPID private key, endpoint URL or subscription keys belong in evidence.

## Grafana

The optional `compose.observability.yaml` provisions 14 panels with datasource UID `trace-link-postgres` and dashboard UID `trace-link-overview`. Its files are mounted read-only. Before an authorized launch, apply `infra/observability/grafana/readonly-role.sql` as migration owner, set the reader password through the approved secret workflow, and supply `TRACE_GRAFANA_PG_PASSWORD`, `TRACE_GRAFANA_ADMIN_PASSWORD` and the approved database TLS mode. The default requires TLS; only an isolated local database test uses `disable` explicitly. Verify PostgreSQL PUBLIC database grants cannot expose other databases.

The reader has no analyst/session/subscription access. The technical annotation view excludes assignments, comments and recipient identities. Queries use bounded primitive rollups, explicit scope populations and absolute epoch bounds. All-city and all-partner selections produce separate series, never an average of percentages or p95 values. Unsupported primitives remain gaps with reasons.

Set the dashboard document's `trace-link-grafana-url` metadata to the approved dashboard URL. Query parameters carry dataset, city, link, partner/country, roaming direction, resolution, timezone and absolute visible bounds; credentials are rejected. Grafana returns nulls for gaps. CSV is exported by the backend from the same exact series.

The isolated Grafana integration check executes real datasource requests, dynamic variables, focused/all scopes and reader privilege denials. Controlled SQL parity covers all 41 dictionary metrics at all four resolutions with tolerance `1e-6`. Full archive/oracle parity is a separate pending gate. Primary configuration references: [PostgreSQL datasource](https://grafana.com/docs/grafana/latest/datasources/postgres/configure/) and [provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/).

## Backup and offline handoff

Use `python3 scripts/backup-trace-release.py --container APPROVED_POSTGRES_CONTAINER --output /private/backup-location`. The output must be new. The script uses actual custom-format dumps and validates their archive lists; failures leave partial artifacts and no success manifest. Files are private. Role passwords are excluded, so restore identity/database secrets through the approved secret workflow. Quiesce writers for coordinated snapshots across databases; independent dumps are not a cross-database transaction.

Restore only into a new isolated installation. Inspect role SQL before applying it, retain the target administrator, restore roles without password hashes, create the required empty databases, then use `pg_restore --exit-on-error --no-owner` for each custom archive. Reapply approved secret configuration. Validate Flyway checksums, source/incident/notification identities, private recipients and runtime grants. Never restore over a shared database to run a check. The automated recovery test verifies an actual dump/restore and runtime immutability between two disposable databases; it does not claim a full populated release restore.

Release images must carry `org.opencontainers.image.revision` matching the clean source commit, supplied through build argument `TRACE_RELEASE_COMMIT`. Build/tag release images separately from shared runtime tags. Then run `scripts/package-trace-release.py` with `--archive`, every runtime `--image`, one `--application-image SERVICE=IMMUTABLE_TAG` mapping per application service, `--output` and `--evidence`. The bootstrap service can reuse the processor image. A generated `compose.release.yaml` selects those tags with pulling disabled, preserving shared local tags. It requires the full source, built browser/JAR assets, all Compose images and Grafana, and actual release evidence. It archives tracked source, assets, archive bytes and Docker images with hashes. Missing files/images or an unpinned application image fail. It does not invent model, image, backup or acceptance artifacts.

Run `python3 scripts/package-trace-release.py --verify /private/package` on the disconnected handoff host. Check the manifest hash against the separately delivered pin before trusting it, load the actual image archive, and follow the approved clean-install procedure on new volumes. Match the recorded image architecture to the handoff host; local ARM images do not establish an x86 installation. Provision secrets outside the package. Record startup, identity, scope/time, restart, reconnect and outage behavior. Packaging success alone does not mark offline acceptance passed.

## Required human and populated runs

Keep the following pending until actually performed:

1. In an approved secure origin, explicitly enable browser permission. Verify encrypted server ownership, browser restart, subscription removal, denied permission, foreground reconciliation and generic background alert. Check same-origin incident clicks after session expiry; no private lock-screen text. Test retry, 404/410 revocation and duplicate suppression. Disable delivery afterward until shared activation is authorized.
2. On a populated installation, run archive import/retry, all five domains at 5m/15m/1h/6h, explicit gaps/partial edges, native and analyst incident states, shared scope filtering, CSV and Grafana parity. Run simultaneous supported fault/control/gap scenarios and trace observations to committed incidents. Verify recovery does not resolve analyst work.
3. Record capacity warmup/steady/burst on the actual coordinated environment, API/identity/ML outages, SSE reconnect, cross-tab notifications, stop/retry/restart/replay and restore. Controlled browser measurements are separate evidence.
4. Perform keyboard-only and screen-reader acceptance, Chrome/Edge/Firefox/Safari review, guide/demo review and offline clean installation. Chromium automation does not substitute for other browser or assistive-technology acceptance.
5. Perform two populated rehearsals against one unchanged release manifest. Record UTC start/end, source/image/dataset/policy hashes, actual incident/scenario IDs, defects and outcomes. Collect the five actual owner signatures only after review; no prefilled acceptance.

## Missing-window investigation

The recorded legacy runs `00c701d7-44de-486d-b83d-40ec1fb472ef` and `22f86d12-fa9f-4b74-9b7f-c49c86d176e4` supplied two complete windows followed by six missing windows. The current legacy publisher waits for Kafka acknowledgement per observation, marks `PUBLISH_FAILED` on failure and cancels remaining windows. A controlled test reproduces two acknowledged windows followed by six unpublished windows, without claiming recovery or opening an invented incident.

This is a possible mechanism, not proof of the historical cause. The original runtime was recreated, and the shared PostgreSQL container was stopped at recheck. To close the historical defect, provide the retained generator/processor/Kafka logs or database snapshot covering `2026-10-13T18:26:00Z`–`18:34:00Z`, including command progress and delivery failures. Keep this gate unresolved until actual evidence identifies the failure. Native scoped runs use a separate durable retry/lease ledger and must retain their own restart checks.
