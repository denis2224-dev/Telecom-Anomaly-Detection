# Sergiu — Day 6 detection walkthrough

Milestone: 15 October 2026, Europe/Chisinau. This is the execution script,
not a receipt of a connected demonstration. Use only the approved shared release
manifest. Its absence currently blocks connected rehearsals and final sign-off.

## Before speaking

Record the common manifest SHA-256, source revision, image digests, dataset and
scenario seeds, policies, baseline/model versions, browser and viewport. Restore
an isolated run to the approved starting state; repeat from that same state for
run two. Do not reset a shared analyst database. Keep ML shadow-only.

Prepare a trace row for every displayed number used below. Include its API query
and response, dataset row/window ID, half-open UTC interval, primitive aggregate,
unit, coverage/freshness, baseline version/value or explicit unavailability,
rule/threshold/version, detection ID and incident evidence ID. Attach the actual
response/export and screenshot with hashes. Healthy/insufficient cases have no
incident ID; record the reason rather than inventing one.

## Walkthrough

1. **Establish provenance.** “These are synthetic measurements. The history uses
   five-minute windows. A separate one-minute producer scenario is not the same
   dataset and must not be substituted for it.” Show dataset, interval and unit.
2. **Explain arithmetic.** Show a rate from its successes and attempts. The
   arithmetic control `990/1000 + 9/10` is `999/1010 × 100 = 98.9108910891%`,
   not the average of two percentages. This is a contract example, not a claimed
   UI observation. For the actual selected row, recompute its own primitives.
   Zero denominator means insufficient evidence, not zero-percent success.
   Pool compatible histogram bins before p95; never average p95 values. Snapshot
   queues and active roamers are not totals across time. Show capacity-weighted
   transport utilization and preserve seconds/milliseconds conversions.
3. **Transport.** Show an impaired link/direction and an independent healthy
   high-load control. Loss or latency can be bad without overload. Trace the
   symptom to the persisted source window, baseline, rule and incident evidence.
   A mapped, fresh, overlapping dependency can support a probable cause; a
   wrong-city, stale, wrong-time or ambiguous dependency leaves it undetermined.
4. **Roaming.** Show partner, direction and procedure. Compare the opposite
   direction and low-attempt control. Failed attempts count procedures, not
   unique affected people; the dataset does not identify individual subscribers.
5. **LTE data.** Show activation and DNS separately. Healthy activation does not
   erase a DNS incident, and healthy DNS does not erase activation failure.
6. **Lifecycle.** Observe opening, equal update, escalation, unknown and recovery.
   Missing input cannot count as healthy recovery. Show a concurrent scope with
   independent evidence. Replay/restart must retain stable episode/detection IDs.
   “Technical recovery means the measurements recovered. Analyst closure is a
   separate reviewed workflow action.”
7. **ML failure and interpretation.** Demonstrate the approved timeout/unavailable
   drill. Deterministic incidents and recovery continue. “Severity expresses
   operational impact; anomaly rank orders unusual observations; cause confidence
   describes supporting evidence. None can be substituted for the others, and
   an uncalibrated anomaly score is not a probability.”
8. **Legacy preservation.** Show VoLTE and SMS evidence, correct units, replay and
   recovery. Include queue/backlog evidence when discussing SMS recovery.
9. **State limitations.** “Unknown is an honest result when evidence is missing.
   A supported probable cause is a hypothesis, not proof. These synthetic tests
   do not establish production accuracy or capacity.”

## Record each run

Use the receipt format in [the Day 6 runbook](../runbooks/sergiu-day6.md).
Record both event-time delay and measured wall-clock latency with their origins.
Compare identities and expected outcomes, not volatile timestamps or log bytes.
Any artifact-changing repair requires a new freeze and two replacement runs.

Historical context only: Day 5 found 65/65 deterministic test episodes, zero
duplicate episodes, and substantially poorer ML shadow performance. Its timing
was event-time study timing, its detector used a static demonstration baseline,
and its cause oracle was component regression evidence, not held-out causal
accuracy. Refer to the original report rather than presenting it as today's run.
