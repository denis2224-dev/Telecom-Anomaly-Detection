# PR #77 historical connected G4 acceptance (2026-10-08)

**Historical technical acceptance: PASS for the runtime identified below.**
This sealed capture is not acceptance of current main or a later PR/final-candidate
SHA. Human reviewer approval and shared owner sign-offs were **PENDING** at capture.
G5 release approval remains **BLOCKED** until the shared final-candidate gates pass.
No PR merge or approval was performed. Optional power correlation remains **NOT_IN_SCOPE**.

For fresh combined-revision verification, follow the
[supported isolated workflow](../runbooks/pr77-connected-verification.md). Its new
results must identify their own tested runtime and must not relabel this capture.

## Candidate and environment

Application images were built from `f3e83b157af134c8138354340717e7e3df946768`.
The historical integrated revision `b6969f404119aa5a6805c7d3a6db72a4854c5311` includes
main as it stood at `5475867`; the intervening changes contain only documentation
and Java tests. This equivalence does not cover main `a52cc85`, subsequent main
revisions, or the current PR publication head.
The [source-equivalence proof](assets/sergiu-g4-pr77/source-equivalence.json) verifies
identical application source/configuration and records per-file SHA-256 hashes.
The historical evidence publication added evidence only; these immutable image
identities and source hashes identify its tested runtime. CI results for later
publication heads must be checked separately on GitHub.

The isolated project was `telecom-g4-pr77`, with fresh PostgreSQL/Kafka volumes,
random temporary credentials and a separate OIDC realm database. The existing
`telecom-anomaly-incident-service-1` was left untouched. Its original startup failure
was `UnknownHostException: postgres`, with no database container available.

The complete isolated stack included PostgreSQL, Kafka, generator, processor, ML,
incident service, NGINX and Keycloak. Geography was enabled, effective from
`2026-10-08T00:00:00Z`; continuous synthetic telemetry was enabled and historical
bootstrap disabled. Public origin was `http://telecom.test:8080`; the isolated proxy
used `incident-service:8082`, avoiding the user's original backend. Ports were
8080 (proxy), 18081 (generator), 18082 (incident), 15432 (database), 19094 (Kafka).
The [initial runtime record](assets/sergiu-g4-pr77/initial-runtime.json) and restored
[runtime record](assets/sergiu-g4-pr77/runtime.json) pin image IDs, configuration
hashes, database version, topic names and dashboard asset hashes. Private credentials,
session identifiers, cookies and tokens are excluded; native browser captures mask
the account/session widget. No responses were intercepted and no fixture fallback was used.

The [cleanup record](assets/sergiu-g4-pr77/cleanup.json) confirms restored readiness,
removal of temporary identities and the isolated stack/volumes/credential files,
and retention of the original user container.

## Historical executed acceptance

| Check | Expected and actual result | Evidence |
| --- | --- | --- |
| Real authenticated city scenarios | Chișinău VoLTE overload and SMS backlog each produced one separate episode. Bălți normal and Cahul SMS telemetry-gap controls produced none. Identical command retry retained run identity. | [City runs](assets/sergiu-g4-pr77/city.json) |
| Hysteresis and canonical identities | Each fault followed OPEN, UPDATE, UPDATE, UPDATE, RECOVERY. Opening followed two breaches; recovery followed three healthy windows. Episode/detection IDs were recomputed independently. Null unique-subscriber impact was preserved. | City runs |
| Independent city gap | Three actual absent-source windows were MISSING, with null observed metrics, `mlEligible=false` and no invented source event IDs or false incident. Twenty city/service histories were read from the backend. | [Gap proof](assets/sergiu-g4-pr77/gap.json) |
| Receipt → feature → detection → API | Original Kafka offsets and immutable receipt/feature hashes traced measured source IDs and KPI payloads to authenticated API history. | [Trace and replay](assets/sergiu-g4-pr77/replay.json) |
| Map, priority queue and details | City, service scope, current state, UTC source window, topology/catalogue, units, measured impact and cause evidence agreed across real API and browser views. Healthy evidence withdrew the prior hypothesis while the episode awaited recovery. | [Display assertions](assets/sergiu-g4-pr77/display.json), masked captures |
| ML unavailable | The real ML container was stopped. Forty legitimate synthetic observation envelopes, derived from prior actual generator measurements and retimed to authorized Edineț sources, entered Kafka and PostgreSQL. There was no scenario/oracle metadata in observations. Deterministic policy and independent impact/identity arithmetic passed with UNAVAILABLE/TIMEOUT transport results and null model/rank. | [Connected controls](assets/sergiu-g4-pr77/controlled.json), [independent trace](assets/sergiu-g4-pr77/controlledTrace.json) |
| UNKNOWN and historical origin | The sequence was OPEN, UNKNOWN, UPDATE, UNKNOWN, UPDATE, UPDATE, RECOVERY. Each UNKNOWN retained the actual earlier measurement's detection ID/window, never its own missing-data window. UNKNOWN reset the healthy streak; three subsequent consecutive healthy windows were necessary. | Connected controls |
| Durable duplicate replay | Redelivering 74 observations and 10 detections, then restarting the processor, left exact receipt, feature, detection, incident identity, version and committed-history snapshots unchanged. Restoring ML did not rescore committed control history. | Trace and replay; connected controls |
| Real reconnect | The isolated proxy was stopped and restored. Two real SSE connections and renewed authoritative geography requests were observed; city selection survived. No intercepted network responses were used. | [Security/reconnect](assets/sergiu-g4-pr77/security.json) |
| Security boundaries | Anonymous REST/SSE returned 401; missing/stale CSRF returned 403; ANALYST could not start scenarios; OIDC rotated the application session and rejected pre-login CSRF; browser storage contained no tokens. Actual UI/provider logout reached signed-out and protected REST/SSE returned 401. | Security/reconnect; [rotation/logout assertions](assets/sergiu-g4-pr77/rotation-logout.json); absolute-expiry record |
| Actual expiry | Idle expiry was observed after 15 minutes. Absolute expiry was observed after the service's 30-minute deadline despite continued authenticated activity. Backend Date headers anchored the final absolute test, avoiding host-clock skew. Protected REST/SSE rejected the expired session. | [Absolute expiry](assets/sergiu-g4-pr77/absolute.json) |
| Protected feature-off command boundary | City dispatch was rejected by the disabled private generator while compatible readers retained city authority. The existing public dispatch contract reports 503 / GENERATOR_UNAVAILABLE; persisted dispatch error was INVALID_SCOPE. The pending command was explicitly cancelled before restoration. Both legacy fault commands returned 202 and were stopped before measurement, proving routing only. Reader container/image identities stayed identical. | [Boundary audit](assets/sergiu-g4-pr77/featureBoundary.json) |
| Safe feature-off/fallback | Geographic generation alone was disabled; current compatible geographic processor/readers were retained. Actual legacy telemetry continued, original geographic history/version snapshots stayed identical, and authentication/scoped UI remained available. Original production and connected UI were restored; an authenticated city command returned 202 and was stopped as a temporary run, without claiming recovery. | [Feature-off rehearsal](assets/sergiu-g4-pr77/fallback.json) |

Exact UTC timestamps, scenario/run IDs, windows, event/detection references, expected
versus actual values and artifact SHA-256 hashes are in the linked JSON and
[bundle index](assets/sergiu-g4-pr77/index.json).

## Failures retained and scope limits

The first city run failed recovery because its final healthy observations arrived
roughly 88 seconds late and were correctly rejected after window closure. Its raw
result remains [recorded](assets/sergiu-g4-pr77/initial-failed-city.json); the successful
fresh city run is a separate execution, not a relabelled failure.

An interrupted control sequence and a later duplicate-driver collision were excluded
from acceptance. A subsequent large clock/timing discontinuity also invalidated a run.
These failures are retained separately in the bundle. Final controls used one driver,
a fresh scope, backend UTC and a temporary system-awake request. A final transport expectation was corrected against the detection contract: stopped
inference may report UNAVAILABLE or TIMEOUT, with null model/rank. That failed harness
result is retained, and a fresh run additionally asserts the ML container stays stopped
in each window. Required policy, identity, origin, replay and security assertions were not
weakened, and no production detector/security fix was inferred from harness or timing
failures. The final absolute check used backend time rather than a host deadline.

A fixed 14-second audit wait expired before asynchronous delivery at 19 seconds;
the fresh accepted run waits for completed durable jobs and the exact API window.
A supplemental boundary attempt hit an existing stopped test command reservation
and returned 409 before private generator dispatch; it is excluded. The fresh
boundary execution uses an unreserved Chișinău scope.
An invalid guessed HNC scope was correctly rejected as SEMANTIC_INVALID; the final
Edineț run verifies the live catalogue and all source/topology bindings before
publishing. These failed audits are preserved as separate artifacts and do not
count as successful checks.

The producer feature-off and presentation fallback checks do not complete the
full Day 5 REL-02 release-owner procedure: reviewed UI entrypoint removal,
recoverable-backup verification and full legacy fault/control timelines remain
release gates. A rejected geographic dispatch stays pending under the existing
503 retry contract, so it must be cancelled before restoring production.
This audit cancelled it and does not imply a geographic UI kill switch.

The feature-off rehearsal kept compatible binaries and data; it was not a binary
downgrade or database rollback. Measurements and topology are synthetic. Unique
subscribers remain unknown. Auxiliary failed-ping/probe coverage remains contract and
ingestion-boundary coverage only; no live power correlation or confirmed power diagnosis
is implied. Unaided colleague navigation and reviewer/owner approvals remain human gates.

## Verification counts and publication

The original **195** focused Java tests at `626721b` and the separate **192**-test audit
at `a1afc6d` remain historical runs. The latter omitted three GeographicReplayTest cases.
CI's original focused cause selection was **179**, with 16 DetectionReplayIT cases in a
separate step. These counts are not totals for this connected audit or new CI runs.
All four workflows passed at `f3e83b1` and at integrated `b6969f4`; the latter
[full CI record](assets/sergiu-g4-pr77/ci-integrated.json) includes model evaluation
and downstream processor verification. CI for later publication heads must be checked
separately and is not inferred from those earlier runs.

## Reproduction contract and archived drivers

The [audit drivers](assets/sergiu-g4-pr77/audit-drivers/) are **historical,
non-executable archives from their committed paths**. They preserve the original
run-specific checks, configuration and timestamps for inspection, not a supported
installer or fresh-run entrypoint. In particular, `prepare.cjs` and `lib.cjs` use
`path.resolve(__dirname, '../..')`: here that resolves to `docs/evidence/assets`,
not the repository root. Their relative dashboard imports have the same relocation
problem. The archived configuration also depends on the original machine, ports,
private identities and recorded source timestamps. Do not invoke or repair these
sealed files in place.

Use the [supported isolated workflow](../runbooks/pr77-connected-verification.md)
to build and verify a fresh integrated candidate with temporary identities and
owned resources. The tracked `scripts/day3-geographic-live.cjs` covers geographic
scenario timelines and identities; alone it does not reproduce receipt tracing,
all presentation/security assertions, controlled ML outages or durable replay in
this historical bundle. The supported focused workflow documents its own coverage
and exclusions. Never run a failure rehearsal against unrelated services.

All 55 artifacts listed by the [bundle index](assets/sergiu-g4-pr77/index.json)
remain byte-for-byte unchanged, including archived drivers and configuration. The
index's PASS statuses describe only this historical runtime. Keep new compact
verification evidence separate from these bulky historical captures, record failed
attempts independently, and publish neither credential files nor temporary identities.

Historical technical acceptance does not supply required reviewer/owner approval,
current-main compatibility, final-SHA acceptance or G5 release authorization.
