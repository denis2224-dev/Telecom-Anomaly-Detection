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

Node/link IDs have a maximum length of 64. Identifiers cannot contain whitespace,
including trailing newlines; schemas explicitly exclude it to protect keys/joins.
IDs are opaque: do not infer region or topology by splitting an ID. Region is an
explicit payload field. Do not replace
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

## CALL payload

Schema: [call-v1.schema.json](../../contracts/events/v1/call-v1.schema.json).
One record per call attempt, emitted at its end, including attempts that fail.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| callId | string | Yes | `CALL-` plus uppercase alphanumeric/hyphen segments, max 64 | Synthetic business identity of the call, not eventId. |
| direction | string | Yes | INBOUND or OUTBOUND | Relative to the subscriber in entityId. |
| destinationCountry | string | Yes | ISO 3166-1 alpha-2 | Called endpoint's country for both directions; not necessarily the other party's country for INBOUND. |
| durationSeconds | integer | Yes | Seconds, >= 0 | Connected talk duration rounded down; FAILED requires 0, short connected calls may also round to 0. |
| outcome | string | Yes | COMPLETED, DROPPED, FAILED | Normal hang-up, unexpected disconnect after connection, or failure to connect. |
| roaming | boolean | Yes | true / false | Subscriber is on a visited network; does not imply an international call. |
| networkNodeId | string | Yes | Synthetic `NODE-...`, max 64 | Serving node at attempt end. |
| towerId | string | No | Synthetic `TOWER-...`, max 64 | Serving tower at attempt end; omit if unavailable. |

Future features: calls_count (all deduplicated attempts), average_call_duration
(COMPLETED/DROPPED only), dropped_call_ratio (DROPPED / all attempts), and outbound
international_call_ratio (outbound destinations different from the subscriber's
home country / outbound attempts). Home country comes from a later synthetic
subscriber profile; the MVP fixtures assume MD. countries_seen uses outbound
destinations, not an inbound caller location that this draft does not capture.
Define empty-denominator behavior downstream; no feature calculations exist today.

## SMS payload

Schema: [sms-v1.schema.json](../../contracts/events/v1/sms-v1.schema.json).
One record per logical message's final result, not per segment or delivery retry.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| messageId | string | Yes | Synthetic `SMS-...`, max 64 | Identity of the logical message. |
| direction | string | Yes | INBOUND or OUTBOUND | Relative to the subscriber in entityId. |
| destinationCountry | string | Yes | ISO 3166-1 alpha-2 | Receiving endpoint's country. |
| deliveryStatus | string | Yes | DELIVERED or FAILED | Terminal delivery result. |
| networkNodeId | string | Yes | Synthetic `NODE-...`, max 64 | Serving node at terminal result time. |

Future features: sms_count, failed_sms_count, outbound international_sms_ratio.
Use outbound messages and the synthetic subscriber's home country for that ratio,
with the same country/direction interpretation as CALL.

## DATA payload

Schema: [data-v1.schema.json](../../contracts/events/v1/data-v1.schema.json).
One completed session record; no periodic updates or cumulative lifetime counters.
Sessions stay on one node in this MVP. Handovers require a later contract decision.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| sessionId | string | Yes | Synthetic `SESSION-...`, max 64 | Business identity of the session. |
| networkNodeId | string | Yes | Synthetic `NODE-...`, max 64 | Node serving the session. |
| bytesUploaded | integer | Yes | Bytes, >= 0 | Successfully transferred user data from subscriber to network. |
| bytesDownloaded | integer | Yes | Bytes, >= 0 | Successfully transferred user data from network to subscriber. |
| durationSeconds | integer | Yes | Seconds, >= 0 | Elapsed session duration rounded down; zero is allowed for subsecond sessions. |

The byte counters exclude retransmission duplicates and protocol overhead. A
zero-byte session is permitted. Future volume features can sum uploaded and
downloaded bytes after eventId deduplication. Session totals do not reveal exactly
when bytes flowed inside a long session: attributing the full total to the end-time
window is a documented approximation, not precise per-minute network throughput.
NETWORK window counters are the primary input for link traffic comparisons. Never
sum DATA and NETWORK volumes together: they can observe the same traffic.

## AUTH payload

Schema: [auth-v1.schema.json](../../contracts/events/v1/auth-v1.schema.json).
One authentication result for an identified synthetic subscriber. Unknown-account
attempts are outside this initial boundary; never manufacture a shared subscriber
ID for all unknown accounts.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| authenticationId | string | Yes | Synthetic `AUTH-...`, max 64 | Business identity of one attempt; retries that are new attempts get new IDs. |
| success | boolean | Yes | true / false | Authentication result. |
| country | string | Yes | ISO 3166-1 alpha-2 | Country of the synthetic serving network at attempt time. |
| deviceId | string | Yes | Synthetic `DEVICE-...`, max 64 | Stable synthetic device identity; no real IMEI or credentials. |
| networkNodeId | string | Yes | Synthetic `NODE-...`, max 64 | Node handling the attempt. |

Future features: failed_auth_count, authentication countries_seen and device
changes per subscriber. Compare by occurredAt, allowing for late delivery.

## BILLING payload

Schema: [billing-v1.schema.json](../../contracts/events/v1/billing-v1.schema.json).
One posted charge; authorization, cancellation, refund and balance events are
outside v1.0. The MVP supports MDL only to keep the minor-unit convention explicit.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| transactionRef | string | Yes | Synthetic `TXN-...`, max 64 | Business transaction identity scoped to the subscriber; may appear in distinct raw charge events. |
| amountMinor | integer | Yes | MDL minor units, >= 0 | Posted amount, e.g. 12345 = 123.45 MDL; zero charges are allowed. |
| currency | string | Yes | Exactly MDL | ISO 4217 code; 100 minor units per MDL. |

Technical duplicate delivery means the SAME eventId appears again: deduplicate
that event. Possible business duplicate billing means DIFFERENT eventIds with the
same subscriber, transactionRef, amountMinor and currency. Preserve both raw
records so later detection can compare them. Never make transactionRef an alias
for eventId, and never reject a repeated transactionRef at schema validation.

## NETWORK payload

Schema: [network-v1.schema.json](../../contracts/events/v1/network-v1.schema.json).
One measurement for a node OR a link over a window; state is sampled at window end.

| Field | Type | Required? | Unit / format | Meaning |
| --- | --- | --- | --- | --- |
| networkNodeId | string | Yes | Synthetic `NODE-...`, max 64 | Measured node for NETWORK_NODE, or reporting endpoint node for NETWORK_LINK. |
| linkId | string | For NETWORK_LINK only | Synthetic `LINK-...`, max 64 | Measured link. Must be absent on node-level measurements. |
| region | string | Yes | Synthetic `REGION-...`, max 64 | Invented operational area of the measured entity; not parsed from an ID. |
| sampleWindowSeconds | integer | Yes | Seconds, > 0 | Length of `[occurredAt - sampleWindowSeconds, occurredAt)`. |
| status | string | Yes | UP, DEGRADED, DOWN | Infrastructure state at window end; a raw observed state, not an anomaly verdict. |
| packetLossRatio | number | Yes | Ratio 0..1 | Lost / attempted probes in the window. MVP always attempts probes, including while DOWN. |
| latencyMs | number or null | Yes | Milliseconds, >= 0 when present | Mean round-trip time of successful probes; null when all probes fail. |
| throughputMbps | number | Yes | Decimal Mbps, >= 0 | Window-average successful user-data transfer rate across this boundary, both directions. |
| bytesTransferred | integer | Yes | Bytes, >= 0 | Successful user data crossing this boundary in this window, both directions, excluding retry duplicates/overhead. Not cumulative. |
| activeSubscriberCount | integer | Yes | Count, >= 0 | Distinct subscribers associated with this entity at window end, including associations retained while DOWN. |

NETWORK_NODE requires entityId == payload.networkNodeId. NETWORK_LINK requires
entityId == payload.linkId. JSON Schema checks presence and identifier shapes;
cross-field equality is a producer/integration responsibility. A link's reporting
node is one topology endpoint, not the measured entity or a complete topology map.

Use consistent counters: throughputMbps = bytesTransferred * 8 /
(sampleWindowSeconds * 1,000,000), rounded to at most six fractional digits.
packetLossRatio is probe loss, not a byte-loss counter. If packetLossRatio is 1,
latencyMs must be null; if less than 1, latencyMs must be a measurement. These
relationships are documented semantic checks, not schema arithmetic.

DOWN at window end can coexist with some transferred bytes or successful probes
earlier in that window. A full-window outage fixture has zero bytes, zero
throughput, total probe loss and null latency. Do not force all DOWN measurements
to zero when the failure happened partway through the window.

activeSubscriberCount represents current association/exposure, not traffic-active
users or a confirmed impacted-subscriber count. For the later deterministic link
cut, associations are retained during the failure and released only by explicit
profile changes. Counts across links, nodes and time can overlap; do not sum them
to claim unique affected customers. Exact unique impact needs synthetic topology,
subscriber/service associations, rerouting behavior and corroborating service
events. Those profiles are future work, not hidden facts in this draft.

bytesTransferred is observed traffic, not lost traffic. A future loss estimate
needs an expected volume from comparable baseline windows, observed volume and
a consistent accounting boundary, then a documented conversion to decimal GB.
Node and link counters may observe the same data. Per-service loss is not directly
available in this minimal aggregate payload. Never encode derived fields such as
impactedSubscribers or lostGB as if they were raw measurements.

## Compatibility and enforcement

The schema dialect is JSON Schema Draft 2020-12; schemaVersion `1.0` is our event
contract version, not the dialect version. The topic suffix v1 is its major
contract family. A future optional field is not automatically compatible with
today's strict schema: agree a version/consumer rollout before emitting it.
Breaking changes need a new major contract and coordinated topic migration.

The [event-v1.schema.json](../../contracts/events/v1/event-v1.schema.json) validates
the complete record and dispatches to the six payload schemas using oneOf (exactly
one event type must match). Per-type schema files describe payload objects only.
The entity branches constrain the key identity; the NETWORK branch additionally
requires linkId for links and forbids it for node totals.

Schema $id URLs under `https://example.invalid/telecom/events/v1/` are stable
logical identifiers, not published endpoints. Register all seven local schemas
with the validator so relative references resolve offline. Do not fetch those URLs.
Schema validation can check structure, ranges and formats; it cannot establish
global eventId uniqueness, delivery ordering, real ISO code membership, equality
between two fields, historical consistency or whether data is truly synthetic.
These remain producer/integration responsibilities and fixture checks where useful.

## References

- [JSON Schema object constraints](https://json-schema.org/understanding-json-schema/reference/object)
- [JSON Schema string formats](https://json-schema.org/understanding-json-schema/reference/string)
- [Kafka topics, keys and partitions](https://kafka.apache.org/41/getting-started/introduction/)
