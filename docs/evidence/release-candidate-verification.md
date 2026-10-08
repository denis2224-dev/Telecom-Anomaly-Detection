# DAY 5 DEVOPS RELEASE VERIFICATION REPORT

## 1. FINAL RELEASE CANDIDATE

SHA: `4d56c67dc6c0f18fdf73d6390d4540258557291c` (application candidate; this branch adds documentation only)

Branch: `devops-release-candidate`

Config: `.env.example`; geography disabled and startup alignment validated

Catalogue: not pinned in deployment configuration

Topology: not observed at runtime

Baseline: `baseline-v2`

Model: `sms-supervised-v1-2` published contract; no live model run

Policy: `geographic-priority-v1`

## 2. ENVIRONMENT

Java: PASS — 21.0.12.1

Maven: PASS — 3.9.16

Node: PASS — 24.14.0

Python: PASS — 3.13.7

Docker: BLOCKED — CLI 29.8.0 is installed, but the Docker daemon socket is unavailable

Database: BLOCKED — no disposable database started

Kafka: BLOCKED — no broker started

Keycloak: BLOCKED — no authentication stack started

## 3. G1

Status: BLOCKED

Evidence: controlled configuration and contract checks pass; runtime prerequisites
are unavailable.

## 4. G2

Status: PARTIAL

10-city matrix: PASS in controlled contract fixtures; not live verified

20-scope matrix: PASS in controlled contract fixtures; not live verified

Resource result: NOT_VERIFIED

Evidence: `scripts/check-contracts.py`, geography alignment tests, Compose
placeholder rendering

## 5. G3

Status: BLOCKED

Investigation verification: NOT_VERIFIED live

History: NOT_VERIFIED live

Scenarios: NOT_VERIFIED live

Probe status: NOT_IN_SCOPE; the optional probe slice is not enabled

Evidence: live investigation requires the unavailable stack and authenticated session

## 6. G4

Status: BLOCKED

Security: BLOCKED — proxy and Keycloak were not running

Replay: NOT_VERIFIED

Reconnect: NOT_VERIFIED

Cause controls: PASS in controlled contract checks only

Rollback: NOT_VERIFIED

Feature-off: configuration alignment PASS; end-to-end fallback NOT_VERIFIED

Evidence: `docs/evidence/release-candidate-manifest.md`

## 7. G5

Status: BLOCKED

Final regression: controlled checks PASS; live regression NOT_VERIFIED

Migration: NOT_VERIFIED

Authenticated live: NOT_VERIFIED

Resource: NOT_VERIFIED

Rollback: NOT_VERIFIED

Evidence: `docs/evidence/release-candidate-manifest.md`

## 8. MENTOR REPRODUCTION

First run: BLOCKED by unavailable Docker daemon

Second run: NOT_VERIFIED

Unaided teammate: NOT_VERIFIED

Problems found: runtime environment could not be started

## 9. EVIDENCE

Manifest: `docs/evidence/release-candidate-manifest.md`

Screenshots: none collected

Logs: no runtime logs retained

CI: controlled checks listed in the manifest; live CI rerun not performed

Runtime: BLOCKED by Docker daemon

Database: not started or modified

Rollback: not run

## 10. KNOWN LIMITATIONS

- No live Compose stack was available.
- Authentication, protected REST/SSE, scenarios, restart/resume, migration,
  rollback and resource measurements were not verified.
- Runtime catalogue, topology and configuration digests were not observed.
- Teammate reproduction was not possible without the runtime environment.

## 11. CONDITIONAL / OPTIONAL FEATURES

Power and probe verification: `NOT_IN_SCOPE`; no enabled release claim.

Transport cases: `NOT_IN_SCOPE`; no runtime transport claim.

## 12. FINAL RELEASE RECOMMENDATION

`DO NOT RECOMMEND RELEASE`

## 13. FINAL DEVOPS STATUS

`PARTIAL / BLOCKED`
