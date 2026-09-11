# Day 01 integration handoff

**Date:** Friday, 11 September 2026. **Owner:** Zavtoni Ion, Streaming & Simulator.
**Branch:** feature/event-v1-contract. **Status:** contract draft ready for review;
M2/M3/M5 approval is still pending. These messages are prepared, not sent.

## M3 - detection and features

Read [every field and unit](event-v1-contract.md),
[nine normal/abnormal fixtures](../../contracts/events/v1/examples/README.md), and
[three MVP scenarios](mvp-scenarios.md).

| Type | Event boundary | Relevant future inputs |
| --- | --- | --- |
| CALL | Final call attempt result | Counts, durationSeconds, outcome, OUTBOUND destinationCountry against synthetic home country. |
| SMS | Final logical message delivery result | Counts, deliveryStatus, OUTBOUND destinationCountry. |
| DATA | Completed session | Integer bytesUploaded/bytesDownloaded, durationSeconds; session totals are not exact window throughput. |
| AUTH | Authentication attempt result | success, country, deviceId per subscriber. |
| BILLING | Posted charge | Distinct eventId with same subscriber + transactionRef + amountMinor + currency. |
| NETWORK | Measurement window ending at occurredAt | status, packetLossRatio, nullable latencyMs, throughputMbps, bytesTransferred, association count, node/link and region. |

Deduplicate technical eventId before feature aggregation. Use occurredAt for time
context. Seconds, bytes, MDL minor units, milliseconds, decimal Mbps and ratios
0..1 are explicit in the contract. Treat activeSubscriberCount as exposure, not
confirmed unique impact. Traffic loss needs a comparable baseline and an accounting
boundary. Topology/profile mappings are future integration dependencies.

**Ready-to-send message:**

> EventV1 draft is ready on feature/event-v1-contract. See docs/streaming/event-v1-contract.md and contracts/events/v1/examples/ for six types, field meanings and units. The three scenario specifications are in docs/streaming/mvp-scenarios.md. Use occurredAt for event time and exclude scenarioRunId and all scenario metadata from features, rules and risk scores. Please review the proposed feature inputs and baseline/topology dependencies.

## M5 - Kafka and infrastructure

See [Kafka requirements](kafka-contract.md). Topic is `telecom.events.v1`; key is
`entityType:entityId`; key encoding is UTF-8 string and value is UTF-8 JSON. The
expected future producer is `event-generator`. Day 03 needs configurable Kafka
bootstrap servers. Confirm local topic partition/replication settings, retention,
broker advertised addresses and ownership of provisioning. No broker setup is
included in Day 01.

**Ready-to-send message:**

> EventV1 draft uses topic telecom.events.v1, key entityType:entityId and UTF-8 JSON values. Expected producer: event-generator. See docs/streaming/kafka-contract.md. Day 03 needs configurable bootstrap servers via KAFKA_BOOTSTRAP_SERVERS mapped to spring.kafka.bootstrap-servers. Please confirm local topic settings and host/container broker addresses before producer integration.

## M2 - backend and integration

Review technical eventId uniqueness/re-delivery, optional traceability-only
scenarioRunId, subscriber IDs and the node/link entity patterns. Subscriber
entityId replaces a redundant subscriberId field. NETWORK requires entityId to
equal the measured payload nodeId (networkNodeId) or linkId, as appropriate.
Those cross-field equalities need application validation later.

Deduplication by eventId and duplicate billing by business identity solve different
problems. A repeated transactionRef is valid raw input and must survive ingestion
when eventIds differ. A Kafka offset is not an eventId, entityId or incident ID.
No incident schema or backend implementation is proposed in this milestone.

**Ready-to-send message:**

> Please review EventV1 identifiers on feature/event-v1-contract: eventId is a unique technical UUID retained on re-delivery; scenarioRunId is optional traceability only; entityType/entityId identify the subscriber or measured node/link. Distinct eventIds with the same subscriber/transactionRef/amountMinor/currency must remain available for duplicate-charge detection. See docs/streaming/event-v1-contract.md and the billing fixture pair.

## Decisions requiring team review

The existing repository had no architecture or naming constraints. This draft
proposes terminal service-event boundaries, SUB- plus 6-12 digits, MDL-only charges,
outbound international ratios using a later home-country profile, nullable probe
latency and association counts retained during outage. Review these before runtime
implementation; today's work does not claim these decisions are already approved.

## Next scheduled task

**Monday, 14 September 2026 - Day 02: Build the generator skeleton.**

No Day 02 code is included. Seeded randomness, a replaceable Clock and synthetic
profiles are design requirements for later work. Kafka producer integration is
later work, with configurable bootstrap servers required on Day 03.
