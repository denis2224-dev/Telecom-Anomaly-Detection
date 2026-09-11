# Streaming & Simulator scope

Owner: **Zavtoni Ion**. Milestone: Day 01, Friday, 11 September 2026.
Status: proposed contract for M2/M3/M5 review; no team sign-off is implied.

## Boundaries

| Component / role | Responsibility |
| --- | --- |
| Streaming & Simulator / Zavtoni Ion | Synthetic event contracts, later normal generators and scenario injection, configurable rates, Kafka publishing, simulator tests and documentation. |
| M3 / detection and features | Later aggregation, contextual baselines and detection from measurements. |
| M5 / Kafka and infrastructure | Topic provisioning and agreed infrastructure configuration; coordinate producer integration. |
| M2 / backend and integration | Review entity and event identities for later backend/incident integration. |
| Other platform components | Persistence, impact analysis and a separate frontend; individual owners require team confirmation. |

Conceptual flow:

```text
Synthetic simulator -> Kafka -> Processing -> Detection -> Impact -> Dashboard
```

CALL, SMS, DATA and NETWORK are the core telecom domains. AUTH and BILLING
support authentication and duplicate-charge demonstrations. All profiles,
identifiers, activity and geographic associations are invented test data.

## Day 01 deliverable

Agree the EventV1 envelope, six payload shapes, entity keys, UTC conventions,
measurement units, normal/abnormal examples and three deterministic scenario
specifications. Record integration questions for M2/M3/M5. No generator,
producer, consumer, database, detection, ML or frontend is implemented today.

## Domain assumptions for later work

- Normal traffic follows plausible subscriber profiles and time-dependent load.
- Keep normal generation separate from controlled scenario injection.
- A seeded random source, replaceable Clock and synthetic infrastructure profiles
  will make later scenarios reproducible. New runs still need distinct event IDs.
- Link outage analysis needs infrastructure identity, region, measurements and
  subscriber association counts. Exposure is not the same as confirmed impact.
- Observed traffic alone cannot prove how much traffic was lost. M3 will need a
  comparable historical baseline and explicit accounting boundaries.
- The event timestamp preserves context for hour/day/night and week-to-week
  analysis. Thresholds are downstream policy, not fields in this contract.
- Later NORMAL/PEAK/STRESS profiles will use configurable rates appropriate to
  local hardware. Producer metrics and performance measurements are later work.
- Use one raw topic for the MVP. Cloud deployment and extra services are not
  prerequisites for a working student project.
