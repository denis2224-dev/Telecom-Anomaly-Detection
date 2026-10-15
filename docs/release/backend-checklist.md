# Backend release checklist

Production source commit: `87bfd3dacdded6f1680ce446ccbd4c400effe718`.
Evidence: [G4 backend feature freeze](../evidence/2026-10-09-g4-backend.md).

- [x] Fresh PostgreSQL migration and populated upgrade pass; runtime has required DML, no DDL or cross-database access.
- [x] OpenAPI `0.3.0`, generated client, fixtures and protected browser responses agree with the persisted incident fields.
- [x] VoLTE and SMS each open one ordered episode and recover only after three healthy windows; analyst resolution is separate.
- [x] Duplicate/replayed detections and stale commands preserve incident identity, sequence and audit count.
- [x] Missing telemetry remains missing/unknown rather than zero or technical recovery.
- [x] OIDC login, issuer/subject lookup, enabled state, roles, CSRF, expiry and SSE reconnect checks pass.
- [x] ML, Kafka, PostgreSQL and Keycloak drills have measured HTTP/database outcomes and recovery instructions.
- [x] Stable configured load records hardware, 22 scopes, 55 receipts/minute, 72.34 seconds, API p95 124.687 ms and zero protected API errors.
- [x] Model, baseline, topology, policy, OpenAPI, image and migration versions are recorded.
- [x] A restored generator accepts the identical scenario retry, rejects a conflicting command and stops telemetry without claiming incident recovery.

Release decision: **accepted for the measured local backend core**. The user waived waiting for external owner review; no teammate acknowledgment is claimed.

Known limits: synthetic local stack and configured rate only; DATA/roaming and device measurements/topology drill-down remain outside the backend core. External owner acknowledgments are not represented as completed; the user asked to finish without waiting for them.

## Trace Link stacked candidate, 14 October 2026

Decision: **draft; acceptance pending**. The previous backend-core release above retains its own source and measured limitations. This section does not extend its acceptance or reviewer waiver.

- [x] Backend production source and local deployed content digest pinned in [backend candidate](backend-release-candidate.json).
- [x] Fresh/populated/repeat migration and runtime isolation verified in disposable databases; consumed migrations preserved.
- [x] Protected subscription/queue, cryptographic startup, exact CSV, identity, capacity-history and legacy regressions pass.
- [x] Controlled real-OIDC lifecycle, restart/read-state and isolated database restore checks recorded.
- [x] Incomplete release is rejected by the automated gate; push and new-domain flags remain off.
- [ ] Resolve current-main V009 and domain-table collision without rewriting consumed history.
- [ ] Complete shared producer/scenario support and all-owner release manifest/image distribution.
- [ ] Record current measured load/capacity and Grafana parity.
- [ ] Verify approved browser origin/behavior, offline package, and receiving teammate reproduction.
- [ ] Run two complete demonstrations on the same immutable integrated candidate.
- [ ] Collect all five observed owner acknowledgments.

See [final sign-off](../evidence/final-backend-signoff.md), [rehearsals](../evidence/backend-rehearsals.md), [shared draft manifest](trace-link-release-manifest.json), and [backend runbook](../runbooks/trace-link.md). Required pending gates keep this release unaccepted.

## Integrated stack refresh, 15 October 2026

The earlier unaccepted Trace Link candidate is superseded. Original source/image hashes and controlled runs remain historical; the release checker rejects their acceptance. #107 supplies current V013 compatibility, enabled-account/capacity repairs and feature integration evidence. The release draft adds a populated V004-to-V013 regression and pins both canonical and historical migration resources when capturing a future candidate. A fresh shared identity, current complete rehearsals and remaining release gates are still required. This does not reopen or extend the earlier accepted backend-core release.
