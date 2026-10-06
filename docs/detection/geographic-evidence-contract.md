# Evidence vocabulary and optional boundary — Sergiu Day 1

The new GeographicCauseProjectionV1 schema is a contract candidate for Denis's read projection and David's wording.
It supplements existing V2 detections; it does not change V2 fields, immutable incident history, correlation keys or episode identities.
Neither this schema nor its validator infers a cause, persists auxiliary evidence or activates a producer/API.

## Cause and confidence vocabulary

| Code | Wording / condition | Confidence |
| --- | --- | --- |
| UNDETERMINED | Cause undetermined; inspect aligned service/dependency evidence. Missing, insufficient or contradictory evidence cannot establish a diagnosis. | LOW |
| IMS_CAPACITY_HYPOTHESIS | High aligned IMS CPU with SIP 503 and degraded call setup, while access is healthy, supports a capacity hypothesis. Verify traces/routing before confirming. | Existing supported hypothesis MEDIUM; downgrade/withhold when contradicted |
| SMSC_BACKLOG_HYPOTHESIS | Fresh aligned SMSC backlog suggests a delivery bottleneck; verify downstream routing. | Existing supported backlog MEDIUM; not confirmed root cause |
| SMS_DELIVERY_DELAY | Completed delivery is delayed; inspect SMSC/transport evidence. | LOW; delay alone does not localize root cause |

The existing IMS predicate is CPU ≥90%, SIP 503 >0 and RRC/bearer drop ≤0.5pp, plus exact authority/time/contributor checks and a VoLTE breach.
The existing SMS backlog uses its independent fresh queue predicate. Preserve those predicates when generalizing nodes on Day 2.
MEDIUM/LOW are categorical confidence, not probabilities. ML anomalyRank is separate. Confirmed POWER_OFF is absent from this vocabulary.

## DTO meanings and source references

The candidate carries scope/service, measurement minute, pinned topology/catalogue, probableCauseCode, probableCause,
causeConfidence, supportingEvidence, contradictoryEvidence, limitations and recommendedChecks. Existing wire names probableCause,
causeConfidence and recommendedChecks are preserved. Semantic grouping/display additions require Denis's OpenAPI/DTO review.

Each evidence reference has sourceEventId, sourceId, nodeId (null for SERVICE), role, scope, UTC start/end, versions and summary.
Supporting/contradictory references must name an authorized SERVICE reporter or exact mapped dependency of that scope, not another city's reporter.
Never substitute current catalogue versions on older evidence. Persisted receipt existence, quality and actual metrics remain prerequisites
for a real projection; the offline validator checks shape/authority/time/version, not database truth or causal sufficiency.

The seven fixture DTOs are hypothetical examples of wording and topology-aligned references, not claims that the referenced golden receipt
metrics establish those causes. Actual mathematical/causal output remains exercised by existing service-explanation-cases.json and ExplanationCasesTest.
The candidate's scenario/groundTruth/runId fields are rejected. Contradictions require withholding/downgrading a hypothesis and naming the limitation;
stale or misaligned evidence belongs in limitations/next checks, not affirmative support.

Freshness, source coverage, measurement availability and cause confidence are distinct. UNKNOWN may retain historical severity/impact in strict V2;
current VoiceEpisode already adds HISTORICAL_SEVERITY/HISTORICAL_IMPACT evidence with source windows. Current measurements must stay null/unknown
when unavailable. Do not display retained history as current degradation or count analyst-open incidents as technically ongoing.

## Optional AuxiliaryNetworkEvidenceV1

The separate schema freezes the proposed bounded wire shape: schemaVersion, evidenceId, type, sourceId, targetNodeId,
topologyVersion, observedAt/emittedAt, status, synthetic, optional vantagePoint/latencyMs/detail. Types are POWER_ALARM,
PING_RESULT and PROBE_WORKER_HEALTH; detail is bounded. No auxiliary field is added to TelecomObservationV2.

Schema validation proves structure only. Before G2 enablement, Ion/Stanislav must publish an allowlisted authority-to-target map and immutable ID/retry
contract. The example POWER-SRC-CHI → SITE-MD-CHI-01 is PLANNED sample metadata, not accepted operational authority. No consumer trusts a sender's freshness flag.
The proposed freshness decision is age ≤90s, observedAt not in the future, emittedAt ≥observedAt, same captured topology,
and observedAt inside the relevant UTC service minute; review/freeze policy before any runtime use. This rule is separate from existing V2 node alignment.

Enable the optional slice only when a named publisher, idempotent consumer, source-authority/freshness/control tests, storage/API path and integration owner
exist at G2. Until then it is PLANNED/NOT_IN_SCOPE. Denis stores evidence and invokes Sergiu's future pure correlation function in its read projection.
No second processor episode state machine; no merging VoLTE/SMS episodes; original IDs/history remain immutable.

An explicit fresh aligned authorized power alarm plus downstream service degradation may support a power-related hypothesis, never an automatic confirmed
diagnosis. Without the alarm the same service degradation stays undetermined. Gap, ping failure, failed worker, absent/stale/wrong-city/time/version evidence
or contradictory healthy observations do not confirm power failure. The Day 4 function and POWER_RELATED_HYPOTHESIS projection extension require the optional
go/no-go contract; they are not implemented by the Day 1 DTO validator.

## Handoff

Denis owns read-model DTO/OpenAPI integration and persisted reference checks; David owns uncertainty/state presentation; Ion owns observation authority;
Stanislav owns optional readiness and pinned runtimes. Sergiu provides the metric/policy/cause examples, baseline decision and negative contract checks.
Formal review dispositions are recorded separately from executable verification.
