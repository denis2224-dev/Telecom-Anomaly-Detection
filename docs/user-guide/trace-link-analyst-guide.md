# Trace Link analyst guide

## Sign in and choose the investigation

Open the application login page and continue to organization sign-in. The application does not collect your password. An enabled analyst account and an application role are required. Supervisors can run supported scenarios and assign work to other enabled analysts.

Open **Trace Link history** in Workspace for the versioned reference inventory and persisted metric queries. The older service overview and service investigation retain the existing VoLTE/SMS catalogue. Historical or legacy labels do not establish membership in the active Trace Link inventory.

An unavailable inventory is an explicit error with a retry action. It does not display guessed cities, connections or history. Ask the backend owner to register the approved dataset and activate its catalogue before attempting a populated investigation.

## Select the shared context

Enter the recorded dataset ID and UTC bounds ending in `Z`. The start is included and the end is excluded. The longest selected period is 30 calendar-length intervals of 24 hours. Presets cover 6h, 24h, 48h, 7d, 14d and 30d relative to the selected end.

Choose resolution independently: 5m, 15m, 1h or 6h. Changing period preserves resolution. Select UTC or Europe/Chisinau for chart presentation. The latter uses the IANA timezone rules, including offset transitions; requests remain UTC.

Zoom changes the visible interval, preserving the selected period. Use the horizontal brush, Zoom in, Zoom out, Pan earlier/later, or Reset zoom. Expanded charts use the same context. The URL carries public query identifiers and absolute timestamps, never session credentials. Invalid URLs and impossible calendar dates are rejected.

## Cities and directional links

Once available, the active Trace Link catalogue contains Chișinău (CHI), Bălți (BAL), Cahul (CAH), Ungheni (UNG), Orhei (ORH), Soroca (SOR), Comrat (COM), Edineț (EDI), Hîncești (HIN) and Căușeni (CAU), with twelve approved links. API identifiers can use the reference namespace's lowercase city IDs. RIB/TIR historical labels remain legacy or unmapped context rather than new inventory nodes.

Choose a registered dataset marked validated archive to use its exact recorded interval. An incomplete archive stays unavailable; a small fixture is not a substitute.

Select a city with the map, keyboard Enter/Space, city table or City control. Select a link with its map action or the directional inventory table. Each declared direction is explicit. Selecting an incompatible city clears link focus. Capacity in the inventory is a declared value, not a measured traffic sample.

For native transport history, choose Transport, a declared direction, and a measurement. Carried traffic, effective capacity, utilization, RTT snapshot and jitter remain distinct. A high utilization value alone is not an outage verdict. Loss, errors/discards and an operational-state timeline are unavailable under the current native read model.

## Read metric evidence correctly

VoLTE registered series display the server's exact buckets, quality, coverage and primitive counts. Call setup success is a percentage; setup time is a duration. Neither substitutes for the other. The registered dictionary covers 41 domain metrics plus the compatible setup-success alias. Each metric declares its population, operator, availability and reference approximation. Missing required primitives produce unavailable values even when a nearby CSV field looks similar.

Registered persisted history supports 5m, 15m, 1h and 6h server aggregation and exact CSV once the full validated archive is projected into incident-owned storage. Period and resolution remain independent. Partial edges contain only complete native intervals and expose effective bounds. Five-minute measurements remain available in a separate expandable view; that compatibility API does not aggregate or export CSV.

For roaming, choose Country, Roaming direction and Partner / direction. Both lists inbound and outbound populations separately. Registration, data and voice procedures have separate identities. With a complete archive, active-user snapshots retain their observation time and age, and finalized roaming SMS keeps its own population. They are never inferred from registration or data success rates. Direction-incompatible snapshots are rejected.

For LTE mobile data, inspect EPS bearer activation, DNS response success and modeled throughput separately. Normal activation cannot mask DNS failure. Expand source records to read primitive populations, source qualifiers, hashes and provenance. Unproven eligibility or modeled weighting remains visible.

In existing SMS investigations, distinguish submitted, finalized delivered/expired, pending queue and delivered-message delay. Pending is not lost. No delivered samples does not establish an empty queue. A low p95 does not prove that pending messages have drained.

Missing values are gaps, never healthy zero. Zero is a valid observation only with a valid population. A zero denominator produces an unavailable rate. PARTIAL, STALE and INSUFFICIENT_EVIDENCE remain visible. Unknown source coverage is not converted to 100% or zero. Source age and request/render latency measure different things.

## Charts, CSV and Grafana

The Values table preserves all server buckets and pages fifty at a time. Dataset details and Source details hold provenance and source qualifications; missing data, synthetic data and partial edges stay labelled in the main view. Dense SVG drawing selects representative extrema, while table and export values remain exact. Expanded views keep the same interval, resolution and timezone. Escape closes the dialog and restores the expand control's focus.

For a registered series, Download CSV sends the validated current series query and downloads the server response. Changing context cancels an obsolete export. The application does not create an authoritative CSV from visually sampled points.

A configured Grafana link carries absolute epoch bounds, dataset, city, directional link, country, roaming direction, resolution and timezone. Administrators can provide the dashboard URL using the `trace-link-grafana-url` meta setting in the application index. Credential-bearing configuration is rejected. Without a configured endpoint, history and CSV remain usable. No embedded Grafana mode is enabled in this release.

## Alerts and investigation workflow

Notifications shows a bounded newest-first private history, total and unread count. Startup history does not create a toast. A new high/critical opening or escalation can create one restrained toast; medium openings remain in history. Stream hints trigger REST reconciliation rather than authoritatively modifying incidents. Repeated hints are silent, and participating tabs coordinate toast claims.

Open the bell with keyboard or pointer. The modal has loading, empty, error and retry states. Escape/Close returns focus to the bell. Mark read updates visible state only after the server succeeds; on failure the item stays unread and its action can be retried.

Follow Investigate incident to inspect its measured window, source evidence and recommended checks. A possible cause is a qualified hypothesis with confidence, or Cause undetermined. Claim/assign before starting investigation when the workflow requires it. Add a comment and confirm that the server saved it. A conflict requires reloading the current version rather than silently replaying the write.

Technical recovery and analyst resolution are separate. Recovered service evidence can coexist with open investigation work. Resolve only when the workflow permits it and with a resolution note. Stopped telemetry remains stopped/unknown rather than recovered.

For persisted native episodes, use Incidents in this context. The list applies the same visible interval, dataset and domain/scope filters, with bounded pages. Original committed evidence and analyst audit have independent pages. Incident links preserve query context. Assign to me, Start investigation, comments and resolution use the current server version; a conflict requires refresh.

## Optional browser alerts

Shared browser delivery remains disabled until secure-origin acceptance is recorded. Protected configuration, encrypted subscription persistence and bounded delivery are implemented; controlled tests do not establish real browser delivery. Use Enable browser alerts explicitly. No permission request occurs on page load. Denied, unsupported, insecure-origin, unavailable and feature-off states retain in-app history. Change browser settings and reload after a denied permission; the application does not repeatedly prompt.

Secure opt-in requires enabled backend configuration, a valid VAPID public key, subscription storage and a server subscription identity. If the server omits a removable identity, the browser subscription is rolled back and enabling reports an error. Disable deletes the server identity and unsubscribes the browser; a failure remains retryable.

A visible controlled window refreshes durable history instead of displaying a duplicate system alert. Otherwise lock-screen text contains only a generic title and severity. Click-through accepts only the same-origin incident path and requires normal authentication. No scope, cause, evidence, analyst or subscriber details are displayed on the lock screen. Ended sessions clear private client state and unsubscribe the active browser subscription.

## Scenario runs and troubleshooting

The primary synthetic scenario controls load the authoritative backend catalogue. They expose only executable definitions and complete domain/scope tuples. Unsupported previews cannot post an execution command. Concurrent runs retain separate scope labels, server identities and status. Local generation is not Kafka delivery or detector acceptance.

Retry an uncertain command with its original idempotency key and unchanged body. A conflicting reuse is a visible error. Planned phases are explanatory; the server's status/progress remains authoritative. Legacy VoLTE/SMS commands remain separately labelled for compatibility.

On a source gap or stale response, retain the timestamps and quality state; do not infer a cause. After API/network interruption, retry the same context. Session expiry clears private state, closes the stream and returns to sign-in. A new user cannot receive the previous user's late response.

400 means invalid context, 401 ended authentication, 403 missing permission/session protection, 404 missing or disabled resource, 409 changed/conflicting state, 429 throttling, and 503 temporary unavailability. The current release explicitly reports an inactive Trace Link catalogue or disabled native API. These states need backend registration/activation, not a fabricated frontend fallback.

## Reference aggregation details

Rates use sums of independent numerators and denominators. Means use elapsed sums and their matching counts, or declared valid seconds for time-weighted rates. Percentages and p95 values are never averaged. Histograms are pooled before one percentile interpolation; SMS delay values and exported bin edges use milliseconds under the explicit conversion policy. Transport carried volume is a qualified estimate from directional rate and elapsed seconds, not a measured byte counter.

Queue growth requires start and end snapshots; the first archive interval has no invented opening queue. A retained snapshot exposes its age and becomes stale after its freshness limit. Synthetic CSV files lack measured source quality and coverage: structural slot coverage is displayed separately, and calculated values remain qualified evidence. Nominal link declarations never replace measured effective capacity in utilization arithmetic.

## Service overview charts and priority anomalies

The overview combines VoLTE setup traffic bars (successful and failed attempts per source window) with the orange setup-success line and dashed recorded baseline. Counts are not Erlang measurements. The primary VoLTE KPIs are setup success, Attempts, SIP 503 rate and IMS CPU; supporting episode measurements remain in Troubleshooting with their recorded values and baseline comparisons. SMS keeps delivery, completed volume, queue and delay populations distinct.

For a long period, scroll the chart horizontally, use Zoom in/out and Earlier/Later, or choose Focus recorded data. Full selected period restores the original drawing. These controls inspect the loaded history without changing the selected period or the exact source table; missing intervals remain missing. The separate persisted-history query and export controls retain their existing behavior.

The map contains the ten approved cities and twelve declared connections. Dots and links remain fixed; hover changes colour only. Select a plain link line or its entry in Intercity link list, then choose Investigate A → B or Investigate B → A. Selection details appear directly below the map. Nominal capacity is labelled; missing traffic, loss and baseline comparisons remain unavailable. The investigation carries the selected absolute interval. A declared connection does not prove that measurements exist in both directions. On a phone, scroll the map horizontally to reach its controls.

Priority anomalies sit below the map and show the three highest-priority unresolved episodes from the highest backend priority page. Compact cards show where the issue occurred, when it was first observed, and its recorded possible cause or Cause undetermined. KPI values and full audit details remain in episode evidence. The incident summary scrolls normally with the evidence. The full incident queue has its own pagination. Use Open episode evidence for the complete immutable records and authorized analyst actions. Technical recovery does not automatically resolve the analyst workflow.

Roaming currently uses explicitly labelled artificial data. The overview shows the location-update success chart and top ten subscriber countries. Open roaming services shows the chart, subscriber table and country KPI table together. Measured partner history opens the separate authenticated history controls; preview values never create scenarios, notifications, stored measurements or incidents.

Show on dashboard marks the recorded incident with a red vertical line and shaded interval on the full selected chart period. It does not automatically narrow the chart to the incident window. If the incident is outside that period, the view moves to include it at the same time scale. Zoom in centres on the marked interval; Full selected period restores context. Dismiss highlight removes the marker.

The top overview cards prioritize VoLTE setup-attempt volume, setup success, backhaul peak utilization, roaming location-update success, roaming subscribers and unresolved anomalies. VoLTE values describe the latest selected-scope window, not Erlang traffic or established-call drops. Roaming cards share the labelled sample data used by the roaming panels. Backhaul utilization remains unavailable without directional measurements; nominal capacity is not substituted.

Troubleshooting shows the affected scope, readable recorded signals, available node names and supporting measurements. It omits empty table headings, long source identifiers and version metadata. Original incident evidence remains unchanged on the server.

The overview stacks VoLTE and roaming independently of the map height, without stretching their panels. The compact priority preview shows three episodes; Open full incident queue retains the complete paginated list.
