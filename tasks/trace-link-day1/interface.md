# Frozen Day 1 calculator interface

All implementations independently read `contracts/fixtures/trace-link/arithmetic-cases.json`.
The coordinator alone owns this suite and `histogram-parity.json`. Expected values are hand-derived, never calculator exports.

Suite: `suiteVersion: trace-link-arithmetic-v1`, `cases: [...]`. Each case contains
`id`, `operation`, `scope`, `window: {from,to}`, `policyVersion`, `units`, `input`, and either
`expected` or `expectedError: INVALID_INPUT`. Window endpoints are UTC and half-open.
Calculators receive only operation/input/window, never expected or evaluator truth.

Every successful result has exactly `primitives`, `values`, `quality`, `nullReason`,
`coverage`, `snapshotAt`. Quality is VALID, PARTIAL, STALE, MISSING, or INSUFFICIENT_DATA.
Normally coverage=1, snapshotAt=null, nullReason=null. Unavailable arithmetic has
quality=INSUFFICIENT_DATA and nullReason=ZERO_DENOMINATOR or EMPTY_HISTOGRAM.
Invalid inputs produce exactly `{error: INVALID_INPUT}`. Validate all inputs before
calculating, including later histogram bins. Numbers must be finite; integer counters
must be nonnegative safe integers; ratio successes cannot exceed attempts.
Reject truth/expected/future fields recursively at the calculator boundary.

Export JSON: `{fixtureSha256: <SHA256 of suite bytes>, contexts: {<case-id>:
{scope,window,policyVersion,units}}, results: {<case-id>: <result>}}`.
Contexts retain the fixture's exact identity/version fields, separate from numeric inputs.
`units` maps each value field to its explicit unit. It is export context, never
calculator input. The final requirement audit added this metadata without changing
numeric goldens; all four exports must be regenerated against the repinned suite.
Do not include expected output or timestamps in exported results. CLI exits nonzero
for infrastructure failures, missing results, or an unexpected valid/invalid case.
Comparison: exact shape/strings/nulls/integer primitives; derived finite floats abs<=1e-9.

## Operations

- `ratio`: input `partitions: [{numerator,denominator}]`. Primitives are summed numerator/denominator; values `{value: 100*n/d or null}`.
- `histogram`: input `histograms: [{counts,edges,unit,schemaVersion}]`, `outputUnit`. All edges/schema/unit must match, finite strictly increasing positive edges, matching nonempty array lengths, nonnegative integer counts. Units SECONDS/MILLISECONDS, output conversion explicit. Fixed p95: first lower edge 0, linear interpolation at .95*total. Primitives `{counts,edges,sampleCount}` retain source units; values `{p95}` in outputUnit. Empty total is null/EMPTY_HISTOGRAM.
- `utilization`: input `segments: [{trafficGbps,effectiveCapacityGbps,validSeconds,quality}]`, `expectedSeconds`. quality VALID/MISSING/STALE; capacity null permits unavailable segment, capacity<=0 is invalid. VALID segments with capacity contribute. Primitives `{validSeconds,trafficTime,utilizationTime}` where trafficTime=sum(traffic*seconds), utilizationTime=sum(100*traffic/capacity*seconds). Values `{meanPct,peakPct,trafficMeanGbps,transferredGbEstimate}` (Gb estimate=sum(traffic*seconds)). coverage=validSeconds/expectedSeconds, reject >1. Partial usable inputs yield PARTIAL/PARTIAL_COVERAGE; no usable inputs yield STALE/STALE_SOURCE if any stale else INSUFFICIENT_DATA/MISSING_CAPACITY, coverage0 and all values null.
- `queue`: input `{openingQueue,submitted,delivered,terminalNegative,closingQueue,durationSeconds}`. Require closing=opening+submitted-delivered-terminalNegative. Primitives copy those six fields. Values `{growth:closing-opening,growthPerSecond:growth/durationSeconds,finalizedSuccessPct:100*delivered/(delivered+terminalNegative) or null}`. No finalized messages => INSUFFICIENT_DATA/ZERO_DENOMINATOR.
- `city_summary`: input `{rows,expectedRows}`. Each row `{cityId,ts,attempts,success,drops,holding,setupSum,smsSubmitted,smsDelivered,smsExpired,smsQueue,smsOldest,dataAttempts,dataSuccess,dnsAttempts,dnsSuccess,throughput,hoAttempts,hoSuccess,voiceCounts,smsCounts}`; all rows aligned 5m timestamps within window, unique cityId/ts; success<=attempts, drops<=success, all procedure successes<=attempts; sum voiceCounts=success, sum smsCounts=smsDelivered. Voice edges [.5,1,2,3,5,8,15,30,60], SMS [1,2,5,10,20,40,80,160,320] seconds. Primitives sum fields attempts/success/drops/holding/setupSum/smsSubmitted/smsDelivered/smsExpired/dataAttempts/dataSuccess/dnsAttempts/dnsSuccess/hoAttempts/hoSuccess; also pooled voiceCounts/smsCounts, throughputSum, rowCount, slotCount, queue (sum last per city), oldest (max last per city). Values `{voiceSR,dropRate,erlang,setupMeanSeconds,setupP95Seconds,smsP95Ms,smsSR,dataSR,dnsSR,hoSR,throughputMbps}`. Erlang holding/(distinct slots*300); means from primitive population. coverage=rows/expectedRows; PARTIAL/PARTIAL_COVERAGE when <1. Empty rows produce nulls, MISSING/NO_OBSERVATIONS, coverage0. snapshotAt=max selected ts (or null). Queue snapshots do not sum over time.
- `link_snapshot`: input `{linkId,cityId,at,dataFrom,dataTo,maxAgeSeconds,rows}` where at=null uses series window `to` exclusively; explicit at selects <=at. Rows `{linkId,a,b,ts,trafficGbps,effectiveCapacityGbps}`. Select matching link and endpoint city (`all` allowed). Period filters ts>=from and ts<to; point filters ts<=at. Outside [dataFrom,dataTo) yields MISSING/OUTSIDE_DATASET. No match MISSING/NO_OBSERVATIONS. Primitives `{trafficGbps,effectiveCapacityGbps}` (both null if absent); values `{utilizationPct}`. snapshotAt=selected ts; age=(evaluationTime-ts)/1000; age>maxAgeSeconds => STALE/STALE_SOURCE, value null, coverage0; retain primitive snapshot. Missing => coverage0. Capacity null => INSUFFICIENT_DATA/MISSING_CAPACITY value null coverage0; capacity<=0 invalid. Valid coverage1.
- `roaming_summary`: input `{country,direction,cityShare,maxAgeSeconds,rows}`; country ISO or all, direction out/in/both, 0<cityShare<=1. Rows `{countryId,partnerId,direction,ts,users,regAtt,regOk,dataAtt,dataOk,voiceAtt,voiceOk,smsAtt,smsOk}`; aligned5m, unique country/partner/direction/ts, success<=attempts. Filter selected country/direction and ts in window. Primitives summed regAtt/regOk/dataAtt/dataOk/voiceAtt/voiceOk/smsAtt/smsOk and users (last per country/partner/direction, raw integer). Values `{users:sum(floor(last.users*cityShare+.5)),regSR,dataSR,voiceSR,smsSR}`. Procedures retain raw counters; allocation affects only estimated users. snapshotAt=max latest ts. If ANY selected latest snapshot older than maxAgeSeconds relative to window.to => STALE/STALE_SOURCE, users null, coverage0; rates remain observed-period ratios. No rows => MISSING/NO_OBSERVATIONS, all values null, coverage0. Otherwise valid coverage1.

For a period endpoint, `to == dataTo` is valid; return OUTSIDE_DATASET only when
from<dataFrom or to>dataTo. For explicit points require dataFrom<=at<dataTo.
Additional source rows outside the selected period are validated then filtered.
Timestamps have at most three fractional digits (common millisecond precision).
Carried traffic must not exceed known positive effective capacity. Point snapshot
selection also requires row timestamp inside [dataFrom,dataTo).
If latest city snapshots differ in time, queue/oldest primitives are null,
quality PARTIAL/reason SNAPSHOT_MISALIGNED, snapshotAt null; preserve counter coverage.
For roaming mixed latest times, raw/estimated users are null, PARTIAL/
SNAPSHOT_MISALIGNED, coverage0/snapshotAt null; period rates remain available.
STALE takes precedence over this mixed-time roaming guard. Round city allocation
without overflowing a safe integer at share1 (round product rather than add .5 first).
Use numeric age strictly greater than threshold as stale. Ratios with zero denominator
inside summaries are individually null; summary quality stays VALID unless coverage or
freshness is unavailable. Unsupported primitives are documented rather than inferred.

Link snapshot values also retain `ageSeconds` explicitly: evaluation time minus
selected observation time, including stale/missing-capacity rows; null when no row
is available. Its export unit is SECONDS. This final audit correction does not
change selection, utilization or freshness rules.
