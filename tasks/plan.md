# Sergiu: days 1–4 (15–18 September 2026)

Implement the approved plan against main `93fc17f`, preserving current observation
fields, source ownership, OpenAPI and teammate code. Deliver locally on
`sergiu/days-1-4`; no push or PR.

1. Freeze version-2 policy, feature order and feature/detection schemas.
2. Independently calculate Python VOLTE/SMS features from validated observations;
   share raw/expected fixtures and verify nulls, denominators and nearest-rank p95.
3. Load versioned policy and UTC scope/hour baselines at processor startup;
   permit explicit same-service peer fallback and report missing coverage.
4. Evaluate the stateless voice rule with decimal-safe boundaries and impact;
   provide honest evidence without emitting or persisting episodes.

Verify contracts, Python feature tests, Java baseline/rule tests, existing Maven
tests, packaging and real packaged health probes. Record actual results by day.
Training, independent Java feature parity, episode persistence, SMS rule execution
and live incident delivery remain later assignments. Teammate sign-offs are pending.
