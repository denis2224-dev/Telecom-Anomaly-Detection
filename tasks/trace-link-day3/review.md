# Day 3 cross-review resolutions

Three agents reviewed measurement authority, runtime persistence and receiving
contracts; the coordinator reviewed their combined integration and evidence.

| Finding | Resolution and regression |
|---|---|
| Configured baseline resolution differed from raw saved context and blocked commit | Completion compares the configured adapter result; raw observations remain unchanged. Real PostgreSQL regression. |
| Source freshness used historical evaluation time for first live scoring | LIVE freezes actual first-claim time in the job; retries keep it. Explicit historical replay mode. Inclusive 600-second/stale backlog tests. |
| Cause primitives disappeared from the delivery envelope | `causeEvidence` preserves candidate/control/source snapshots independently of selected cause metadata. |
| Malformed rejected attribution could suppress an independent symptom | Rejected inputs remain diagnostics; only accepted support/contradictions require semantic qualification. Producer and independent receiver regressions. |
| Schema-only wire validation accepted impossible arithmetic/freshness | Both Java runtimes validate metric/unit/population, primitives, counts, source age, quality and context. Tampering regressions. |
| Forged OPEN episode IDs could be rehashed into internally consistent detection IDs | Both runtimes recompute opening episode identity from complete scope, policy and first bad window; approved policy version is enforced. |
| Quiet verdicts were not retained as queryable committed outcomes | Evaluated-window results commit atomically with lifecycle and outbox. Rollback and replay regressions. |
| Migration tests encoded old exact counts | Exact new migration/table counts and immutable runtime grants verified; existing isolation/ownership assertions retained. |
| Initial native critical fixture used exactly the HIGH boundary | Corrected synthetic DNS population to a three-point drop and updated original checksum. CRITICAL expectation retained; thresholds unchanged. |
| Capacity publication samples counted both window spans and exact deliveries | Exact per-detection traces are used once; 2301 unique publication latency samples. |
| Subsequent receiver timestamps could look like original live latency | Receipt artifact is hash-bound and labeled separate replay; original incident-delivery latency remains unmeasured. |
| Default rate profile differed from executed harness selection | Executed four/eight-rate profile is tied to the measured harness source and source-tree hash; no post-run retuning claim. |

All original VoLTE/SMS contract/model pins remain subject to the unchanged frozen
hash check. No test was skipped or removed to resolve a finding.
