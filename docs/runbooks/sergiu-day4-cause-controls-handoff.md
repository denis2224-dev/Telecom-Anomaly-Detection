# Serghei Day 4 cause-control handoff

Scope: core detector controls. Optional power correlation is **NOT_IN_SCOPE**;
auxiliary samples remain **PLANNED**. This handoff does not claim owner sign-off or shared G4 acceptance.

## Denis and David: explanation behavior

The paired controls hold the SERVICE receipt bytes and UTC windows fixed, changing only
legitimate mapped dependency evidence. Run `GeographicCauseControlsTest` to generate
`services/processor/target/day4-cause-<scope>.json` for all twenty city/service scopes.
These artifacts are explicitly `CONTROLLED_FIXTURE`, not public API captures.

| Measured control | Expected explanation | Impact/state boundary |
| --- | --- | --- |
| Degraded VoLTE, aligned IMS CPU >=90%, SIP 503, healthy RRC/bearer | IMS capacity hypothesis, MEDIUM | Supports a hypothesis, never a confirmed diagnosis |
| Identical degraded VoLTE, healthy or absent IMS | Undetermined, LOW | Same service impact and episode identity |
| High IMS CPU but healthy VoLTE service | No incident | Dependency pressure alone is not a service breach |
| CPU <90%, no SIP 503, or unhealthy bearer setup | No IMS capacity hypothesis, LOW | Preserve the actual service breach |
| Degraded SMS, fresh measured queue backlog | Backlog hypothesis, MEDIUM | Pending queue count is measured; never subscribers |
| Identical degraded SMS, healthy or absent queue | Delay-only explanation, LOW | Same affected-delivery count; pending impact zero |
| Healthy completed SMS, fresh queue backlog | Backlog breach remains eligible | Service completion health does not override independent backlog |
| Ongoing degraded service, newly healthy dependency | Withhold prior hypothesis in the next UPDATE | Same episode; prior committed detections remain immutable |
| Reported-MISSING telemetry after breach | UNKNOWN, LOW | Retained severity/impact reference original measured window and receipts |

Runtime V2 detections retain `probableCause`, `causeConfidence` and `evidence[]`.
The separate geographic cause DTO with supporting/contradictory arrays is still a contract
candidate, not an implemented detector response. Preserve LOW/MEDIUM categories, null
uniqueSubscribers, and technical-state/analyst-state separation. Transport explanations
remain planned; no new transport cause code is introduced.

## Ion and Stanislav: evidence and runtime boundary

`GeographicMlFallbackReplayTest` uses real HTTP and PostgreSQL but controlled scorer
responses. It compares full deterministic payloads after removing only `mlStatus`,
`modelVersion`, and `anomalyRank`. Restore scoring and replay completed windows:
saved detections, jobs, IDs, timestamps and scoring counts must remain unchanged.
Only new windows may acquire fresh model evidence. HTTP 422 is an explicit rejection
control, not a fabricated compatible baseline.

The failed-ping/probe examples pass the separate auxiliary schema. Strict V2 ingestion
rejects standalone auxiliary payloads, auxiliary fields and scenario/oracle metadata,
leaving committed accepted evidence untouched. Rejection records are expected.
Label this `CONTRACT_AND_INGESTION_BOUNDARY_ONLY`; no auxiliary publisher, authorization,
consumer, persistence or live correlation has passed a readiness gate.

Fixtures and expected values remain outside detector inputs. Scenario names, injected
causes, run IDs and seeds are never model/cause features. The twenty-scope persistence
test uses real mapped receipts; reported-MISSING receipts test explicit missing data,
not inference of unrepresented geographic intervals.

## Shared gates and reproduction

The city simulator still limits scope targeting to legacy scopes in both public
ScenarioCommandService and private ScenarioExecutionService. Ion/Denis must integrate
city targeting before the authenticated city fault path can establish shared G3/G4.
Day 4 does not bypass those boundaries or alter their APIs.

Use JDK 21, Docker, and the project Python environment. Focused Java suites are selected
in `.github/workflows/integration.yml`; full verification is `./mvnw.cmd -q clean verify`.
Set `ML_SERVICE_URL` to an isolated ready model service for packaged-model delivery and
geographic compatibility tests. The existing SMS shadow-replay opt-in requires its own
evidence directory and is outside this core scope. Exact executed counts, hashes and
limits are recorded in the Day 4 evidence and manifest; skips are not runtime acceptance.
