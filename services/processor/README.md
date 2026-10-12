# Processor

The existing VoLTE/SMS path consumes one-minute V2 observations, persists receipt
and window decisions, and publishes through durable outboxes. Its validators,
identities and finalizers remain separate from Trace Link historical source data.

The additive Trace Link job verifies a supplied native five-minute archive and
imports immutable source rows using durable, bounded checkpoints. It is opt-in
through `TRACE_LINK_BOOTSTRAP_MODE=true` and has no Kafka listener or detector.
See the [native import runbook](../../docs/runbooks/trace-link-native-import.md)
for startup, retry, progress queries, provenance limits and integration tests.

The source catalogue is `CONTRACT_ONLY`. The job does not activate inventory,
publish metric projections, or claim authenticated API/UI acceptance. Migrations
V014–V015 are implemented; V016 remains reserved in the
[migration ledger](../../docs/architecture/trace-link-migration-ledger.md).

The separate [native domain producer job](../../docs/runbooks/trace-link-domain-producers.md)
reads completed imports and writes deterministic Transport, Roaming and LTE
mobile-data primitive artifacts. It runs without Kafka or Flyway and creates no
delivery intents. Nullable source evidence and reference approximations remain
explicit; consumer delivery is `PENDING_REVIEW`.

The [Day 5 reliability runbook](../../docs/runbooks/streaming.md#trace-link-producer-reliability)
describes safe native import resume, database outage diagnosis, immutable artifact
replay and retention. Disposable PostgreSQL tests pause the actual database,
reject output while it is unavailable, and compare source rows and artifact bytes
after recovery. Existing retention deletes only eligible legacy receipts and
ACK-marked rejections; native source/checkpoint/provenance tables and held V016
outbox rows have no cleanup lifecycle. Connected new-domain delivery and capacity
remain blocked on consumer and environment approval.
