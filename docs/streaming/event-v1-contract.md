# EventV1 contract draft

Day 01 - 11 September 2026. Owner: Zavtoni Ion, Streaming & Simulator.
Status: **draft for integration review**, not a claim of team approval.

## Event boundaries

One record represents one completed CALL attempt, one final SMS delivery result,
one completed DATA session, one AUTH attempt, one posted BILLING charge, or one
NETWORK measurement window. Failed service attempts are records too. This avoids
counting the same operation once on start and again on completion.

CALL/SMS/DATA/AUTH/BILLING are keyed by the synthetic subscriber. NETWORK is keyed
by the measured node or link. A network record is an aggregate observation, not a
record for one subscriber. Only one source measurement is emitted per entity and
window; re-delivery preserves its eventId.

## Common envelope

All fields below except scenarioRunId are required. Unknown envelope and payload
fields are rejected by the full schema to catch typos and accidental label leaks.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| eventId | string | Yes | Lowercase canonical UUID v4 | Unique technical event ID. Assign once; retry/replay of that same event preserves it. Never reuse for distinct events. |
| schemaVersion | string | Yes | Exactly `1.0` | Version of this envelope and its payload definitions. |
| eventType | string | Yes | CALL, SMS, DATA, AUTH, BILLING, NETWORK | Chooses exactly one payload schema. |
| occurredAt | string | Yes | ISO-8601 UTC, uppercase T and Z; seconds or 1-3 fractional digits | Event time, not Kafka ingestion/processing time. Example: `2026-09-11T08:15:30Z`. |
| entityType | string | Yes | SUBSCRIBER, NETWORK_NODE, NETWORK_LINK | Kind of entity whose records share a Kafka key. |
| entityId | string | Yes | Synthetic identifier, at most 64 ASCII characters | Identity within entityType; stable across events and comparable historical periods. |
| scenarioRunId | string | No | Lowercase canonical UUID v4; omit when absent | Simulator run correlation only. MUST NOT enter ML features, rule decisions or risk scoring. |
| payload | object | Yes | Schema selected by eventType | Raw type-specific facts, with the units defined below. |

There is no duplicate subscriberId field: for subscriber events, entityId IS the
subscriber identifier. Source is the agreed `event-generator` producer; a source
field is not needed in this initial single-producer contract.

`isAnomaly`, `expectedDetection`, `scenarioName`, `fraud` and similar labels are
not event fields. Filename labels and scenario documentation remain outside Kafka
record values. Later scenario correlation should cover controls and injected
traffic alike; exclude scenarioRunId even when its presence seems predictive.

## Entity identity and Kafka key

Primary raw topic: **`telecom.events.v1`**.

Record key: **`entityType:entityId`**, encoded as a UTF-8 string. Value: one UTF-8
JSON object (not an array, a double-encoded JSON string or a Java class wrapper).

| Event types | entityType | entityId pattern | Example key |
| --- | --- | --- | --- |
| CALL, SMS, DATA, AUTH, BILLING | SUBSCRIBER | `SUB-` plus 6-12 digits | `SUBSCRIBER:SUB-000001` |
| NETWORK, node measurement | NETWORK_NODE | `NODE-` plus uppercase alphanumeric segments separated by hyphens | `NETWORK_NODE:NODE-CHI-001` |
| NETWORK, link measurement | NETWORK_LINK | `LINK-` plus uppercase alphanumeric segments separated by hyphens | `NETWORK_LINK:LINK-CHI-001` |

Node/link IDs have a maximum length of 64. IDs are opaque: do not infer region or
topology by splitting an ID. Region is an explicit payload field. Do not replace
the key with eventId or scenarioRunId, which would break per-entity grouping.

Same-key records go to one partition when the partitioner and partition count
remain stable. Kafka orders records inside a partition; this does not guarantee
global or event-time order. Late events remain possible. Changing partition count
can move a key, so M5 must coordinate that change with consumers.

## Time and units

All occurredAt values use Z, never local timestamps or `+00:00`. Fractions use up
to millisecond precision. Calendar validity requires a validator with date-time
format checking enabled as well as the schema's lexical pattern.

CALL: attempt end. SMS: final delivery/failure result. DATA: session end. AUTH:
attempt result. BILLING: charge posting time. NETWORK: exclusive window end;
its window is `[occurredAt - sampleWindowSeconds, occurredAt)`.

Downstream windows use event time. Derive hour/day/week consistently, initially
UTC. A later business-time baseline may explicitly choose `Europe/Chisinau` and
handle daylight-saving changes. Compare equivalent entity, window, weekday and
time-of-day contexts across weeks; never silently treat ingestion time as event
time. No hourly thresholds or rolling-window calculations are implemented here.

| Measurement | Raw representation | Convention |
| --- | --- | --- |
| Durations | Nonnegative integer seconds | NETWORK sample windows must be positive. |
| Money | Nonnegative integer minor units | Charges only; MDL 12345 = 123.45 MDL. No floating point money. |
| Data volume | Nonnegative integer bytes | Decimal GB = bytes / 1,000,000,000; GiB is a different unit. |
| Latency | Nonnegative number in milliseconds, or null | Null means no successful probe; zero is a real measurement. |
| Throughput | Nonnegative number in Mbps | Decimal megabits/second: 1 Mbps = 1,000,000 bits/second. |
| Packet loss | Number from 0 through 1 | 0.10 means 10%; not the number 10. |
| Counts | Nonnegative integers | Subscriber association counts, not derived impacted-customer labels. |
| Countries | Uppercase ISO 3166-1 alpha-2 | Schema checks two-letter shape; the producer must use assigned codes. |
| Currency | MDL for this MVP | ISO 4217 code; two minor-unit digits. Other currencies require a reviewed extension. |

Integer measurements are bounded by 9,007,199,254,740,991 (2^53 - 1) for exact
JSON interoperability with future JavaScript consumers. Emit integer tokens for
integer fields. JSON Schema treats mathematically integral numbers as integers;
it does not enforce the spelling of a numeric token.

## Compatibility and enforcement

The schema dialect is JSON Schema Draft 2020-12; schemaVersion `1.0` is our event
contract version, not the dialect version. The topic suffix v1 is its major
contract family. A future optional field is not automatically compatible with
today's strict schema: agree a version/consumer rollout before emitting it.
Breaking changes need a new major contract and coordinated topic migration.

The final event-v1.schema.json will validate the complete record and dispatch to
the six payload schemas. Per-type schema files describe payload objects only.
Schema validation can check structure, ranges and formats; it cannot establish
global eventId uniqueness, delivery ordering, real ISO code membership, equality
between two fields, historical consistency or whether data is truly synthetic.
These remain producer/integration responsibilities and fixture checks where useful.

## References

- [JSON Schema object constraints](https://json-schema.org/understanding-json-schema/reference/object)
- [JSON Schema string formats](https://json-schema.org/understanding-json-schema/reference/string)
- [Kafka topics, keys and partitions](https://kafka.apache.org/41/getting-started/introduction/)
