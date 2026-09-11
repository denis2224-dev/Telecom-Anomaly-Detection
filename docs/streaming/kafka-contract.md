# Kafka integration contract

Day 01 requirements for M5 and the later simulator producer. This document does
not provision a broker/topic or implement a producer/consumer.

| Item | Agreement / requirement |
| --- | --- |
| Primary raw topic | `telecom.events.v1` |
| Record key | `entityType:entityId`, required, case-sensitive UTF-8 string |
| Record value | One UTF-8 JSON EventV1 object validated against the local schema bundle |
| Expected producer | `event-generator`, owned by Streaming & Simulator |
| Event-time field | `occurredAt`, UTC ISO-8601 with Z |
| Payload selection | `eventType`: CALL, SMS, DATA, AUTH, BILLING, NETWORK |
| Contract version | `schemaVersion`: `1.0` |
| Day 03 connection requirement | Configurable `spring.kafka.bootstrap-servers`, supplied through `KAFKA_BOOTSTRAP_SERVERS` in the later Spring configuration |

## Topic and key choice

Use one topic for this MVP because the six event types share an envelope and can
be routed using eventType. Do not create one topic per subscriber or scenario.
Separate future topics only when retention, ownership or consumption requirements
justify them. Avro, Protobuf and Schema Registry are not required for Day 01.

| Records | Key example | Reason |
| --- | --- | --- |
| Subscriber activity across all five service types | `SUBSCRIBER:SUB-000001` | Co-locates activity for that subscriber, including repeated billing references. |
| A node measurement | `NETWORK_NODE:NODE-CHI-001` | Groups node observations over time. |
| A link measurement | `NETWORK_LINK:LINK-CHI-001` | Groups that link's outage and recovery observations. |

The payload's serving node does not replace a subscriber event's key. A link's
reporting node does not replace its link key. Cross-entity joins (subscriber to
node/link) require downstream topology/integration; they are not achieved by
pretending Kafka globally orders these keys.

## Terms to explain during review

| Term | Meaning in this project |
| --- | --- |
| Producer | Later event-generator client that serializes and sends EventV1 records. |
| Consumer | Later client reading records to process/detect activity. |
| Topic | Named stream telecom.events.v1, stored as one or more partitions. |
| Partition | Ordered append-only log within the topic; ordering is local to it. |
| Message key | Entity grouping input to the partitioner; not a uniqueness constraint. |
| Consumer group | Consumers cooperate to read partitions. Independent applications needing all events use different groups. |
| Offset | Position in one partition, scoped to topic and partition; not an event ID or timestamp. |
| Serialization | UTF-8 string key and JSON value, with no language-specific class envelope. |
| Delivery / retry | Sending is asynchronous; the later producer must observe success/failure and retain eventId across retries. |

Stable partition count and partitioner keep the same key in one partition.
Kafka log order is not necessarily occurredAt order. A later consumer must choose
its own late-event and offset-commit behavior. An offset is not a global sequence.

## Configuration handoff, not implementation

- Suggested local starting point: one broker, one partition, replication factor
  one. M5 must confirm this against available hardware. This permits a simple
  ordering demo but offers no broker redundancy; it is not a production sizing claim.
- M5 owns topic creation, retention/storage choices and any partition changes.
  Set retention against local disk budget; no arbitrary performance target today.
- Day 03 must allow a bootstrap address appropriate to the execution environment.
  A process on the host and a container can need different advertised broker
  addresses. No credentials or environment-specific broker address is committed.
- Future Spring producer: string key serialization and JSON value serialization.
  Avoid Java type headers as an integration dependency for Python consumers.
- Future producer should use broker acknowledgements, idempotent delivery and
  bounded retry/delivery-timeout settings compatible with the chosen Kafka client.
  Confirm exact settings with M5 during producer implementation; there is no
  exactly-once claim for the end-to-end platform. eventId remains the application
  deduplication identity, including after restart or intentional replay.
- Later counters should distinguish generated events, acknowledged published
  events and failures. Do not count an asynchronous send call as acknowledged
  publication. Metrics and load profiles are not implemented today.

See the [EventV1 contract](event-v1-contract.md),
[fixtures](../../contracts/events/v1/examples/README.md), and the official
[Kafka introduction](https://kafka.apache.org/41/getting-started/introduction/).
