# Trace Link evaluated-domain adapter v1

Owner: Moroz Denis. Producer owner: Zavtoni Ion. Planning work package: 12 October 2026.

Reserve incident-service V009__trace_link_domain_incidents.sql after the merged V008 primitive projection. Reserved by this backend work package on main f7364d3 after detecting the merged V008 reservation. Cross-owner producer acceptance remains pending and does not enable delivery. V005–V007 and all existing legacy migrations remain immutable.

Transport, roaming and LTE data use a separate five-minute evaluated-detection contract, topic telecom.trace-domain.detections.v1, immutable trace_domain_detection facts and a contiguous trace_domain_incident projection. Kafka key is episodeId. The adapter does not consume evaluator scenario truth or create decisions from ML-only inference.

Stable identity is lowercase SHA-256 over compact UTF-8 JSON arrays. Episode inputs are datasetId, domain, metric, metricVersion, scopeType, scopeId, policyVersion, topologyVersion and canonical UTC firstObservedAt. Detection inputs are episodeId, canonical UTC windowStart, phase and metricVersion. A revision is a separate attribute; changing a committed detection body requires a new identity/namespace design, never a rewrite.

The initial metric registry implements link.utilization, registration.success_rate and data.session_success_rate, alongside the existing voice.success_rate. Transport primitives are integrated Gbps-seconds; roaming and city data primitives are successes and attempts. Do not reverse-fill link data or derive packet counts from scalar percentages.

The projection detail route is GET /api/v2/domain-incidents/{id}. It exposes technical state, severity, version and immutable evidence with dataset, metric version, policy/topology and synthetic provenance. Cause is UNDETERMINED. The legacy incident assignment/workflow and notification foreign keys remain applicable to VoLTE/SMS incidents. New-domain workflow, notification linking and live hints require their own compatible contract increment.

TRACE_DOMAIN_DETECTIONS_ENABLED defaults to false. The Day 3 integrated gate is incomplete until the producer emits this agreed contract with matching persisted metric windows, one positive case and one negative control per new domain. Contract examples only validate shape and hashing.

The Day 3 backend guide implements protected series and transport/roaming snapshots. The planned overview route remains unavailable until its multi-domain summary semantics and implementation are reviewed; keep its implementation status planned. Exact CSV and Web Push remain the next assigned work package.

The merged primitive consumer metric volte.setup.success and its source-quality/provenance DTOs remain supported. The reference CSV adapter records sourceQuality/sourceCoverage as null, source slot coverage separately, and qualified INSUFFICIENT_EVIDENCE responses; importing the archive never upgrades unknown source quality to healthy. These imported facts cannot support positive/recovery domain decisions.
