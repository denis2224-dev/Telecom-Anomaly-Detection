# Trace Link rehearsal script

Use the same release build/schema hashes, Chromium version, 1366×768 viewport, temporary analyst, recorded dataset and seed 42 for both repetitions. Start from sign-in each time. Repeat at phone width after the desktop demonstration.

1. Sign in through the provider. Open Trace Link history and select the fully imported pinned dataset. Its recorded catalogue can remain inactive: authenticated historical inventory uses the dataset's explicit version. If history is unavailable, stop populated acceptance and record the exact failure.
2. When the archive is registered, select its absolute bounds and city. Change period and resolution independently. Brush/zoom/pan/reset, expand the chart and close it with Escape to demonstrate focus return.
3. Investigate one declared link direction. Read capacity and traffic as different values. Show native provenance and any unavailable loss/state fields. Do not infer an outage from high utilization.
4. Select roaming country, direction and partner. Explain that registration/data/voice are separate populations and that active-user snapshots/finalized roaming SMS are not supplied by this source.
5. Inspect LTE activation and DNS independently, then throughput qualifications. Read null, stale, partial and source-quality-unavailable states explicitly.
6. Open durable notifications. Demonstrate startup silence, failed/successful mark-read and one new high alert where supported. Repeat stream hints and reconnect; compare the REST snapshot and show one tab transport.
7. Claim/assign an existing supported incident, investigate, comment, observe technical recovery and resolve with a note. Explain why recovery alone leaves analyst work open.
8. Download registered-series CSV with the same visible query and compare exact buckets, primitives, qualification and provenance. Open the configured Grafana link and compare its actual queries at the same absolute interval. The optional original five-minute native view retains its separate source limitations.
9. Show permission denied/unsupported/off fallback while in-app history remains usable. Secure system-push delivery is not accepted while its backend feature is off.
10. Show concurrent backend-supported scenario runs, preserved retry identity and stopped/unknown telemetry. Distinguish local generation from delivery/detector acceptance. Sign out and confirm private-state and stream cleanup.

The authenticated history check requires the full pinned archive and recorded inventory. It rejects absent/incomplete history, verifies all five domains and exact JSON/CSV, and repeats identical selections. Run it only against the approved candidate with `E2E_REAL_LOGIN=1 E2E_RELEASE_LIVE=1 E2E_BASE_URL=<origin> npm --prefix apps/dashboard run test:e2e -- trace-link-release.spec.ts --workers=1`. It provisions and removes one temporary identity using the configured local Compose stack; ensure the browser origin belongs to that stack. No API routing mocks are installed. It does not start services, import data, activate a catalogue or enable push.

This automated history check does not certify the incident lifecycle, secure push, Grafana parity, offline installation, guide-only review or either complete rehearsal. For each complete repetition, record the unchanged release manifest SHA256, deployed image digests, browser version/viewport, UTC start/end, actual scenario and incident IDs, expected/actual counts, screenshots without private information and any failure. Another person must complete the principal investigations using the guide alone. Human acceptance remains pending until actually performed.

## Current verified candidate

The full supplied archive, backend/Grafana parity and isolated real-sign-in history/lifecycle workflows now pass. See the integrated release manifest and current evidence index. The isolated reference runner accepts `--reference-pack /absolute/Trace_Link_Integration_Pack`; its four required cases include fresh legacy fault/control/gap scenarios and populated desktop/phone/reconnect flows. Scheduled scenarios take their genuine eight-minute intervals. This is partial automated acceptance, not two complete demonstrations, timed expiry review or five human signatures.
