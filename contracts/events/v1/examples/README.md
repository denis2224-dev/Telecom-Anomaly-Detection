# EventV1 fixtures

Each file is a complete Kafka value validated with
[event-v1.schema.json](../event-v1.schema.json). All data is synthetic. The readable
UUIDs are fixed test values; generated events still need unique UUIDs.

| File | Purpose | Kafka key |
| --- | --- | --- |
| [normal-call.json](normal-call.json) | Completed domestic call; baseline subscriber home country is MD. | SUBSCRIBER:SUB-000001 |
| [normal-sms.json](normal-sms.json) | Delivered domestic message; demonstrates millisecond event time. | SUBSCRIBER:SUB-000001 |
| [normal-data.json](normal-data.json) | Completed session with separate integer upload/download counters. | SUBSCRIBER:SUB-000001 |
| [normal-auth.json](normal-auth.json) | Successful network authentication with a synthetic device. | SUBSCRIBER:SUB-000001 |
| [normal-network.json](normal-network.json) | Healthy link window [08:15:00Z, 08:16:00Z), 600,000,000 bytes and 80 Mbps. | NETWORK_LINK:LINK-CHI-001 |
| [network-link-cut.json](network-link-cut.json) | Full outage window [08:17:00Z, 08:18:00Z), with no traffic or probe responses. | NETWORK_LINK:LINK-CHI-001 |

The network files are snapshots. The full degradation, outage, recovery and
control sequence is defined in the scenario document. No detector or GB-loss
calculation has been implemented.

Normal/abnormal labels appear only in filenames and this README. They are not part
of the JSON events. `scenarioRunId` may be shared by control and injected events,
but M3 must exclude it from features, rules and risk scores.

Replaying a fixture with the same `eventId` represents re-delivery. A new run must
use new event and business IDs. A future replay tool must order the files by event
time rather than filename.
