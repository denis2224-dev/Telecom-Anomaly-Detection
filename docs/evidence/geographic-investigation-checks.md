# Geographic investigation implementation checks

Checked 7–8 October 2026 on `feature/geographic-investigation`, refreshed to remote main
`a00f61d` from the earlier `b58ca1ef771e0bae730ad544b6028c1fd83dae63`. At verification time these changes were **uncommitted**. G3 implementation and local acceptance are **COMPLETE**; G4 implementation has not started. The two live runs are local acceptance evidence, not a teammate signoff.

The initial working tree contained changes to canonical OpenAPI,
`ScenarioCommandService`, and `ScenarioControllerIT`. Those changes were retained.
The feature branch was refreshed from `cd5b013` through the inspected remote baseline to current remote main `a00f61d`, preserving the existing patch.
The initial patch is also saved locally at `/private/tmp/telecom-geographic-initial.patch`.

## Implemented and tested

- Protected `GET /api/geography/cities/{cityId}/topology`, default 50/max 100 nodes,
  stable SQL paging, explicit archived catalogue selection, same-city containment
  validation, configured measured footprints and separate service dependency edges.
  Missing city/version returns 404; invalid/cross-city parent and invalid bounds return 400.
  The enabled-analyst exception handler covers the new route.
- Additive incident `location`, obtained in a batch of at most 100 incident IDs
  from immutable opening evidence. Country/city/aggregation/site/cell paths use
  that topology's catalogue pinned by the exact opening-window coverage fact when unique; otherwise a unique historical topology match. Missing/unknown/ambiguous versions have
  explicit reasons; neither legacy scope is assigned to a city. A newer detection
  and catalogue cannot replace the opening path. Original evidence is unchanged.
- Validated catalogue accessors return copies, preventing post-validation mutation
  of import/scenario authority. Tests reject duplicate nodes, cycles, dangling and
  cross-city parents, and invalid hierarchy depth.
- Existing scenario route tested across all 4 × 22 combinations: 66 accepted,
  22 incompatible. Tests preserve city retry after deactivation, changed-body
  conflict, same-scope overlap, independent other-city reservation, private HTTP
  delivery, uncertain retry, stop/reconcile, roles and CSRF.
- The private generator now uses the active geography authority for city commands,
  applies the same 4 × 22 compatibility matrix, and builds each city's VoLTE/SMS
  observations with its authorized sources and scope ID. A city publication test
  verifies Kafka key/payload scope alignment, exact-run retry and overlap
  reservation. Legacy generation remains on its original path; city scenarios
  before activation are rejected. This is unit/integration test evidence, not a
  connected public-to-Kafka replay.
- Twenty city histories retain half-open UTC selection, deterministic paging and
  ≤24-hour requests. Two adjacent 24-hour requests remain legal; one 48-hour
  request, non-UTC values, excessive sizes and offset overflow are rejected.
- Protected `GET /api/operations/priority` implements bounded project policy v1
  queue. It filters city, service and technical state in SQL before paging, resolves
  city from opening evidence only, labels unknown/stale severity as historical,
  separates technical from analyst state and exposes `ACTIVE` with the
  policy version. Its SQL follows Serghei's offline handoff at
  `a119bec0939ee723e4a3b27de873dccefad5071d`: fresh severity, then SMS-before-VoLTE
  grouping, then service-specific impact descending with missing impact last, then
  age and UUID. Unknown/stale and recovered rank by age/UUID only. Nine
  PostgreSQL/MockMvc tests cover exact 90-second freshness, future exclusion,
  latest-evidence impact, historical null impact, service/impact/age/UUID page
  ties, filters/bounds, access, and legacy/ambiguous locations. The accepted project rule and change-control notes are in
  [geographic priority policy](../detection/geographic-priority-policy.md).

Canonical OpenAPI was updated first. Its topology status is `backend-implemented`;
priority is `backend-implemented`. Type generation was checked into a temporary file;
David's dashboard API copy and generated types were synchronized on this branch; the production build passed. No migration was added
and V001–V004 were not changed.

## Actual verification

| Run | Result |
| --- | --- |
| G3 focused nine suites | 61 tests, 0 failures, 0 errors, 0 skipped |
| Incident-service `./mvnw -q verify` | 200 reported, 199 executed, 0 failures, 0 errors, 1 skipped |
| Priority policy v1 `PriorityProjectionIT` | 10 tests, 0 failures/errors/skips; includes service-aware impact and stable page ties |
| Generator + shared-support `./mvnw -q -pl services/event-generator -am test` | 194 tests (generator 83, support 111), 0 failures/errors/skips; includes private 4×22 matrix and city-key publication |
| Focused evidence/replay/SSE/security baseline | 44 tests, 0 failures/errors/skips; existing behavior, not G4 completion |
| Supplementary bounds + topology + incident-location run | 10 tests, 0 failures, 0 errors, 0 skipped; includes two new bounds tests |
| `.venv/bin/python scripts/check-contracts.py` | PASS, including 62 geographic reference cases, 30 coverage cases, 12 rejected catalogues and existing observation/detection/explanation fixtures |
| Canonical OpenAPI structure/reference validation | 0 problems using installed Redocly core |
| `openapi-typescript` temporary generation | PASS |
| Dashboard production build + generated API types | PASS against current main dashboard and the updated canonical API |
| `./scripts/verify` with active local geography | PASS: infrastructure, applications, OIDC and authentication routing |
| Authenticated city route and delivery smoke | PASS in 1.5 minutes: real Orhei UI, ten topology routes, twenty bounded city histories, ACTIVE priority response, protected VoLTE/SMS city commands, request retry/conflict/overlap/CSRF checks, and persisted processor receipts for both scopes. The protected incident/source-window trace also passed for real VoLTE and SMS Orhei incidents, including city-filtered priority. |
| Dashboard unit suite | PASS: 113 tests in 23 files after API copy/type synchronization |
| `git diff --check` | PASS |

The full Maven run used Java 21 and reachable Docker 29.7.2, PostgreSQL 16.4
Testcontainers and the repository's Kafka test configuration. Surefire reported
148 tests (one skipped); Failsafe ran 52. The skip is the existing opt-in
`SmsShadowReplayTest`, which requires `SMS_SHADOW_REPLAY_DIR`.
Do not add focused reruns to the full-suite count.

Initial development runs failed on Spring constructor selection, a PostgreSQL
recursive-array type mismatch and the new controller's missing 403 handler; all
were corrected and the subsequent focused/full runs passed. Two test expectations
were corrected: SMS has an optional transport dependency, and ambiguous catalogue versions remain unresolved unless one exact opening-window coverage fact pins the version. A live duplicate-topology catalogue exposed that case; the corrected location and priority queries passed focused and full tests. System
`python3` lacked `jsonschema`; the repository's existing `.venv` passed the checks.

The full run also passed the existing evidence replay, immutable conflict,
out-of-order recovery, after-commit/no-rollback notification, security, session,
workflow and migration tests. These establish a regression baseline, not a new
G4 cause-correlation or live reconnect sign-off.

## Redacted artifacts

- [Verification counts and source digests](assets/geographic-investigation/verification.json)
- [Ten topology API response bodies](assets/geographic-investigation/topology-api.json)
- [Historical incident response with persisted opening ID/hash](assets/geographic-investigation/incident-location-api-db.json)
- [Three paginated priority API responses](assets/geographic-investigation/priority-api.json)
- [Service-group and impact-order API response](assets/geographic-investigation/priority-impact-api.json)
- [Authenticated live city smoke result](assets/geographic-investigation/geographic-live-smoke.json)
- [Protected real incident and opening-evidence trace](assets/geographic-investigation/geographic-incident-trace.json)

The topology/location/priority fixtures are PostgreSQL/MockMvc results with test OIDC
principals; scenario integration tests use a private HTTP test server. The separate
live smoke used a temporary real Keycloak identity, the current dashboard, public
scenario controls and persisted processor receipts. It establishes the live path
through Kafka ingestion for both Orhei services. The separate trace confirms persisted detections and opening-evidence-pinned paths for both services; live recovery was not exercised. Artifacts contain no cookies, sessions,
credentials, CSRF values or authorization headers. The temporary identity was deleted
and its analyst record disabled after the run.

## Remaining gates

| Requirement | Status and reason |
| --- | --- |
| Project priority policy and implementation | COMPLETE locally: on 7 October the task owner directed us to proceed without Rusu. `geographic-priority-v1` follows Serghei's handoff at `a119bec0939ee723e4a3b27de873dccefad5071d`; response status is ACTIVE. This records the user's project decision, not Rusu approval or shared live acceptance. |
| Full shared G2/G3 live integration | COMPLETE locally: current main has the connected geography dashboard. The handoff's earlier private generator `INVALID_SCOPE` city fault is fixed and both local validators pass. The active Compose stack passes `./scripts/verify`, and the authenticated Orhei rerun passed 30 city routes, priority, public-to-generator command behavior and persisted processor receipts for both services. A host clock gap interrupted the first full eight-minute run, but later persisted detections yielded both Orhei incidents. The protected incident and priority trace passed after fixing exact-window catalogue pinning. Technical self-review remains before the G3 commit/merge. |
| G3 commit and reviewed merge | NOT DONE: final self-review and requested one-commit merge remain. |
| G4 branch, changes, commit and reviewed merge | NOT STARTED: requires the completed G3 merge under the requested sequence. Existing evidence/replay/SSE/security tests passed as a 44-test baseline; required new negative-control and live acceptance evidence is still outstanding. |
| Auxiliary publisher/persistence/correlation | PLANNED: the available schema/examples are candidates. `docs/detection/geographic-evidence-contract.md` explicitly describes a future pure function and missing authority/readiness requirements. No runtime function or approved G2 enablement was found. No auxiliary migration or power claim was added. |

Additional remote inspection found Rusu's branch at `ccf6ea5` with startup-alignment
and integration-report changes, and the connected dashboard changes now merged into `origin/main` at `50a0b0a`. The priority policy was accepted by the task owner without Rusu; neither branch supplies a reviewed runtime cause-correlation function. The integration report at `ccf6ea5` explicitly records
connected acceptance as PARTIAL at that earlier revision; the current local smoke and incident trace now pass.

At verification time no commit, merge, push or external review message had been created. The task owner's policy decision
is recorded. Perform final technical self-review, then follow the exact requested commit/merge sequence. The canonical task guide
remains in the parent `Guides` folder.
