# Day 4 backend detection handoff

Verified on 21 September 2026 from branch `feat/ordered-episode-evidence`. The
integration changes described here are recorded by the commit that contains this
evidence document.

## Accepted Sergiu output

The incident service consumes the shared, schema-valid fixture
`contracts/fixtures/detections/voice-open-illustrative-v2.json` from
`telecom.detections.v2`. The Kafka record key is the fixture's exact `episodeId`.
Before persistence, the receiver validates the complete `ServiceDetectionV2`
schema and recomputes `correlationKey`, `episodeId`, and `detectionId` from their
canonical hash inputs. It also uses the detector's `firstObservedAt` as the
incident episode start rather than incorrectly substituting the later OPEN window.

The test `SergiuDetectionKafkaTest` starts real Kafka and PostgreSQL containers,
publishes that fixture through Kafka, and waits for the normal application listener.
The first delivery produced:

- one immutable `detection_evidence` row;
- one `incidents` row at latest sequence 1 and technical state `ONGOING`;
- one SYSTEM `incident_audit` row;
- the same probable-cause text carried by Sergiu's fixture.

Publishing the identical keyed record again produced consumer disposition
`DUPLICATE`. A valid sequence-2 UPDATE was then consumed from the same Kafka
partition, proving the replay had been processed without relying on a fixed sleep.
The final state contained two evidence and audit rows, one incident, latest
sequence 2, and `CRITICAL` severity.

## Reproduction

```text
cd services/incident-service
./mvnw -Dtest=SergiuDetectionKafkaTest test
./mvnw test
cd ../..
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python scripts/check-contracts.py
```

Recorded results:

- `SergiuDetectionKafkaTest`: 1 test, zero failures or errors.
- Incident-service suite: 65 tests, zero failures, errors, or skips.
- Shared checker: 12 observation fixtures and 4 detection/policy/baseline payloads passed.

The service suite includes four episode lifecycle tests covering out-of-order
OPEN/UPDATE delivery, exact replay, incomplete or conflicting messages, UNKNOWN,
RECOVERY, immutable analyst status, and the first-breach episode timestamp.

For a manually running backend and Kafka broker, publish a validated fixture with:

```text
PYTHON_BIN=.venv/bin/python ./scripts/publish-detection-fixture
```

The helper validates the JSON Schema before publishing and always uses `episodeId`
as the Kafka key.

To validate without publishing, add `VALIDATE_ONLY=true`.

## Remaining producer handoff

Sergiu's current Day 4 implementation intentionally stops at a stateless voice
evaluation. It does not yet assign `OPEN`, `UPDATE`, `UNKNOWN`, or `RECOVERY`, does
not persist episode sequence, and does not publish Kafka detections. Therefore this
record proves the shared `OPEN` contract and the complete receiver transport, but
not a live detector episode lifecycle.

The next producer handoff must provide one schema-valid episode containing:

1. `OPEN` sequence 1;
2. `UPDATE` sequence 2;
3. an exact replay of sequence 2;
4. `UNKNOWN` sequence 3;
5. `RECOVERY` sequence 4.

It must also replay the order `UPDATE` 2 followed by `OPEN` 1. Every record must be
keyed by `episodeId`, reuse stable IDs on exact retry, use the canonical compact
hash formulas, and publish committed outbox rows in episode sequence order.

Denis's receiver-side service tests prove that `UPDATE` before `OPEN` is retained
without creating a false incident and applied after the gap closes. They also
cover UNKNOWN and RECOVERY state transitions. Sergiu's acceptance of those vectors
and a live producer commit remain pending; no full G1 signoff is claimed here.
