# Day 3 receiving-owner handoffs

Prepared for review; these notes have not been sent externally and do not record receiving-owner acceptance.

## Ion — measurement and historical authority

Supply per directional-link window lost and expected packet counts (at least 1,000 expected packets), RTT sum and probe count (at least 30), contemporaneous effective capacity and utilization. A reduced capacity value does not identify a failed LAG member; provide member inventory, state and event-valid membership to investigate that hypothesis.

Supply roaming success/eligible-attempt counters independently for registration, data and voice, keyed by partner, country, direction and procedure. Preserve the country/partner catalogue and historical path mappings; source IDs and dependency relation validity are required. Finalized roaming SMS needs its own finalized population before detection can be added.

Supply EPS eligible activation successes/attempts independently of qualifying post-activation DNS successes/attempts. Generic IP activation, DNS request totals and inferred rate samples cannot stand in for these populations. Supply any required backlog measurement and a documented drain predicate.

Every measurement needs source observation time, complete 300-second coverage, revision, dataset and namespace, units/metric policy and event-valid topology version. Freshness is checked at explicit evaluation time with an inclusive 600-second limit. Historical CSV timestamps do not prove source freshness. Provide maintenance intervals and demonstrated drainage for queue/delay contamination before fitting those populations.

## Denis — envelope and stable identities

Use the additive offline context/rule/cause contracts and examples under `docs/evidence/assets/trace-link-day2/`. Day 1 evidence v1 is unchanged. Carry richer baseline provenance and rejected-cause diagnostics in the separate proposed envelope; do not attach rejected candidate evidence as an accepted dependency.

Partition lifecycle state by dataset, namespace, domain, complete scope and anomaly kind, with policy version. Mapped transport service episodes additionally carry `serviceScope` so different partners/procedures cannot collide on the same link. Canonical scope, first eligible breached window and policy determine the episode ID; sequence and source-window identity determine detection IDs. Preserve replay fingerprints and reject conflicting committed payloads. The offline state owner is responsible for persistence in Day 3.

Expose `UNDETERMINED` with null confidence/dependency when attribution lacks support. `SUPPORTED_HYPOTHESIS` has MEDIUM confidence and remains a hypothesis. A cause-only counterfactual does not change the symptom, severity, scope or episode. Proposed mapping and receiving-service acceptance remain Day 3 gates; no route or migration is activated by this work.

## David — interpretation and presentation

High utilization alone is an overload candidate. A rule verdict is BREACH, HEALTHY, GRAY or UNKNOWN; inadequate volume, coverage, freshness or baseline availability yields UNKNOWN with a reason. Missing optional measurements do not erase a separately eligible impairment. Report insufficient evidence without claiming healthy service.

Opening needs two adjacent eligible breached windows. Technical missing data produces UNKNOWN while retaining the active episode and highest severity. GRAY keeps the episode but resets recovery progress. Recovery needs three adjacent eligible healthy windows and any required drained backlog; the event is RECOVERY and its technical state is RECOVERED. Opposite roaming direction is a cause control, not a condition for preserving the target symptom.

Use the lifecycle and integrated exports as examples. Do not display model anomaly rank because this offline policy has no model. Keep cause confidence distinct from severity.

## Stanislav — reproduction and acceptance

Run `scripts/verify-trace-link-day2.ps1` from the repository root with explicit Python, JDK 21 and extracted reference-pack paths. Install `requirements-dev.txt` plus `services/ml-service/requirements-ml.txt` in the selected environment. The Python timezone source is pinned `tzdata==2026.2`; Java records its actual bundled TZDB version. Node and Docker with `postgres:16.4-alpine` are needed for fresh Day 1 four-language parity. The PostgreSQL container is disposable, has no published ports and uses `--network none`.

The entrypoint runs contract/root-Python/ML-Python/processor verification, legacy and geographic parity, fresh four-language arithmetic, independent baseline fitting parity, two full history builds and hash comparison, then validates/publishes evidence. Published compressed baselines are checksum verified before Java loading. The evidence manifest records source revision, runtime versions, UTC interval, hashes and Java skips; the report records Python counts and actual command failures.

Before enabling live delivery, agree persisted-window/replay semantics, register workers explicitly, test Kafka and incident-service contracts, confirm event-valid mappings and source quality, and obtain receiving-owner acceptance. These remain Day 3 work.
