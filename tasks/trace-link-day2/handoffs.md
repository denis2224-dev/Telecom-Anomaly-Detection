# Day 3 receiving-owner handoffs

Prepared for review. Stanislav has **ACCEPTED_OFFLINE_REPRODUCTION** for Day 2 infrastructure, Grafana provisioning, reporting SQL views, and query parity at the reviewed commit recorded below.

| Receiver | Reviewable material | Receiver reproduction / review gate |
| --- | --- | --- |
| Ion | Measurement populations, directional link windows, roaming success counters, EPS/DNS populations | PENDING_REVIEW; measurement and historical authority handoff. |
| Denis | Additive offline context/rule/cause contracts, lifecycle partitioning, cause attribution diagnostics | PENDING_REVIEW; envelope and stable identities handoff. |
| David | Rule verdict states, episode lifecycle, recovery rules, cause confidence vs severity | PENDING_REVIEW; interpretation and presentation handoff. |
| Stanislav | Grafana provisioning, `reporting` SQL views, `trace_link_reader` role, parity matrix, contract checks | **ACCEPTED_OFFLINE_REPRODUCTION**: independently verified Grafana datasource/dashboard provisioning, SQL views/boundaries in `incidents_db`, 6-case parity matrix, root and ML test suites, and contract validation. |

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

Stanislav has **ACCEPTED_OFFLINE_REPRODUCTION** for Day 2 infrastructure and Grafana observability deliverables.

| Receiver | Reviewer identity | UTC time | Checkout SHA | Commands | Output SHA-256 hashes | Comments / disposition |
| --- | --- | --- | --- | --- | --- | --- |
| Stanislav | Stanislav / GitHub `stasikkk777` | 2026-10-14T18:54:30Z | `cfc3e98d4ff7c0d1161a74fd230fe9f756ba3423` | `python3 scripts/check-trace-link-grafana`, `python3 scripts/check-trace-link-parity.py --matrix`, `python3 scripts/check-contracts.py`, `python3 -m unittest discover -s tests -v`, `python3 -m unittest discover -s services/ml-service/tests -v` | See output hash table below | **ACCEPTED_OFFLINE_REPRODUCTION**: verified datasource `trace-link-pg`, 14-panel dashboard `trace-link-overview`, `reporting` schema views, least-privilege `trace_link_reader` role with revoked `app` schema access, 6 core parity matrix points, and 258 total passing tests (184 root + 74 ML). |

### Output Hashes

| Artifact | SHA-256 |
| --- | --- |
| `infra/observability/grafana/sql/trace-link-views.sql` | `04322ef65dede0ed8ceff4de223a3750327cd5078671bf71c36b5cb2890edbfc` |
| `infra/observability/grafana/dashboards/trace-link-overview.json` | `f75988a7dc17ebc08dabafd42432aeaf9e2aaa11a43c849c04fb62a06a45acd8` |
| `infra/observability/grafana/provisioning/datasources/trace-link.yml` | `0e37478ba2ab3e647bef896bf66d2e5e610364f6d34ca2e926b5190b9e36749b` |
| `docs/evidence/2026-10-11-trace-link-grafana-parity.md` | `90518f9df079c882aa9ea483bbad34eefcbfafae48a07f7943c5b5ddf91fc54a` |

Before enabling live delivery, agree persisted-window/replay semantics, register workers explicitly, test Kafka and incident-service contracts, confirm event-valid mappings and source quality, and obtain receiving-owner acceptance. These remain Day 3 work.
