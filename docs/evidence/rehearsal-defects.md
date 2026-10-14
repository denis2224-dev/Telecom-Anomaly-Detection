# Backend rehearsal defects

Observed 14 October 2026, Europe/Chisinau. The current V009 migration is preserved. These results are from disposable databases, not deployment or real-browser acceptance.

## Permanent scenario rejection

Reproduction: run RehearsalRegressionTest against foundation 8939625. Before repair, expected HTTP 400 was HTTP 503 (one failure, zero errors). After repair, the regression passes and verifies durable FAILED state, stable run ID on identical retry, and 409 for a changed request body.

Repair: classify generator HTTP 400 as a terminal invalid command. Stopping telemetry remains independent of technical recovery.

## PostgreSQL delivery timestamps

Reproduction: PushDeliveryStoreIT against PostgreSQL 16.4. An untyped timestamp plus interval was inferred as interval; an untyped CASE result was inferred as text. Before repair, lease and completion SQL failed. After explicit timestamptz casts, both real database lease/recovery/exhaustion tests pass. The compatibility claim repository receives the same cast.

## Web Push cryptographic provider

The standalone full Maven compile exposed a missing direct Bouncy Castle provider dependency in the guide. Declare bcprov-jdk18on 1.81 explicitly and exclude bcprov-jdk15on from Web Push. Subsequent full backend verification passes. This is build-repair evidence, not a behavioral failing-before test.

## Integration migration collision

Foundation PR #103 and current main both use V009 and incompatible trace_domain_detection tables. The user chose to preserve the current migration and prepare stacked draft PRs. V011 reserves the additive scenario-type constraint. No consumed migration or existing database was changed. Main integration and external owner acceptance remain pending.
