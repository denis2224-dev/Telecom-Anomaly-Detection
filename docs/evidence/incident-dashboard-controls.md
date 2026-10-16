# Incident dashboard controls

The Service Overview uses the same combined VoLTE chart as the service workspace: successful/failed call bars, an orange setup-success line, and contextual baseline. Roaming remains below it; the map stays on the right. Priority anomalies use compact single-column cards.

Shared actions connect priority cards, the queue, episode drawers, incident lists and details. Dashboard links retain incident identity, measured scope, service/domain, partner or city when recorded, and incident bounds. Highlights are static and dismissible. Native links also retain independent resolution and the recorded evidence metric.

Acknowledgement uses the existing assignment and OPEN → INVESTIGATING APIs with current versions, session checks and manual retry after partial assignment. The existing audit stores the actor and timestamp. Technical recovery, analyst resolution, severity and unresolved totals retain their original meanings; separate awaiting-acknowledgement counts update on the overview and queue page.

Legacy audit responses expose the actor and acknowledgement time. Native audit responses expose the time but omit actor identity; the interface explicitly reports this limitation. No actor identity or acknowledgement timestamp is invented. Preview actions remain disabled.

## Verification

- Production build passed. Initial bundle: 524.54 kB; existing 500 kB warning remains.
- Unit suite: 194 passed across 37 files.
- Full controlled browser regression run: 191 passed, 25 skipped. The skipped checks require separate configured environments.
- After final native-card and metric-link adjustments: 17 focused browser checks passed, including desktop/mobile overview, acknowledgement persistence, directional navigation, session-safe context and history.
- OpenAPI copies match; generated types were not edited. Whitespace checks passed.
- Controlled transport fixtures verify frontend behaviour. These runs do not certify connected ingestion, real push delivery or populated release acceptance. No shared database or backend source was changed.

[Desktop screenshot](assets/incident-dashboard-controls/overview-1440.png) · [Mobile screenshot](assets/incident-dashboard-controls/overview-390.png) · [Checksums](assets/incident-dashboard-controls/sha256.txt)

Compressed build, unit and browser logs are stored alongside the screenshots.
