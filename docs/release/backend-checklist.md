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
