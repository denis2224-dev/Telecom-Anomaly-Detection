# Trace Link backend reliability

Observed 14 October 2026, Europe/Chisinau. Source: `6b4d4246a5a8eb563394525dbd04394d432ed8ca`. This is evidence for the stacked draft candidate, not final shared release acceptance.

## Verification

- Standalone backend `./mvnw -f services/incident-service/pom.xml verify`: 194 unit tests, zero failures/errors, one existing skip; 94 integration tests, zero failures/errors/skips.
- Root `./mvnw verify`: generator, processor, and shared streaming modules pass.
- Repository Python tests: 148, one skip, pass. Packaged ML tests: 64, pass in a disposable container with the declared runtime and development dependencies.
- Dashboard: 131 tests across 26 files and production build pass on the behavior source. Contract checks, generated client equality, Compose configuration, reference engine (708498 assertions), and DOM shim (23 assertions) pass.
- Real PostgreSQL tests cover subscription ownership/limits, expired leases, stale completion fencing, exhausted attempts, optional cryptographic startup, and complete historical capacity coverage. The defects and failing-before observations are in [rehearsal-defects.md](rehearsal-defects.md).

Structured counts and private-log hashes: [verification.json](assets/backend-reliability/verification.json). No raw logs, credentials, browser cookies, tokens, encrypted keys, or database dumps are committed.

## Isolated composed candidate

Project: `trace-link-backend-qa-20261014`. All 171 production/contract/configuration files compared with the recorded revision match. The backend local content identity is `sha256:4f035a8ecf3d1e2b393941f9d1ad624ea8e36129485bf5a0ce5b3095cf341029`; no registry publication is claimed. The original environment and databases were not modified. Push and new-domain scenario activation remain off.

The pinned synthetic 30-day archive (`trace-link-md-30d-v1`, manifest SHA-256 `e3033f025f6b5866edc0ed34f3078cfa9f9c01cb126eac4577055ef30247ee1b`) imports 86400 mobile-data, 172800 roaming, and 103680 transport windows. Exact repeat import inserts zero rows. Effective capacity changes use actual valid-time authority; source quality and coverage remain null and API quality stays INSUFFICIENT_EVIDENCE.

Two automated controlled Kafka lifecycle runs use real OIDC analyst/supervisor sessions. Each observes OPEN, escalation, assignment, recovery, replay stability, actor-private read state, stale-version 409, foreign-item 404, and missing-CSRF 403. Technical recovery and workflow resolution remain separate. Final service restart preserves 28 visible items and their read state; logout makes fresh protected reads return 401. The exact observed count in [controlled-smoke.json](assets/backend-reliability/controlled-smoke.json) is authoritative. Disabled-profile incident/service/notification reads all return 403.

Exact JSON/CSV parity passes for sixteen queries across transport (including changed capacity), roaming, and mobile-data at 5m/15m/1h/6h. Partial UTC edge buckets are retained: the selected 24-hour 6h query has five buckets and the full 30-day 6h query has 121. Invalid range, timezone, resolution, and scope exports return structured 400 responses.

Database restore evidence: [restore.json](assets/backend-reliability/restore.json). A private custom-format dump is restored into a separate QA database; row counts and event/read-state identity fingerprints match. Application reconnect to the restored database and pending runtime push recovery are not claimed; queue fencing/retry recovery is proven in disposable PostgreSQL tests.

## Remaining release gates

The preserved V009 foundation conflicts with current main; V011 reserves the additive scenario constraint without rewriting consumed migrations. Full producer/processor support for the new domain scenario types, Grafana parity, full load measurement, disconnected package reproduction, real browser behavior on the approved origin, two complete owner rehearsals on one accepted identity, and five owner acknowledgments remain pending. These controlled automated runs do not satisfy full release rehearsal acceptance. Keep the release PR draft and feature flags off until the required evidence exists.
