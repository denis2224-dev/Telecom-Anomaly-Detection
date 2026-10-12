# Day 2 cause interface

`TraceLinkCausePolicy.evaluate(symptom,candidates,controls,dependencySnapshot,policyJson)`
is pure and returns a JSON cause result. `symptom` is the frozen rule-result shape.
`candidates` and `controls` are arrays. All input evidence uses Day 1 evidence v1.
Raw negative candidates have `dependency=null`; they are not attached support.

Candidate fields: `candidateId`, `hypothesisCode` (exact `<symptom.domain>_DEPENDENCY_HYPOTHESIS`),
`sourceId`, `pathId`, `evidence`. A candidate is one measured dependency hypothesis.
Control fields: `candidateId`, `kind=CONTRADICTORY|OPPOSITE_DIRECTION`, `sourceId`,
`pathId`, `evidence`. Controls must overlap both symptom and candidate event time.
Control health is calculated from its measured metric using
the same support policy; there is no trusted input health/expected/truth label.

Dependency snapshot fields: `topologyVersion`, `relations` array. Each relation
has `relationId`, `sourceId`, `pathId`, `scope` (full symptom scope), `targetScope`
(full candidate/control evidence scope), `topologyVersion`, `from`, `to`.
Relation validity must contain the entire symptom/evidence overlap. Historical
topology versions must agree. No implicit city/path/source mapping is allowed.

Policy fields: `cause.policyVersion`, `cause.minimumCoverage`,
`cause.maxSourceAgeSeconds`, `cause.supportThresholds`, where each metric entry
contains `comparison=GT|LT|DROP_PP_GT`, `value`, `minimumSamples`. These are configuration.
Freshness `asOf` must exactly equal symptom `evaluatedAt`; source age is
recomputed at that explicit evaluation time, never the wall clock.
Candidate metric primitives must match the Day 1 dictionary; sampled thresholds
cannot be satisfied by a snapshot. Only fresh VALID evidence with adequate counts,
finite actual and exact arithmetic can support a hypothesis.

For a roaming symptom or roaming candidate metric, an eligible opposite-direction
control is mandatory (including mapped transport/service composition). It must share
partner/procedure, have a different non-null direction and otherwise match the
candidate scope. A healthy opposite direction supports direction-specific
isolation; impaired or missing opposite control leaves attribution undetermined.
An eligible healthy CONTRADICTORY control for the same dependency scope and metric
invalidates the claim. An impaired contrary control does not invalidate it.
Invalid controls are retained as rejected records and cannot support a claim.

Output required fields: `schemaVersion=trace-link-cause-result-v1`, `evaluatedAt`, `datasetId`,
`namespace`, `domain`, `scope`, `window`, `revision`, `topologyVersion`,
`policyVersion` (cause policy), `symptomPolicyVersion`, `baselineVersion`,
`status=SUPPORTED_HYPOTHESIS|UNDETERMINED`, `probableCauseCode`, `probableCause`,
`causeConfidence=null|MEDIUM`, `supportingEvidenceIds`, `contradictoryEvidenceIds`,
`rejectedEvidence` (objects with `candidateId`, `evidenceId`, `reason`),
`reasonCodes`, `dependency` (accepted relation plus evidence overlap, otherwise null).
Supported cause has exactly one distinct supported candidate; duplicate identical
candidate/evidence inputs collapse. Conflicting duplicate IDs are rejected.
Every other outcome has code UNDETERMINED, null confidence and dependency=null.
Cause evaluation never changes the symptom or its episode/severity fields.

Rate support uses evidence.baseline and evidence.baselineVersion matching the symptom
baselineVersion. DROP_PP_GT compares baseline*denominator - 100*numerator against
configuredPP*denominator exactly. Absent or mismatched baselines cannot support.

`mappingEligibility(symptom,candidate,dependencySnapshot,policyJson)` returns
`{eligible:boolean,reason:string|null}` using the same authority/quality/time/count/
baseline gates, without requiring a BREACH or crossing the support threshold.
It supports mapped healthy recovery and does not assert a probable cause.

When raw evidence already carries non-null dependency metadata, both relation IDs,
topologyVersion and the complete from/to validity interval must match the selected
authoritative snapshot relation. dependency.scopeId names the dependency target
(relation.targetScope.id), not the primary symptom scope, consistent with Day1
CITY service evidence attaching a link dependency. Mismatch rejects with
DEPENDENCY_PROVENANCE_MISMATCH. Null raw metadata remains eligible for resolution
against the explicit authoritative snapshot.
