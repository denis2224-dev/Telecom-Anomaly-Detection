# Kafka integration contract

Day 01 records the Kafka choices for M5 and the simulator producer. Broker setup
and producer/consumer code are planned for later work.

| Item | Agreement / requirement |
| --- | --- |
| Primary raw topic | `telecom.events.v1` |
| Record key | `entityType:entityId`, required, case-sensitive UTF-8 string |
| Record value | One UTF-8 JSON EventV1 object |
| Expected producer | `event-generator`, owned by Streaming & Simulator |
| Event-time field | `occurredAt`, UTC ISO-8601 with Z |
| Payload selection | `eventType`: CALL, SMS, DATA, AUTH, NETWORK |
| Contract version | `schemaVersion`: `1.0` |
| Day 03 connection | `KAFKA_BOOTSTRAP_SERVERS` configures `spring.kafka.bootstrap-servers` |

## Topic and key choice

The MVP uses one topic because all five types share the EventV1 envelope. Consumers
can route records by `eventType`. Add topics later only if retention, ownership or
consumer needs differ. Day 01 uses JSON without Schema Registry.

| Records | Key example | Reason |
| --- | --- | --- |
| Subscriber activity across all four subscriber event types | `SUBSCRIBER:SUB-000001` | Keeps one subscriber's records together. |
| A node measurement | `NETWORK_NODE:NODE-CHI-001` | Groups node observations over time. |
| A link measurement | `NETWORK_LINK:LINK-CHI-001` | Groups that link's outage and recovery observations. |

Subscriber events keep the subscriber key even when their payload contains a node.
Link measurements keep the link key. Subscriber-to-network joins need topology
data in downstream processing.

## Terms to explain during review

| Term | Meaning in this project |
| --- | --- |
| Producer | The planned event-generator client that sends EventV1 records. |
| Consumer | A client that reads records for processing or detection. |
| Topic | The `telecom.events.v1` stream, stored in one or more partitions. |
| Partition | Ordered append-only log within the topic; ordering is local to it. |
| Message key | Value used by the partitioner to group entity records. It is not unique. |
| Consumer group | Consumers sharing work across partitions. Separate applications use separate groups. |
| Offset | Position in one partition, scoped to topic and partition; not an event ID or timestamp. |
| Serialization | UTF-8 string key and JSON value, without Java-specific wrappers. |
| Delivery / retry | The producer checks asynchronous results and keeps the same eventId on retry. |

With the same partitioner and partition count, a key stays in one partition. Kafka
log order can differ from `occurredAt` order. Each consumer must handle late events
and offsets. Offsets are local to a partition, not a global sequence.

## Planned configuration

- Start locally with one broker, one partition and replication factor one. M5 will
  confirm this. It is enough for the first demo but has no broker redundancy.
- M5 owns topic creation, retention and partition changes. Retention must fit the
  available local disk space.
- Day 03 will make the bootstrap address configurable. Host processes and containers
  may use different advertised broker addresses. Do not commit local credentials.
- The Spring producer will use string keys and JSON values. Avoid Java type headers
  because Python consumers should not depend on Java classes.
- Producer settings should include acknowledgements, idempotence and bounded retry
  time. Confirm the exact values with M5 when the producer is built. `eventId` is
  still needed for application-level deduplication.
- Metrics will count generated, acknowledged and failed events separately. An
  asynchronous send call is not a successful publication until Kafka acknowledges it.

See the [EventV1 contract](event-v1-contract.md),
[fixtures](../../contracts/events/v1/examples/README.md), and the official
[Kafka introduction](https://kafka.apache.org/41/getting-started/introduction/).
