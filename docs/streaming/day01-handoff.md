# Day 01 integration handoff

**Date:** Friday, 11 September 2026. **Owner:** Zavtoni Ion, Streaming & Simulator.
**Branch:** feature/event-v1-contract. **Status:** ready for M2/M3/M5 review.
The messages below are drafts and have not been sent.

## M3 - detection and features

Review the [field definitions and units](event-v1-contract.md),
[example events](../../contracts/events/v1/examples/README.md) and
[MVP scenarios](mvp-scenarios.md).

| Type | Event boundary | Planned feature inputs |
| --- | --- | --- |
| CALL | Final call attempt result | Counts, durationSeconds, outcome, OUTBOUND destinationCountry against synthetic home country. |
| SMS | Final logical message delivery result | Counts, deliveryStatus, OUTBOUND destinationCountry. |
| DATA | Completed session | Integer bytesUploaded/bytesDownloaded, durationSeconds; session totals are not exact window throughput. |
| AUTH | Authentication attempt result | success, country, deviceId per subscriber. |
| BILLING | Posted charge | Distinct eventId with same subscriber + transactionRef + amountMinor + currency. |
| NETWORK | Measurement window ending at occurredAt | status, packetLossRatio, nullable latencyMs, throughputMbps, bytesTransferred, association count, node/link and region. |

Deduplicate by `eventId` before aggregation and use `occurredAt` for time windows.
The contract defines seconds, bytes, MDL minor units, milliseconds, decimal Mbps
and ratios from 0 to 1. `activeSubscriberCount` is exposure, not confirmed unique
impact. Traffic-loss estimates need a baseline and consistent network boundary.

**Ready-to-send message:**

> EventV1 is ready for review on feature/event-v1-contract. Field definitions and units are in docs/streaming/event-v1-contract.md, examples are under contracts/events/v1/examples/, and scenarios are in docs/streaming/mvp-scenarios.md. Use occurredAt for event time. Keep scenarioRunId and scenario metadata out of features, rules and risk scores. Please check the planned feature inputs and baseline/topology needs.

## M5 - Kafka and infrastructure

See the [Kafka contract](kafka-contract.md). It uses topic `telecom.events.v1`, key
`entityType:entityId` and UTF-8 JSON values. The planned producer is
`event-generator`. Before Day 03, confirm local partition/replication settings,
retention, advertised broker addresses and who creates the topic.

**Ready-to-send message:**

> EventV1 uses topic telecom.events.v1, key entityType:entityId and UTF-8 JSON values. The planned producer is event-generator. Day 03 will map KAFKA_BOOTSTRAP_SERVERS to spring.kafka.bootstrap-servers. Please confirm the local topic settings and host/container broker addresses in docs/streaming/kafka-contract.md.

## M2 - backend and integration

Review `eventId`, optional `scenarioRunId`, subscriber IDs and node/link patterns.
Subscriber records use `entityId` instead of repeating `subscriberId`. NETWORK
requires `entityId` to match the payload's `networkNodeId` or `linkId`; application
validation will check that equality.

`eventId` handles delivery deduplication. Billing duplicates use subscriber,
`transactionRef`, `amountMinor` and `currency` across different event IDs. Ingestion
must keep both charges. Kafka offsets are separate from event, entity and incident
IDs. Day 01 does not define incidents or backend code.

**Ready-to-send message:**

> Please review the EventV1 IDs on feature/event-v1-contract. eventId is a technical UUID kept on re-delivery, scenarioRunId only traces simulator runs, and entityType/entityId select the subscriber or network element. Keep charges with different eventIds when subscriber, transactionRef, amountMinor and currency match. See docs/streaming/event-v1-contract.md and the billing example pair.

## Decisions requiring team review

The repository had no existing naming rules. This draft proposes terminal service
events, `SUB-` plus 6-12 digits, MDL-only charges, outbound international ratios
based on a planned home-country profile, nullable probe latency and subscriber
associations retained during an outage. The team still needs to approve these.

## Next scheduled task

**Monday, 14 September 2026 - Day 02: Build the generator skeleton.**

Day 02 will add seeded randomness, a replaceable Clock and synthetic profiles.
Kafka producer integration and configurable bootstrap servers are planned for Day 03.
