# EventV1 contract draft

Day 01 - 11 September 2026. Owner: Zavtoni Ion, Streaming & Simulator.
Status: **draft waiting for team review**.

## Event boundaries

One record represents a completed CALL attempt, final SMS delivery result,
completed DATA session, AUTH attempt or NETWORK measurement window. Failed service
attempts also produce records. Start events are not emitted, so the same operation
is not counted twice.

CALL, SMS, DATA and AUTH use the synthetic subscriber as their key.
NETWORK uses the measured node or link. One NETWORK record holds an aggregate
measurement for one entity and window. Re-delivery keeps the same `eventId`.

## Common envelope

All fields except `scenarioRunId` are required. The full schema rejects unknown
fields to catch typos and accidental detection labels.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| eventId | string | Yes | Lowercase UUID v4 | Technical ID used for deduplication. Keep it on retry; use a new ID for a new event. |
| schemaVersion | string | Yes | `1.0` | Version of the envelope and payload contract. |
| eventType | string | Yes | CALL, SMS, DATA, AUTH, NETWORK | Selects the payload schema. |
| occurredAt | string | Yes | ISO-8601 UTC, uppercase T and Z; seconds or 1-3 fractional digits | Event time, not Kafka ingestion/processing time. Example: `2026-09-11T08:15:30Z`. |
| entityType | string | Yes | SUBSCRIBER, NETWORK_NODE, NETWORK_LINK | Type of entity used for the Kafka key. |
| entityId | string | Yes | Synthetic ID, up to 64 ASCII characters | Stable ID within `entityType`. |
| scenarioRunId | string | No | Lowercase UUID v4; omit when absent | Used only to trace a simulator run. Never use it for detection. |
| payload | object | Yes | Schema selected by `eventType` | Type-specific measurements. |

Subscriber events use `entityId` as the subscriber ID, so they do not repeat it in
`subscriberId`. The only planned producer is `event-generator`, so v1.0 does not
need a `source` field.

Fields such as `isAnomaly`, `expectedDetection`, `scenarioName` and `fraud` do not
belong in events. Scenario labels stay in test filenames and documentation.
`scenarioRunId` may appear on both control and injected events and is never a
detection input.

## Entity identity and Kafka key

Primary raw topic: **`telecom.events.v1`**.

Record key: **`entityType:entityId`**, encoded as UTF-8. The value is one UTF-8 JSON
object, without arrays, double encoding or Java-specific wrappers.

| Event types | entityType | entityId pattern | Example key |
| --- | --- | --- | --- |
| CALL, SMS, DATA, AUTH | SUBSCRIBER | `SUB-` plus 6-12 digits | `SUBSCRIBER:SUB-000001` |
| NETWORK, node measurement | NETWORK_NODE | `NODE-` plus uppercase alphanumeric segments separated by hyphens | `NETWORK_NODE:NODE-CHI-001` |
| NETWORK, link measurement | NETWORK_LINK | `LINK-` plus uppercase alphanumeric segments separated by hyphens | `NETWORK_LINK:LINK-CHI-001` |

Node and link IDs are limited to 64 characters and cannot contain whitespace.
Treat IDs as opaque values; use the payload's `region` instead of parsing an ID.
Using `eventId` or `scenarioRunId` as the key would break per-entity grouping.

With the same partitioner and partition count, records with the same key stay in
one partition. Kafka orders a partition, but this is not global or event-time
order. M5 must coordinate partition-count changes because keys may move.

## Time and units

`occurredAt` uses UTC with `Z`, not local time or `+00:00`. It supports seconds and
up to three fractional digits. Validators must also check that the date is real.

CALL: attempt end. SMS: final delivery/failure result. DATA: session end. AUTH:
attempt result. NETWORK: exclusive window end;
its window is `[occurredAt - sampleWindowSeconds, occurredAt)`.

Downstream windows use event time. Hour, day and week are first derived in UTC.
A later business-time baseline may use `Europe/Chisinau` if it also handles daylight
saving. Week-to-week comparisons should use the same entity, window, weekday and
time of day. Thresholds and window calculations are not part of Day 01.

| Measurement | Raw representation | Convention |
| --- | --- | --- |
| Durations | Nonnegative integer seconds | NETWORK sample windows must be positive. |
| Data volume | Nonnegative integer bytes | Decimal GB = bytes / 1,000,000,000; GiB is a different unit. |
| Latency | Nonnegative number in milliseconds, or null | Null means no successful probe; zero is a real measurement. |
| Throughput | Nonnegative number in Mbps | Decimal megabits/second: 1 Mbps = 1,000,000 bits/second. |
| Packet loss | Number from 0 through 1 | 0.10 means 10%; not the number 10. |
| Counts | Nonnegative integers | Subscriber association counts, not derived impacted-customer labels. |
| Countries | Uppercase ISO 3166-1 alpha-2 | Schema checks two-letter shape; the producer must use assigned codes. |

Integer measurements stop at 9,007,199,254,740,991 (2^53 - 1), which JavaScript
can represent exactly. Producers should write integer fields as integer JSON tokens.

## CALL payload

Schema: [call.schema.json](../../contracts/events/v1/call.schema.json).
Emit one record when each call attempt ends, including failed attempts.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| callId | string | Yes | `CALL-` plus uppercase alphanumeric/hyphen segments, max 64 | Business ID for the synthetic call. It is separate from `eventId`. |
| direction | string | Yes | INBOUND or OUTBOUND | Relative to the subscriber in entityId. |
| destinationCountry | string | Yes | ISO 3166-1 alpha-2 | Country of the called endpoint. For INBOUND calls, this can be the subscriber's country. |
| durationSeconds | integer | Yes | Seconds, >= 0 | Connected talk duration rounded down; FAILED requires 0, short connected calls may also round to 0. |
| outcome | string | Yes | COMPLETED, DROPPED, FAILED | Normal hang-up, unexpected disconnect after connection, or failure to connect. |
| roaming | boolean | Yes | true / false | Whether the subscriber is on a visited network. This is separate from call destination. |
| networkNodeId | string | Yes | Synthetic `NODE-...`, max 64 | Serving node at attempt end. |
| towerId | string | No | Synthetic `TOWER-...`, max 64 | Serving tower at attempt end; omit if unavailable. |

Planned features are `calls_count`, `average_call_duration`, `dropped_call_ratio`,
`international_call_ratio` and `countries_seen`. Deduplicate by `eventId` first.
Average duration uses COMPLETED and DROPPED calls. International ratios use OUTBOUND
destinations against the subscriber's synthetic home country; fixtures assume MD.
M3 still needs to define how empty ratios are handled.

## SMS payload

Schema: [sms.schema.json](../../contracts/events/v1/sms.schema.json).
Emit one final result per logical message, not one record per segment or retry.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| messageId | string | Yes | Synthetic `SMS-...`, max 64 | Identity of the logical message. |
| direction | string | Yes | INBOUND or OUTBOUND | Relative to the subscriber in entityId. |
| destinationCountry | string | Yes | ISO 3166-1 alpha-2 | Receiving endpoint's country. |
| deliveryStatus | string | Yes | DELIVERED or FAILED | Terminal delivery result. |
| networkNodeId | string | Yes | Synthetic `NODE-...`, max 64 | Serving node at terminal result time. |

Planned features are `sms_count`, `failed_sms_count` and
`international_sms_ratio`. The ratio uses OUTBOUND messages and the subscriber's
synthetic home country, like CALL.

## DATA payload

Schema: [data.schema.json](../../contracts/events/v1/data.schema.json).
Emit one completed session record. The counters cover that session only. The MVP
keeps a session on one node; handovers need a later contract change.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| sessionId | string | Yes | Synthetic `SESSION-...`, max 64 | Business identity of the session. |
| networkNodeId | string | Yes | Synthetic `NODE-...`, max 64 | Node serving the session. |
| bytesUploaded | integer | Yes | Bytes, >= 0 | Successfully transferred user data from subscriber to network. |
| bytesDownloaded | integer | Yes | Bytes, >= 0 | Successfully transferred user data from network to subscriber. |
| durationSeconds | integer | Yes | Seconds, >= 0 | Elapsed session duration rounded down; zero is allowed for subsecond sessions. |

Byte counters exclude retransmissions and protocol overhead. Zero-byte sessions are
valid. Volume features can sum uploaded and downloaded bytes after deduplication.
A completed session does not show when its bytes moved, so assigning its total to
the end-time window is only an approximation. Use NETWORK counters for link traffic
comparisons. DATA and NETWORK may measure the same traffic and must not be summed.

## AUTH payload

Schema: [auth.schema.json](../../contracts/events/v1/auth.schema.json).
Emit one result for an identified synthetic subscriber. Unknown-account attempts
are outside v1.0; do not group them under a fake shared subscriber ID.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| authenticationId | string | Yes | Synthetic `AUTH-...`, max 64 | Business identity of one attempt; retries that are new attempts get new IDs. |
| success | boolean | Yes | true / false | Authentication result. |
| country | string | Yes | ISO 3166-1 alpha-2 | Country of the synthetic serving network at attempt time. |
| deviceId | string | Yes | Synthetic `DEVICE-...`, max 64 | Stable synthetic device identity; no real IMEI or credentials. |
| networkNodeId | string | Yes | Synthetic `NODE-...`, max 64 | Node handling the attempt. |

Planned features are `failed_auth_count`, `countries_seen` during authentication
and device changes per subscriber. Use `occurredAt` and allow for late records.

## NETWORK payload

Schema: [network.schema.json](../../contracts/events/v1/network.schema.json).
One record measures either a node or a link. `status` is sampled at window end.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| networkNodeId | string | Yes | Synthetic `NODE-...`, max 64 | Measured node, or reporting endpoint for a link measurement. |
| linkId | string | For NETWORK_LINK only | Synthetic `LINK-...`, max 64 | Measured link. Must be absent on node-level measurements. |
| region | string | Yes | Synthetic `REGION-...`, max 64 | Operational area of the measured entity. |
| sampleWindowSeconds | integer | Yes | Seconds, > 0 | Length of `[occurredAt - sampleWindowSeconds, occurredAt)`. |
| status | string | Yes | UP, DEGRADED, DOWN | Network state at window end, not a detection result. |
| packetLossRatio | number | Yes | Ratio 0..1 | Lost / attempted probes in the window. MVP always attempts probes, including while DOWN. |
| latencyMs | number or null | Yes | Milliseconds, >= 0 when present | Mean round-trip time of successful probes; null when all probes fail. |
| throughputMbps | number | Yes | Decimal Mbps, >= 0 | Window-average successful user-data transfer rate across this boundary, both directions. |
| bytesTransferred | integer | Yes | Bytes, >= 0 | Successful user data crossing this boundary in this window, both directions, excluding retry duplicates/overhead. Not cumulative. |
| activeSubscriberCount | integer | Yes | Count, >= 0 | Distinct subscribers served by the entity at window end. Associations may remain while DOWN. |

For NETWORK_NODE, `entityId` must match `payload.networkNodeId`. For NETWORK_LINK,
it must match `payload.linkId`. JSON Schema checks presence and ID formats, while
the producer must check these equalities. A link's reporting node is one endpoint,
not a complete topology map.

Keep the counters consistent:
`throughputMbps = bytesTransferred * 8 / (sampleWindowSeconds * 1,000,000)`, rounded
to at most six decimals. `packetLossRatio` measures probe loss, not lost bytes.
When all probes are lost, `latencyMs` is null; otherwise it contains a measurement.
These rules need application validation because JSON Schema does not do the math.

If a link fails partway through a window, a DOWN record may still contain bytes or
successful probes from earlier in that window. The full-window outage fixture uses
zero bytes, zero throughput, full probe loss and null latency.

`activeSubscriberCount` measures association and possible exposure, not confirmed
impact. The planned link-cut scenario keeps associations during the outage. Counts
can overlap across links, nodes and time, so summing them would overcount customers.
Exact impact needs topology, subscriber/service mappings, rerouting information and
service events. Those inputs are planned for later work.

`bytesTransferred` is observed traffic, not lost traffic. A loss estimate
needs an expected baseline for the same window and entity, minus the observed
volume, followed by conversion to decimal GB. Node and link counters can cover the
same traffic. The payload does not split loss by service. Derived values such
as `impactedSubscribers` or `lostGB` do not belong in raw measurements.

## Compatibility and enforcement

The schemas use JSON Schema Draft 2020-12. `schemaVersion: 1.0` is the event contract
version, while the topic suffix `v1` is its major version. Because the schemas reject
unknown fields, even a new optional field needs a coordinated contract update.
Breaking changes require a new major version and topic migration.

The [event.schema.json](../../contracts/events/v1/event.schema.json) validates
the full record and selects one of the six payload schemas. Type-specific files
validate payload objects only. NETWORK requires `linkId` for link records and
rejects it for node records.

The `https://example.invalid/telecom/events/v1/` IDs identify schemas but are not
real endpoints. Register all seven local schemas so references resolve offline.
Schema validation covers structure, ranges and formats. The producer and integration
tests must check global ID uniqueness, ordering, valid ISO code membership,
cross-field equality, historical consistency and synthetic-data sources.

## References

- [JSON Schema object constraints](https://json-schema.org/understanding-json-schema/reference/object)
- [JSON Schema string formats](https://json-schema.org/understanding-json-schema/reference/string)
- [Kafka topics, keys and partitions](https://kafka.apache.org/41/getting-started/introduction/)
