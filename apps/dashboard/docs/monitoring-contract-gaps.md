# Monitoring extension

The frontend keeps the existing scenario API, authentication, scopes and incident workflows.
VoLTE IMS CPU overload and SMS queue delay remain real server commands. The other scenario
choices produce bounded, seeded, eight-minute local previews. They do not create persisted
incidents or change live service status. Roaming is explicitly illustrative until its API exists.

## Backend support required

- Agree new scenario identifiers, compatible scope validation, affected city/node/link/country
  identifiers, deterministic generation and the existing run lifecycle before extending the API.
- Publish IMS node CPU, setup duration and link utilization measurements with units, baselines,
  UTC windows, topology membership and missing-data semantics. Current CPU values are service
  context; they do not establish the health of an individual IMS node.
- Publish inbound/outbound country scope and registration evidence, active roaming user counts,
  eligible attempts and successful outcomes per country. Subscriber counts need real distinct
  user accounting; call attempts cannot substitute for subscribers.
- Publish SMS average/p95 delay and delivered-late counts with their eligible delivered sample.
  Received, delivered, pending and lost totals must conserve messages across recovery.
- Extend detector types and persisted evidence to identify affected nodes, links and countries.
  Healthy KPI points alone must not resolve technical or analyst state.
- Supply approved intercity link IDs, endpoint city IDs, throughput/capacity units, utilization,
  UTC observation times, freshness and topology version. The dashboard's seven sample routes
  are illustrative overlays and are never merged into the approved topology catalogue.
- Supply separate failure-cause counters before showing a ranked cause breakdown. Current
  CSSR numerator/denominator describe setup outcomes; SIP 503 is an independent signal.

## Frontend verification

`npm test` and `npm run test:e2e -- --workers=2` run from `apps/dashboard`.
The preview regression checks seeded profiles, queue conservation, country isolation,
SMS/VoLTE navigation, responsive graphs and full-screen incident sheets.

CPU 85% and link 90% markers in previews are display references, not production detector rules.
The live CPU chart also labels its 85% guide as a reference. Unsupported live measurements remain
unavailable. The existing SMS chart is accessible through “Show SMS graph” and the SMS page.
