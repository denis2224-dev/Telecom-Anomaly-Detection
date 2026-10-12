# Frozen Day 2 offline interface

All Java components are pure, explicitly constructed, and accept/return Jackson
JsonNode. No Spring registration, database, Kafka or wall-clock reads. JSON fields
are strict; contexts pass TraceLinkInputs.validateContext before evaluation.

Context fields (all required): schemaVersion="trace-link-evaluation-v1",
datasetId, namespace="trace-link-reference-v1", domain=TRANSPORT|ROAMING|MOBILE_DATA,
scope (unchanged Day1 scope object), window (unchanged Day1 window object), revision
(nonnegative integer), topologyVersion, evaluatedAt (UTC Z), evidence (Day1 v1
items), baselines (metricId -> BaselineLookup), backlog={required:boolean,drained:
boolean|null}. Identity and evidence context must agree. No truth/label fields.

BaselineLookup fields: status=DIRECT|PEER|STATIC|BASELINE_UNAVAILABLE, center and mad
(number|null), sourceScope (scope object|null), baselineVersion, policyVersion,
trainingFrom, trainingTo (UTC Z), coverage (0..1), reason (string|null).
Unavailability retains the requested artifact version, with null center/MAD.

Each rule's evaluate(JsonNode context) returns List<JsonNode>; the domain policy
is supplied through its constructor. Common result required fields:
schemaVersion="trace-link-rule-result-v1", datasetId,namespace,domain,scope,window,
revision,topologyVersion,evaluatedAt,policyVersion,baselineVersion,anomalyKind,
verdict=BREACH|HEALTHY|GRAY|UNKNOWN,reason,severity=null|HIGH|CRITICAL,
overloadCandidate (boolean),evidenceIds (strings),anomalyRank=null.
New outputs also emit optional baselineDeviation (signed actual-minus-lookup-center
in original metric units, or null); older/manual results may omit it.
Mapped transport service results additionally require serviceScope (complete
roaming/mobile-data scope); it participates in lifecycle identity.
baselineVersion is the lookup version, or trace-link-seasonal-v1 when no lookup
is required/available. Relative rate rules use signed pp differences; no division
by baseline. Context retains rich lookup metadata; evidence v1 is not extended.

Coordinator owns TraceLinkInputs.java and context/result schemas + Python validator.
Worker B owns TraceLinkDomainPolicy.java: constructor(JsonNode), static load(),
json() returning immutable copy. Worker A owns estimator/registry JSON interfaces;
lookup(key,eventContext) returns the shape above. Worker C owns cause input/output
schemas and TraceLinkCausePolicy.evaluate(symptom,candidates,controls,
dependencySnapshot,policyJson). C must document its candidate/dependency shapes
before coordinator writes integration fixtures. No inter-worker edits.
At review, coordinator explicitly delegated baseline-boundary fixes to A; all
other ownership remained separate. The baseline boundary accepts an explicit
baseline policy override for configured static values, including zero.

Defaults: window300s,open2,recover3,coverage1,age<=600s; rate min100,drop>1pp,
recovery<=.5pp,critical>2pp; loss min1000,>2%,<=1%,critical>4%; RTT min30,
>100ms,<=80ms,critical>200ms; overload>=85%. HIGH ratchets to CRITICAL until
recovery. Gap-ending window counts toward neither streak. Episode identity keys
include full dataset/namespace/domain/scope/anomalyKind and policy version.

Fixtures never pass expected outputs to evaluators. Missing target packet counts,
RTT sample counts, EPS eligibility and qualifying DNS counts remain insufficient.
Reference-history fitting may use explicit structural historical eligibility,
preserving unknown freshness; this never authorizes runtime freshness.
