# Fresh authenticated live acceptance — 2026-10-02

**Overall acceptance verdict: ACCEPTED / PASS.**

Provenance: the user supplied the authenticated live verification results as
authoritative evidence for the final repository audit. This document records that evidence; it does not claim a second live
acceptance performed by the repository auditor. It supersedes the acceptance
blockers in the October 1 audits and the earlier October 2 connectivity audit.

| Acceptance check | Result |
| --- | --- |
| AUTH | PASS |
| `/api/auth/me` | PASS |
| Scenario Runner | PASS |
| VoLTE IMS overload | PASS |
| SMS queue delay | PASS |
| Telemetry gap | PASS |
| Incident detail | PASS |
| REST reconstruction | PASS |
| SSE live updates | PASS |
| SSE intentional reconnect | **NOT DIRECTLY VERIFIED** |
| Generator connectivity fix | PASS |
| UI analyst-workflow wording fix | PASS |

Authenticated identity: `ion-supervisor`, role `SUPERVISOR`,
`/api/auth/me` HTTP 200. No session material is retained.

## Generator

The host incident-service uses `http://127.0.0.1:8081`. Port publication is
loopback only. Generator readiness was UP; zero `GENERATOR_UNAVAILABLE` errors
were observed in the acceptance runs.

## VoLTE IMS overload

Run ID: `f29c61a8-0e9e-4462-9d5d-f1f3b3a8fd41`.
Scope: `VOLTE-MD-CENTRAL`. Seed: 42.

Normal CSSR was 99.3%; degraded CSSR was 94.0%. SIP 503 count reached 55 and
IMS CPU reached 97%. The incident persisted, RECOVERY was observed, technical
state became RECOVERED, and the run COMPLETED.

## SMS queue delay

Run ID: `967d4e1a-6e02-498e-8690-e2baa4b73802`.
Scope: `SMS-MD-ROUTE-A`.

P95 reached 58,821 ms, queue depth reached 335, and oldest pending age reached
117 s. The incident persisted; the queue drained to 0 and P95 returned near
normal. Technical state became RECOVERED and the run COMPLETED.

## Telemetry gap

Run ID: `50058776-6490-403a-95d0-c56a8cd6c9c7`.

Freshness and quality became MISSING; service health became UNKNOWN.
Observed values, numerators and denominators were null. There was no zero-fill,
false NORMAL verdict or false recovery.

## Workflow and live updates

The UI says "open analyst incidents" and separates analyst workflow from
technical service health. Technical recovery may coexist with analyst OPEN.
Incident detail, REST reconstruction and SSE live updates passed.
SSE intentional reconnect remains **NOT DIRECTLY VERIFIED**. Automated unit and
controlled-browser reconnect coverage does not upgrade this live status.

## Durable screenshots

Five original PNG screenshots were visually inspected and copied from the temporary evidence directory into [repository assets](assets/service-assurance/README.md).
They contain application UI, aggregate telemetry and non-secret run/episode IDs.
No login screens, browser storage, network captures, auth headers or raw logs
are retained. Screenshot filenames alone are not proof of a scenario phase;
the manifest describes the visible content and limits of each image.

See [the final repository audit](2026-10-02-final-repository-audit.md) for file
review, final test results, commit grouping and the separate `scripts/up` decision.
