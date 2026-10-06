# Sergiu Day 1 — numeric meaning

This contract freezes the October five-day pack's G1 numerical meaning on integration base
`46c72f9b02beabf5b1b8e58ee9bb2bf0df33a6bf`.
Geographic consumers remain contract-only. The detector, strict V2 schemas, model artifacts and defaults retain their existing behavior.

## Metric dictionary

| Field / quantity | Definition and unit | Unavailable behavior |
| --- | --- | --- |
| Eligible technical attempts, D | attempts − userOutcomes = technicalSuccesses + technicalFailures; COUNT | Invalid counters rejected; absent measurements remain absent |
| Technical CSSR | 100 × technicalSuccesses / D; PERCENT | D=0 → null / ZERO_DENOMINATOR (existing evaluator: INSUFFICIENT_DATA) |
| Display deltaPp | observedPct − baselinePct; PERCENTAGE_POINTS | Null if observation or matching baseline unavailable |
| Detector cssrDropPp | baselinePct − observedPct; PERCENTAGE_POINTS | Opposite sign to display delta; keep existing rule semantics |
| Expected successes, E | Σ(Di × Bi / 100) over matching disjoint partitions | Unavailable for full set if any required baseline/partition is missing |
| Weighted baseline | 100 × E / ΣDi; PERCENT | Never average percentages; no baseline for zero total denominator |
| Estimated extra technical failures | max(0, E − ΣSi); estimated COUNT | Keep fractional precision; null for incomplete full-set context |
| Unique subscribers | null | Attempt/message counts cannot establish people |
| SMS p95DeliveryMs | Sample at ceil(0.95 × n) in sorted raw minute delays; MILLISECONDS | Empty measured samples → null / INSUFFICIENT_DATA |
| SMS p95DelayRatio | p95DeliveryMs / matching positive baseline delay; RATIO | Null for missing/zero delay baseline |
| deliveredMessages / sampleCount | Count of completed measured deliveries; COUNT | V2 validators require deliveredMessages = length(deliveryDelayMs); missing is not zero |
| delivery success rate | 100 × deliverySuccesses / deliveryAttempts; PERCENT | Null for zero denominator |
| queueDepth | Authorized SMSC queue snapshot; COUNT | Never sum repeated snapshots across time |
| oldestPendingAgeSec | Age of oldest pending SMS; SECONDS | Raw observation name is oldestPendingAgeSeconds; convert names, not units |
| affectedDeliveredMessages | Existing delay-rule scope/window completion count | Not the number above p95, unique messages across time, or subscribers |
| pendingMessages | Existing backlog-rule scope/window queue snapshot | Do not add to completed deliveries as one subscriber impact |

The model's VOLTE cssrDeltaPp is observed − baseline, consistent with display sign. It is not the detector's positive drop.
Integer counters stay exact; Java uses decimal-safe product comparisons before division. Python/Java numeric comparison tolerance is 1e-9; it does not relax detector boundaries.

## Aggregation eligibility

Aggregate only compatible service, units, aligned UTC start/end and pinned topology/catalogue versions, with nonoverlapping measured footprints and distinct partitions. A topology parent, service dependency or copied cell KPI is not an additional measured partition. Legacy/unallocated scopes are excluded from city totals. Multi-site measured partition implementation remains deferred.

Required complete partitions are defined before calculation. Partial data may expose observed-subset counters/rate with coverage=PARTIAL. The reference deliberately withholds full-set baseline, delta/drop and impact with PARTIAL_COVERAGE. It does not declare full health. A fresh existing eligible breach may remain visible with the coverage limitation; never suppress SMS's independent fresh-backlog path just because its service samples are missing.

If any required baseline is missing, retain available observed counters/rate but withhold full-set baseline/deviation/impact with BASELINE_MISSING. A measured zero-volume partition can have complete raw coverage and unavailable rates. Heartbeat freshness and contributor sourceEventIds cannot prove full measurement coverage. Expected, received and usable sources are separate, as are configured/received service scopes.

For SMS, display a single finalized city-scope minute's actual p95. Combine raw samples only when partitions are compatible and disjoint. Multiple child/time-bucket scalar p95 values without raw samples or an agreed mergeable distribution yield null / NOT_AGGREGATABLE; never average them. This sprint does not introduce a distribution format.

ZERO_DENOMINATOR, PARTIAL_COVERAGE and NOT_AGGREGATABLE here are proposed projection/reference reasons, not additions to strict V2 detection enums. Denis maps them into the reviewed DTO; existing rule states remain EVALUATED, INSUFFICIENT_DATA and BASELINE_MISSING.

## Golden arithmetic

| Case | Expected result |
| --- | --- |
| 90/100 and 999/1000 | 1089/1100 = 99.0%, not 94.95% |
| Above with 99.3% baseline | E=1092.3; delta=−0.3pp; detector drop=+0.3pp; extra failures=3.3 |
| D1=100, B1=90%; D2=1000, B2=99.9% | E=1089; weighted baseline=99.0%; impact=0 |
| Attempts=120, user outcomes=20, technical successes=99 | D=100; CSSR=99%; user outcomes are excluded |
| D=0 | CSSR null; no fake healthy 0% |
| Missing required baseline | Observed may exist; baseline/deviation/impact null |
| One required partition missing | Only observed subset is exposed; full-set metrics unavailable |
| Samples 1..20 | p95=19 |
| Scalar child p95=10 and p95=20 without distributions | Aggregate p95 null, NOT_AGGREGATABLE |

Shared input/expected cases are `contracts/fixtures/geography/numeric-cases-v1.json`. `scripts/geographic_numeric_contract.py` is an offline Decimal oracle; GeographicAggregationCasesTest independently calculates the same expected values using Java BigDecimal. Neither is a production aggregator or a fresh geographic feature export.

## Unchanged policy boundary table

| Decision | Strict / inclusive conditions | Boundary examples |
| --- | --- | --- |
| VoLTE eligibility | D ≥100 and COMPLETE service, matching baseline | 99 unavailable; 100 eligible |
| VoLTE breach | drop >1.0pp | 0.99/1.00 do not breach; 1.01 breaches |
| VoLTE healthy recovery | drop ≤0.5pp | 0.50 healthy; 0.51..1.00 gray |
| VoLTE severity on breach | HIGH extra ≥50; CRITICAL extra ≥200 | 49/50/51; 199/200/201 |
| SMS delay breach | ≥30 samples, p95 >20,000ms AND >3× positive baseline | 29 insufficient; 30 eligible; equal absolute/ratio limits do not breach |
| SMS backlog breach | Fresh aligned authorized queue: depth ≥100 AND age >60s | 99/100/101; 59/60/61; independent of absent delivery samples/baseline |
| SMS HIGH | Backlog breach OR delay breach with completed deliveries ≥100 | 99/100/101; preserve operator grouping |
| SMS CRITICAL | Fresh queue: depth ≥1000 AND age ≥300s on a breach | Both conditions required; 999/1000/1001 × 299/300/301 |
| SMS healthy recovery | COMPLETE service, fresh queue, age ≤30s AND (eligible p95 ≤10,000ms AND ≤2× baseline OR measured deliveries=0 AND depth=0) | Missing is not measured zero; sample/queue freshness still matters |
| Episode opening | Two adjacent eligible breached UTC minutes | Anchor first breach; opening occurs on second |
| Episode recovery | Three adjacent healthy minutes | UNKNOWN/gray/gap interrupts the streak; analyst resolution is independent |
| Timing | 60s UTC windows; closure end+10s | Do not speed demo by changing thresholds/time |
| ML | thresholdRank=0.99; six ordered inputs; 250ms/eight permits | Rank is not probability; deterministic fallback remains |

VoLTE ordered vector: cssrDeltaPp, sip503Ratio, rrcDeltaPp, bearerDeltaPp, packetLossRatio, imsCpuPct.
SMS: p95DelayRatio, p95DeliveryMs, queueDepth, oldestPendingAgeSec, deliverySrDeltaPp, deliveredMessages.
Unavailable required input yields empty model feature arrays, never zero-fill. Independent deterministic rules can still evaluate.

## Consumer handoff

Ion owns Java feature construction and raw receipt authority; use these exact counters and expected results for paired Day 2 parity. Denis owns aggregate/read DTO implementations and null reasons. David uses signed pp, milliseconds/seconds, sample counts and explicitly estimated attempt impact. Stanislav uses actual test/hash evidence and preserves the packaged model runtime. Review dispositions live in the Day 1 evidence record; publication of this document is not a teammate signature.
