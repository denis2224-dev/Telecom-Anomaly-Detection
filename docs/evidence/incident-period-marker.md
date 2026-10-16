# Incident marker preserves context

Show on dashboard retains the full selected period and draws a red vertical marker with a translucent incident band over the measurements. It no longer replaces the selected period with the narrow incident window. An incident outside the current period receives context at the same time scale; a link without a selected period receives 24 hours of context. Native domain navigation preserves independent resolution.

Analysts can zoom manually around the marked interval, restore the full selected period, or dismiss the highlight. Marker rendering stays above traffic bars so the red line remains visible. Missing measurements retain their existing gaps.

Production build passed (526.59 kB initial bundle; existing 500 kB budget warning). All 197 unit tests passed. Nineteen targeted controlled browser checks passed; an additional full-24-hour marker, manual zoom and dismissal check passed after the final rendering adjustment. These checks do not certify connected data ingestion.

[Full selected period](assets/incident-period-marker/full-period.png) · [Manual zoom](assets/incident-period-marker/manual-zoom.png). Logs and checksums accompany the screenshots.
