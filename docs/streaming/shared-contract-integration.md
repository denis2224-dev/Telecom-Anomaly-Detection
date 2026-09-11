# Shared EventV1 integration decisions

Streaming & Simulator owner: Zavtoni Ion. Backend integration reviewer: Moroz Denis.
This integration aligns Day 01 contracts and synthetic evidence for team review.

## Canonical event contract

[event.schema.json](../../contracts/events/v1/event.schema.json) and its five
payload schemas are the producer contract for Kafka and incident evidence.
The envelope uses `eventId`, string `schemaVersion: "1.0"`, `eventType`,
`occurredAt`, `entityType`, `entityId`, optional `scenarioRunId` and `payload`.
Only CALL, SMS, DATA, AUTH and NETWORK are allowed.

Event and run IDs are lowercase UUID v4. Subscriber IDs use `SUB-` plus 6-12
digits. Entity kinds are SUBSCRIBER, NETWORK_NODE and NETWORK_LINK. Timestamps
use UTC `Z`; event time is `occurredAt`. No producer-emission timestamp is part
of this envelope. Run IDs provide traceability only.

CALL evidence uses `durationSeconds`, `destinationCountry`, `callId`, `direction`,
`outcome`, `roaming` and `networkNodeId`. International activity is derived from
OUTBOUND destination country versus the subscriber's synthetic home country.
AUTH retains its existing `country` field for the serving network's country.

## Incident API and fixtures

[incident-api.yaml](../../contracts/openapi/incident-api.yaml) retains OpenAPI 3.0.3.
Its EvidenceSample envelope outline mirrors the canonical producer fields and
links to the producer schema through `x-canonical-schema`. Contract tests compare
the envelope definitions and validate every raw and nested example with the
Draft 2020-12 producer validator and format checking.

[OpenAPI 3.0 Schema Objects](https://spec.openapis.org/oas/v3.0.3.html#schema-object)
use a restricted JSON Schema dialect. The outline intentionally delegates payload
dispatch and entity-dependent constraints to the full producer validator. API-only
validation is insufficient for ingestion; this requirement applies to future
backend validation too. It does not create a separate event representation.

The incident fixture, REST examples and SSE example contain the same completed
OUTBOUND call to RO on NODE-CHI-001. Its synthetic home country is MD. All required
CALL fields are supplied; the existing ten-second duration, incident scores and
reason values are preserved. The fixture is one sample from its own 60-call window,
not the full simulator sequence from the scenario document.

The incident subscriber ID is now `SUB-000001`, so the fixture's detection hash
is recomputed from the existing documented formula. Event/run fixture UUIDs satisfy
the producer format. Incident, audit, request and analyst UUIDs are unchanged.
No stored data is migrated; these files are synthetic contract examples only.

## Scenarios and shared infrastructure

The three [MVP scenarios](mvp-scenarios.md) map to the API's existing scenario routes:

| Scenario | Route type | Incident anomaly type |
| --- | --- | --- |
| High / unusual call activity | account-compromise | ACCOUNT_COMPROMISE |
| Network link cut / outage | network-outage | NETWORK_OUTAGE |
| Mobile data traffic drop | data-traffic-drop | DATA_TRAFFIC_DROP |

These names define contract values only. Simulator request limits, detection
thresholds and traffic-loss calculations still need owner review and later
implementation. Raw DATA/NETWORK measurements contain no derived loss or detection
labels. The existing ML_ANOMALY API value remains a separate downstream category.

The [Kafka contract](kafka-contract.md) follows the existing shared Compose topic,
partition, retention and address settings. EventV1 uses `telecom.events.v1` with
key `entityType:entityId`. Shared backend, frontend and infrastructure ownership
remains in the [owner map](../architecture/owner-map.md).

## Validation and remaining work

Run the [contract validation commands](validation.md) before review. The checks
cover JSON, schemas, API examples, incident evidence and fixture consistency.
Runtime ingestion, generator, detection, identity and browser behavior are future
work. Ion and Denis should review these shared contract changes together before
dependent implementation.
