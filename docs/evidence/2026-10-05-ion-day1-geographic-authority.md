# Ion Day 1 geographic authority - 5 October 2026

ION DAY 1: **PASS** (contract/authority scope only). SHARED G1: **PARTIAL**.
Day 2 was not implemented. Geographic producers/default authority are not activated.

## Current repository and publication boundary

Original checkout: `feature/volte-sms-service-assurance`, HEAD
`8e456f8035308057514929e8839508a81a611f0e`. After successful refetch, origin/main is
`c539b67a40ac776c693e9301fac91b035e4037bc`; original HEAD is 5 ahead / 43 behind.
Original tracked changes in dashboard branding mark/tokens, matching Keycloak branding and `scripts/up`
were preserved. Existing untracked `.agents/`, `.codex/`, `AGENTS.md`, telemetry audit artifacts and
`graphify-out/` were preserved. Existing stash `stash@{0}` on feature/durable-scenario-handoff,
`day11-g2-presync-2026-09-29`, was neither applied nor deleted.

No accepted redesign integration branch was found. `feature/g1-streaming-slice` and
`verify/g1-ion-acceptance` are the earlier September G1, not the five-day geographic redesign.
Created isolated worktree `C:\OrangeSystems\Program\ion-day1-geographic-authority`, branch
`feature/ion-day1-geographic-authority`, at latest main above, initially clean and 0 ahead / 0 behind.

Fresh GitHub metadata: PR #43 OPEN/CONFLICTING, head
`8e456f8035308057514929e8839508a81a611f0e`; PR #46 MERGED on 3 October 2026 19:37:49 UTC,
head `23f24c15f381f5f6f6437dbf94b766083882f13e`. #45/#42/#44 are merged. The PDF snapshot
`cdac43e3f294df213e44de046ccb09d2d1c47547` is stale for current branch/PR status. Current merged
session streaming, replay, bounded history and dark UI were retained from main; no old PR was merged
or cherry-picked. Main was fetched again before final publication review and remained c539b67.

Recent affected history inspected: c539b67 dark UI merge; 23f24c1 bounded-history reconciliation;
b376975 authorized-stream logout completion; ec31ec6 live investigation/audit history; b2b962e
session-bound committed hints; b05e7fc incident replay merge; 5ca86fb continuous/history telemetry;
earlier merged finalizer/replay safety. Current processor migration maximum is V007, unchanged.

## Hard-coded assumption inventory

Paths below are relative to the repository. EG = `services/event-generator/src/main/java/md/utm/telecom/generator/`;
P = `services/processor/src/main/java/md/utm/telecom/processing/`;
SS = `services/streaming-support/src/main/java/md/utm/telecom/observation/`.

| File | Current assumption | Owner | Day 1 disposition | Day 2+ action |
| --- | --- | --- | --- | --- |
| EG `continuous/HealthyTelemetry.java`:12,18-19 | Two legacy SCOPES; literal VOLTE-MD-CENTRAL/SMS-MD-ROUTE-A switch | Ion | Preserve; inventory | Day 2 iterate activated catalogue scopes |
| EG `VoiceScenario.java`:30,73-79,111-112,130-133 | VOLTE-MD-CENTRAL, VOLTE-ADAPTER, IMS-A, TRANSPORT-A; literal measurement switch | Ion | Preserve IDs and timing | Day 2 parameterize source/role context with legacy overloads |
| EG `VoiceScenarioPublisher.java`:32 | Kafka key fixed VOLTE-MD-CENTRAL for legacy preview | Ion | Preserve legacy entrypoint | Keep legacy key/body equality; city producers use payload scope |
| EG `scenarios/SmsQueueScenario.java`:24-26,240-247 | SMS-MD-ROUTE-A, SMS-ADAPTER, SMSC-A; natural identity excludes seed | Ion | Preserve | Day 2 scope/source/role context, legacy IDs unchanged |
| EG `api/ScenarioExecutionService.java`:179-201 | Validation uses topology service, generate calls builders without command scope | Ion | Document target mismatch risk | Day 3 pass validated command scope into builders and assert body/key/command equality |
| `contracts/topology/demo-scopes-v2.json` | Two baseline scopes, exact legacy reporters | Ion | Unchanged; separate additive candidate | Activate new version after reader/drain gate |
| SS `TopologyCatalog.java`:87-112 | Exact fields only; per-scope unique nodes/reporters | Ion | Reused unchanged for candidate | No hierarchy/roles injected into strict objects |
| SS `ObservationValidator.java`:43-65 | Strict V2/schema and injected authority, no city-name fallback | Ion | Unchanged; city/unauthorized tests added | Wire activated authority after contract gate |
| P `topology/ScopeRegistry.java` | Delegates to injected strict topology; no city literals | Ion | Unchanged; test candidate injection | Consume pinned geography role context |
| P `topology/EvidenceJoiner.java` | Exact scope/window, approved node/reporter, COMPLETE numeric measurement | Ion | Unchanged; all-city isolation/MISSING tests | Builder selects role-specific metrics; no fallback NODE |
| P `kpi/ServiceFeatureBuilder.java`:109-110,126-127 | IMS-A cpuPct, TRANSPORT-A packetLossRatio, SMSC-A queue/age | Ion; Rusu semantics review | Preserve feature order/schema | Day 2 use GeographyCatalog.resolve in paired parity change |
| P `detection/DetectionWorker.java`:91 | service SMS ? SMSC-A : IMS-A; generic delivery app.voice_delivery | Rusu | Handoff only | Day 2 role selector conversion and coverage outbox review |
| P `detection/VoiceSetupRule.java`:121-122,143 | Literal IMS-A node/reporter/evidence target | Rusu | Handoff only | Resolve node/reporter from VOLTE_IMS |
| P `detection/SmsDeliveryRule.java`:81,126 | Literal SMSC-A node/evidence target | Rusu | Handoff only | Resolve SMS_SMSC |
| `services/ml-service/app/features/service_features.py`:113-125 | TRANSPORT-A, IMS-A, SMSC-A feature selectors | Rusu | Unchanged; nine reference tests pass | Match Java resolver semantics and freshly exported parity |
| `services/incident-service/src/main/java/md/utm/telecom/simulator/service/ScenarioCommandService.java`:271-272 | Only VOLTE-MD-CENTRAL / SMS-MD-ROUTE-A compatible | Denis | Handoff only | Catalogue-aware backend scenario projection/validation |
| P `history/HistoricalTelemetryBootstrap.java`:125,147-165 | Legacy job; delivery ID joined to feature window ID; KPI-only drain | Ion | Preserve; precise future boundary | Day 3 separate versioned job and explicit coverage-topic drain |

## Contract and compatibility evidence

Full field definitions, all ten names/twenty scopes, source/node naming, containment, required roles,
legacy aliases, version/activation, identity, coverage and auxiliary boundaries are in
`docs/streaming/geographic-authority.md`. Candidate versions: topology `2-geography-g1`, catalogue
`1-geography-g1`; status CONTRACT_ONLY/effectiveFrom null. The baseline still loads 2 scopes; the
candidate loads 22, including 20 geographic scopes. Java resolver exposes exact authority Node objects.

Observation identity: namespace telecom-observation-v2 + sourceId/scopeId/kind/canonical windowStart,
Java nameUUIDFromBytes; seed excluded. Existing legacy fixtures, event IDs, natural-key/episode code,
Kafka payload-scope key, 60s window, +10s closure and lateness behavior remain unchanged.

Coverage identity: SHA-256 of compact UTF-8 JSON
`["scope-window-coverage-v1",scopeId,canonicalWindowStart,topologyVersion,catalogueVersion]`.
Required raw profile: 3 VoLTE / 2 SMS. COMPLETE zero raw volume is usable telemetry, but does not
invent rates/p95/ML eligibility. Received MISSING/INCOMPLETE remains unusable; HEARTBEAT is excluded.
Absent optional SMS transport is not a deficit. sourceEventIds never defines the expected inventory.

Future atomic boundary: WindowDecisionLock/finalization transaction -> finalized feature/KPI -> accepted
receipt coverage snapshot -> generic app.voice_delivery(id=coverageId, topic=telecom.coverage.v1,
key=scopeId) -> finalized marker. Existing leased publisher handles broker delivery later. This is an
explicit handoff requiring Rusu/Denis review, not a new runtime outbox or a completed consumer.

Digests are SHA-256 of Git blob UTF-8/LF bytes (not a canonical-JSON semantic digest).
The final digest check caught Windows checkout CRLF hashes; metadata was corrected to Git blob hashes
before publication. Normalize checkout CRLF bytes before comparing:

| Artifact | Digest |
| --- | --- |
| geography/demo-geography-v1.json | 134fb76a79e0942a930700a323b693108010fb1f64f58e708b67075aabf5eced |
| topology/geographic-scopes-v2.json | b0f39e02a4687c34bbe3ba988100402033a137eb9500a290d671a63aee6a5532 |
| coverage/scope-window-coverage-v1.schema.json | 67bd4240a8e7e00037f753f5867eb5d6b7b1874f73f7014cc604bcfa2c168866 |

`2026-10-05-ion-day1-contract-manifest.json` records every contract/fixture digest and golden IDs.
No changes versus origin/main in baseline topology, observation schema/legacy fixtures, feature schema/order,
generator runtime, processor runtime/migrations, models/policies, detector/baseline/episode code,
incident-service/auth/security/OpenAPI/migrations, dashboard or deployment configuration. All changes
are the Day 1 allowlist; generated Graphify output is retained untracked, excluded from the commit.

## Rejection evidence

Shared invalid catalogue fixtures reject: duplicate city -> Duplicate cityId; duplicate scope ->
Duplicate scopeId; duplicate companion node -> Duplicate nodeId; absent parent -> Dangling parent;
cycle -> Containment cycle; cross-city parent -> Cross-city parent; missing role -> Missing role;
duplicate role -> Ambiguous role; absent target -> Unknown node; wrong-service/capability target ->
Wrong role capability; other-city target -> Unknown node in scope; other-city footprint -> Invalid footprint.
Both Java and Python assert these reasons. Authority tests reject unknown fields, duplicate per-scope
node/reporter, changed legacy mapping, unauthorized SERVICE/NODE sources and cross-city evidence.
Coverage tests reject bad identity/version/service, unauthorized sets, usable without receipt and missing
quality reasons. Existing and new batch tests reject changed content under unchanged natural identity.

## Validation results and environment

Python is the existing repository venv interpreter:
`C:\OrangeSystems\Program\Telecom-Anomaly-Detection\.venv\Scripts\python.exe`.
Java is Eclipse Adoptium 21.0.12.1; Maven is 3.9.16. `mvnw.cmd -v` failed with Cannot index into a null
array/Cannot start maven; used its cached distribution at
`C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16\56ba1f9f\bin\mvn.cmd` (abbreviated MVN below).
Initial offline resolution failed for main's Spring Boot 3.5.16 parent; normal authorized Maven resolution
succeeded. A new CoverageContractTest lambda compile error was corrected before final PASS. An initial
unquoted PowerShell -D argument prevented reactor startup; corrected quoted command below succeeded.
No failures were suppressed or no-tests-found flags used. Docker Desktop was started for tests;
Docker engine 29.3.1 provided isolated PostgreSQL Testcontainers. Kafka was isolated at 127.0.0.1:65534.

| Exact command (with interpreter/distribution prefix above) | Result |
| --- | --- |
| `python scripts/check-contracts.py` | PASS: 13 legacy observations; 4 detection/policy/baseline payloads; 12 explanation trajectories; 7 VoLTE + 12 SMS reference cases; 50 city observations; 12 rejected catalogue cases; 30 coverage cases |
| `python -m unittest discover -s tests -v` | PASS: 26 tests; 0 failures/errors/skips |
| `python -m unittest discover -s services/ml-service/tests -p test_service_features.py -v` | PASS: 9 tests; 0 failures/errors/skips |
| `MVN -pl services/streaming-support test` | PASS: 105 tests; 0 failures/errors/skips (final rerun after activation accessor/profile guard) |
| `MVN -pl services/processor -am verify '-Dspring.kafka.bootstrap-servers=127.0.0.1:65534'` | PASS reactor: streaming 105, generator 59, processor 239; 403 total, 401 passed, 0 failures/errors, 2 skipped |
| `MVN -pl services/processor -am test '-Dtest=TopologyCatalogTest,ObservationValidatorTest,GeographyCatalogTest,CoverageContractTest,ScopeRegistryTest,EvidenceJoinerTest,ObservationGeneratorTest,VoiceScenarioTest,SmsQueueScenarioTest' '-Dspring.kafka.bootstrap-servers=127.0.0.1:65534'` | PASS final focused: streaming 105, generator 18, processor 41; 164 total, 0 failures/errors/skips |
| `git diff --check` | PASS |

The two SKIPPED tests are SmsDeliveryTest.livePackagedModelEnrichesRecoveredSmsEpisode and
VoiceDeliveryTest.livePackagedModelEnrichesRecoveredVoiceEpisode: existing assumption requires
ML_SERVICE_URL, which was not configured. No live model acceptance is claimed. Database-backed replay,
late input, missing windows, finalizer races and history tests ran. Broader reactor ran before the final
activation accessor/profile guard; final focused affected-module tests and Python checks ran afterward.
Current Java/Python parity cases retain their expectations, but no fresh city-feature parity export or
authenticated geographic live run was performed. Incident-service/frontend shared regression: NOT RUN
in this Ion contract task, not claimed passed.

Graphify was used for initial navigation per local AGENTS.md. `graphify update .` launcher failed because
the referenced .local/bin/graphify is missing. Direct installed interpreter
`C:\Users\Admin\AppData\Roaming\uv\tools\graphifyy\Scripts\python.exe -m graphify update .` succeeded:
4,292 nodes / 11,372 edges / 283 communities. SQL AST extraction is unavailable for 10 SQL files
(tree_sitter_sql absent); no semantic document refresh is claimed.

## Ion acceptance and shared blockers

| Ion Day 1 criterion | Status |
| --- | --- |
| Exactly 10 geographic areas / 20 scopes / both services in every city | PASS |
| Legacy valid, separate and unchanged | PASS |
| Strict observation authority preserved | PASS |
| Companion geography/role catalogue, version/activation defined | PASS |
| Role resolution and deterministic invalid mapping rejection | PASS |
| Expected/received/usable coverage semantics and deterministic identity frozen | PASS |
| No unresolved Ion-owned identity/role/coverage field guessed | PASS |
| Old fixtures and focused Java/Python tests | PASS |
| Contract validation and diff --check | PASS |
| No unrelated code or ownership changes | PASS |

ION DAY 1 = PASS. SHARED G1 = PARTIAL. NOT READY FOR ION DAY 2 as shared gated implementation.
Exact outstanding gates:

- **Stanislav / David / Denis / Ion**: no accepted five-day integration SHA/ledger is recorded; PR #43 is
  still OPEN/CONFLICTING. Stanislav must record keep/adapt/already-present/defer dispositions and run
  combined frontend/backend/security regression. Ion has preserved and audited its source base, not
  reconciled other owners' code.
- **Rusu**: review new role vocabulary/capability semantics and coverage delivery boundary; freeze the
  city peer baseline/ML-version compatibility decision before paired Java/Python/detector conversion.
- **Denis**: review ScopeWindowCoverageV1 and publish the coverage projection/OpenAPI/source-set
  consumer contract, including duplicate/conflict policy and catalogue version pinning. No such
  geographic projection contract or consumer exists on current main.

These shared approvals/contracts are not inferred from Ion tests. The conditional auxiliary boundary
has no approved publisher/consumer; runtime remains deferred. This is not an Ion Day 1 blocker.

## Handoffs (prepared locally; no messages sent)

- **Rusu**: strict candidate + companion + digests + GeographyCatalog.Role/resolve/expectedSourceIds;
  exact DetectionWorker/VoiceSetupRule/SmsDeliveryRule/Python selectors above; 50 city observation
  goldens and 30 receipt/coverage goldens; retain legacy feature parity fixtures and six-feature order.
  Review catalogue/coverage semantics and synthetic peer baseline regime; perform paired Day 2 parity
  exports and detection-package outbox change. No fake city feature golden is presented.
- **Denis**: all IDs/display names, immutable catalogue/activation contract, V1 coverage schema/identity,
  expected/received/usable rules, optional transport and telecom.coverage.v1 scope key; ScenarioCommandService
  literal check at 271-272. Own consumer/projection/OpenAPI and incidents_db version reservation.
- **David**: city table, twenty scopes, single equally supported country/city/aggregation/site/cell chain,
  scope membership versus measured granularity, synthetic provenance, legacy/unallocated exclusion.
  Render unavailable parent/leaf measurements honestly; no fabricated copied aggregate.
- **Stanislav**: manifest/digests/commands, Java21/Maven/Docker prerequisites, 50 city raw + 20 city KPI
  future windows/minute (60 raw if optional SMS transport on; legacy separate), producer-after-consumer
  activation/drain requirements and exact shared blockers above. Reserve migrations only when needed;
  none added today. Keep Graphify build artifacts outside the focused commit.

## Git result interpretation

The focused commit containing this evidence identifies the implementing SHA; it cannot embed its own
SHA without a self-reference. Commit only the Day 1 allowlist after all final checks. Normal branch push
is permitted by the task when remote ownership/history is compatible. No force push, merge, rebase,
stash deletion or teammate PR action is authorized/performed. Final commit/push/remote confirmation
is reported in the task's final response.
