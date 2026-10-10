# Trace Link Day 1 measurement and evidence contract

Date: 10 October 2026. Status: **CONTRACT_ONLY**. These artifacts define target
measurement meaning and offline evidence. They activate no new ingest path,
detector, topology catalogue, model, database migration or API route. Existing
V2 VoLTE/SMS behavior and one-minute contracts remain authoritative and unchanged.

The KPI dictionary contains 43 definitions across VoLTE, transport, roaming, SMS,
LTE mobile data and source quality. Each definition retains population, unit,
aggregation, target primitives, scope, quality requirements and metric policy.
`minimumVolume=null` means no detector eligibility threshold is approved on Day 1;
it does not authorize evaluation with zero observations.

## Reference availability and target populations

`reference.availability` describes the supplied reference pack, independently of
runtime quality. `SUPPORTED` means the synthetic aggregate fields support the
declared calculation. `APPROXIMATION` requires the recorded limitation and an
explicit approximation policy. `REQUIRES_ADDITIONAL_PRIMITIVES` means the target
definition is ready but the pack cannot instantiate it. Return a null actual with
an insufficient-evidence reason when those target primitives are unavailable.
An independently hand-calculated fixture may supply target primitives; it is not
an assertion that the CSV contains them.

| Population | Contract and reference limitation |
| --- | --- |
| VoLTE setup | Established calls / eligible setup attempts. New reference policy is distinct from V2 technical eligibility excluding user outcomes. Setup elapsed sum and histogram describe successful setups. |
| Carried voice | Holding seconds / valid elapsed seconds. Disjoint fictional city cohorts and 300 valid seconds per supplied slot are explicit reference assumptions. |
| Link traffic/capacity | Directional carried rate uses contemporaneous positive effective capacity. Mean utilization time-weights per-slot ratios over valid time; never substitute nominal capacity after reduction. |
| Peak link utilization | `transport.link.utilization.peak` is the maximum per-slot effective-capacity utilization across selected declared link-direction scopes and time. It is distinct from the time-weighted mean metric. |
| Transport volume | Rate in Gbit/s × valid seconds × 10^9 / 8 yields a synthetic byte estimate; raw byte deltas/reset epochs are absent. The calculator retains Gbit before this display conversion. Do not label the estimate measured bytes. |
| Transport loss/RTT | Percentage/scalar alone cannot supply lost/expected counts or RTT probe sum/count. Retain separately named snapshot/worst-reference values; target weighted means/rates need new primitives. |
| Jitter/errors/discards | Jitter estimator, error counter/reset epoch and queue identity are absent. Illustration does not establish a measured error/discard ratio. |
| Link/LAG state | No state/member timeline is supplied. Capacity reduction cannot independently prove a member-loss cause. |
| Roamers | Same-time outbound/inbound snapshots; never time sums. Outbound country is visited country, inbound country is home country. Reference city shares are fictional proportional estimates, not observed subscriber cohorts. |
| Roaming procedures | Registration, data and voice have separate success/attempt counters. Partner + country + direction + procedure remain independent scopes. Technology and procedure eligibility are synthetic declarations. |
| Roaming SMS | `sms_success/sms_attempts` is a reference procedure ratio. Finalized completion-cohort outcomes cannot be proven from those fields. Do not relabel it finalized delivery. |
| SMS finalized success | Delivered / (delivered + terminal failed + expired), by completion time. Pending is excluded; completed messages may have been submitted in earlier windows. Reference `sms_expired` is one combined terminal-negative count; do not count it twice. |
| SMS delay | Delivered-message elapsed population; excludes pending-message ages. Mean needs an elapsed sum, absent in the pack. Histogram p95 is an estimate and can remain low despite unresolved backlog. |
| SMS queue | Last same-time snapshot; empty fresh queue age is zero. Growth is end minus start and obeys accepted minus delivered minus terminal negatives. First CSV interval lacks the opening queue; never assume zero. |
| LTE activation | Selected EPS bearer activation successes / attempts. Reference generic counters do not establish attach/default/dedicated eligibility. |
| DNS | Target population is qualifying post-activation DNS requests with independent success counters. Reference `dnsAttempts = dataAttempts * 8` does not prove post-activation eligibility; its ratio remains reference-only. |
| User throughput | Modeled city-slot scalar mean only; no measured per-user sample weighting, speed tests or transferred-byte/active-time population. |
| Quality | Expected windows and validity support coverage; explicit evaluation time supports source age. Rows lack per-source quality, ingest timestamps and freshness metadata. |

The source pack's native resolution is 300 seconds and history is
`[2026-09-10T09:00:00Z,2026-10-10T09:00:00Z)`. At later wall-clock times it is
historical evidence. The reference supplies only A-to-B links; the new contract
can represent B-to-A, but no missing direction is synthesized as observed data.

## Histogram, null and unit policy

Histogram counts are **non-cumulative finite bins**. Pool matching schema,
population, units and edges before interpolating once within the selected p95
bin, using zero as the first lower edge. Do not average p95 values. Reject
negative counts, mismatched count/edge lengths or incompatible edges. Empty
histograms yield null with insufficient evidence. The final finite bin contains
remaining synthetic mass; this is a bounded estimate, not an overflow policy for
operator telemetry.

Voice edges, seconds: `0.5,1,2,3,5,8,15,30,60`.
SMS edges, seconds: `1,2,5,10,20,40,80,160,320`.
Voice output remains seconds. SMS mean/p95 output uses milliseconds under the
separate `trace-link-sms-delay-ms-v1` metric policy: multiply an estimated seconds
quantile by 1000 exactly once. Existing V2 raw-millisecond delay samples keep
their current policy and nearest-rank behavior.

Zero denominator/count/valid time yields null and `INSUFFICIENT_EVIDENCE`.
Missing, insufficient, partial, stale and valid are distinct states. Stale
snapshots may retain their actual and observation time; they cannot become a
fresh zero. A valid evidence item has complete coverage, fresh measurements and
no quality reason. Baseline/threshold can be null; a supplied baseline requires
its version. Deviation is observed minus baseline, preserving its sign, and is
null when either measurement is unavailable. Threshold null means no threshold
has been supplied; the evidence examples do not create a detection policy.

## Evidence interface and validation

`contracts/evidence/trace-link-evidence-v1.schema.json` is a strict standalone
schema. Every item carries evidence/measurement/window identity, metric/unit,
metric policy, primitive aggregate, actual/baseline/threshold/deviation, baseline
version, coverage, quality/reason, source observation/evaluation/age policy,
scope, half-open UTC bounds and source resolution, dependency relation/topology,
rule/model versions, dataset/synthetic provenance, observed revision and generated
time. Rule/model versions may be null for offline arithmetic; this is not proof
of a scored anomaly. Revision is separate from generation time.

Primitive variants are ratio, sum/count, histogram, snapshot, count, valid-time
weighted sum, named maximum, categorical state event and snapshot difference. Only the variant
matching the metric's aggregation is accepted. `primitives=null` requires a null
actual and explicit missing/insufficient quality. Period snapshots must precede
the exclusive window end; point-at-end snapshots need a separate point query.
Snapshot timestamps are retained with freshness; dependency metadata must have the same topology version, overlap
the event window and be fresh/valid. These shape and consistency checks do **not**
prove that a dependency exists: future runtime correlation must resolve the
relation against valid-at-event-time authoritative topology and controls.

The fixture 90-second freshness bound is explicitly supplied test metadata; it
does not establish a new-domain production freshness policy. Known freshness
requires observation time, evaluation time, computed age and maximum age.
Reference-derived evidence requires a source SHA-256 checksum; its shape is
validated here, while actual file verification belongs to the parity/bootstrap
workflow. Hand-calculated target cases declare their origin and use no fabricated
source checksum.

All objects reject unknown fields; numeric values must be finite. Scenario IDs,
injected causes, expected labels, future outcomes and scenario-event exports
have no detector-input field. Dataset/synthetic metadata is legitimate lineage,
not a prediction feature. The HTML incident list visualizes known scenario truth;
it is not an independent detector result or cause-support evidence.

Count primitives, ratio populations, histogram bins/totals and count-valued
snapshots/differences use nonnegative integers bounded by `2^53-1` so Java,
Python and JavaScript retain exact counters. Elapsed/weighted sums and valid time
remain continuous numeric values; the Erlang denominator represents valid
seconds rather than a sample count. Count-valued differences may yield signed
growth. Dictionary validation also checks frozen metric units and that declared
primitive fields match their aggregation operator.

CITY scopes use uppercase runtime IDs and have null direction/partner fields.
Directional LINK identities use lowercase reference endpoints plus the explicit
matching direction suffix, such as `chi-hin:a-to-b`; endpoint city metadata must
agree, and partner/procedure fields are null. SMS delay examples use
`DELIVERED_MESSAGE_DELAY`; queue/oldest-pending examples use
`PENDING_MESSAGE_SNAPSHOT`. A VoLTE procedure cannot label an SMS population.

Validation entrypoints in `scripts/trace_link_contract.py`:

- `validate_dictionary(value)` checks the 43-metric inventory and ID/version semantics.
- `validate_evidence(value, dictionary=None)` checks strict shape and primitive,
  interval, quality, freshness, population and provenance consistency.
- `validate_trace_link_contracts()` validates checked-in dictionary and evidence cases;
  coordinator registers this in the existing shared contract command.

Imports work as `scripts.trace_link_contract` and as `trace_link_contract` when
`scripts/` is on `sys.path`. Run focused tests with
`python -m unittest discover -s tests -p test_trace_link_contract.py -v`.
Integer arithmetic goldens are exact; shared finite-floating goldens use absolute
`1e-9` tolerance. CSV floats are rounded to six decimals; CSV-to-engine comparisons
need a separate quantization allowance, never a weakened arithmetic tolerance.

## City identifiers and owner handoffs

This explicit target map does not activate or rewrite the old geographic catalogue.
Historical RIB/TIR evidence remains intact; those identifiers are absent from the
new active target map.

| Runtime ID | Reference ID | Display name |
| --- | --- | --- |
| CHI | chi | Chișinău |
| BAL | bal | Bălți |
| CAH | cah | Cahul |
| UNG | ung | Ungheni |
| ORH | orh | Orhei |
| SOR | sor | Soroca |
| COM | com | Comrat |
| EDI | edi | Edineț |
| HIN | hin | Hîncești |
| CAU | cau | Căușeni |

Denis receives the versioned definition/evidence interface and source-availability
limits for future API/projection work. David receives exact display names, units,
signed deviation, and null/quality semantics. Ion receives missing primitive and
population requirements, including DNS eligibility and finalized roaming SMS.
These artifacts are prepared handoffs; no recipient review or acceptance is
claimed by their existence.
