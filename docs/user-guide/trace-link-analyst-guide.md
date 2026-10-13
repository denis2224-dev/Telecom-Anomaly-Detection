# Trace Link analyst guide

This guide covers the isolated repair branch. It is not a release sign-off.
Receiving-owner review and authenticated connected rehearsal remain pending.

## Sign in and choose an investigation

Use the existing backend login. Analyst roles can inspect protected history;
SUPERVISOR or ADMIN is required for scenario commands. Session expiry returns
to login and clears private notification and scenario state. Sign in again and
reload server history rather than trusting a saved browser view.

**Trace Link history** opens versioned backend inventory and native persisted
metrics. **Service overview**, VoLTE/SMS detail and incident investigation retain
their existing service contracts. Their legacy geography can include labels
outside the active Trace Link reference inventory; those labels are retained
for historical investigation. Illustrative link/roaming previews are labelled
and do not establish current measurements.

## Inventory and history

1. Open Trace Link history. A successful active inventory contains ten cities
   and twelve links, with explicit declared directions. Use the map or city
   selection table; link direction buttons select the same focus.
2. Enter the recorded dataset ID and its available UTC `[from,to)` interval.
   The service currently supports at most 30 days of five-minute native history.
   The UI does not discover or invent dataset availability. Obtain the exact
   manifest and bounds from the dataset owner.
3. Choose a city and metric supplied by the service. The current candidate
   registers `voice.success_rate`, a ratio of summed VoLTE attempts. It is not
   an alias for the live producer's `volte.setup.success` population.
4. Select 5m, 15m, 1h or 6h independently of the selected period. Presets end at
   the selected exclusive `to`. Drag horizontally over the chart to zoom, or use
   the keyboard zoom/pan buttons. Zoom changes visible bounds only; reset restores
   the selected interval. Expand metric opens the same query and exact bucket
   table in a focus-managed dialog. URL context contains selections, no tokens.
5. Review the exact bucket table, units, numerator/denominator, quality,
   coverage, source resolution and policy/topology identity. Partial edge buckets
   are identified. Stale measurements remain visible with their recorded quality.
6. Download exact server CSV. It uses the same current visible query and server
   aggregation, without browser arithmetic or drawing-point export.

A measured zero remains zero. A rate with denominator zero is unavailable.
Missing intervals break the chart line. Null is not healthy, zero or recovery.
This read contract does not supply a baseline or source-event list; the UI does
not fabricate either. Transport, roaming, SMS and LTE panels show unavailable
history until approved metric definitions/read mappings exist. Inventory
capacity and measurement-source declarations are not observations. Inbound and
outbound partners remain separate even when both directions are selected.

Grafana has no configured, verified connection in this candidate. New-domain
incident drilldown also requires receiving mappings. Use existing incident
investigation for recorded VoLTE/SMS evidence; do not infer a new-domain incident
from a scenario name or a chart shape.

## Scenario commands

The Trace Link section is separate from legacy VoLTE/SMS controls. Both new
runtime flags default off. When the isolated runtime is enabled, five scenarios
are executable: transport overload, roaming registration failure, LTE DNS
failure, normal control and telemetry gap. Thirteen catalogue definitions remain
unavailable and cannot be submitted successfully by forging a command.

Choose the exact link/direction, country/partner/direction, or LTE DNS city.
Transport utilization alone is not packet loss or customer outage. DNS and EPS
bearer activation remain independent populations. Percent inputs allow two
decimal places; missing minute indices are unique, zero-based 0–7. The server
owns parameter defaults and the next-minute schedule.

Keep the same request ID and unchanged body after an uncertain response. A
conflicting retry is rejected rather than becoming another run. Independent
scopes can run concurrently. The server's status is authoritative; browser time
only helps interpret the displayed schedule.

- COMPLETED means local generation completed, not delivery or detector acceptance.
- STOPPED means telemetry stopped; it does not prove recovery.
- INTERRUPTED retains incomplete history and never resumes from minute zero.
- Planned minutes 6–8 are recovery traffic, not proof of technical recovery.

The local observation history is labelled generated. Kafka remains disabled;
offered and acknowledged counts are zero, while persisted/accepted counts are
unavailable. See the [runtime runbook](../runbooks/trace-link-scenario-runtime.md)
for configuration, storage limits and safe rollback.

## Notifications and incident work

Open Notifications to inspect recipient-scoped durable history. Startup history
does not toast. New HIGH/CRITICAL openings and escalations can produce one
restrained in-app toast after REST reconciliation; MEDIUM entries remain in the
list. Repeated hints and active same-origin tabs coordinate duplicate suppression.
The store keeps one bounded page, bounded seen IDs and its current newest-page
IDs. Historical page navigation must not re-toast an old opening.

Mark read changes only after a successful server response and subsequent REST
read. If it fails, retain the visible unread state and retry. The dialog supports
keyboard entry, Escape and focus return. Cause wording is `Possible cause` plus
the supplied confidence or `Cause undetermined`. Technical recovery does not
resolve analyst work. Incident links still require normal authentication.

The repair reads existing recipient records; automatic notification production
from detector/assignment transitions remains a separate receiving integration
gate. Browser push is unavailable and no permission prompt or service worker is
activated. No secure-origin push acceptance is claimed.

## Troubleshooting and handoff

400 means review command/query bounds, scope and versions; 401 requires sign-in;
403 means role/profile/session protection is insufficient; 404 keeps another
analyst's private run or notification undisclosed; 409 requires reviewing the
conflicting identity/version; 503 requires retrying the same uncertain command.
Do not reset a database or delete run history to clear an error.

Record the dataset, UTC interval, resolution, exact scope/direction, displayed
run UUID, status, and error code for the owner. Never include passwords, cookies
or CSRF values. Human wording, accessibility, capacity, Grafana and the two
authenticated release rehearsals remain pending; see the
[engineering evidence](../evidence/2026-10-12-david-trace-link-repair.md).
