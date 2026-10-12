# Trace Link Day 2 — seasonal baselines and offline policies

Assignment: 11 October 2026. Day 1 base b4af29f; main 9067701;
reviewed integration base 99adf9ed32fc80d4bf798789d258a31e588625b0.
Branch: feat/trace-link-day2-seasonal-policies.

Implement additive offline seasonal baselines, deterministic transport/roaming/LTE
rules, lifecycle and cause controls. Live registration, Kafka, migrations and
incident APIs remain Day 3. Frozen V2 contracts/models remain unchanged.

Coordinator owns shared input/result schemas, registration, fixture index,
Maven resources, integration tests, verification and evidence. Three simultaneous
workers own baselines, rules/lifecycle, and causes respectively. Shared interface
is frozen in interface.md. Tests precede implementation; Maven runs are coordinated.

Checkpoints: integrated Day 1/main and frozen interfaces; focused component
checks; combined trajectories; independent review and full fresh reproduction.
Training/validation/held-out split is 20/5/5 days at 09:00 UTC on September 10,
September 30, October 5 and October 10. All numeric defaults are demonstration
policy, independently versioned from V2. See the assignment for full acceptance.
