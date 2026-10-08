# Release candidate evidence manifest

## Final release candidate

- Repository: `denis2224-dev/Telecom-Anomaly-Detection`
- Branch: `evidence/release-environment`
- SHA: `4d56c67dc6c0f18fdf73d6390d4540258557291c`
- Working tree: clean before this evidence commit
- Configuration: `.env.example`; geography disabled with an aligned effective-from value
- Catalogue version: not pinned in the deployment configuration
- Topology version: runtime value not observed
- Baseline version: `baseline-v2` in the published model contract
- Model version: `sms-supervised-v1-2` for the published SMS model; no live model run
- Policy version: `geographic-priority-v1` in the geographic priority implementation

## Executed evidence

| Case | Owner | Required | Evidence type | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| RC-01 | Stanislav | yes | CONTROLLED_FIXTURE | PASS | `git` candidate and clean worktree |
| RC-02 | Stanislav | yes | CONTROLLED_FIXTURE | PASS | `scripts/check-contracts.py` |
| RC-03 | Stanislav | yes | CONTROLLED_FIXTURE | PASS | geography alignment tests, 5 cases |
| RC-04 | Stanislav | yes | CONTROLLED_FIXTURE | PASS | Compose render with non-secret placeholders |
| RC-05 | Stanislav | yes | CONTROLLED_FIXTURE | PASS | dashboard production build |
| RC-06 | Stanislav | yes | CONTROLLED_FIXTURE | PASS | branding test and fixture check |
| RC-07 | Stanislav | yes | RUNTIME | BLOCKED | Docker daemon unavailable |
| RC-08 | Stanislav | yes | AUTHENTICATED_LIVE | NOT_VERIFIED | no live stack or session |
| RC-09 | Stanislav | yes | RUNTIME | NOT_VERIFIED | stop/restart/resume not run |
| RC-10 | Stanislav | yes | BACKEND_DATABASE | NOT_VERIFIED | migrations require disposable database |
| RC-11 | Stanislav | yes | AUTHENTICATED_LIVE | NOT_VERIFIED | SSE and mentor flow require live stack |
| RC-12 | Stanislav | yes | RUNTIME | NOT_VERIFIED | feature-off rollback not rehearsed |
| RC-13 | Stanislav | conditional | RUNTIME | NOT_IN_SCOPE | power/probe slice is not enabled in this candidate |

## Configuration and safety

- No password, cookie, token, private key or database dump was recorded.
- No database volume was deleted or reset.
- No migration history was edited.
- No force push was used.
- The old route remains the safe fallback while mandatory live gates are blocked.

## Release decision

`G5: BLOCKED`

`DO NOT RECOMMEND RELEASE`

The controlled checks pass, but the mandatory runtime, migration, authenticated
live, restart/resume, SSE, rollback and teammate-reproduction gates were not
executed against a running environment.
