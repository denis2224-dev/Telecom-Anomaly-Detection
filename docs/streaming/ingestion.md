# Durable observation ingestion ? Revision 3 Day 04

The processor consumes `telecom.observations.v2` in stable group
`telecom-processor-v2`. Keys are exact, case-sensitive UTF-8 `scopeId` strings;
values are raw bytes, so malformed JSON reaches the durable rejection boundary.
The generator still only creates previews; it does not publish Kafka messages.

## Transaction and delivery contract

```text
Kafka record -> parse / shared schema + semantic + source-authority validation
             -> PostgreSQL transaction
                accepted: receipt + interval bucket + source state
                rejected: rejection outbox
                duplicate: no state change
             -> COMMIT -> service proxy returns -> manual immediate ACK

DB / infrastructure error -> ROLLBACK -> exception -> NO ACK -> Kafka retry
```

`ObservationInput`, `ObservationValidator` and `ScopeRegistry` retain their shared
topology semantics. Typed validator categories avoid parsing exception messages.
Malformed JSON, schema failures, semantic failures, unauthorized sources, key
mismatches, event ID conflicts and natural-key conflicts are durable rejections.
Database and unexpected infrastructure exceptions propagate; they are never
converted into bad-input rejections. The Kafka error handler retries indefinitely
with one-second backoff, without recovered-record commits or default-budget skips.
An unavailable database therefore holds up that partition until persistence works.

`IngestionService.ingest` is invoked through Spring's transaction proxy at
READ COMMITTED isolation. All application timestamps use an injected `Clock`.
No detector, finalizer, ML call or publisher runs in this transaction.

## PostgreSQL state and roles

`V001__observation_state.sql` creates only these ingestion-state tables in
`processing_db.app`:

| Table | Durable identity and state |
| --- | --- |
| `observation_receipt` | UUID primary key; unique `(source_id, scope_id, kind, window_start)`; validated canonical JSONB, SHA-256, window/emission/quality fields and first accepted Kafka coordinates. |
| `interval_bucket` | Primary key `(scope_id, window_start)`; unique accepted count, timestamps, `finalized=false` and nullable `finalized_at`. The upsert locks the future finalizer's row. |
| `source_state` | Primary key `(scope_id, source_id)`; conditional upsert prevents older windows or emissions from moving durable activity backwards. |
| `rejection_outbox` | Identity primary key; unique `(kafka_topic, kafka_partition, kafka_offset)`; reason, extractable event ID, hash, key and bounded raw evidence. |

Indexes support receipt retrieval by scope/window and pending buckets by window
end. The table constraints enforce receipt/natural identity, one-minute windows,
nonnegative Kafka coordinates, allowed categories and bucket finalization consistency.
`processing_migrator` owns the tables and applies Flyway; `processing_app` has
DML/sequence access, no application DDL and no connection access to `incidents_db`
or `keycloak_db`. Runtime cannot alter Flyway history. Provisioning stays in the
repository's non-destructive `scripts/prepare-databases`; never delete volumes to
upgrade an older developer environment.

Runtime configuration uses `PROCESSING_DB_URL`, `PROCESSING_DB_USER` and
`PROCESSING_DB_PASSWORD`. Flyway uses the same URL plus `PROCESSING_MIGRATOR_USER`
and `PROCESSING_MIGRATOR_PASSWORD`. Compose addresses `postgres:5432`; host/IDE
configuration may use `localhost:5432`. Liveness only checks process state.
Readiness also checks PostgreSQL and Kafka; migrations must succeed at startup.

## Idempotency and evidence

An `INSERT ... ON CONFLICT DO NOTHING` establishes the unique receipt before any
bucket increment. Concurrent inserts wait on PostgreSQL's unique constraints.
Only the winner increments a bucket and updates source state. After a conflict,
a fresh READ COMMITTED statement sees the committed winner: matching event ID,
natural key, canonical hash and JSONB content is DUPLICATE. Changed content under
an event ID is EVENT_ID_CONFLICT; a different event at the natural key is
NATURAL_KEY_CONFLICT. Both preserve accepted state and persist rejection evidence.
Failure after insertion, including at COMMIT, rolls back the entire transaction.

SHA-256 hashes UTF-8 canonical JSON: object properties sort recursively, array
order remains meaningful, and no runtime metadata is added. Malformed documents
hash the exact original bytes. Duplicate properties and trailing documents are
rejected as malformed. Parsing caps nesting at 64 levels, strings at 1 MiB and
numeric tokens at 128 characters before recursive canonicalization. A streaming parse preserves a top-level event ID encountered
before a syntax failure when possible; accepted documents use the validated UUID.

Outbox raw evidence stores up to 65,536 original bytes as `bytea`, full byte length
and a truncation flag. A null Kafka value remains null evidence and is schema-invalid.
Hashing covers the complete input, not just the bounded prefix. PostgreSQL text
fields escape NUL; diagnostic text is capped at 2,048 characters, and invalid event
IDs/keys at 1,024 characters. The raw bytes retain their original representation.
For source state, newer windows take precedence; within the same window, emission
time then UUID breaks ties deterministically. Latest emission/updated timestamps
never decrease. Bucket finalization and late-after-finalization policy are deferred.

## Verification

Normal `./mvnw test` (Windows `./mvnw.cmd test`) includes real PostgreSQL 16
Testcontainers provisioned with the actual repository initializer and real Kafka
consumer tests. Coverage includes migration ownership, DML/DDL/isolation, all four
unique keys, exact replay, conflicts, raw rejection evidence, bounded payloads,
out-of-order source state, eight concurrent identical deliveries, natural-key
races, rollback after later writes and at commit, and committed rows visible from
an independent connection inside the ACK callback. A broker test holds a database
failure beyond the default retry budget, then verifies recovery and committed
offsets. HTTP probes verify database and Kafka failures affect readiness only.

Run the Python contract/ML suites, Java package and streaming smoke, then
`./scripts/prepare-databases`, `./scripts/up`, `./scripts/verify`. Export processor
DB variables for host Java/smoke processes. See the [streaming runbook](../runbooks/streaming.md).

## Day 05 handoff (not implemented)

Finalize UTC voice minutes at window end + 10 seconds under the same bucket row
lock. Compute eligible attempts, CSSR and same-window radio/bearer metrics, then
write one immutable feature/outbox record with idempotent finalization and exact
60-second parity fixtures. No ML call belongs inside that transaction. Coordinate
new migration numbers with the detection owner. No Day 05 code is present here.
