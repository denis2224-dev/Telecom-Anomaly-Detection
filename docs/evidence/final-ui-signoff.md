# Trace Link frontend verification

Verified on 2026-10-13. Frontend checks pass; full release acceptance remains incomplete. Changes are committed on `feature/trace-link-analyst-experience`.

The map remains stationary when sample links are toggled. The analyst interface now shares validated UTC bounds, timezone and scope context across native investigations, URL navigation and Grafana links. Branding, qualified history tables, registered CSV checks, notification presentation and opt-in push fallback are covered by automated checks. Illustrative traffic is restricted to fixture previews.

## Checks completed

- 181 unit checks passed across 35 test files.
- The complete controlled functional/accessibility browser run passed 172 checks; 25 opt-in checks were skipped.
- Six focused history checks passed after the final partner-focus and CSV fixture repairs.
- Four focused alert checks passed after the timezone presentation change.
- Two fixture map checks passed at desktop and mobile widths.
- Five production resource checks passed without weakening the thresholds.
- Three push worker checks and three API contract checks passed. Schema validation, branding consistency, fixture validation and the final production build passed.
- Local stack readiness passed. An authenticated LIVE performance run passed, and authenticated dependency-boundary rehearsal passed with two repetitions.

The [release manifest](assets/analyst-experience/release-manifest.json) records the exact source revision, schema checksum, final bundle checksums, counts and limitations. The complete controlled browser/resource runs preceded the final small query repair; the final revision has the focused history checks, unit suite and production build recorded above. The LIVE timing sample uses an earlier recorded revision and does not certify populated native history or secure push delivery.

## Release blockers

The full checksum-pinned archive is unavailable on this machine. The local incident database has no registered Trace Link dataset, active catalogue, metric windows or native source rows. GitHub contains a manifest and bounded test slice; substituting that slice for the required full archive would invalidate acceptance. The archive location is still needed.

The public native-domain and push APIs remain disabled or absent. Authenticated checks observed inventory 503, notifications 200, native measurements 404 and push subscription 404. The interface presents these unavailable states; populated history and real browser push delivery are not certified. Native rollups, native CSV and additional registered domain definitions require upstream contracts and data.

The real incident-lifecycle attempt failed. Both scenario submissions returned 202 for the interval 18:26–18:34 UTC: VoLTE run `00c701d7-44de-486d-b83d-40ec1fb472ef` and SMS run `22f86d12-fa9f-4b74-9b7f-c49c86d176e4`. Of 16 persisted source windows, only the first two per scope were complete; the remaining six per scope were missing. No new committed incident opening was observed before the test timed out. [Persisted source-window evidence](assets/analyst-experience/scenario-source-windows.json) and [lifecycle attempt](assets/analyst-experience/incident-lifecycle-attempt.json) preserve this failure for backend investigation.

Guide-only teammate acceptance remains unperformed. The [demo script](../demo/david-script.md) states the prerequisites for two populated rehearsals; passing unavailable-state checks is not a substitute.

## Evidence

The [analyst guide](../user-guide/trace-link-analyst-guide.md) documents supported interactions and unavailable features. The [performance checkpoint](2026-10-12-ui-performance.md) links controlled and real measurements. Compressed command logs, actual rehearsal output, screenshots and hashes are retained under [the evidence directory](assets/analyst-experience/release-manifest.json). Initial development-server performance interruptions are retained alongside the passing production measurements.
