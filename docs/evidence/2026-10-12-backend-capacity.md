# Trace Link API and live-update capacity baseline

Planning work package: 12 October 2026. Actual execution: 13 October 2026, Europe/Chisinau.
Branch: feat/trace-link-transactional-history. Draft PR #103. Runtime baseline used pinned read/history/SSE code 9b9b1f1 in the isolated codex-trace-history-review project. Later domain-history/snapshot guard fixes do not change these measured paths.

**Result: failed/provisional baseline. No healthy sustainable operating limit is claimed.** The required 120-second warmup, 900-second steady phase, 60-second burst and 180-second recovery were executed. Request timeouts, client rejection and failed telemetry are included in the archived evidence. Successful-request percentiles must not hide failed requests.

| Phase | Planned seconds | Actual seconds | Offered | Successful JSON | Request errors | Client rejected | Successful p95 range, ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| warmup | 120 | 120.001 | 1200 | 1128 | 72 | 0 | 31.05–45.58 |
| steady | 900 | 909.805 | 9000 | 8750 | 250 | 0 | 24.66–40.54 |
| burst | 60 | 69.903 | 1800 | 1311 | 88 | 401 | 11.63–25.87 |
| recovery | 180 | 180.001 | 1800 | 1747 | 46 | 7 | 24.63–41.66 |

Two real OIDC-session SSE clients remained connected/reconnected. Peak sampled SSE connections: 2; queue drops: 0; sampled pool pending peak: 0. A post-run SQL observation reported no pending legacy detection backlog. This small-history, read-only baseline does not measure the generator/processor offered/accepted/persisted throughput or the configured 200-connection limit.

Successful requests: 12936; request errors: 456; client rejection: 408; telemetry errors: 11.

Telemetry polling in the first harness could pause its scheduler and inflate client rejection. The fix moves polling to a separate thread and a slow-telemetry regression passes. The corrected short diagnostic is archived separately; its durations are 15/60/30/30 seconds, so it does not replace the required full run. An intermediate diagnostic with expired sessions is labelled invalid and retained separately. The harness now fails authenticated JSON preflight before measuring an expired/disabled session.

The proxy access sample included 499 client-closed responses. A subsequent controlled sample recorded 100/100 successful direct-backend requests (maximum 19.71 ms), 100/100 canonical-host proxy requests, and 99/100 loopback-proxy requests with one timeout. This points to an intermittent proxy/host transport limitation; it is not proof of a backend query bottleneck or a resolved transport defect. Require a fresh full-duration run on the agreed deployed topology after infrastructure investigation.

Hardware, actual query plans and memory settings are in assets/backend-transactional-history/query-plans.json and jvm-flags.txt. The load profile used pool maximum 12, minimum idle 4, timeout 3000 ms; the host JVM had its actual default heap settings, rather than the Compose application 384 MiB ceiling. Grafana/check tooling ran alongside portions of the steady phase. Projection receipt-age samples include workflow commits and must not be presented as pure processing latency.

Raw per-route/per-phase p50/p95/p99, request statuses, client queue delays, telemetry, CPU/memory, pool and SSE samples: assets/backend-capacity/capacity.json. Diagnostic and result/limit qualifications: collector-diagnostic.json and summary.json. Source-quality uncertainty, producer delivery, deployment-owner Grafana receipt and frontend sign-off remain open release gates.

No cookies, passwords, OIDC tokens or subscriptions are committed.
