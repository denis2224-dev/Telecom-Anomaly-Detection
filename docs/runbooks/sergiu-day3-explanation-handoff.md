# Serghei Day 3 queue and display handoff

Status: offline acceptance contract prepared for Denis and David review. This document does not claim owner approval, live endpoint acceptance or shared G3 completion.

## Executable examples

`contracts/fixtures/geography/day3-queue-display-cases.json` is a dedicated review artifact, not a detection or API wire schema. Its IDs and receipt references are explicitly offline examples. The pinned context is topology `2-geography-g1`, catalogue `1-geography-g1`; resolve each real incident using its captured versions and binding. Do not hash city labels, geographic coordinates or projection values into episode identities.

Run `python -m unittest discover -s tests -p test_day3_queue_display_cases.py`. The tests independently calculate signed deviation, technical denominators, estimated failure counts and nearest-rank SMS p95, and verify queue order and retained provenance. They validate fixture meaning; they do not call production SQL or render the dashboard.

## Denis: priority and retained provenance

Freeze this ordering in the owned read projection: fresh ONGOING first; UNKNOWN or stale second; RECOVERED last. Within fresh ongoing, use severity descending. The fixture chooses fixed service grouping (SMS then VOLTE) for equal severity, then comparable impact descending inside that group, missing impact last, oldest `firstObservedAt`, and incident UUID ascending. For uncertain/stale and recovered, use oldest `firstObservedAt`, then UUID. Historical severity/impact must not compete as current values. Analyst status is not a sorting input.

The mixed-service grouping is a proposed deterministic default requiring Denis review. It does not assert that SMS has higher operational priority. A comparator that compares impact only for same-service pairs but age for mixed-service pairs can be nontransitive; implement a total order instead. Compare VoLTE extra failed attempts only with VoLTE estimates and SMS affected delivered messages only with SMS counts. Neither pending queue counts nor milliseconds are interchangeable with affected delivered messages. Preserve the read model's freshness policy; this handoff introduces no detector freshness threshold.

At implementation-time source inspection, `/api/operations/priority` is marked `x-implementation-status: proposed` in canonical OpenAPI, with no matching priority controller or SQL in incident-service. Existing repository/list routes order by `detectedAt` descending. Do not report them as passing the priority contract. Denis should expose the owned bounded, protected endpoint and verify the exact fixture order across pagination, repeat reads and equal timestamps. Generated dashboard types should follow the canonical API after implementation.

`IncidentProjection.from` already separates UNKNOWN `currentImpact=null` from `retainedImpact` and marks it STALE. However, `impactSourceDetectionId` and `impactWindowEnd` currently identify the UNKNOWN record itself. The detector's `HISTORICAL_IMPACT` and `HISTORICAL_SEVERITY` evidence identifies the true original window and source receipts; historical severity can come from a different window than historical impact. Resolve the original evaluated immutable detection from evidence/history, keep these two provenance chains distinct, and publish accurate origin fields in Denis's projection. For legacy state without origin metadata, display unavailable provenance; never substitute the UNKNOWN window. Do not reinterpret retained severity as currently measured or change the strict detection wire shape for this handoff.

## David: identical meaning in map, queue and detail

Use D1-D4 as display oracles: `90%` observed against `98%` baseline is `-8 pp`, not `-8%`; the denominator is `attempts - userOutcomes`, and extra technical failures are `max(0, baseline - observed) * denominator / 100`. D1 has 900/1000 eligible attempts and an estimated 80 extra failures. Improvement has a positive signed deviation and zero extra failures. Null baseline retains the measured observed subset but makes comparison/estimate unavailable. PARTIAL coverage must stay visible; it does not establish full-city health or redefine detector eligibility.

SMS D4 has true minute nearest-rank p95 of 30,000 ms from 30 completed samples, 5,000 ms baseline and a 6x delay ratio. Geography backend already calculates `deltaPp` and `delayRatio`; shared `metric-presentation.ts` and `dashboard-geography.ts` currently display SMS differences in ms. A difference can remain supplementary, but the required ratio must be displayed consistently and must never be labelled as percentage points. Show sample count beside p95; do not average p95 values across windows/cities.

Keep `uniqueSubscribers=null` as “Unavailable”; attempts and messages are not distinct subscribers. Show technical and analyst states independently, including RECOVERED + INVESTIGATING (awaiting analyst resolution) and ONGOING + RESOLVED (still technically ongoing). The current incident detail already separates these states and labels retained impact. Preserve that behavior.

For UNKNOWN, show current comparison unavailable and label severity/impact historical with their original window and receipt references. Current evidence timeline includes a retention notice and source evidence; detail's impact window currently inherits the projection mismatch above. Queue, map and detail must agree on service, scope, pinned versions, units, freshness and measurement origin. A blank queue never establishes health.

## Acceptance record

Complete owner review by recording integration SHA, fixture IDs, API request/response captures and browser evidence. Validate one affected city and at least two unrelated cities. For UNKNOWN, capture the original evaluated detection plus the new UNKNOWN decision and demonstrate that displayed provenance refers to the original measurement. G3 remains pending until live map/queue/detail and owner reviews agree; local fixture tests provide offline evidence only.

## Live validation result and blocking dependency

The disposable-stack validation observed 80 normal receipt-to-KPI-to-coverage-to-protected-API points across all twenty geographic service scopes, with zero browser page errors. This demonstrates the normal geographic read path. It does not establish fault episode traces, geographic simulator acceptance or shared G3 acceptance.

The authenticated request `POST /api/simulator/scenarios/VOLTE_IMS_OVERLOAD` with `scopeId=VOLTE-MD-CHI` returned HTTP 400 `INVALID_SCOPE`. `ScenarioCommandService.validate` still permits only the two legacy scope literals, and the event-generator's `ScenarioExecutionService.validate` has the same restriction. Ion and Denis must integrate city-target scope validation through the active authoritative registry in both layers, preserving service compatibility, enabled-supervisor authorization and request replay behavior. Enabling only one layer will leave the fault path blocked. Serghei's detector work does not bypass these boundaries.

Reproduce against a disposable stack with `node scripts/day3-geographic-live.cjs <private-env-file> <output-dir>`. Supply `DAY3_USERNAME` and `DAY3_PASSWORD` in a private file used only for authentication; an optional `DAY3_BASE_URL` selects the running stack. The runner keeps the browser session in memory and does not commit credentials or cookies. Store reviewable API/browser artifacts separately from that private file. Once the owner dependency is fixed, rerun the city fault, normal-control and telemetry-gap profiles and retain their actual source, window and detection references before claiming fault-trace acceptance.
