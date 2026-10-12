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
V014–V016 are implemented; consumer activation remains gated in the
[migration ledger](../../docs/architecture/trace-link-migration-ledger.md).

## Native Trace Link primitive projection

Use [the producer projection runbook](../../docs/runbooks/trace-link-primitive-projection.md)
for the explicit `TRACE_LINK_PROJECTION_MODE` tool. V016 projects only dictionary
`VOLTE / volte.setup.success` from completed immutable native imports, preserves
unknown source quality, and holds delivery intent pending Denis's consumer
agreement. The tool has no Kafka publisher or public API.
