# Three MVP simulator scenarios

Day 01 specification only. No scenario generator, feature aggregation, detector,
API endpoint or dynamic threshold is implemented in this milestone.

## Reproducibility and separation

Later implementations will accept a seeded random source (example seed 11092026),
a replaceable Clock and fixed synthetic subscriber/infrastructure profiles. The
timelines below are controlled inputs for repeatable demonstrations, not thresholds
that a production detector must hard-code. Background traffic uses a separate
random stream so changing its rate does not change the injected sequence.

A repeated setup must reproduce relative event times, entities and measurements.
A NEW run assigns fresh UUID v4 eventIds and a new optional scenarioRunId; compare
reproducibility after excluding technical and run-scoped business IDs. Business IDs
are scoped to the new run, except for deliberate repeated transactionRef values
within duplicate billing.
Re-delivering an existing event preserves both its eventId and contents.

Normal controls exist before/during/after injection. If scenarioRunId is present,
it covers normal controls and injected traffic in that run. The names in this
document and fixture filenames are not Kafka fields. Scenario metadata MUST NOT
influence feature extraction, detection rules or risk scoring.

## 1. Account compromise / high call activity

**Target:** SUBSCRIBER:SUB-000001, synthetic home country MD, NODE-CHI-001.

**Control:** SUBSCRIBER:SUB-000002, same home country, independent normal activity.

Input behavior for a future fixed demonstration:

| Period (UTC, 11 September 2026) | Target input | Normal control |
| --- | --- | --- |
| [08:15:00, 08:20:00) | Two completed domestic calls, ending 08:15:30 and 08:18:30; 120 seconds each. | Two completed MD calls ending 08:16:00 and 08:19:00; 30 seconds each. |
| [08:20:00, 08:21:00) | Exactly 60 OUTBOUND completed calls, one ending each second from :00 through :59; one second of connected talk each. | One OUTBOUND completed MD call ending 08:20:30, lasting 30 seconds. |
| [08:21:00, 08:26:00) | Return to two completed MD calls ending 08:23:30 and 08:25:30; 120 seconds each. | Two completed MD calls ending 08:22:00 and 08:25:00; 30 seconds each. |

For the 60-call burst, the destination sequence is 30 RO, 18 DE, 6 JP, then 6 MD.
That is 54/60 international calls (90%) relative to home country MD. JP is unusual
for this synthetic subscriber because it never occurs in the setup history;
no country is inherently anomalous. Use unique callId and eventId values for all
attempts, roaming=false and the same serving node. This deliberately compressed
short-call pattern models scripted account activity, not typical human calling.

**Expected raw events:** CALL records conforming to EventV1; the normal example is
[normal-call.json](../../contracts/events/v1/examples/normal-call.json). Today we
do not materialize 60 nearly identical files or generate the sequence.

**Future feature expectations:** a sharp rise in calls_count, high outbound
international_call_ratio, new outbound destinations and a shorter average call
duration compared with this subscriber's history. M3 will choose context-sensitive
decisions. Raw behavior supports investigation, not proof of account compromise.

## 2. Network link cut / network outage

**Target:** NETWORK_LINK:LINK-CHI-001, reporting node NODE-CHI-001,
REGION-CHI-CENTRAL, 500 associated synthetic subscribers.

Assume a single reporting endpoint, no alternative routing, and stable subscriber
associations during the demonstration. These are scenario setup assumptions;
real topology and service mappings are not inferred from aggregate counts.

Each window lasts 60 seconds. Times below are occurredAt (exclusive UTC window
ends on 11 September 2026); each value is a raw measurement target.

| Window end | status | packetLossRatio | latencyMs | throughputMbps | bytesTransferred | activeSubscriberCount |
| --- | --- | --- | --- | --- | --- | --- |
| 08:16:00Z | UP | 0.001 | 12.5 | 80 | 600000000 | 500 |
| 08:17:00Z | DEGRADED | 0.35 | 180 | 8 | 60000000 | 500 |
| 08:18:00Z | DOWN | 1 | null | 0 | 0 | 500 |
| 08:19:00Z | DOWN | 1 | null | 0 | 0 | 500 |
| 08:20:00Z | UP | 0.001 | 12.5 | 80 | 600000000 | 500 |

**Normal control:** independent LINK-CHI-002 / NODE-CHI-002 in
REGION-CHI-NORTH, 300 associated subscribers. Emit one UP measurement at every
listed window end, each with 0.001 loss, 12.5 ms latency, 40 Mbps and 300000000
bytes. No rerouting or shared failure dependency in this controlled setup.

**Expected raw events:** five target and five control NETWORK measurements. The
healthy and first full-outage snapshots are already provided as
[normal-network.json](../../contracts/events/v1/examples/normal-network.json) and
[network-link-cut.json](../../contracts/events/v1/examples/network-link-cut.json).
Fixtures are selected examples; the complete scenario is not implemented today.

**Future feature expectations:** increased probe loss and latency during
degradation, reduced traffic, then no successful probes/traffic during full outage.
CALL outcomes can later corroborate service degradation through the serving node,
but a correlated dropped-call generator is not required by this initial scenario.

**Future impact analysis:** identify the link, reporting node and region; use the
association snapshot as potential subscriber exposure. Exact impacted subscribers
and services need topology and subscriber/service mappings and observed outcomes.
Estimate traffic lost using comparable expected traffic for the same boundary and
window, then convert bytes to decimal GB. Do not sum overlapping node/link counters,
or multiply packet-loss ratio by bytes to invent a lost-traffic measurement.

The preceding healthy snapshot is a demo reference, not a historical weekly
baseline. Later time-aware normal generation/history must support hour/day/night
and week-to-week comparisons. Raw observations show outage/degradation; proving a
physical cable cut rather than another cause requires additional evidence.

## 3. Duplicate billing

**Target:** SUBSCRIBER:SUB-000001. One intentionally repeated business charge.

| occurredAt (UTC, 11 September 2026) | Fixture | transactionRef | amountMinor | currency |
| --- | --- | --- | --- | --- |
| 08:15:33Z | normal-billing.json | TXN-000001 | 12345 | MDL |
| 08:15:34Z | billing-charge-first.json | TXN-000002 | 12345 | MDL |
| 08:15:36Z | billing-charge-repeat.json | TXN-000002 | 12345 | MDL |

**Expected raw events:** three individually valid BILLING records with distinct
eventIds. The last two share subscriber, transactionRef, amountMinor and currency.
The normal control has the same amount/currency but a different transactionRef;
matching amounts alone must not make it a business duplicate.

**Future feature expectations:** M3 may group distinct charge events by the
business identity above after technical eventId deduplication. This document does
not implement a detector, count window or incident. Re-delivery of the SAME first
charge eventId is a technical duplicate, not a second business charge. Another
subscriber reusing the reference is not the same business identity.

All three concrete examples are in the
[fixture index](../../contracts/events/v1/examples/README.md). Metadata is shared
across controls and repeated charges and cannot be used as a detection shortcut.
