# Transactional history merge reconciliation

Executed 15 October 2026, Europe/Chisinau. PR #103 is reconciled against main `ce35fec7ecf0b99fcfd4df1dd9516aaa588c8a7f`. The author explicitly requested merging without waiting for teammate reviews. This authorizes the code merge; it does not assert production readiness or replace pending runtime release evidence.

The separate checkout preserves the unrelated in-progress merge in the original workspace. Resolution retains both scenario and domain-incident API paths and regenerates Angular types from the mirrored OpenAPI. One notification controller delegates to the transactional history service, preserving main’s existing error codes and adding no-store responses.

Main incident migrations V001–V011 are byte-identical. The unshipped PR #103 episode delta moves from its obsolete V009 reservation to `V012__trace_link_episode_history.sql`. Its immutable table is `trace_domain_episode_detection`, separate from V010’s existing measurement-verdict `trace_domain_detection`. No persistent database repair or checksum change was performed. The populated V011-to-V012 migration test retains prior rows and checksums, compares fresh and upgraded schemas, validates Flyway, and verifies a no-op repeat.

| Verification on the reconciled tree | Result |
| --- | --- |
| Java surefire-reports | 191 tests; 0 failures; 0 errors; 1 skips |
| Java failsafe-reports | 137 tests; 0 failures; 0 errors; 0 skips |
| Python tests | 176 tests, zero failures/errors, one existing PowerShell skip |
| Dashboard tests | 181 passed across 35 test files |
| Dashboard production build | PASS |
| Shared contract validation and strict evaluated-domain examples | PASS |
| Canonical/mirrored OpenAPI equality and generated Angular types | PASS |
| Existing main incident migration bytes | PASS |
| Staged whitespace/conflict-marker checks | PASS |

Reproduction uses the existing project Python virtual environment, `npm ci --ignore-scripts`, `npm run generate:api`, `npm test`, `NG_BUILD_MAX_WORKERS=2 npm run build`, the shared-validator Maven install, and full incident-service Maven verify. Local socket/Docker tests require normal host permissions. The initial restricted Python attempt lacked the environment/local socket permission, and the first full backend attempt exposed the duplicate controller before consolidation; final runs above passed.

The earlier authenticated runtime and capacity evidence remains historical evidence at its recorded commit. No new deployed capacity success, all-panel Grafana sign-off, producer activation, or production deployment is claimed by this merge. The evaluated-domain consumer remains disabled by default. Failed/provisional capacity results remain unchanged and must be addressed before claiming a healthy deployed operating limit.
