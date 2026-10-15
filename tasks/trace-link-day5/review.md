# Day 5 implementation review

Reviewed against the accepted plan: correctness, readability, boundaries,
integrity and resource use. Production rules, models, migrations and API behavior
remain unchanged. Truth is external; the Java harness calls the canonical
adapter/rules/lifecycle; unsupported model scopes remain explicit. Threshold
selection uses validation only. The normal holdout preserves its original hashes.

Resolved during verification: initial missing evaluator; sandbox-only execution
restrictions; new backlog test measured before the actual enqueue boundary.
Retain all failing invocations. No fixture/seed/policy changes to improve scores.

Limitations: sparse synthetic study, paired groups sharing seeds, no ML episode
or cause policy, component cause oracle distinct from held-out validation,
STATIC detector baseline distinct from candidate-fitted baselines. MOBILE_DATA
has no direction, so its opposite-direction-labelled group is only a same-scope
normal control. Independent external reproduction and shared release acceptance
must not be inferred from automated checks.

## Platform correction review, 15 October 2026

The colleague reproduced predictions, thresholds and results from the archived
Java export, then requested changes because Linux ran a Windows byte-rebuild
assertion. That comparison is partial evidence, not a fresh Java/Docker/reference
pack reproduction or formal approval. The strict assertion is now an explicit
Windows gate; ordinary discovery retains all held-out isolation assertions.
The verifier and CI preserve failure propagation and mandatory Windows → Linux
→ processor → Day 5 dependencies. New regressions exercise both scopes, corrupt
bytes, and missing prerequisites. No artifact, policy, study or threshold is tuned.
Human acceptance and formal merge approval remain pending.
