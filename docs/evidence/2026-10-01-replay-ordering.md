# Acceptance evidence: replay ordering

- Planned date: 1 October 2026; execution: 2 October 2026, Europe/Chisinau.
- Branch: `fix/incident-replay-ordering`, based on `57fc003` after regular merges of security PR #40 and runtime PR #41.
- Implementation: **PASS** for automated backend acceptance. Release failure-drill acceptance: **PARTIAL**.
- Java 21.0.12.1, Spring Boot 4.0.8, PostgreSQL 16.4 Testcontainers, local Docker; no schema migration.

## Verification

`./services/incident-service/mvnw -f services/incident-service/pom.xml -o -DargLine=-javaagent:<local Mockito 5.23.0 jar> verify` passed: **111 Surefire + 22 Failsafe tests, zero failures/errors/skips**. Counts are from this execution log, not stale reports from previous branches. Contract validation and `git diff --check` passed.

The eleven ReplayOrderingIT cases use independent committed PostgreSQL transactions and the runtime database role. They verify contiguous gap draining, immutable replay, rollback before commit, failed acknowledgement after commit, concurrent OPEN, retained opening projection, ingestion/reconciliation concurrency, conflicting replay, terminal recovery, overlapping windows and changed episode anchors. The acknowledgement failure is a simulation around a real committed database boundary, not a process-kill/Kafka-offset drill.

Episode ingestion and reconciliation now share a PostgreSQL transaction advisory lock even before an incident exists. The reconciler processes up to 100 applicable episodes per scan and advances a keyset cursor. Missing events are never invented, immutable evidence is never rewritten, and analyst status is preserved through technical recovery.

## Remaining release acceptance

A disposable-stack termination before commit and after commit/before Kafka acknowledgement with an isolated group still needs recorded offsets and counts. No teammate signoff is invented. Current producer contract and existing processor CI remain compatible; real owner reproduction remains a separate acceptance action.

## Merge decision

The user explicitly requested completing, committing, merging and pushing without squashing. Merge the tested implementation with a regular merge commit after PR CI passes, retaining these release-evidence limits. This supersedes the older preparation-only instruction to leave all work unmerged pending human handoffs.

## CI portability correction

The first PR run passed replay tests but FirstSliceIT could not start `apache/kafka-native:latest`: the native broker segfaulted on the hosted Linux runner. The shared backend test fixture now uses the pinned JVM image `apache/kafka:3.9.1`, matching the local Compose broker, instead of an unversioned native binary. The Kafka tests are retained; none are disabled.
