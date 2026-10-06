# SMS ML shadow integration

Source intent: optional `sms-supervised-v1-2` shadow evidence, cutoff 0.55.
Deterministic rules retain all incident decisions. Runtime requests/events have no labels.
Real-network validation: **PENDING_DATA**.

## Checkpoints

- Restoration: archive the five retrained defaults and matching training manifest, verify raw and normalized hashes, restore only verified frozen Git blobs, run unchanged Day 1 assertions.
- Contracts: separate event/topic and protected paginated reads; frozen schemas unchanged.
- Serving: trusted packaged classifier, shared offline/runtime scorer, disabled by default.
- Persistence: independent leased claims, transaction-free HTTP, atomic result/outbox, idempotent consumer and reads.
- Replay: fresh observations, real Kafka/PostgreSQL/HTTP, test-only clock, parity and incident isolation.
- Real-data tooling: separate feature/label inputs, strict joins and coverage, frozen gates.
