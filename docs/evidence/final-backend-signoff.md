# Final Trace Link backend sign-off

Recorded 14 October 2026, Europe/Chisinau. Decision: **pending; release must remain draft**. This is the backend handoff record, not five-owner acceptance.

The observed application source/image/API/migration/dataset and public configuration identities are pinned in [backend candidate](../release/backend-release-candidate.json). The [shared manifest](../release/trace-link-release-manifest.json) is explicitly an incomplete backend fragment. The application revision is 6b4d4246a5a8eb563394525dbd04394d432ed8ca; release-test/document commits are newer and do not pretend to be the deployed application source.

| Gate | Result | Evidence |
|---|---|---|
| Ordered legacy evidence, workflow, replay and recipient isolation | Passed backend tests and controlled OIDC checks; full browser rehearsal pending | [Reliability](2026-10-14-backend-reliability.md) |
| Fresh install, populated V004 upgrade, repeat migration, runtime isolation | Passed in disposable PostgreSQL; V011 target, ten migrations, zero on repeat | [Upgrade](assets/backend-release/upgrade.json), [QA grants](assets/backend-release/grants.json) |
| UTC bounds, resolution/range/point limits and capacity history | Backend checks pass; complete archived browser flow pending | [Verification](assets/backend-reliability/verification.json) |
| Transactional notifications, replay, restart and read state | Backend tests and controlled checks pass | [Controlled runs](assets/backend-reliability/controlled-smoke.json) |
| Enabled issuer/subject, CSRF, expiry, roles, SSE and logout | Backend tests and real-OIDC probes pass; live browser/SSE demonstration pending | [Reliability](2026-10-14-backend-reliability.md) |
| Push privacy, fencing, retry bounds and feature-off startup | Backend tests pass; HTTPS browser delivery unverified, effective push flag false | [Verification](assets/backend-reliability/verification.json) |
| Exact CSV, primitive aggregation and provenance | Sixteen real authenticated parity queries and PostgreSQL tests pass | [Controlled runs](assets/backend-reliability/controlled-smoke.json) |
| New-domain producer/processor/scenario controls | Pending complete shared implementation; effective flag false | [Defects](rehearsal-defects.md) |
| Current capacity/load and Grafana read-only parity | Pending; old backend-core measurements are historical only | [Candidate](../release/backend-release-candidate.json) |
| Isolated database restore | Passed; application reconnect to restored DB not claimed | [Restore](assets/backend-reliability/restore.json) |
| Offline package and receiving teammate reproduction | Pending | [Runbook](../runbooks/trace-link.md) |
| Two complete rehearsals on one accepted identity | Pending; two automated controlled runs are partial evidence | [Rehearsals](backend-rehearsals.md) |
| Main migration integration | Pending V009/table collision; original migrations preserved | [Defects](rehearsal-defects.md) |

## Five-owner acceptance

| Owner | Observed acknowledgment |
|---|---|
| Denis | Pending reviewer acknowledgment; backend automation is recorded above |
| Ion | Pending generator/processor command reproduction and evidence |
| Sergiu | Pending rule/cause/control reproduction and evidence |
| David | Pending authenticated browser/accessibility/reconnect reproduction and evidence |
| Stanislav | Pending integrated deployment, complete manifest, grants, restore/offline reproduction and evidence |

The [release checker](../../scripts/check-backend-release.py) correctly rejects the current manifest: capacity acceptance, two complete rehearsals, and all five sign-offs remain absent. [Gate result](assets/backend-release/gate-check.json). Each future sign-off must name all eight candidate identity fields and checksum its observed evidence. Checksums establish internal consistency; they do not prove observations or provide electronic signatures.

No consumed migration, original database, or other owner release acceptance is overwritten. Keep NOTIFICATION_PUSH_ENABLED=false and TRACE_LINK_SCENARIOS_ENABLED=false until their supported integration and acceptance gates are met. The earlier backend-core release waiver remains historical and does not fill this candidate's pending sign-offs.
