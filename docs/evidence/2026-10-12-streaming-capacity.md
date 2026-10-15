# Day 3 streaming capacity — 12 October 2026

**Connected capacity: BLOCKED.** The producer dependency and consumer/Kafka
mapping remain `PENDING_REVIEW`. No approved compatible new-domain publication
path exists for this branch. A disposable PostgreSQL environment supports
source import and arithmetic verification, but cannot establish Kafka capacity.
The 2-minute warmup, 15-minute steady state, bounded burst and recovery profile
was **not executed**. No fixture throughput is presented as a benchmark.

The [load profile](../../scripts/load-profile.json) preserves the approved
warmup/steady durations. Burst bounds, recovery deadline, target offer rate and
topic are null until the coordinated environment supplies them; no values are
invented. This does not request renewed consumer approval.

Environment inspected on 12 October 2026 (Europe/Chisinau):

| Fact | Observed value |
| --- | --- |
| CPU | Intel Core i5-11300H at 3.10 GHz; 4 physical cores, 8 logical processors |
| Host physical RAM | 16,856,289,280 bytes |
| Docker engine | 29.3.1; 8 CPUs, 8,166,535,168 bytes available memory |
| Verification services | Testcontainers PostgreSQL 16.4-alpine and disposable Kafka for existing regression gates |
| Test container CPU/RAM limits | No explicit per-container quota configured by these fixtures; Docker's shared resources apply |
| Shared application | Existing incident-service container was restarting at inspection; left untouched |
| Source population | 10 city, 12 measured A-to-B link and 20 partner/direction scopes; reverse inventory is not measured |
| SQL producer parity population | One city, one link direction, one partner in each direction; 12 native slots per scope |

| Required connected measurement | Status |
| --- | --- |
| Offered / acknowledged / persisted / finalized / published observations per second over matching intervals | NOT MEASURED |
| Represented calls/messages per second | NOT MEASURED; distinct units from source observations |
| Kafka lag, finalizer lag and oldest unpublished delivery-intent age | NOT MEASURED for new domains |
| CPU utilization / memory working set during steady load | NOT MEASURED |
| Error rate under load | NOT MEASURED |
| Burst backlog / recovery time / backlog drain | NOT MEASURED |
| Authenticated API/UI or Grafana acceptance | NOT VERIFIED |

Producer counters describe local artifact creation, exact replay, invalid input,
native rows read and calculation duration, with fixed domain/outcome labels.
They have no event/scope/run/subscriber labels. Existing importer logs retain
committed batch progress and validation failures. No new durable delivery intent
exists, so pending count/age for this isolated path is not applicable. PR #92's
held delivery records and observability are unchanged.

The separate [domain evidence](2026-10-12-domain-producers.md) records actual
functional counts and source/SQL parity. Those results establish deterministic
producer arithmetic, not sustained throughput, connected delivery or capacity.
