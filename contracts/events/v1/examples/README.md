# EventV1 fixtures

Each JSON file is one complete Kafka value, validated with
[event-v1.schema.json](../event-v1.schema.json), not just a payload. Every ID and
record is synthetic. The readable UUIDs are fixed test constants, not a proposed
runtime UUID generation algorithm. Live distinct events must use unique IDs.

| File | Purpose | Kafka key |
| --- | --- | --- |
| [normal-call.json](normal-call.json) | Completed domestic call; baseline subscriber home country is MD. | SUBSCRIBER:SUB-000001 |
| [normal-sms.json](normal-sms.json) | Delivered domestic message; demonstrates millisecond event time. | SUBSCRIBER:SUB-000001 |
| [normal-data.json](normal-data.json) | Completed session with separate integer upload/download counters. | SUBSCRIBER:SUB-000001 |
| [normal-auth.json](normal-auth.json) | Successful network authentication with a synthetic device. | SUBSCRIBER:SUB-000001 |
| [normal-billing.json](normal-billing.json) | Normal control charge, distinct transactionRef from the repeated pair. | SUBSCRIBER:SUB-000001 |
| [billing-charge-first.json](billing-charge-first.json) | First charge for TXN-000002. | SUBSCRIBER:SUB-000001 |
| [billing-charge-repeat.json](billing-charge-repeat.json) | Second charge: different eventId, same business identity and amount. Meaningful as a pair, not anomalous by itself. | SUBSCRIBER:SUB-000001 |
| [normal-network.json](normal-network.json) | Healthy link window [08:15:00Z, 08:16:00Z), 600,000,000 bytes and 80 Mbps. | NETWORK_LINK:LINK-CHI-001 |
| [network-link-cut.json](network-link-cut.json) | Abnormal full outage window [08:17:00Z, 08:18:00Z), no delivered traffic/probe responses. | NETWORK_LINK:LINK-CHI-001 |

The two network examples are selected snapshots, not a complete time series. The
intermediate degradation, recovery and unaffected controls are specified in the
scenario documentation. A single example does not demonstrate a working detector
or a measured GB-loss estimate.

Labels such as normal/abnormal belong in this README and filenames only. No JSON
value contains a detection label. Normal controls and abnormal examples share
scenarioRunId within their demonstration run. M3 must exclude that optional field
from every feature, rule and risk calculation.

Replaying the exact same fixture preserves eventId and represents technical
re-delivery. To simulate a NEW run later, assign new event and business IDs (except
intentional duplicate billing references within that run), while retaining the
controlled measurement pattern. Files are not emitted in alphabetical order;
a future replay tool must use the intended event-time sequence.
