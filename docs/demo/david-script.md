# Trace Link rehearsal script

Use the same release build/schema hashes, Chromium version, 1366×768 viewport, existing analyst, recorded dataset and seed 42 for both repetitions. Start from sign-in each time. Repeat at phone width after the desktop demonstration.

1. Sign in through the provider. Choose Investigate history to open Historical analysis and select the fully imported pinned dataset. Its recorded catalogue can remain inactive: authenticated historical inventory uses the dataset's explicit version. If history is unavailable, stop populated acceptance and record the exact failure.
2. When the archive is registered, select its absolute bounds and city. Change period and resolution independently. Brush/zoom/pan/reset, expand the chart and close it with Escape to demonstrate focus return.
3. Investigate one declared link direction. Read capacity and traffic as different values. Show native provenance and any unavailable loss/state fields. Do not infer an outage from high utilization.
4. Select roaming country, direction and partner. Explain that registration/data/voice are separate populations and that registered archive snapshots/finalized roaming SMS retain their own populations and freshness. The separate original native view can report these unavailable; do not infer them from registration.
5. Inspect LTE activation and DNS independently, then throughput qualifications. Read null, stale, partial and source-quality-unavailable states explicitly.
6. Open durable notifications. Demonstrate startup silence, failed/successful mark-read and one new high alert where supported. Repeat stream hints and reconnect; compare the REST snapshot and show one tab transport.
7. Claim/assign an existing supported incident, investigate, comment, observe technical recovery and resolve with a note. Explain why recovery alone leaves analyst work open.
8. Download registered-series CSV with the same visible query and compare exact buckets, primitives, qualification and provenance. Open the configured Grafana link and compare its actual queries at the same absolute interval. The optional original five-minute native view retains its separate source limitations.
9. Show permission denied/unsupported/off fallback while in-app history remains usable. Secure system-push delivery is not accepted while its backend feature is off.
10. Show concurrent backend-supported scenario runs, preserved retry identity and stopped/unknown telemetry. Distinguish local generation from delivery/detector acceptance. Sign out and confirm private-state and stream cleanup.

The authenticated history check requires the full pinned archive and recorded inventory. It rejects absent/incomplete history, verifies all five domains and exact JSON/CSV, and repeats identical selections. Run it only against the approved candidate with `E2E_REAL_LOGIN=1 E2E_RELEASE_LIVE=1 E2E_BASE_URL=<origin> npm --prefix apps/dashboard run test:e2e -- trace-link-release.spec.ts --workers=1`. It uses an existing enabled analyst configured privately through `SESSION_USERNAME` and `SESSION_PASSWORD`; ensure the browser origin belongs to the approved stack. It does not create users or change analyst, archive or workflow records. No API routing mocks are installed. It does not start services, import data, activate a catalogue or enable push.

This automated history check does not certify the incident lifecycle, secure push, Grafana parity, offline installation, guide-only review or either complete rehearsal. For each complete repetition, record the unchanged release manifest SHA256, deployed image digests, browser version/viewport, UTC start/end, actual scenario and incident IDs, expected/actual counts, screenshots without private information and any failure. Another person must complete the principal investigations using the guide alone. Human acceptance remains pending until actually performed.

## Earlier connected implementation evidence

The published implementation evidence at `280bdacf41d9db9cf69c8bfa4085940ece288bc6` records full supplied archive, backend/Grafana parity and isolated real-sign-in history/lifecycle passes. Those runs do not certify a later frontend candidate. See the integrated release manifest and its evidence index. The isolated reference runner accepts `--reference-pack /absolute/Trace_Link_Integration_Pack`; the earlier published run covered four legacy fault/control/gap and populated desktop/phone/reconnect cases. Its expanded configuration also requires real idle and absolute expiry checks, whose acceptance must be recorded separately. Scheduled scenarios take their genuine eight-minute intervals. This is partial automated acceptance, not two complete demonstrations, timed expiry review or five human signatures.

## Approved inventory and interpretation

Use exactly CHI, BAL, CAH, UNG, ORH, SOR, COM, EDI, HIN and CAU from the versioned geography. The twelve link identities are `chi-orh`, `orh-sor`, `sor-bal`, `bal-edi`, `bal-ung`, `ung-chi`, `chi-hin`, `hin-com`, `com-cah`, `chi-cau`, `cau-com`, `orh-bal`. Validate direction availability in the current response rather than assuming both directions are measured. Historical retired labels are context, not active nodes.

Say **Possible cause** with the supplied confidence or **Cause undetermined**. Do not announce a **Confirmed cause** without authoritative confirmation. **Technical recovery** describes recovered service evidence; **Analyst resolution** is a separate reviewed workflow action.

For a read-only automated history check, configure the existing test account privately and run `E2E_REAL_LOGIN=1 E2E_RELEASE_LIVE=1 npm --prefix apps/dashboard run test:e2e -- --config=playwright.release.config.ts trace-link-release.spec.ts`. Missing credentials or an incomplete archive must fail with a prerequisite explanation. Do not use those failures as justification to insert fixtures into shared storage.
