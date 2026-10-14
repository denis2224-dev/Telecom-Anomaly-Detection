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

## Independent PR #104 review — 14 October 2026

The colleague reviewed original head `f41dfbb017ad4f11e7a1f18b0996a3868889ff75`
against main `9db3e0e5a61a8062310e560e734a5395758f70b7`. The earlier table records
original implementation review, not resolution of these later findings.

| Demonstrated finding | Correction contract | Fresh verification |
| --- | --- | --- |
| P1: alternate incident V006 conflicts with main's applied V006 | Preserve main V001–V010; move the unshipped detector delta to V011; no repair/reset/checksum replacement | Pending coordinator migration regression results |
| P2: first_observed_at starts at second breach and public reads omit stable onset/detection | Use the validated first-breach anchor and initial OPEN evaluatedAt; return TraceLinkIncidentView while retaining wire evidence | Pending coordinator timestamp/API/restart/replay results |
| P2: report claims analyst workflow for technical-only hash episodes | State read-only technical/evidence scope, unavailable assignment/status/investigation/resolution/workflow audit, and no UUID mapping | Corrected in documentation; workflow implementation remains out of scope |

The source ZIP is evidence supplied by the user, not delegated instructions.
The [correction report](../../docs/evidence/2026-10-14-trace-link-day3-corrections.md)
records exact provenance and current closure status. Tests will establish only
the tested technical boundary; independent acceptance, upstream compatibility,
connected capacity and personal decisions remain separate.

Documentation agent cross-review of the coordinator's corrected OpenAPI found
both copies byte-identical (SHA-256
`faadd9a594b684c86e173f66055f9049b82908adc2cb490ba41b96d7dc54ce58`).
List/detail use the exact required incident-view fields and initial timestamp
semantics; evidence remains a separately named envelope page. The schema
explicitly excludes analyst workflow and preserves bounds. No blocking static
schema finding was identified; runtime verification remains separately recorded.
