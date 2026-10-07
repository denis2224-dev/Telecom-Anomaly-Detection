# Revision 3 Day 15: geographic streaming load accounting

Status: **DAY 15 COMPLETE — READY FOR REVIEW** for Ion's load-accounting scope. Shared deployment capacity and whole-pipeline resource budgets require the Stas handoff below.

## Repository and ownership

- Branch: `feature/ion-day15-load-accounting`.
- Refreshed base: `b58ca1ef771e0bae730ad544b6028c1fd83dae63` (`origin/main`).
- Tested implementation SHA: `7c00c3a65ff05a93d1f6e4e9942831bc795d6ddf`. The subsequent evidence commit changes documentation only.
- Isolated worktree: `C:\OrangeSystems\Program\ion-day15-load-accounting`. The original dirty checkout and teammate PRs #61/#62 were preserved.
- Changes: generator accounting, generator regressions, a new Ion streaming integration test under processor tests, and this evidence. Processor production, detector/delivery behavior, incident-service, dashboard, deployment and CI files are unchanged. Day 16 and Day 17 were not started.

## Exact bounded profile

Inventory is reused from `contracts/geography/demo-geography-v1.json` and `contracts/topology/geographic-scopes-v2.json`; there is no second load inventory.

- Bundled geography file SHA-256: `5721ac8d7726b443893161d4b5c39634327cf098b6525efdd2289c55204d1cbb`.
- Bundled version `1-geography-g1` is `CONTRACT_ONLY`. The integration fixture explicitly calls `GeographyCatalog.activate(2026-10-07T08:00:00Z)`.
- Verified active version: `2-geography-day2-ed4d77fd2612a418e7cb9f0c0f4208edca5bc07fa722fb96f410a4ff2de66694`.
- Active catalogue digest: `a5da34093810f108109f8a8b1d8aed72633b2a9144227d7f4daf6aecea8e9f5e`; topology version `2-geography-g1`.
- Seed `42`, publish delay `1s`, legacy disabled, no scenario reservations. Required roles provide VoLTE SERVICE + IMS + TRANSPORT (3 records) and SMS SERVICE + SMSC (2 records). Optional SMS transport is excluded.

| City | SMS scope (2 records) | VoLTE scope (3 records) |
| --- | --- | --- |
| BAL | SMS-MD-BAL | VOLTE-MD-BAL |
| CAH | SMS-MD-CAH | VOLTE-MD-CAH |
| CHI | SMS-MD-CHI | VOLTE-MD-CHI |
| COM | SMS-MD-COM | VOLTE-MD-COM |
| EDI | SMS-MD-EDI | VOLTE-MD-EDI |
| ORH | SMS-MD-ORH | VOLTE-MD-ORH |
| RIB | SMS-MD-RIB | VOLTE-MD-RIB |
| SOR | SMS-MD-SOR | VOLTE-MD-SOR |
| TIR | SMS-MD-TIR | VOLTE-MD-TIR |
| UNG | SMS-MD-UNG | VOLTE-MD-UNG |

Ten cities × two services = **20 scopes**, producing a nominal **50 observations per 60-second source window**, or `50/60 = 0.833333…` logical observations per source second. The tests measure actual counts below; the configured rate alone is not throughput evidence.

Legacy remains optional and unchanged. Enabling it adds `VOLTE-MD-CENTRAL` and `SMS-MD-ROUTE-A`, giving 22 scopes; their traffic is excluded from geographic accounting. The catalogue regression checks both configurations. Legacy is disabled in the measured Kafka/PostgreSQL runs; no combined geographic-plus-legacy throughput claim is made.

## Accounting semantics

- `expectedObservations`: required geographic sources from the active catalogue for that minute, before scenario/busy-chain/deadline exclusions.
- `offeredObservations`: stable logical payloads admitted as one scope chain. The whole chain is offered once, including records still waiting for earlier ACKs. A technical retry reuses the same payload/event ID and does not add an offer.
- `acknowledgedObservations`: a current chain's future succeeded before its producer cutoff. A submitted record, failed future, timeout, uncertain ACK, cancelled future or stale callback is not an ACK.
- `failedObservations`: the current logical record after the existing chain failure budget is exhausted. This is an ACK/publication failure, not proof that Kafka or PostgreSQL lacks the record. Remaining offered records in that failed chain are cancelled.
- `expiredOrCancelledObservations`: remaining offered records cancelled by expiry, stop, rejected submission or unavailable scheduling; terminal failures are counted separately.
- `sendAttempts`, `failedSendAttempts`, `timedOutSendAttempts`: technical counts, separate from logical outcomes. The existing three-failure chain budget and 250ms backoff are retained.
- `pendingObservations = offered - acknowledged - failed - expiredOrCancelled`.
- `unofferedObservations = expected - offered`. A scenario reservation or a held old chain remains an explicit gap. `complete` means enrollment is sealed and every offer has a terminal outcome; it does not imply all expected records were offered or acknowledged.

`geographicPublicationResults()` returns an immutable list of immutable records for at most two UTC minutes. Stop/expiry accounting is idempotent. Summary logs expose minute totals without subscriber information or per-event metric dimensions. Results are process-local, not durable history; a same-minute lifecycle restart replaces that minute's retained result, while already returned snapshots stay immutable.

The existing maximum 20 scope chains, 20 daemon workers, queue capacity 20, per-scope order, seed, payload/event identity, Kafka topic/key, stable retry bytes, optional legacy publication and no-downtime-backfill behavior remain. Producer cutoff is windowStart +67s (windowEnd +7s); processor closure remains +70s (windowEnd +10s). ACK callbacks at or after the existing cutoff are fenced even if the expiry scheduler has not yet run.

## Same-window measured reconciliation

`GeographicLoadAccountingTest` uses the actual generator, real embedded KRaft Kafka (`acks=all`), actual wire records and the transactional ingestion/listener under the PostgreSQL runtime role. PostgreSQL is disposable Testcontainers PostgreSQL; Flyway migrations are applied and validated. Each consumer offset is committed after the listener's ingestion transaction returns.

The integration harness pins the source clock and manually fires scheduled ticks/retries. It uses a `1s` generator ACK timeout, within the existing allowed limit, and KafkaTestUtils producer properties plus `acks=all`. It warms metadata before sampling. It does not apply every deployment producer property or replay real wall-clock minute spacing. The unchanged application defaults are ACK timeout `500ms`, max.block.ms `500`, request.timeout.ms `1000`, delivery.timeout.ms `1500`, linger.ms `0`, and `acks=all`.

Counts are restricted to the **same exact UTC windowStart and the 20 listed geographic scopes**, using committed `app.observation_receipt` rows and the sum of `app.interval_bucket.accepted_input_count`.

| Case / source window | Expected | Offered | ACK | Durable receipts | Failed logical | Expired/cancelled | Wire records | Failed send attempts / timeouts |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Normal: 2026-10-07T08:00:00Z | 50 | 50 | 50 | 50 | 0 | 0 | 50 | 0 / 0 |
| Uncertain ACK before retry: 2026-10-07T08:01:00Z | 50 | 50 | 49 | 50 | 0 | 0 | 50 | 1 / 1 |
| Same uncertain window after stable retry | 50 | 50 | 50 | 50 | 0 | 0 | 51 | 1 / 1 |

The uncertain case deliberately hides one already successful broker ACK behind a timeout future. Before retry, persisted receipts are 50 while observed ACKs are 49 and one logical record remains pending. Retry sends the original bytes/key/event ID at another Kafka offset. Ingestion returns `DUPLICATE`; durable receipts and accepted-input totals remain 50. Both windows finalize exactly 20 features at +70s. Equality is asserted after retry, not fabricated for the intermediate state.

Measured offered, ACK and persisted counts are each **50 per injected source minute** after completion (0.833333… per source second). Before the uncertain retry, observed ACK rate is **49 per injected source minute** (0.816666… per source second), while persisted rate is 50. These are source-window accounting rates, not demonstrated continuous wall-clock throughput.

The normal measured work phase took 3.4030399s; the uncertain phase took 2.6927232s. Each phase includes publication, consumption, committed ingestion, reconciliation and finalization. Dividing 50 receipts by those measured phase durations gives approximately 14.69 and 18.57 logical receipts/s for these isolated test phases only. Neither number proves sustained deployment capacity, latency percentiles, resource budgets or a long-running Kafka limit.

Raw measured results: [2026-10-07-day15-load-reconciliation.json](2026-10-07-day15-load-reconciliation.json). They were captured by the successful full verify run, not from configured values.

## Fresh validation

Java 21 was available. Relevant root reactor command: cached Maven 3.9.16 with `-pl services/processor -am verify`, covering streaming-support, event-generator and processor. Finished `2026-10-07T13:43:20+03:00`, elapsed 3m50s, **BUILD SUCCESS**.

| Module | Run | Passed | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| streaming-support | 111 | 111 | 0 | 0 | 0 |
| event-generator | 81 | 81 | 0 | 0 | 0 |
| processor | 383 | 379 | 0 | 0 | 4 |
| Total | **575** | **571** | **0** | **0** | **4** |

The four existing skips are the external HTTP geographic scorer, the live packaged SMS model, the live packaged voice model, and external SMS shadow replay. No Day 15 accounting test was skipped. This verification is local/component evidence; shared CI and deployment acceptance are separate.

- First regression: 1 test errored with `NoSuchMethodException: ContinuousTelemetryService.geographicPublicationResults`; production lacked the requested observable result.
- Focused generator: 15 passed, 0 failures/errors/skips. All nine pre-existing tests were retained, with six additional tests.
- Focused combined generator/reconciliation: 17 passed, 0 failures/errors/skips. An initial evidence writer failed to serialize `Instant`; enabling timestamp serialization fixed the writer and the fresh run passed.
- Terminal single-record ACK failure: expected/offered 50, ACK 49, failed 1, expiry 0, send attempts 52, failed attempts 3.
- All chains fail on their first record: offered 50, ACK 0, failed 20, cancelled 30, technical attempts/failures 60.
- Stop and deadline cases: offered 50, ACK 0, cancelled 50; late callbacks cannot add ACKs.
- One blocked SMS scope: offered 50, ACK 48, pending 2 while the other 19 scopes progress; stopping yields cancelled 2. In the next minute with the old uninterruptible send still held, expected 50 / offered 48 / ACK 48 / unoffered 2 is explicit.
- Immutable snapshots, two-minute retention, all 20 scopes, stable identity/key/order/seed, default deadline/timeout limits and optional legacy configuration are covered.
- Repository `.venv` `python -B scripts/check-contracts.py`: exit 0, all nine printed validation groups PASS, including 10 cities / 20 geographic scopes / 50 observations, 12 rejected catalogues, 30 coverage cases and geographic numeric/reference fixtures.
- `git diff --check`: PASS. Graphify AST update: PASS via installed Python module (the Windows launcher for `graphify update .` had a missing-script error). Graph outputs were retained outside the PR.

Local logs are in `C:\OrangeSystems\Program\ion-day15-evidence\`: `accounting-red.log`, `generator-focused.log`, `reconciliation-green.log`, `maven-verify.log`, `verification-counts.txt`, `contracts.log`, `catalogue-runtime.log`, `graphify-module-update.log`.

## Hardware and actual resource samples

Host: Windows, Intel Core i5-11300H @3.10GHz, 4 physical / 8 logical cores, physical RAM **16,856,289,280 bytes**. Runtime: Temurin OpenJDK `21.0.12.1+1`, Maven `3.9.16`, Spring Boot `3.5.16`.

Samples use the test JVM's process CPU-time counter and heap-usage counter. The embedded broker runs in this JVM; PostgreSQL runs in a separate container. These measurements exclude PostgreSQL/container CPU and memory, OS load and a separately deployed generator/processor. Heap point samples are not RSS, peak memory or an enforced RAM budget.

| Phase | Real sampling start UTC | Duration ms | JVM CPU time ms | Heap before bytes | Heap after bytes |
| --- | --- | ---: | ---: | ---: | ---: |
| Normal, 20 scopes / 50 logical records | 2026-10-07T10:40:39.932916300Z | 3403.0399 | 3890.625 | 64061608 | 87105344 |
| Uncertain retry, 20 scopes / 50 logical records / 51 wire records | 2026-10-07T10:40:43.569387900Z | 2692.7232 | 1640.625 | 95493952 | 71121432 |

No whole-pipeline CPU/RAM or capacity PASS is claimed from these samples.

## RESOURCE MEASUREMENT HANDOFF TO STAS

Use branch `feature/ion-day15-load-accounting`, tested implementation `7c00c3a65ff05a93d1f6e4e9942831bc795d6ddf`; later commits add evidence only. No Stas-owned files were edited.

1. Reproduce the component evidence on Java 21 with Docker available:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$day15Maven='C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16\56ba1f9f\bin\mvn.cmd'
& $day15Maven '-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2' -pl services/processor -am test '-Dtest=GeographicPublicationTest,GeographicLoadAccountingTest' '-Dsurefire.failIfNoSpecifiedTests=false'
& $day15Maven '-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2' -pl services/processor -am verify
& 'C:\OrangeSystems\Program\Telecom-Anomaly-Detection\.venv\Scripts\python.exe' -B scripts/check-contracts.py
git diff --check
```

2. On Stas's intended shared machine, run the existing deployment with its reviewed alignment settings. Pin generator and processor to the same catalogue activation. Exact generator properties for the minimum load:

```text
--telecom.geography.enabled=true
--telecom.geography.effective-from=2026-10-07T08:00:00Z
--telecom.continuous.enabled=true
--telecom.continuous.legacy-enabled=false
--telecom.continuous.seed=42
--telecom.continuous.publish-delay=1s
--telecom.continuous.kafka-timeout=500ms
```

Retain the existing Kafka timeout properties and `telecom.observations.v2` topic; avoid scenario reservations during the load sample. Collect **5 minutes warm-up plus 15 complete measured UTC windows** (20 scopes; nominal 750 logical records over the measured 15 minutes). This duration and count are a handoff target, not a completed soak result.

3. Sample actual generator, processor, Kafka and PostgreSQL container CPU/RAM every second during the measured period. An existing Docker environment can collect without infrastructure edits:

```powershell
$day15ResourceLog=Join-Path $PWD 'day15-resource-samples.jsonl'
1..900 | ForEach-Object {
    $day15Containers=@(docker stats --no-stream --format '{{json .}}')
    [pscustomobject]@{ sampledAtUtc=[DateTime]::UtcNow.ToString('o'); containers=$day15Containers } |
        ConvertTo-Json -Compress | Add-Content -LiteralPath $day15ResourceLog
    Start-Sleep -Seconds 1
}
```

`docker stats --no-stream` itself takes time: retain timestamps and actual elapsed duration; do not assume this loop has exact one-second cadence or ends exactly at 15 minutes. Run monitoring alongside the selected 15 complete windows and use Stas's existing collector if available. Report average/peak CPU and resident/container RAM with process/container identity, hardware, actual duration, deadlines/timeouts, observations and stated resource budgets.

4. Join the generator's terminal `Geographic publication windowStart=...` summary logs to committed receipt and accepted-input totals for each identical UTC minute and exactly the scopes in the table. Use read-only queries, for example:

```sql
SELECT window_start, count(*) AS persisted_receipts
FROM app.observation_receipt
WHERE window_start >= :measured_from AND window_start < :measured_through
  AND scope_id ~ '^(SMS|VOLTE)-MD-(BAL|CAH|CHI|COM|EDI|ORH|RIB|SOR|TIR|UNG)$'
GROUP BY window_start ORDER BY window_start;

SELECT window_start, sum(accepted_input_count) AS accepted_inputs
FROM app.interval_bucket
WHERE window_start >= :measured_from AND window_start < :measured_through
  AND scope_id ~ '^(SMS|VOLTE)-MD-(BAL|CAH|CHI|COM|EDI|ORH|RIB|SOR|TIR|UNG)$'
GROUP BY window_start ORDER BY window_start;
```

The colon variables are SQL-client parameters for complete UTC windows. Retain missing windows as gaps. Normal expectation is offered = ACK = persisted = 50 per minute; report uncertainty, expiry, reservations, late input and duplicate wire attempts explicitly. Producer ACKs are broker evidence, not database receipt proof.

## Remaining boundary

Ion's remaining Day 15 accounting and same-window reconciliation are implemented and tested. Whole-pipeline resource/capacity acceptance on the intended machine remains **external to this PR and handed to Stas**. Local controlled tests do not prove shared CI, deployed Kafka throughput, live UI acceptance or Day 16/17 readiness. This PR is for review and is not automatically merged.
