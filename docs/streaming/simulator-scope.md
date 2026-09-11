# Streaming & Simulator scope

Owner: **Zavtoni Ion**. Milestone: Day 01, Friday, 11 September 2026.
Status: contract draft waiting for M2/M3/M5 review.

## Boundaries

| Component / role | Responsibility |
| --- | --- |
| Streaming & Simulator / Zavtoni Ion | Event contracts; later: normal traffic, scenario injection, configurable rates, Kafka publishing, tests and docs. |
| M3 / detection and features | Aggregation, baselines and detection from event measurements. |
| M5 / Kafka and infrastructure | Topic setup, Kafka configuration and producer integration. |
| M2 / backend and integration | Review event/entity IDs for backend and incident integration. |
| Other platform components | Persistence, impact analysis and the separate frontend. Owners still need team confirmation. |

Conceptual flow:

```text
Synthetic simulator -> Kafka -> Processing -> Detection -> Impact -> Dashboard
```

CALL, SMS, DATA and NETWORK are the main telecom domains. AUTH remains in the
existing contract. All records and identifiers are synthetic.

## Day 01 deliverable

Day 01 defines EventV1, five payloads, entity keys, UTC timestamps, units, examples
and three scenarios. It also records questions for M2/M3/M5. The generator, Kafka
clients, database, detection, ML and frontend are planned for later work.

## Assumptions for later work

- Normal traffic follows plausible subscriber profiles and time-dependent load.
- Keep normal generation separate from controlled scenario injection.
- Use a fixed seed, replaceable Clock and synthetic infrastructure profiles so
  scenarios can be repeated. New runs still need new event IDs.
- Link outage analysis needs the node/link, region, measurements and subscriber
  association count. Association shows exposure, not confirmed impact.
- Lost traffic requires a comparable historical baseline and a clear accounting
  boundary; it cannot be calculated from one observation.
- `occurredAt` supports hour, weekday and week-to-week comparisons. Thresholds
  belong in downstream processing, not EventV1.
- NORMAL, PEAK and STRESS profiles will use configurable rates that fit local hardware.
- The MVP uses one raw topic. Cloud deployment is outside the current scope.
