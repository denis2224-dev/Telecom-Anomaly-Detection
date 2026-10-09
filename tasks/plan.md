# Detection foundations — implementation plan (15–18 September 2026)

Historical plan based on main `93fc17f`, preserving observation fields, source ownership
and OpenAPI. Results are recorded in
[the detection evidence](../docs/evidence/2026-09-18-sergiu-days-1-4.md).

1. Freeze version-2 policy, feature order and feature/detection schemas.
2. Independently calculate Python VOLTE/SMS features from validated observations;
   share raw/expected fixtures and verify nulls, denominators and nearest-rank p95.
3. Load versioned policy and UTC scope/hour baselines at processor startup;
   permit explicit same-service peer fallback and report missing coverage.
4. Evaluate the stateless voice rule with decimal-safe boundaries and impact;
   provide honest evidence without emitting or persisting episodes.

Verify contracts, Python feature tests, Java baseline/rule tests, existing Maven
tests, packaging and real packaged health probes. Record actual results for each check.
Training, independent Java feature parity, episode persistence, SMS rule execution
and live incident delivery remain later assignments. Teammate sign-offs are pending.

## Sergiu Day 2 — geographic detection (6 October 2026)

Base: main `cd5b013`; branch `feature/sergiu-day2-role-aware-detection`, same checkout.
Source: October five-day planning pack, Rusu Serghei Day 2. Preserve the historical plan above.

1. Activate the approved twenty same-service peer baselines only with geographic authority;
   retain baseline-v2 numeric meaning, explicit provenance and legacy queued-window compatibility.
2. Resolve detector receipts through the published role catalogue and exact feature topology;
   retain source/window/quality/contributor checks and atomic leased completion.
3. Extend the Python reference with explicit authority and inferred-missing support;
   preserve counters, p95, vector order, window identity and contributing provenance.
4. Add all-city fixtures and fresh persisted Java exports; compare all fields in both parity scripts.
5. Verify city model input, incompatible-baseline rejection, deterministic fallback and recovery/replay.
6. Exercise three live minutes across twenty scopes through receipt/KPI/coverage/protected API;
   record actual results and distinguish local checks from owner/UI/shared gate acceptance.

Checkpoints: focused baseline/configuration tests; city rule/worker tests; full Java/Python verification
and fresh parity; connected acceptance. No schema migration, model replacement, threshold change,
auxiliary power runtime or separate checkout. Geography remains opt-in. Stop producers before rollback
and retain compatible readers until geographic work drains. Formal owner reviews remain external.

Implementation and local verification are complete. The protected API matrix has
three live minutes across all twenty city scopes; existing frontend live bindings
still show Mapping pending. Shared G2 acceptance remains pending owner/UI review.
See [the evidence and manifest](../docs/evidence/2026-10-06-sergiu-day2-role-aware-parity.md).
# Sergiu Day 3 — explainable geographic incidents (7 October 2026)

Implementation base: `6800e94`, retaining delivery fairness and connected investigation fixes.
One coordinator integrates three independent workers: geographic episode invariants,
city explanation goldens, and queue/display acceptance handoffs. Preserve prior plans below.

1. Freeze pinned catalogue context, ID/timestamp expectations and exclusive file ownership.
2. In parallel extend all-city episode tests, legacy-preserving explanation fixtures,
   and independently calculated queue/display acceptance examples.
3. Integrate geographic durable replay checks; make production changes only for proven defects.
4. Run focused and full Java/Python/contract checks; review changes across worker boundaries.
5. Validate isolated live city scenarios through receipts, incidents, protected API and browser;
   record actual results and external blockers without claiming shared G3 acceptance.

Denis owns projection/queue implementation; David owns UI implementation. No model,
threshold, schema migration, optional power correlation or analyst workflow changes.

Day 3 result: local implementation and verification complete; eighty live normal points validated.
City fault dispatch returns INVALID_SCOPE in both owner layers; shared G3 remains pending.
See docs/evidence/2026-10-07-sergiu-day3-geographic-explanations.md.

# Sergiu Day 4 — cause controls (8 October 2026)

Approved core-controls scope, based on `ee52b9e`; branch `feature/sergiu-day4-cause-controls`.
Preserve the incomplete shared G2/G3 gates above. Optional power correlation is NOT_IN_SCOPE;
auxiliary contracts remain PLANNED. No runtime publisher/consumer/storage gate is claimed.

1. Cause worker owns new GeographicCauseControlsTest: paired identical service observations
   with changed legitimate dependency evidence, precise hypothesis guards, stable IDs and
   independent SMS backlog semantics across twenty scopes.
2. ML worker owns MlClient/Test and detection/MlClientFailureTest: response/input failure
   matrix, null model evidence, eight-permit saturation/release and real HTTP timeout.
   Only a package-private HttpClient constructor seam changes production code.
3. Replay worker owns geographic replay/fallback tests and any replay helpers: real SQL
   worker restart, scorer restoration without rescoring completed windows, cause withdrawal,
   UNKNOWN provenance and twenty-scope persisted isolation.
4. Coordinator owns separate ping/probe schema examples and strict ingestion-boundary tests,
   CI selection, sequential verification, review, evidence manifest and owner handoffs.

Checkpoints: validate boundary fixtures; compile/run focused worker suites; verify reactor,
Python/contracts and isolated packaged-model delivery with actual report counts. Workers do
not run Maven concurrently or edit shared ownership. Rules, model bytes, feature order,
baselines, V2 wire contracts and subscriber nulls remain frozen. No external messages or PR
publication are part of this task. City fault dispatch remains an Ion/Denis prerequisite;
offline controls must never be labelled connected G4 acceptance.

Day 4 core implementation and verification are complete: focused195, Java891 executed/1
shadow opt-in skip, Python51/54, packaged HTTP22 and fresh parity62 passed. Sixteen frozen
contract/model files match the base. The evidence/manifest and handoff record paired results
and remaining owner dependencies. Shared G4 remains pending; optional power stays NOT_IN_SCOPE.
# PR #77 review remediation — 9 October 2026

Integrate current main without rewriting PR history. Preserve the sealed historical
G4 bundle byte-for-byte, explicitly label its archived drivers non-executable, and
provide a separate supported isolated verification workflow. Fresh connected checks
cover real authentication, open-dashboard 15-minute idle and 30-minute absolute
expiry, actual proxy/SSE reconnect, and geographic map/queue/detail agreement.

Three isolated workers own evidence documentation, focused browser checks, and the
portable runtime runner. The coordinator integrates commits and operates acceptance;
workers never operate the user's Docker projects. Runtime checks use freshly built
production source/assets, explicit Compose targeting and temporary identities.

Record tested runtime SHA, integrated main, image/asset/configuration hashes and actual
CI checkout SHAs separately from evidence-only publication. All six existing CI jobs
must pass after publication. Historical acceptance is never relabelled current-SHA
acceptance. Failed attempts remain separately recorded. Shared G5 stays BLOCKED.

Completed focused runtime verification at `1c889216`, integrating main `0eb9b5b`:
all three real connected cases and guarded cleanup passed. Six candidate CI checks
passed separately. The evidence-only publication SHA and its subsequent six CI
results are recorded in the PR description; see the 9 October remediation report.

Latest-main update (9 October): acceptance of main 0eb9b5b is retained separately.
Main advanced during publication CI to 957876a (material dashboard/session-facing
changes). Integrate it, review overlapping UI/stream changes, rebuild and rerun all
three focused connected cases and six CI checks. Publish a separate compact bundle
at docs/evidence/assets/pr77-review-main-957876a; keep the prior 29 and
original 55 sealed artifacts unchanged. Do not carry prior-main acceptance forward.
