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
