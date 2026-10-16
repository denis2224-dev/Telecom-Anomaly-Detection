# Accepted Trace Link frontend requirements

This is the record of the user's latest frontend decisions. Preserve these in future features and fixes unless the user explicitly changes them. Later corrections supersede earlier screenshots and requests; do not restore a rolled-back design.

## Visual layout

- Keep the existing dark design, orange accent, compact spacing, rounded controls and no decorative borders.
- Give buttons a small gap from adjacent controls and content. Use shared group gaps and outer spacing; keep full-width controls inside their containers and map targets fixed.
- Keep page titles and section headings consistent, group overview time filters separately from service/region filters, and keep Apply with Incidents on the right. Use content-sized cards, prominent plots with quiet grids, compact legends and right-aligned numeric table cells. Emphasize incident severity/title/cause while keeping technical metadata secondary; use muted gray for unavailable states.
- Desktop overview: VoLTE on the left, roaming below it, map on the right. Panels size independently; avoid empty stretched space. Keep layouts usable on mobile.
- Overview charts are prominent and taller; supporting results, donut, scope/country tables and open-service controls stay smaller. Apply consistently to VoLTE, SMS and roaming.
- The main VoLTE overview uses successful/failed call bars, setup success percentage and a dashed baseline in one chart. Preserve actual units: setup attempts are not Erlang traffic or established-call drops.
- Match legend colors to their series: successful calls green, failed calls red, setup success orange; baseline purple/dashed. Preserve keyboard inspection and readable UTC/date labels.
- Keep operator text short: useful labels, locations, times, causes and key values. Avoid repeated explanations, source metadata, empty table headings and raw identifiers on routine cards.
- Do not restore the map's Network paths subtitle or empty selection instructions. Show map details after selection. Keep chart readouts stable during inspection, using recorded-window count and time bounds instead of idle instructions; omit empty readouts. Keep keyboard help accessible and measurement limits/provenance in short expandable sections.

## Incident cards and navigation

- Use shared severity badges everywhere: HIGH red, MEDIUM amber, LOW blue, CRITICAL stronger red. Keep severity text as well as color.
- Remove Assigned/Assignee display lines from cards and summaries; preserve assignment, investigation, commenting and resolution controls and permissions.
- Buttons stay compact and share dimensions, padding, font and radius. Current desktop action size is up to 128px wide by 34px high; retain at least 44px touch targets on mobile.
- Regular episode cards: Show on dashboard, Acknowledge/Acknowledged and Open incident detail stay together on the left. View incident evidence belongs at the far right of that same desktop row, with an orange fill. Wrap cleanly into two columns when mobile space requires it. Preserve both preview and direct detail navigation.
- Priority anomaly cards: evidence button bottom-left, Show on dashboard and Acknowledge bottom-right; equal compact sizing and comfortable inner padding. Keep Where / When / Why concise.
- Show on dashboard opens the owning dedicated service page, preserving incident, service/scope/city/partner and time context. Mark the event with a red line and shaded interval, retaining the full selected period. Manual zoom can inspect the event; dismiss clears the highlight. Do not automatically reduce the graph to only the incident window.
- Acknowledgment must use the existing API/workflow and update counts consistently. Display short service-related references such as VOL-001, SMS-002, ROAM-003 or NET-004, with the real timestamp. References persist locally across reloads; backend audit identities remain unchanged. Do not reintroduce actor UUIDs or an ACK prefix on the acknowledgment line.
- Incident queue fills the viewport with outer padding and a Close button. Keep only Severity, Service and State filters in one row. Remove count headings, awaiting-count sentences and duplicate technical/helper lines. Retain title, scope/service, severity, technical/workflow states, observed/detected times, baseline deviation, cause and actions.
- Keep the incident queue subtly translucent with background blur; cards remain nearly opaque and text/buttons stay fully opaque for readability.
- Incident summaries scroll normally; do not reintroduce a sticky summary/header over the evidence timeline.

## Map and measurement truth

- Keep node positions static. No dragging, pulsing or movement on hover/selection. Plain small dots and thin static network links; hover may change color and selection shows details below.
- Keep city names and genuine health percentages beside nodes. Mark technically active anomalies red; recovered incidents awaiting analyst resolution alone must not keep a node red.
- Do not restore check/question icons, large link arrow chips or the Intercity link list. Follow the approved topology contract; do not invent links, utilization or transport health from service success percentages.
- Long periods retain their complete selected interval, horizontal scrolling, manual zoom, incident markers and missing-data gaps. Never connect lines across absent measurements or invent values.
- Roaming keeps responsive KPI cards, a readable graph and country tables. Recorded, sample, loading and unavailable states remain explicit. A failed recorded-data read must not silently become sample data.
- Freshness labels describe the latest usable measurement, not browser fetch time. Distinguish historical coverage, missing tails, unavailable measurements and samples.

## Analyst assistance

- Related incidents match overlapping recorded time and actual shared scope, city or dependency. Keep archives separate; cross-scope location/dependency matches need a common recorded topology. Show match reasons and checked-page coverage. A relationship is not proof of a shared cause.
- Suggested next checks are service/state-specific investigation steps, never confirmed causes. Restore missing evidence before assessing recovery; distinguish technical recovery from analyst resolution.
- Shift handover captures the current queue/context page, filters and time bounds. Label partial coverage/lower-bound counts and unavailable workflow states honestly. Copy/download remain explicit user actions. Keep the snapshot fixed until refreshed, clear it on session expiry and never mutate incident state through handover.

## Safe implementation and verification

- Reuse existing components and APIs. Reject stale responses and keep loading, empty, retry, session-expiry and reconnect states usable. Preserve keyboard access, visible focus, unclipped labels and readable mobile layouts.
- Frontend work does not authorize backend, generated API type, authentication, CSRF or database changes. Do not import data, create live incidents or enable push delivery as test setup.
- Preserve unrelated and untracked files, including numbered duplicate files. Stage explicit paths. Use the branch requested by the user; do not switch/merge into main or create a replacement branch without a relevant request. Keep new branch names and commit messages free of the prohibited terms specified by the user: `codex` and `day`.
- Validate the production build, unit tests and relevant controlled desktop/mobile browser tests. Keep meaningful assertions; report mock checks separately from connected backend checks and disclose skipped checks or build warnings.
