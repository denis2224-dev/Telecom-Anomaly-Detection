# Trace Link completion status

Verified 2026-10-15, Europe/Chisinau. Implementation source: `7ed100944c398c0e5a31695a23f22d2ebc1522a5` on `feature/trace-link-completion`, rebased onto fetched GitHub main `ce35fec7ecf0b99fcfd4df1dd9516aaa588c8a7f`. No shared deployment, merge, database reset or delivery activation was performed. The original checkout and its unrelated untracked files were preserved.

## Initial classification

At the starting revision, authentication/CSRF, immutable incident evidence, baseline native producers/detection, shared EventSource and bounded notification presentation were implemented. Full incident-owned history, complete registered rollups, notification production/push APIs, connected native analyst workflow and reference Grafana provisioning were incomplete. The full pinned archive, populated connected acceptance, secure push, offline handoff and human reviews were unverified. Older reports were checked against actual source, services and fresh tests before reuse.

## Implemented

- Approved ten-city and twelve-link reference geography drives active maps and filters. RIB/TIR summaries and new scenario commands are excluded; historical evidence and legacy catalogue identities remain readable. Missing HIN/CAU legacy service memberships are explicit unavailable options, never invented scopes.
- A checksum-pinned full-archive validator/importer projects 362,880 immutable native rows into incident-owned storage in one locked transaction. Identical retries preserve rows; dataset, catalogue, membership, payload and source-identity conflicts abort. It rejects the bounded fixture before any database connection. The actual full archive remains unavailable, so full import acceptance is pending.
- The registry exposes 41 domain metrics plus the compatible setup-success alias. Operators cover counts, ratios of sums, weighted/time-weighted means, maxima, snapshots/differences and pooled histogram p95 at 5m/15m/1h/6h. Gaps, zero denominators, quality, structural/source coverage, provenance, retained snapshot age and partial edges are explicit. The first queue interval cannot invent an opening snapshot. SMS delay values and histogram edges use milliseconds.
- Registered dataset metadata selects its recorded inventory version without requiring live catalogue activation. Historical scope/time context connects overview/service links, topology, domain panels, expanded charts, exact CSV, Grafana and bounded native incident filtering. Query/session generations reject stale responses.
- Native technical episodes retain their original identity and immutable detections. Additive public links, assignment, comments, investigation, resolution and append-only audit use optimistic versions and existing authentication/CSRF. Technical recovery remains separate from analyst resolution.
- OPEN, ESCALATED, RECOVERED and ASSIGNED transitions persist private recipient history transactionally. Replay and routine updates do not manufacture notifications. Browser state remains bounded, REST reconciles stream hints, and the authenticated tab retains one shared EventSource.
- Protected push configuration/subscription APIs, encrypted subscription ownership and bounded leased delivery are implemented. Actual database checks cover duplicate event/queue creation, expired-owner fencing, retry timing and endpoint revocation. Shared push remains disabled; real secure-origin delivery is pending.
- The existing supported transport-overload, roaming-registration and LTE-DNS positive/control/gap scenarios retain durable scoped retries, stop/restart behavior and their connected detector path. Unsupported catalogue profiles remain explicitly unavailable. Retired legacy city commands are rejected without changing stored runs.
- All 14 Grafana panels use a restricted PostgreSQL reader and bounded primitive SQL. Four dynamic variable queries and 20 targets execute for focused/all-population contexts. Analyst/private notification tables are denied to the reader. Unknown primitives remain qualified gaps.
- Backup and offline tools fail on missing inputs/commands rather than generating placeholder success. Image revision labels and per-service immutable image mappings preserve shared tags. Guides, demonstration steps, release checklist and human acceptance procedures are updated.

Additive incident migrations are V012–V015. Existing migrations were not edited. API types were generated from the canonical OpenAPI contract.

## Fresh checks and evidence

The machine's default Node 26 was identified during verification; final supported-runtime dashboard checks use cached Node 24.21.0. Java checks use Java 21, disposable PostgreSQL 16.4 databases and isolated Kafka/test services. Model runtime checks use Python 3.13.9 containers. Controlled fixtures and isolated databases are not populated connected acceptance.

Detailed counts, modes, pins and artifact checksums are in [verification JSON](assets/trace-link-completion/verification.json). Executed groups:

- Complete rebased reactor: streaming support 139, generator 159 and processor 1,117 tests; no failures, eight skips.
- Complete incident service: 197 unit checks and 137 integration checks; no failures, one unit skip. Includes clean/upgrade checks, immutable history, security/CSRF, Kafka ordering/replay, native workflow, notifications, leases, Grafana and actual isolated restore.
- Dashboard on Node 24.21.0: 184 unit checks and 183 controlled Chromium workflows passed, with 25 environment-dependent browser checks skipped. Desktop/mobile history and keyboard/focus checks are included. The CI-specific geography regression also passed with two workers.
- Root Python/contract checks: 180 tests, one PowerShell-dependent skip; canonical/dashboard contracts match. Branding, fixtures, generated API and 42 isolated-runner safety checks passed.
- Actual Grafana: 14 panels, 20 targets, four variable queries, two scope contexts. Independent backend/SQL parity covers all 41 dictionary metrics and all four resolutions at tolerance `1e-6`, including long gaps, first queue opening and malformed late histogram bins. See [Grafana results](assets/trace-link-completion/grafana-queries.json).
- Actual PostgreSQL custom backup/restore between two fresh databases passed Flyway validation, retained source hashes and runtime immutability. See [restore results](assets/trace-link-completion/isolated-restore.json).
- Production browser capacity checks record actual controlled response load, frame gaps, retained heap/DOM and render timing. These are not coordinated deployment throughput measurements.
- All four application images build under separate local tags pinned to one implementation commit. Image IDs and architecture are recorded in [image pins](assets/trace-link-completion/images.json).
- Fresh Java/Python ML feature parity passed for 34 vectors. Linux inference/failure checks passed, and actual isolated-container HTTP checks passed.

## Failures and limits retained honestly

The initial broad geographic lifecycle run reported 19 recoveries instead of 20. Focused and subsequent complete reactor runs passed without changing detector rules. An initial browser resource sample exceeded its DOM delta bound; focused, complete and production repetitions passed with the bound unchanged. These historical intermittent failures remain visible in the verification record.

Additive migrations initially exposed stale migration-count expectations and disposable-test cleanup foreign keys; those test fixtures were corrected while retaining checksum, row-preservation and privilege assertions. An overlapping build invalidated a test classpath; sequential verification passed. A browser run during source edits was discarded and repeated against frozen source. These are not claimed as product passes.

The full Linux ML suite ran 91 tests and failed its strict candidate byte-rebuild assertion. It did not reproduce the pinned Windows artifact bytes. The repository explicitly assigns byte reproduction to Windows. Actual upstream Windows reproduction and dependent Linux integration jobs passed for the unchanged model source and inputs; see [upstream CI evidence](assets/trace-link-completion/upstream-model-ci.json). That evidence does not assert CI completion for this unpublished implementation branch. Model artifacts and strict assertions were not regenerated or weakened.

The upstream dashboard CI failure was traced to a mock EventSource that never connected, permitting a wall-clock fallback refresh to invalidate a hard-coded request total. The corrected mock follows connection behavior, and manual-refresh assertions compare against the preceding count. No application retry/refresh policy was relaxed.

The production build passes its error budget but warns that its initial bundle is approximately 501.4 kB against a 500 kB warning threshold. Full browser/assistive-technology and actual release-environment performance acceptance are still required.

## Remaining external and human gates

1. Provide the seven-file archive matching `contracts/datasets/trace-link-md-30d-v1.manifest.json`, via an exact local directory or authorized URL, and the approved Grafana/oracle pack. Then execute full import/retry, persisted history, populated backend/Grafana parity and full backup/restore. No full-source import, cardinality or package acceptance has been fabricated.
2. Provide an approved HTTPS origin and browser/device for explicit push opt-in, background delivery, foreground reconciliation, privacy, click validation and revocation. Keep shared delivery disabled until that acceptance passes and activation is separately authorized.
3. Provide the retained generator/processor/Kafka logs or database snapshot for the two recorded legacy runs covering `2026-10-13T18:26:00Z`–`18:34:00Z`. A controlled broker failure reproduces two acknowledged windows followed by six unpublished windows, but does not prove the historical cause. The original shared PostgreSQL container was stopped at recheck; no reset or restart was used to invent fresh evidence.
4. Execute connected authenticated browser/outage/reconnect checks, the supported browser matrix and screen-reader/guide reviews on the populated candidate. Record coordinated warmup/steady/burst capacity and identity-secret restore on the approved target. Verify offline image architecture and clean installation/upgrade using new isolated volumes and actual artifacts.
5. Run two populated rehearsals against one unchanged release manifest and obtain the five actual owner signatures. Branch CI/publication and any shared deployment require separate authorization. No owner approvals or signatures are recorded here.

**Complete release gates: NOT PASSED.** Feasible local implementation and controlled/isolated verification are recorded separately from these pending acceptances. Follow the [completion runbook](../runbooks/trace-link-completion.md) and [analyst guide](../user-guide/trace-link-analyst-guide.md).
