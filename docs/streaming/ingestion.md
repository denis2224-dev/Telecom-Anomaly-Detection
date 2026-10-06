# Durable observation ingestion

The processor consumes `telecom.observations.v2` in stable group
`telecom-processor-v2`. Keys are exact, case-sensitive UTF-8 `scopeId` strings;
values are raw bytes, so malformed JSON reaches the durable rejection boundary.
The simulator publishes authoritative interval observations through its durable delivery path.

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
mismatches, event ID conflicts, natural-key conflicts and late arrivals are durable rejections.
Database and unexpected infrastructure exceptions propagate; they are never
converted into bad-input rejections. The Kafka error handler retries indefinitely
with one-second backoff, without recovered-record commits or default-budget skips.
An unavailable database therefore holds up that partition until persistence works.

`IngestionService.ingest` is invoked through Spring's transaction proxy at
READ COMMITTED isolation. All application timestamps use an injected `Clock`.
Application receipt time is sampled once at ingestion entry, before validation or
waiting for the shared scope/minute `WindowDecisionLock`.
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

Under the shared window decision lock, an accepted event identity is checked first,
then the natural interval identity, then closure/finalization. An
`INSERT ... ON CONFLICT DO NOTHING` establishes the unique receipt before any
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
never decrease. Business counters are authoritative measurements for each interval;
smaller valid counts in the next minute do not imply malformed input or source-state regression.

## Frozen late-data policy

Windows are `[start, end)`, exactly 60 seconds. `closureInstant` is `windowEnd +
DetectionPolicy.allowedLatenessSec()` (currently 10 seconds). A new otherwise-valid
observation may participate only when its application receipt time is strictly before
closure and its bucket is not finalized. Receipt exactly at closure is late.
An already finalized bucket is closed even if the application clock moves backwards.
Ingestion and both normal/missing-window finalization use `WindowDecisionLock`.

Observable precedence is validation/authority/Kafka key, then accepted identical
event (`DUPLICATE`), conflicting event ID (`EVENT_ID_CONFLICT`), conflicting
`sourceId/scopeId/kind/windowStart` (`NATURAL_KEY_CONFLICT`), then
`LATE_OBSERVATION`, then acceptance. Therefore an exact accepted technical retry
after closure remains a duplicate. Conflicting identities stay identifiable after closure.

Late input is evidence only: it inserts no receipt, bucket, source state, feature or
episode evidence. It cannot increment accepted counts, rewrite emitted KPIs,
re-finalize a window, or alter detections already derived from an immutable feature.
The policy applies to SERVICE, NODE and HEARTBEAT observations.
Database outages remain retryable infrastructure errors: rollback, no ACK,
and no rejection falsely labelling the delivery as malformed/schema-invalid.

## Durable rejection publication

`V005__rejection_delivery.sql` adds the late reason, `published_at`, and a partial
pending index to the existing `rejection_outbox`. Flyway runs as `processing_migrator`;
runtime `processing_app` still has no DDL or Flyway-history editing privileges.

`RejectionPublisher` polls committed pending rows ordered by `(created_at, outbox_id)`.
Its configured batch is 1..1,000 (default 100) and interval defaults to 1,000 ms.
It performs no network operation inside an ingestion/finalizer transaction or while
holding their locks. Each send waits up to 10 seconds for broker acknowledgement;
only then is `published_at` written. A send failure or failed database mark leaves
the row pending. Retries can deliver identical Kafka messages more than once, with
one durable logical rejection per original `(topic, partition, offset)`.
Rejection publication has no claim lease; multiple processor instances can resend the same evidence.

Routing is configured through `KAFKA_V2_INVALID_TOPIC` (default
`telecom.observations.invalid.v2`) and `KAFKA_V2_LATE_TOPIC` (default
`telecom.observations.late.v2`). Only `LATE_OBSERVATION` routes to late; every
invalid/conflict reason routes to invalid. `REJECTION_DELIVERY_ENABLED`,
`REJECTION_DELIVERY_BATCH_SIZE` and `REJECTION_DELIVERY_POLL_INTERVAL` control polling.

No shared public rejection schema exists in `contracts/`. The publisher uses this
minimal internal JSON evidence representation, tested by `LateInputIT`:

| Field | Meaning |
| --- | --- |
| `rejectionId` | Stable original-delivery identity: `delivery:<topic length>:<topic>:<partition>:<offset>`. |
| `reasonCode`, `reasonDetail` | Stable enum and bounded diagnostic (2,048 characters). |
| `eventId`, `payloadHash` | Extractable bounded ID (nullable, 1,024 characters) and complete-input/canonical SHA-256. |
| `kafkaTopic`, `kafkaPartition`, `kafkaOffset`, `kafkaKey` | Original delivery coordinates and bounded nullable key (1,024 characters). |
| `payloadSize`, `payloadTruncated` | Original byte length and whether raw evidence was truncated. |
| `rawPayloadBase64` | At most 65,536 original bytes, base64 encoded; null for null input. Preserves arbitrary bytes. |
| `createdAt` | Immutable rejection timestamp, UTC ISO Instant. |

The publication key is the extractable event ID only when it is a canonical UUID;
otherwise it is `rejectionId`. Content and keys are built exclusively from immutable
row values, so retries preserve them. Consumers should deduplicate by `rejectionId`,
since conflicts or repeated invalid deliveries can share an event ID.

Stanislav can reproduce pending/published invalid/late counts without a metrics subsystem:

```sql
SELECT CASE WHEN reason_code = 'LATE_OBSERVATION' THEN 'late' ELSE 'invalid' END AS category,
       count(*) FILTER (WHERE published_at IS NULL) AS pending,
       count(*) FILTER (WHERE published_at IS NOT NULL) AS published
FROM app.rejection_outbox GROUP BY 1 ORDER BY 1;

SELECT reason_code, count(*) AS total,
       count(*) FILTER (WHERE published_at IS NULL) AS pending,
       count(*) FILTER (WHERE published_at IS NOT NULL) AS published
FROM app.rejection_outbox GROUP BY reason_code ORDER BY reason_code;
```

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

## Finalization handoff

Normal and missing SERVICE windows finalize at closure under the same scope/minute
decision lock. `WindowFinalizer` creates one immutable feature/outbox record;
`VoiceDeliveryService` derives KPI/episode delivery from that finalized feature.
No ML call belongs inside the finalization transaction. Sergiu can rely on the
frozen late policy above; ingestion preserves rule and episode behavior.

## Replay, concurrent finalization, and retention

The listener returns through the real Spring transaction interceptor before it
acknowledges the input consumer offset. A failed write or deferred commit leaves
that offset uncommitted. If the receipt committed but acknowledgement was lost,
redelivery is `DUPLICATE`, including after feature and episode processing. Receipt,
accepted count, source state, complete feature identity, evaluated-window state,
and detection identity stay unchanged. Producer broker acknowledgements are a
separate boundary: a failed `published_at` mark can resend identical KPI,
detection, or rejection records. Deduplicate KPI by `windowId`, detection by
`detectionId`, and rejection by `rejectionId`.

Normal and missing finalizers and ingestion serialize using PostgreSQL transaction
advisory locks for the same scope/window. Controlled replay/finalizer tests observe database
lock waiters and independent committed state, then verify exactly one final
feature agrees with the winning SERVICE/NODE receipts. An observation captured
one microsecond before closure can win admission; new observations at closure or
after it are late and cannot change finalized evidence. Finalizer retries preserve
the full stored payload, original payload hash, metadata, and `sourceEventIds`.

Raw observations default to 24-hour Kafka retention
(`KAFKA_RAW_RETENTION_MS=86400000`). No processor receipt/outbox cleanup job exists
yet: receipts and unpublished `feature_outbox`, `voice_delivery`, and
`rejection_outbox` work remain retained without expiry. A future retention job
must start with a **minimum 48-hour receipt horizon**, longer than configured raw
retention, and must never delete pending output. This is an application invariant,
not a claim that the runtime role cannot manually delete any table: its existing
receipt/rejection permissions are unchanged.

Run `ReplayIT` and `FinalizerRaceIT` explicitly or through the normal processor
suite; both are included in Surefire defaults. Their generated
`target/day13-replay.json` and `target/day13-finalizer-races.json` contain exact
before/after snapshots. The retention test deletes actual raw Kafka records and
advances an injected application clock by 49 hours; it does not claim a physical
49-hour soak or test a cleanup job that has not been implemented.

See [Replay and finalizer evidence](../evidence/2026-09-30-day13-replay-finalizer.md) for commands,
counts, feature/episode identities, and the Denis/Sergiu downstream handoff.

### Detector job and ordered delivery leases

`V006__detection_leases.sql` backfills `detection_job` from saved feature windows
and existing evaluated-window timestamps. Completed windows remain completed;
saved episodes and output payloads remain unchanged. `DetectionWorker` commits a
30-second database-clock claim of the oldest unfinished window for a scope, then
calls ML outside a transaction. Completion compares the token and unexpired lease
and atomically saves episode state, evaluated-window identity, KPI/detection output,
and the job's completion timestamp. An expired worker cannot commit its result.
Failures around statement execution or commit roll back the entire completion.

KPI/detection publication claims one head per `(topic,kafka_key)`. Earlier pending
work, including an active claim, blocks later windows/sequences for that stream.
Broker I/O holds no database transaction; acknowledgement is followed by a fenced
publication mark. Runtime can update delivery bookkeeping, but cannot rewrite the
saved payload, ID, topic, or key. Another scope/stream can progress independently.

Delivery remains at least once. A failed mark can replay the same payload; an
already in-flight Kafka send cannot be revoked by a database lease. Consumers must
still deduplicate identities and reconcile sequence gaps. Rejection publication
uses the separate existing publisher and has no new lease. There is no cleanup job.
`DetectionReplayIT` and `DetectionMigrationTest` verify these boundaries with real
PostgreSQL runtime permissions. See the [Sergiu handoff](../evidence/2026-10-01-sergiu-days-13-14.md).
