# Trace Link terminology

**Observed** is the server measurement for the stated window and population. **Baseline** is a versioned expectation for the same metric. An absent baseline is unavailable. A change in percentage points is not a relative percentage change.

**Call setup success (%)** and **setup time (seconds)** describe different measurements. **Delivered-message delay** excludes pending-message age. **Pending** is neither delivered nor lost. **Active roamers** is a same-time snapshot, not an interval sum.

**Effective capacity** can differ from nominal capacity. Directional utilization retains its direction and capacity validity. High utilization does not independently establish outage, impairment or a confirmed cause.

**Possible cause** is a qualified hypothesis with the supplied confidence. **Cause undetermined** is used when qualification is absent. Scenario intent is not detected cause. **Technical recovery** does not mean **analyst resolution**. **Stopped telemetry** means stopped/unknown, not recovered.

**Synthetic** identifies provenance. **Fixture preview** is explicitly illustrative and is confined to preview builds or labelled local scenario illustration. Production history uses typed backend responses. **Legacy/unmapped** retains historical context without asserting current inventory membership.

**Missing**, **partial**, **stale**, **insufficient evidence**, **unknown coverage**, and **valid zero** remain distinct. Original source qualifications accompany native values even when arithmetic is computable. Units, half-open UTC bounds and presentation timezone are visible.

## Earlier service terminology and review record

The following record preserves the existing service definitions and historical acceptance state. Its counts and dates describe the earlier checkpoint.


## Trace Link repair interpretation

Local **generated** observations, Kafka **offered/acknowledged** records,
**persisted** metrics and processor **accepted** observations are separate
quantities. An unavailable acceptance count is not zero. A COMPLETED scenario
proves only the explicitly reported layer. Planned recovery and stopped telemetry
cannot establish technical recovery; technical recovery cannot resolve analyst
workflow. New-domain attribution remains undetermined without approved evidence.
The native history `voice.success_rate` population is not silently equated with
the live producer's `volte.setup.success`. These notes add no owner approval.

Planned date: 2026-10-06
Commit reviewed: pending
Metric reviewer: Sergiu — pending
Nullable-field reviewer: Denis — pending
Non-telecom teammate: pending
Review date/environment: pending
Status: pending human acceptance

## Percent and percentage points

Percent is a rate. Percentage points (pp) are the arithmetic difference between
two percentage rates. From 98% to 96% means -2 pp, approximately -2.04% relative
to 98%. Dashboard rate deviations use pp. Relative percent needs a nonzero
reference value and is not displayed as the same quantity.

## Delivery p95 and sample volume

Delivery p95 is the delay at or below which 95% of completed-message samples fall.
It is not the average delay. Pending messages are excluded, so inspect queue
depth and oldest queued-message age too. Do not average per-window p95 values.

Zero completed samples means p95 cannot be measured. A null completed count
means the sample count is unknown, not zero. The existing warning below 30
completed messages is a UI caution, not a new model/detection eligibility rule
or a reliability guarantee at 30. Sergiu must approve its wording.

## Estimated extra failed attempts

The backend estimates failures above its expected baseline using the recorded
window counters: max(0, expectedPercent * attempts / 100 - successfulAttempts).
For counters consistent with 96% actual and 98% expected across 1,000 attempts,
the estimate is 20. Do not recompute it from rounded display values in the UI.
An attempt is not a unique customer. Aggregate observations cannot identify
unique customers. UNKNOWN updates can retain the last evaluated impact;
retained impact is not a new evaluation or proof of current health.

## Severity, model rank, and cause confidence

Severity describes rule-based service-impact priority: MEDIUM, HIGH, CRITICAL.
Model anomaly rank compares unusualness with normal calibration data on a
0–1 scale. Rank 0.82 is not an 82% fault probability. A valid zero rank is zero;
a missing/failed result is unavailable. No new severity is inferred from rank.

Cause confidence describes support for a hypothesis: LOW, MEDIUM, HIGH.
It is not a calibrated probability, proof, or severity. Recommended checks
help confirm or reject the hypothesis.

## Five exceptional states and next actions

1. Missing observations: current measurements unavailable. Refresh and check
   the telemetry source. Missing data cannot establish recovery.
2. Absent baseline: expected value unavailable. Keep a supported actual value
   visible; ask the baseline owner about the scope/time coverage. Baseline zero
   is a real value, not missing.
3. Low volume: interpret p95 cautiously and inspect nearby windows/queue evidence.
   Keep low positive, zero, and unknown counts distinct.
4. ML unavailable: show the reason and no rank. Continue with measured KPIs,
   rule-based severity, and recommended checks; escalate persistent model issues.
5. Stale evidence: retain historical values and explicitly mark current health
   unknown. Refresh/check telemetry before declaring recovery.

The server's freshness field drives stale/current labels. An old historical
timeline record is not automatically stale for its own window. A disconnected
live stream means live refresh is interrupted; do not manufacture server
freshness or recovery from a client timestamp or connection status.

## Null and zero rules — Denis

- observed/baseline/numerator/denominator null display as unavailable.
- Real numeric zero stays zero when the metric is supported.
- Zero rate denominator cannot support a success rate.
- Unknown/zero completed samples cannot support delivery p95.
- Independently observed queue values remain useful on incomplete windows.
- No baseline means no numeric comparison or unsupported normal label.
- Model status is respected; failed ML cannot display a leftover usable rank.
- Existing permissions, versions, pagination, and SSE behavior remain enforced.

## Teammate explanation exercise

Use the app, with its contextual help, without developer tools or verbal coaching.
Record what the teammate says before revealing the expected interpretation.

- Missing observation: teammate's explanation/action: pending
- Absent baseline: teammate's explanation/action: pending
- Low volume: teammate's explanation/action: pending
- ML unavailable: teammate's explanation/action: pending
- Stale evidence: teammate's explanation/action: pending
- 98% → 96%: teammate distinguishes -2 pp from relative change: pending
- p95 versus average and pending messages: pending
- Estimated failures versus unique customers: pending
- Rank 0.82 versus probability, severity, and confidence: pending
- Zero versus unavailable: pending

## Acceptance and defects

Sergiu's metric wording/sign-off: pending
Denis's nullable-field/sign-off: pending
Teammate correctly interprets all five cases and rank: pending
Keyboard/touch readability: automated keyboard and 390px checks passed; human touch review pending
Browser resource budget regression: passed (2 controlled browser checks, 2026-10-03)
Remaining defects, owner, and target date: pending
Final team acceptance: pending

## Automated verification (2026-10-03)

- Dashboard unit tests: 70 passed.
- Production build: passed.
- Five-state terminology browser checks: 12 passed at desktop and mobile widths, including zero-rank keyboard help and zero-completion SMS evidence.
- Reconnect, service explanations, and evidence-timeline controlled browser checks: passed.
- Browser resource checks: 2 passed.
- Full controlled browser suite: 55 passed, 6 real-login cases skipped without the backend environment.
