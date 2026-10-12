# Offline deterministic rule and lifecycle interface

All rules construct with `TraceLinkDomainPolicy` and expose
`List<JsonNode> evaluate(JsonNode context)`. Inputs pass the coordinator's
`TraceLinkInputs.validateContext` first. Transport returns independent
`TRANSPORT_PACKET_LOSS` and `TRANSPORT_LATENCY` results; overload is an annotation,
never a stand-alone incident. Roaming scopes use procedure REGISTRATION, DATA or
VOICE and return `ROAMING_REGISTRATION`, `ROAMING_DATA` or `ROAMING_VOICE`.
Mobile CITY scope may have null procedure and returns independent
`MOBILE_DATA_ACTIVATION` and `MOBILE_DATA_DNS` results. Target metric identity
declares the population; unsupported reference eligibility must remain null
primitives/insufficient evidence upstream.

`TransportRule.evaluateWithService(transportContext,serviceContext,candidates,
controls,dependencySnapshot)` retains the two original transport results and adds
one `TRANSPORT_SERVICE_<service anomaly kind>` result for each independently
evaluated roaming/mobile-data service result. Composite results carry optional
`serviceScope` with the complete service identity; lifecycle hashes include it.
Candidates must exactly equal chosen evidence in the validated service context,
including baseline center/version matching its lookup. Both utilization and
positive capacity must be fresh/valid. BREACH additionally requires utilization
at least 85% and one supported C-policy event-time dependency hypothesis.
Healthy service results recover only with an eligible mapping; absent/stale/
wrong-path evidence is UNKNOWN. Eligible service BREACH below85% is GRAY; healthy
service can be HEALTHY below85%. Gray service remains GRAY. Service children
remain independently visible and cannot be erased by this transport composition.

`TraceLinkRecoveryPolicy.advance(ObjectNode state, JsonNode evaluation,
JsonNode backlog)` mutates only the caller-supplied state and returns a transition
or null. Backlog uses the frozen context shape. A fresh empty state is allowed;
otherwise its stored key must equal dataset/namespace/domain/full scope/anomaly
kind/policy version. State stores key, end, bad, healthy, active, phase, candidate,
first, severity, sequence and canonical committed payload fingerprints as needed.
Caller persists each state independently. Fingerprints retain window ID/revision
history to reject conflicting replay even after later windows are committed.
Initial missing evidence is UNKNOWN state without an incident transition.
Candidates appear in state; only OPEN/UPDATE/UNKNOWN/RECOVERY return transitions.

Transitions contain schemaVersion=trace-link-lifecycle-v1, episodeId,
detectionId, sequence, phase, technicalState, severity, window and evaluation.
Identity hashes canonical full scope and policy; identical replay or unseen older
windows return null without changing state; conflicting committed replay rejects.
An interval gap counts toward neither streak and
emits UNKNOWN for active episodes. GRAY resets both streaks. Missing backlog
drain evidence converts otherwise healthy recovery to UNKNOWN; false drain is
GRAY. Breaches can still open while backlog is unknown. Severity ratchets until
recovery; the RECOVERY transition retains historical severity, and recurrence
starts a new episode and severity history.

Numeric comparisons are strict for trigger/critical and inclusive for recovery.
GRAY: loss (1,2]%, RTT (80,100]ms, success-rate drop (.5,1]pp. Overload candidate
is inclusive at 85%. Counter-product comparisons avoid rounded-rate boundaries.
Missing baselines make relative rates UNKNOWN; absolute loss/RTT remain eligible.
Lookup version is retained even when unavailable. Static fallback resolution is
the baseline registry's responsibility, never a hidden rule default.

New rule outputs include optional `baselineDeviation`: signed actual minus the
available seasonal/static center in the metric's original units, otherwise null.
Mapped service results preserve that diagnostic. No MAD scaling or model rank is
introduced; the unchanged Day 1 evidence remains separately validated.
