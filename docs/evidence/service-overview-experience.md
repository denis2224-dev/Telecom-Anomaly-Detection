# Service overview experience verification

Application source: `7edfa2583c8d3feff1f66d2ee58a4d173849a514`, based on main `58b78a39581c220b75ced16cea80961c152f92ff`. Documentation and evidence commits follow without changing the application. This is frontend verification using controlled APIs, not connected archive acceptance or a deployment.

## Changes

- Removed the requested legacy geography explanation. The map now shows the approved ten cities and twelve declared connections. Each link has a selectable marker, list equivalent, and directional history links carrying the absolute selected interval. Phone maps scroll horizontally to keep controls reachable; selecting a link does not move the map vertically.
- Split the overview's VoLTE display into setup traffic counts and a separate green call setup success curve against the recorded baseline. Traffic counts use the source numerator/denominator. Erlang, regional traffic and link utilization values from the reference image are not fabricated where the current API does not supply them.
- Long historical charts have bounded additional horizontal space, intermediate dates, zoom, pan, focus recorded data and full-period reset. These drawing controls preserve the selected query, exact source rows, missing gaps and inspected windows during live refresh.
- Replaced raw eligible-attempt labels with Attempts. The primary VoLTE KPIs are setup success, attempts, SIP 503 rate and IMS CPU. Lower-priority radio/bearer/loss/count details remain in the episode's Troubleshooting evidence with original values, baseline and primitives. SMS delivery, queue, delay and completion populations remain separate.
- Added up to six unresolved anomaly cards from the highest backend priority page below the overview panels. Cards include severity, technical/workflow states, observation time, measured deviation, qualified cause and actual episode evidence summaries. The full incident queue retains its own pagination and existing analyst actions. No acknowledgement is fabricated and technical recovery does not resolve a workflow automatically.

## Verification actually run

- Unit suite: 186 passed in 35 files, including zoom bounds, gap preservation and retained inspection during refresh.
- Complete controlled Chromium suite: 190 passed, 25 environment-dependent cases skipped, zero failures after the chart repairs. The later phone-only scrolling adjustment was followed by the final checks below.
- Final layout/accessibility/geography subset: 25 passed, three fixture-server-specific cases skipped.
- Final overview checks on Chromium, Firefox and WebKit: nine passed, zero skipped or failed. Each checks all twelve link buttons at desktop and phone widths, map position, backend priority order, evidence links and long-period navigation.
- Final production-build resource suite: five passed. These are controlled browser regression checks, not connected ingestion/capacity certification or field latency measurements.
- Production and fixture builds passed. Four fixture examples validate; branding generation/check passed. Canonical API copies remain identical and generated API types were not edited.

The first broad run exposed duplicate accessible names for the two charts and a cleared tooltip on live bounds updates. Both were repaired and rerun. Older assertions for seven primary VoLTE KPIs and only two date labels were updated for the requested presentation. Broader link clicking then exposed mobile overlaps; the final scrollable phone map passes all twelve clicks in all three engines. Failing and passing logs are retained separately.

The production initial bundle is 516.47 kB against the unchanged 500 kB warning budget. This is a build warning, not a failed build. No backend code, database records, authentication, CSRF, incident identities or delivery settings changed. No merge or deployment was performed. Live measurements still depend on the connected backend and available archive; missing values remain unavailable.

## Artifacts

[Desktop preview](assets/service-overview-experience/overview-1366.png), [phone preview](assets/service-overview-experience/overview-390.png), [source/browser/build summary](assets/service-overview-experience/summary.json), [artifact checksums and compressed test logs](assets/service-overview-experience/artifact-index.json).
