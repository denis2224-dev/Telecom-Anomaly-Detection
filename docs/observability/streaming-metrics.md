# Processor streaming metrics — Revision 3 Day 16

These process-local metrics cover the live Kafka listener and window finalization scheduler. They provide operational evidence for Stanislav's dashboard and alert mapping. They are not durable load accounting, production acceptance, or deployed dashboards/alerts. Day 15 reconciliation remains authoritative for durable accounting.

Actuator/Micrometer already arrive through `streaming-support`; no dependency or migration is added. The processor's existing HTTP exposure stays `health` only, including its Kafka/database readiness behavior. This change adds no NGINX routes, public metrics endpoint, or exporter. A private monitoring backend and any endpoint/export configuration remain a separate deployment handoff. [Spring Boot metric registry documentation](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html).

## Metric contract

Names below are the exact Micrometer names. Backend adapters may render different names or base time units; dashboard queries must use the selected adapter's actual contract.

| Name | Type | Finite labels | Units | Source of truth and update | Empty / unknown behavior | Interpretation and proposed alert |
| --- | --- | --- | --- | --- | --- | --- |
| `telecom.processor.observations` | Counter | `outcome=accepted,duplicate,invalid,late` | outcomes | `ObservationListener`, after `IngestionService` returns through its transaction interceptor; one increment per successful listener invocation | All four counters start at zero; transaction failures increment none | Rates of committed listener outcomes. Investigate sustained invalid/late rate or duplicate spikes against an established traffic baseline. |
| `telecom.processor.finalizer.delay` | Timer with percentile histogram and fixed SLO boundaries | none | duration; use seconds in dashboards | `WindowFinalizationScheduler`, only after proxy return of `FINALIZED`. Delay is actual persisted `feature_outbox.window_end` to the injected clock sampled immediately after successful return | Count/sum start at zero. No successful samples means no meaningful percentile | Completion latency includes the configured +10 second lateness, scheduler/lock/transaction delay. Propose p95 above lateness plus the agreed processing budget. |
| `telecom.processor.finalizer.delay.histogram` | Derived gauge in the current simple registry | `le=10,30,60,300,900,3600,86400` | observations within a duration bucket; `le` is seconds | Micrometer's timer bucket snapshot, sampled when the derived gauge is read | Empty buckets are zero; retention follows the registry's histogram window | Diagnostic finite bucket counts. For cross-instance p50/p95 alerts, use the chosen exporter histogram contract rather than assuming these gauges are cumulative counters. |
| `telecom.processor.sources` | Gauge | `state=fresh,stale,never_seen` | expected scope/source pairs | Authoritative loaded `ScopeRegistry` inventory, service adapter plus distinct node sources per scope. Classification delegates to existing `SourceFreshness.activityFreshness()` and durable `source_state` | `NaN` until first successful refresh and after a refresh failure. A successfully measured zero means no pairs in that state | Activity freshness, not interval evidence. Propose sustained stale pairs; gate never-seen alerts on intentional monitoring activation/startup grace. |
| `telecom.processor.outbox.oldest.age` | Gauge | `queue=delivery,rejection` | seconds | Minimum `created_at` among rows with `published_at IS NULL` in `app.voice_delivery` and `app.rejection_outbox`, using one PostgreSQL `clock_timestamp()` sample | Zero for an empty queue; future timestamps clamp to zero; `NaN` before refresh or on failure | Oldest pending output age, including leased unpublished rows. Propose age above the delivery SLO; combine with traffic expectations and refresh health. |
| `telecom.processor.metrics.snapshot.age` | Gauge | none | seconds | Injected clock minus the last successful aggregate refresh completion | `NaN` until a successful refresh; continues aging after failures; negative clock differences clamp to zero | Age of freshness/outbox measurement. Propose above three refresh intervals or persistent `NaN`; never treat missing samples as healthy. |

All counters/timer samples disappear on process restart. Counters describe successful listener invocations, so an exact retry increments `duplicate`, not `accepted`. A retried rejected delivery can increment its rejection outcome again even if the outbox row is deduplicated. No counters are emitted for infrastructure/transaction failure, and such failures remain retryable. Historical bootstrap calls ingestion/finalization directly and is intentionally outside these live-boundary metrics.

`LATE_OBSERVATION` maps only to `late`. `MALFORMED_JSON`, `SCHEMA_INVALID`, `SEMANTIC_INVALID`, `SOURCE_UNAUTHORIZED`, `KAFKA_KEY_MISMATCH`, `EVENT_ID_CONFLICT`, and `NATURAL_KEY_CONFLICT` map only to `invalid`. `NOT_DUE`, `ALREADY_FINALIZED`, `NOT_FOUND`, other nonfinalizing returns, and rolled-back finalizations produce no timer sample. Both real-service and missing-service finalizations are measured, including a `SERVICE_PRESENT` fallback that subsequently returns `FINALIZED`.

## Refresh and clock semantics

Freshness and queue gauges share an atomically replaced snapshot, refreshed after an initial delay and then at a fixed delay of 10,000 ms by default. Configure `telecom.metrics.refresh-interval` in milliseconds. A scrape reads cached values; it performs no database work. Freshness delegates one durable lookup per distinct expected scope/source pair; queue refresh uses one aggregate SQL query. Inventory growth changes the query work and aggregate values, never the labels. The refresh is a sampled view, not one cross-table transaction snapshot. Values can change between refreshes.

`SourceFreshness` continues to use `latest_emitted_at`, the injected UTC clock and the policy threshold: absent state is `NEVER_SEEN`; age exactly `staleAfterSec` is `FRESH`; greater age and future emission times are `STALE`. Replayed old observations cannot refresh activity. A healthy heartbeat can establish activity while interval evidence is still missing. Missing interval evidence must be read from coverage/feature contracts, not inferred from this gauge.

Outbox age uses database wall time because delivery row creation/publication bookkeeping already uses that clock. It includes every pending `voice_delivery` topic without a topic label, including coverage and SMS shadow outputs. It excludes published rows and all immutable `feature_outbox` history. The latter has no pending-publication meaning. Ages remain fixed between refreshes; snapshot age exposes measurement age. App/database clocks should be aligned by deployment operations.

A failed refresh sets all source/queue gauges to `NaN`, retains the last successful refresh timestamp, and emits a structured `metrics_refresh_failed` warning. Finalization observation reads immutable committed feature metadata; if that read fails, it emits `finalization_metrics_unavailable` and loses that sample. Observability failures are isolated from acknowledgment, monitoring acknowledgment and retries. Process death between commit and metric/log recording can also lose samples: these are best-effort operational metrics, not exactly-once accounting.

## Cardinality policy and histogram

Application labels have explicit finite sets: four outcomes, three freshness states and two queues. The timer and snapshot age have no application labels. Never add `eventId`, `scopeId`, `sourceId`, `windowStart`, `windowId`, Kafka offset, Kafka key, topic, or `runId` as labels. Even the current city inventory must remain aggregated. Exporter/resource labels also require a separate bounded deployment policy.

The timer enables a percentile histogram, with an expected range of 1 ms to 1 day and fixed SLO boundaries at 10, 30, 60, 300, 900, 3600 and 86400 seconds. Backend-generated histogram labels are finite bucket boundaries, never trace IDs. The expected range bounds histogram storage/bucket generation; it does not cap the recorded total/max delay. An appropriate histogram backend can calculate p50/p95 across instances. The current in-memory registry is not a deployed percentile backend. [Micrometer histogram documentation](https://docs.micrometer.io/micrometer/reference/concepts/histogram-quantiles.html).

The regression's `SimpleMeterRegistry` contains 11 application meter identities plus seven derived `telecom.processor.finalizer.delay.histogram` gauges with `le=10,30,60,300,900,3600,86400`: 18 identities before and after 750 deliveries and 500 finalizations. It also verifies the same identities with a 100-scope / 200-source-pair catalogue. Exporter-generated bucket series can have a different fixed count; that count must not depend on event, scope or inventory IDs.

## Structured event-to-feature trace

Trace IDs belong in SLF4J key/value fields, rendered by the processor's `%kvp` logging pattern. Complete observation payloads, credentials and tokens are never logged. Invalid input supplies trace fields only when identifiers parse safely; receipt logs are not acceptance claims. Stable phases are:

```text
phase="kafka_received" eventId="879adeeb-7816-3394-80aa-753dbd80f27e" scopeId="VOLTE-MD-CENTRAL" windowStart="2026-09-15T08:00:00Z" kafkaPartition="0" kafkaOffset="1"
phase="ingestion_committed" outcome="accepted" eventId="879adeeb-7816-3394-80aa-753dbd80f27e" scopeId="VOLTE-MD-CENTRAL" windowStart="2026-09-15T08:00:00Z"
phase="feature_finalized" scopeId="VOLTE-MD-CENTRAL" windowStart="2026-09-15T08:00:00Z" windowId="513f5809a908204afaed14fa0d949759c4bf1abde3df4fe7bd18322693e90fcc" sourceEventIds="[879adeeb-7816-3394-80aa-753dbd80f27e]"
```

This is a disposable PostgreSQL test trace, with selected fields shown. `observation_receipt.event_id` identifies the committed receipt, its scope/window joins to `feature_outbox`, and the finalized `ServiceFeatureWindowV2.sourceEventIds` contains that exact ID. `feature_finalized` is logged after the finalizer proxy returns, proving committed feature production; it does not claim Kafka publication or downstream acceptance. Heartbeats do not belong to feature `sourceEventIds`.

For a read-only investigation:

```sql
SELECT r.event_id, r.scope_id, r.window_start, f.window_id,
       f.payload->'sourceEventIds' AS source_event_ids
FROM app.observation_receipt r
JOIN app.feature_outbox f
  ON f.scope_id=r.scope_id AND f.window_start=r.window_start
WHERE r.event_id='879adeeb-7816-3394-80aa-753dbd80f27e'::uuid
  AND f.payload->'sourceEventIds' ? r.event_id::text;
```

## Stanislav handoff

Map these exact names to the chosen private exporter before writing dashboard queries. Add traffic-relative outcome rate panels, finalization histogram percentiles, aggregate source activity state and pending queue age. Show sample freshness and missing values alongside gauges. Agree alert budgets with processor/delivery owners; distinguish disabled/unactivated inventory and empty traffic from outages. No dashboard or alert has been deployed by this change. F3 delivery fairness, detector decisions, episodes, incident-service, dashboard ownership, deployment and Day 17 remain outside Day 16.
