# VoLTE / SMS mentor service assurance

Owner: Zavtoni Ion. Base: `55e373a2a18eac56f9726c8f7b0ea307bde0a9ce` (PR #39 merged).
This is a synthetic teaching/service-assurance environment, not a production operator NOC.

## Repository map

Cached Graphify query/path navigation followed Angular `TelecomClient`, service overview/history,
incident investigation, `ServiceOverviewController` / `ServiceController`, persisted detection
evidence, processor `ServiceFeatureBuilder`, generator continuous/scenario execution and
`TopologyCatalog`. No Graphify repair is included.

## SUPPORTED NOW

| Mentor requirement | Verified source and boundary |
| --- | --- |
| Service views | Session-authenticated `/api/services`; only VoLTE and SMS. |
| VoLTE KPIs | `ServiceFeatureBuilder`: cssrPct, eligibleAttempts, sip503Ratio, sip503Count, rrcSrPct, bearerSrPct, packetLossRatio, imsCpuPct. |
| SMS KPIs | p95DeliveryMs, deliverySrPct, deliveredMessages, queueDepth, oldestPendingAgeSec. |
| Contextual baseline | Persisted KPI baseline and baselineVersion; UTC hour-of-week behavior belongs to processor, not UI. |
| History | Persisted minute windows; PR #39 healthy history and continuous telemetry. API requests remain at most 24 hours, size at most 100. |
| Episodes | OPEN, UPDATE, RECOVERY, UNKNOWN detections, authoritative technical state, workflow state, sequence and timestamps. |
| Explanation | probableCause, LOW/MEDIUM/HIGH causeConfidence, evidence summaries/node IDs/source event IDs, recommendedChecks. |
| Impact | VoLTE extraFailedAttempts; SMS affectedDeliveredMessages/pendingMessages; uniqueSubscribers is always null. |
| Investigation | Assignment, comments, INVESTIGATING, recovery-gated RESOLVED, notes, optimistic locking and audit timeline. |
| Scenarios | VOLTE_IMS_OVERLOAD, SMS_QUEUE_DELAY, NORMAL_CONTROL, TELEMETRY_GAP. |
| Live notifications | Session-bound `/api/incidents/stream` emits `incident.upsert` only after transaction commit. Heartbeat every 15 seconds; at most 128 streams globally and four per session. No replay buffer. Logout/session expiry closes the stream. |

## Delivered dashboard exposure

| Mentor requirement | Recovered starting point | Implemented exposure |
| --- | --- | --- |
| Dense overview | Large introductory panel; no primary KPI/delta/trend | Compact service cards, text health/freshness, baseline delta, latest minute and trend between measured minutes. |
| VoLTE correlation | CSSR chart exists; supporting KPIs lack cards/charts | Canonical KPI cards and access/IMS/transport/sample charts. |
| SMS actual vs baseline | SMS history is a table; fixture detail skips loading history | P95 chart, delivery/queue/age/volume panels; honest fixture history. |
| Dependency view | ServiceScope provides dependencyIds; topology provides source IDs | Source plus dependency cards using actual scope dependencies; no link-alarm inference. |
| Anomaly story | Incident interval shading; source evidence is collapsed | Persisted detection phase bands, recent incident explanation/evidence and impact. |
| Missing data | Existing chart gaps mostly honest; overview uses static client thresholds | Missing/zero distinction, UNKNOWN text, evidence-authoritative episode state; no static UI anomaly thresholds. Current health uses all current scope episodes, independently of the selected historical interval. |
| Live operation | No client SSE or periodic refresh | Session-bound notification client, reconnect REST reload, one 30-second refresh timer and retained history. Stream events are coalesced; fixture mode opens no stream. |

## NOT CURRENTLY MODELLED

| Mentor concept | Boundary |
| --- | --- |
| Full physical/network path and protection | dependencyIds are monitored service relationships, not a physical Moldova topology, link state or rerouting model. |
| SMS transport health | TRANSPORT-A is in topology, but SMS continuous telemetry emits only SMS-ADAPTER and SMSC-A. Show UNKNOWN / NO CURRENT MEASUREMENT. |
| Subscriber identities | Aggregate attempts/messages cannot identify customers; show “Unique subscribers: not available in aggregate demo”. |
| CS voice concepts | No Q.850, MSC/MGW, TCH, ISUP, call-duration distribution or call-drop measurements. |
| Broader SMS concepts | No independent MO/MT, SMSC CPU, MAP/SIGTRAN failures, paging, partner-route success or P99. |
| Other domains | Mobile Data, roaming, video, gaming/cloud apps and generic RAN/network anomaly engines are excluded. |

## Implementation rules and acceptance

Use existing routes, Angular/CSS/SVG and REST contracts. Charts draw only real baseline fields,
break actual lines across missing windows, and mark phases from persisted detections. Healthy
samples never substitute for episode recovery. Correlation is supporting context; cause remains
a backend hypothesis. Topology nodes without a measurement are unknown, even without alarms.

48h history consists of two legal <=24h slices, paginated, deduplicated by window ID/start and
sorted. Refresh current summaries separately; fetch only the history tail for advancing ranges.
Historical explicit ranges remain fixed. Logout/destruction must release subscriptions, timers
and EventSource; no auth tokens are stored or extracted.

The private generator API can verify source publication and state transitions without changing
browser authentication. It does not verify the authenticated public simulator command ledger,
the user's identity/role, authenticated REST serialization or live browser SSE delivery.
Controlled browser tests and backend integration tests must retain that distinction.

Focused rendering/history/live lifecycle tests, existing workflow tests and browser suites must
be recorded with exact outcomes. Real integration must use human browser authentication and
preserved Docker volumes. Controlled browser data is explicitly distinct from actual telemetry.
