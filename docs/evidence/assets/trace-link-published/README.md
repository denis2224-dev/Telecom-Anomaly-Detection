# Integrated verification evidence

Runtime source: `280bdacf41d9db9cf69c8bfa4085940ece288bc6`, integrated with upstream `82f69d8d34afbadc4a43e70814af170b5ebdfac7`. Later repository evidence updates do not alter the tested runtime.

`verification-summary.json` records exact modes, counts, skips, retained failures and pending acceptance. `runtime.json`, `verification.json`, `scenarios.json` and the two populated history records/captures come from a real production build with OIDC, API, Kafka, processor and PostgreSQL in fresh owned services. Four cases pass; cleanup and credential removal pass. The scenarios are actual scheduled source runs, not inserted incidents or fixture browser arrays.

`full-reference.json`, `processor-import.json` and `grafana.json` use the complete checksum-pinned source. Database tests import/retry and restore all native rows; actual Grafana queries compare against restricted SQL. The reference engine and DOM-shim logs are separate from Chromium results. Selector/queue failing-before logs remain alongside passing results.

`offline-manifest.json` records the fully assembled local package and byte checksums, including actual images and the complete archive. Package integrity passed. Offline installation/receiving-person acceptance remains pending. Registry publication, shared deployment, secure-origin push, coordinated capacity, complete rehearsals and owner signatures are not implied.

`superseded-backend-manifest.json` preserves the prior unaccepted backend fragment exactly. It is historical evidence; the current integrated manifest is `docs/release/trace-link-release-manifest.json`. Original evidence directories retain their own pins/checksums. Verify `artifact-index.json` before reuse.
