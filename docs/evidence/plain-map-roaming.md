# Plain map and roaming preview

Map targets are fixed dots and thin lines. Node symbols, arrow chips and illustrative transport overlays were removed. Hover changes colour without movement. Mouse and keyboard selection expose details directly below the map, including declared nominal capacity and links to each directional investigation. Missing traffic, loss and baseline comparisons remain explicitly unavailable. Priority cards use tighter padding and a single short description row.

The overview shows a purple location-update success chart and ten subscriber-country rows. The dedicated roaming route adds the country KPI table. Artificial roaming values are deterministic for a given seed and interval, labelled Sample data, and never enter the backend incident or measurement stores. Measured partner history remains available through its separate authenticated route. Preview percentages are not presented as aggregated measured populations.

## Checks

- Production build passed: 523.76 kB initial bundle, with the existing 500 kB budget warning.
- Unit suite: 194 tests passed in 37 files.
- Focused browser suite: 9 passed, covering desktop/mobile roaming navigation, both country tables, plain map targets, fixed coordinates during hover/click and incident acknowledgement.
- Broad controlled browser run: 188 passed, 25 skipped, 4 failed during a frontend hot reload. Three lost their execution context; the retained-heap check also failed during that run. These failures are preserved in the log.
- All five resource checks passed when rerun on unchanged source with one worker, including sustained refreshes, stream count, retained heap and navigation. No threshold was weakened.
- Whitespace checks passed. No backend code, generated API types, shared data or volumes changed.

[Overview desktop](assets/plain-map-roaming/overview-desktop.png) · [Overview mobile](assets/plain-map-roaming/overview-mobile.png) · [Roaming desktop](assets/plain-map-roaming/roaming-desktop.png) · [Roaming mobile](assets/plain-map-roaming/roaming-mobile.png)

Logs and checksums are stored beside the screenshots. These controlled checks do not certify live roaming data or complete release acceptance.
