# Sergiu — Day 1: numeric and evidence contract freeze

Prepared 5 October 2026; implemented on `feature/sergiu-day1-numeric-evidence` from current main `46c72f9`.
Technical implementation and verification are complete. External owner reviews/shared G1 remain pending.
Actual results and handoff: [Day 1 evidence](../../docs/evidence/2026-10-05-sergiu-day1-numeric-evidence.md).

## Source and original planning context

Source archive: `D:/Univer/Anul2/Orange_intership/project/Orange_Internship_5_Day_Planning_Pack_FINAL.zip`.
Primary assignment: `source/02_Rusu_Serghei_5_Day_Tasks_FINAL.md`, Day 1 and Numeric and test reference. The assignment explicitly identifies Rusu Serghei as Rusu Sergiu, historical author SergiuTOP.
Shared authority: `source/00_Shared_Project_Redesign_5_Day_Plan_FINAL.md`, KPI dictionary, baseline decision, G1 and AGG acceptance matrix. Audit: `source/06_Repository_Audit_FINAL.md`.

The pack was audited against `cdac43e3f294df213e44de046ccb09d2d1c47547`; the original checkout inspected during planning was `dad36016321d5f9e4d512b9e8c8da24b0dd9760c`. Implementation later used fresh main `46c72f9` and the published Ion catalogue. The linked evidence records actual checks and leaves Stanislav's shared integration acceptance pending. The pack's PR/runtime state remains an audit snapshot.

The archive contains editable Markdown but no PDF entries, despite README references to seven PDFs. The Markdown provides the assignment and shared specification needed here.

The earlier September `tasks/plan.md` and `tasks/todo.md` are preserved. This assignment's checklist is `tasks/sergiu-day1-redesign/todo.md`.

## Outcome and scope

Deliver one reviewable metric dictionary, exact policy boundary table, numerical fixture set, baseline/ML compatibility decision, role-resolution agreement and evidence wording contract. Ion, Denis and David review the relevant outputs; Stanislav receives runtime prerequisites and evidence. Day 1 ends with Sergiu's deliverables accepted and the shared G1 status explicitly recorded.

City-aware runtime detector/feature changes and twenty-scope baseline loading belong to Day 2. Day 1 specifies them sufficiently to implement without guessing. Existing Java ServiceFeatureBuilder and EvidenceJoiner belong to Ion; incident storage, OpenAPI and read DTO integration belong to Denis. Do not introduce a second episode state machine or change strict V2 schemas casually.

## Ordered execution plan

Time estimates below are planning estimates for approximately 7–8 focused hours, including review. Team response time can extend completion.

### 1. Establish the integration inputs — 30–45 minutes

Read the shared plan, assignment and audit. Obtain Stanislav's integration SHA and regression baseline, plus Ion's proposed city/source/role catalogue. Record editing ownership and which contracts are approved versus proposed. Review local changes before using a separate implementation branch/worktree; leave unrelated files intact.

**Acceptance:** exact base SHA is recorded; catalogue version or its pending owner is recorded; no outdated PR status is treated as current fact. Draft independent numeric work immediately if the catalogue is pending, but leave city-specific mappings and G1 approval pending.

**Verification:** compare source anchors against the selected integration checkout and list any differences.

**Outputs:** proposed `docs/evidence/sergiu-day1-g1.md`; no detector edits. Dependencies: none.

### 2. Capture the unchanged detector/model contract — 60 minutes

Inspect DetectionPolicy, VoiceSetupRule, SmsDeliveryRule, RecoveryPolicy, VoiceEpisode, SMS episode logic and MlClient; read policy, feature order, baseline catalogue, model manifest and scoring.py. Record SHA-256 for policy, feature order, manifest, model and calibration bytes, and the existing feature/detection schemas. Verify artifacts against manifest checksums, rather than copying the declared hashes alone.

Preserve:

| Area | Existing contract to freeze |
| --- | --- |
| Window | 60-second aligned UTC minute; 10-second allowed lateness |
| VoLTE eligibility | At least 100 eligible technical attempts |
| VoLTE breach/recovery | Drop strictly >1.0pp; healthy recovery drop ≤0.5pp; interval between is gray |
| VoLTE severity | HIGH at ≥50 estimated extra failures; CRITICAL at ≥200, for breached windows |
| SMS delay breach | ≥30 delivered samples AND p95 >20,000ms AND p95 >3× baseline |
| SMS backlog breach | Authorized fresh queue depth ≥100 AND oldest age >60s; preserve this independent evidence path |
| SMS recovery | Complete service and fresh queue, oldest age ≤30s; eligible p95 ≤10,000ms AND ≤2× baseline, or measured zero deliveries and zero queue |
| SMS severity | Capture actual rule: HIGH backlog or delay with ≥100 affected deliveries; CRITICAL fresh queue depth ≥1000 AND age ≥300s |
| Episode | Two adjacent breaches open; three adjacent healthy windows recover; UNKNOWN/gray/gaps do not count as healthy |
| ML | isoforest-v2-synthetic-1; rank threshold 0.99; 250ms budget/eight permits; rank is not probability |

Preserve ordered VOLTE features: cssrDeltaPp, sip503Ratio, rrcDeltaPp, bearerDeltaPp, packetLossRatio, imsCpuPct. SMS: p95DelayRatio, p95DeliveryMs, queueDepth, oldestPendingAgeSec, deliverySrDeltaPp, deliveredMessages. Missing required input produces empty model arrays, not zero-fill; independent rules can still evaluate.

**Acceptance:** thresholds, comparators, recovery behavior and vector order are documented from code; actual artifact hashes are recorded; no threshold/model/schema changes.

**Verification:** manually match every boundary-table entry to policy and evaluator; add below/equal/above expectations to the fixture plan.

**Outputs:** proposed `docs/detection/geography-numeric-contract.md` and evidence manifest. Dependencies: Task 1 integration base for final hashes.

### 3. Define metric meaning and exact golden cases — 90 minutes

Define eligible attempts = attempts − userOutcomes = technicalSuccesses + technicalFailures. CSSR = 100 × successes / eligible attempts. Display deviation = observed − baseline; detector drop = baseline − observed. Rates aggregate by counters over compatible disjoint partitions of the same service/window/units. Baseline expected successes = Σ(Di × Bi / 100); weighted baseline = 100 × expected successes / ΣDi. Extra technical failures = max(0, expected successes − observed successes). Keep precision until display and uniqueSubscribers null.

Define SMS p95 as nearest rank over raw minute samples, queue depth as snapshot, age in seconds, delay in milliseconds and samples separately. A wider p95 cannot be calculated from child p95 scalars alone. Source coverage tracks expected/received/usable sources; contributor sourceEventIds do not prove full coverage.

Minimum fixtures:

| Case | Expected outcome |
| --- | --- |
| 90/100 + 999/1000 | 1089/1100 = 99.0%; no average of percentages |
| Above with baseline 99.3% | Display −0.3pp; detector +0.3pp; expected successes 1092.3; extra failures 3.3 |
| Different baselines: 90% for D=100, 99.9% for D=1000 | Weighted baseline 99.0%, not 94.95%; expected successes 1089 |
| Zero denominator | Null rate with INSUFFICIENT_DATA; never healthy zero |
| Missing required baseline | Observed rate may remain; full-set baseline, deviation and estimated impact unavailable |
| Partial partition/source coverage | Explicit subset and coverage limitation; no full-city health claim; retain fresh known breach where existing eligibility permits |
| Samples 1..20 | p95 = 19; not interpolated |
| Child p95 scalars without samples/distribution | Null aggregate with proposed NOT_AGGREGATABLE reason |
| Different service/unit/window or overlapping footprint | Reject incompatible aggregation; no synthetic combined number |
| Policy limits | Below/equal/above each strict/inclusive threshold, low sample/attempt volume and SMS queue-only path |
| Recovery sequences | Second adjacent breach opens; third adjacent healthy closes; UNKNOWN/gray/gap resets consecutive progress |

Separate proposed display/aggregation null reasons from existing strict V2 enum values; any necessary DTO addition must be coordinated with Denis, not inserted into V2 without review.

**Acceptance:** expected values and null reasons are explicit and independently calculated; fixtures include raw inputs, scope/window/version and expected outputs; no fabricated subscriber/cause values.

**Verification:** implement proposed GeographicAggregationCases in the appropriate existing Java test/fixture conventions, plus Python reference cases where relevant; reuse existing explanation fixtures. A specification fixture is not proof that geographic runtime aggregation exists.

**Files likely touched:** numeric dictionary, proposed geographic expected fixture, proposed GeographicAggregationCases test, existing explanation fixture only if needed. Dependencies: Task 2.

**Checkpoint:** numeric examples, comparator table and unavailable semantics are ready for Ion/Denis/David review.

### 4. Freeze baseline and role compatibility — 60–75 minutes

With Ion, enumerate the proposed ten cities/twenty service scopes and explicit authoritative service/dependency roles. Preserve legacy IMS-A/SMSC-A/TRANSPORT-A resolution. Define resolver input (scope, service, catalogue/topology version, role), unique authorized result, and failure for missing/ambiguous/unauthorized mappings. Evidence still requires matching scope/window, COMPLETE quality and contributing event reference. List consumers: Ion's Java builder/joiner; Sergiu's DetectionWorker, rules and Python reference.

Prefer explicit same-service peer mappings into the unchanged baseline-v2 synthetic regime. Record per-scope donor, service, all 168 UTC hour slots, provenance DIRECT/PEER/BASELINE_MISSING and mapping digest. Reject cross-service fallback. If baselineVersion or numeric regime changes, mark ML input incompatible and preserve deterministic fallback until separately reviewed. Do not relabel it baseline-v2 to bypass scoring.py.

**Acceptance:** mappings are implementable and versioned; legacy cases retain meaning; invalid mappings and model compatibility decisions have expected outcomes. City-specific completion remains pending if Ion's catalogue is unavailable.

**Verification:** review BaselineRegistry and its strict loader/schema constraints; specify compatible peer, missing hour, cross-service peer, wrong version and ambiguous role tests for Day 2.

**Outputs:** proposed baseline-role decision document and mapping fixture. Dependencies: Tasks 1–3 and Ion's catalogue. No Java feature ownership transfer.

### 5. Agree explanation vocabulary and optional boundary — 45–60 minutes

With Denis, specify semantic DTO fields for cause, categorical confidence, supporting/contradictory evidence, event/node references, source authority, scope/time/version, limitations and next checks. With David, agree clear wording for IMS capacity hypothesis, SMS queue bottleneck, cause undetermined, missing baseline and insufficient data. Preserve technical state versus analyst state; distinguish current measurements from historical retained severity/impact on UNKNOWN.

IMS CPU plus SIP 503 and healthy access can support a hypothesis, not a confirmed diagnosis. Missing telemetry, ping failure or failed worker never proves power failure. Keep confidence categorical and ML rank separate.

Define optional AuxiliaryNetworkEvidenceV1 separately from V2: authority, target mapping, timestamps, topology alignment and freshness policy. The shared plan proposes ≤90s freshness; record whether approved, rather than silently making it an existing rule. Denis owns storage/API projection; Sergiu's future correlation is a pure function. Enable only at Day 2 readiness with publisher, consumer, authority fixtures and storage/API path; otherwise PLANNED/NOT_IN_SCOPE.

**Acceptance:** wording/DTO meanings and negative controls are reviewable; optional power scope is explicitly decided; no scenario truth in detector inputs.

**Verification:** reuse explanation cases and review absent, stale, mismatched and contradictory evidence examples. Recheck any older local unresolved UNKNOWN contract note against the actual integration SHA rather than assuming it is still unresolved.

**Outputs:** proposed explanation-contract document and examples aligned with Denis's DTO ownership. Dependencies: Tasks 3–4.

### 6. Verify, review and hand off G1 — 60–90 minutes

Run on the agreed checkout using repository dependencies and Java 21:

```powershell
python scripts/check-contracts.py
python -m unittest discover -s tests -v
python -m unittest discover -s services/ml-service/tests -v
mvn -pl services/processor -am '-Dtest=VoiceRuleTest,SmsRuleTest,BaselineRegistryTest,DetectionConfigurationTest,ExplanationCasesTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Add GeographicAggregationCases to the focused Java command once implemented. Check repository Maven tooling before execution. Run episode tests if new boundary cases exercise episode behavior. Record command, integration SHA, runtime, exit status and actual pass/fail/skip counts; label missing runtime as NOT_RUN. Hash invariant files again. Fresh Java/Python parity is Day 2; Python-only checks must be named accurately. Packaged scorer execution requires pinned libraries; identify this prerequisite for Stanislav without claiming execution.

Ion reviews counters, feature expectations, roles and evidence authority; Denis reviews aggregation/nulls, baseline provenance and DTO meaning; David reviews units, deviation signs and cause wording. Record explicit review disposition and unresolved owner, not assumed sign-off. Send no external messages as part of executing this plan unless separately instructed.

**Acceptance:** Sergiu's required cases pass and reviews are recorded; policy/order/model checksums are unchanged. Shared G1 also requires Stanislav's combined-tree baseline and resolved integration/SSE path, plus team-approved ten-city/twenty-scope contracts. Sergiu cannot declare shared G1 passed from isolated unit tests.

**Outputs:** metric dictionary, boundary table, expected fixtures, role/baseline decision, cause examples, hashes, test evidence and review matrix. Dependencies: Tasks 1–5; team G1 prerequisites.

## Completion and blockers

Day 1 is complete when the checklist is satisfied and G1 disposition is explicit. If catalogue, integration base or reviews are missing, deliver the finished independent numeric work and record the remaining dependency with its owner; do not start dependent Day 2 runtime changes using guessed IDs or semantics. No retraining, threshold weakening, strict-schema relaxation, live city rollout or PR reconciliation is assigned to Sergiu by this plan.
