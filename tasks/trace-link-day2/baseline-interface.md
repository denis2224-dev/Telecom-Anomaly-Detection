# Frozen baseline interface

Pure Java `SeasonalBaselineEstimator(JsonNode policy).fit(JsonNode request)` and
`TraceLinkBaselineRegistry(JsonNode artifact, JsonNode policy).lookup(JsonNode key,
JsonNode eventContext)` return JSON. No runtime registration. Python exports
`fit(request, policy)` with the same artifact shape; `build(reference_root, policy)`
extracts independently verified reference history.

Key fields: `datasetId,domain,metricId,scope,unit,metricPolicyVersion,topologyVersion`.
Scope is the unchanged Day 1 scope object. Event context requires `windowStart`
(UTC Z) and `evaluationFrom` (UTC Z). Lookup adds local slot and weekday class.
It returns exactly the BaselineLookup fields in interface.md. `mad` is raw MAD;
zero MAD is valid. Consumers use signed pp differences, never divide by MAD.

Fit request fields: `trainingFrom,trainingTo,evaluationFrom,sourceManifestSha256,
topologySnapshotSha256,exclusions,samples`. Each exclusion has UTC `from,to` and
`reason` (DECLARED_INJECTION or MAINTENANCE); each sample has `measurementId,key,
from,to,value,coverage,eligible`. Identical identities deduplicate; conflicting
identities reject. Samples beyond
training bounds fail. Structural historical eligibility does not claim freshness.
Exclusions overlap half-open windows and apply globally for conservative fitting.
No scenario identity/label, source-generated baseline or future outcome is accepted.

Artifact fields: `schemaVersion,baselineVersion,policyVersion,trainingFrom,trainingTo,
evaluationFrom,timezone,slotMinutes,estimator,estimatorConfiguration,timezoneRulesVersion,sourceManifestSha256,
topologySnapshotSha256,topologyVersion,exclusions,sampleCount,expectedCount,
acceptedCount,excludedCount,missingCount,invalidCount,cells`. Counts separate
unique supplied rows, expected UTC rows, accepted eligible rows, excluded rows,
missing/null rows and structurally invalid/ineligible rows. Invalid shapes fail
rather than being silently skipped. Cell fields:
`key,slot,weekdayClass,center,mad,coverage,sampleCount,distinctDays,expectedDays`.
Only eligible cells (>=3 local dates and coverage>=.90) are emitted. Coverage uses
the actual training UTC calendar and removes declared excluded dates/slots.
One per-date contribution is the median of repeated DST occurrences. Nonexistent
spring civil slots are not expected. Artifact bytes contain no generated timestamp.
The complete estimator policy is embedded as `estimatorConfiguration` and must
equal the registry's loaded policy. Samples and learned centers are nonnegative;
percent values also have an upper bound of 100. `timezoneRulesVersion` records
the actual IANA rules version. Python reads zone bytes from pinned tzdata 2026.2
(IANA 2026b), independently of host zoneinfo; Java records its runtime TZDB version.
Parity compares cells and full configuration, reporting any runtime TZDB version
difference explicitly. `TraceLinkBaselineRegistry.load(path, expectedSha256, policy)`
checks published bytes before gzip decoding, JSON parsing and schema validation.

Default peers are empty. Explicit peers must share dataset/domain/metric/unit,
metric policy/topology, namespace and non-ID scope dimensions. Static99 applies
only roaming registration/data/voice success and mobileData activation/DNS success.
Static entries have MAD0, coverage0 and reason STATIC_DEMONSTRATION_POLICY.
Unavailable returns null center/MAD/sourceScope and explicit reason. Incompatible
dataset/topology or pre-training evaluation never falls back to static.

History split: Sep10 09Z–Sep30 09Z training; Sep30 09Z–Oct5 09Z validation;
Oct5 09Z–Oct10 09Z held-out. The builder fits only transport traffic/utilization/
RTT snapshot, roaming registration/data/voice success, and mobile-data throughput.
Missing target loss/RTT/DNS/EPS populations are never reconstructed. Injection
intervals come from the checksum-pinned scenario CSV, no guessed tails; maintenance
is explicitly empty. No SMS queue/delay metric is fitted, so queue drain tails are
outside this artifact. Topology snapshot is a reference-only fixed declaration
covering the manifest interval, hashed from city/link/partner inventory file hashes;
it never references today's runtime geography. Its version is the explicitly
declared synthetic `trace-link-md-topology-v1`, compatible with offline contexts.
CLI output ending `.gz` publishes deterministic gzip (`mtime=0`) and records
compressed and uncompressed SHA256/byte sizes in the report. No fitted timestamp
is embedded. Artifact schema is validated before publication.

Exclusion diagnostics count declared canonical UTC grid slots even when their
source rows are absent. Missing counts cover eligible grid slots only. Distinct
measurement IDs for the same key and UTC window are rejected; identical replay
is deduplicated. DST folds remain separate UTC windows before local-date reduction.
