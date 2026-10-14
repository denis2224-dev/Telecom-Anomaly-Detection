# PR #104 description — review corrections

Publication draft; verification placeholders below must be replaced with actual
results before posting. This file does not publish or approve the PR.

## Summary

Corrects the independent review findings against `f41dfbb` and integrates pinned
main `9db3e0e` while preserving the existing five-minute detector and main's
separately versioned synthetic policy.

- Preserve main's incident V001–V010 and processor V001–V016. Move the unshipped
  detector tables from conflicting incident V006 to additive V011; retain
  processor V017 and verify both fresh and populated upgrades.
- Preserve the first breached window's onset and the initial OPEN evaluation
  time. List/detail return a dedicated incident view with stable firstObservedAt
  and detectedAt; latestDetection and evidence items retain strict wire payloads.
- Correct the scope claim: the new hash episodes support read-only technical
  incidents/evidence. Analyst assignment/status/investigation/resolution/workflow
  audit and legacy UUID integration remain unavailable.

## Validation

Fresh correction verification: **PENDING**. Record final source commits, suite
results, migration checks, generated API/dashboard/browser checks and explicit
skips from the [correction report](2026-10-14-trace-link-day3-corrections.md).
Historical test/measurement counts are not fresh correction results.

## Acceptance boundaries

Activation stays disabled. Upstream 60/300-second compatibility, simultaneous
source-to-incident capacity, analyst workflow integration and human approvals
remain separate gates. No long capacity campaign was rerun. Original archived
measurements remain unchanged and tied to their recorded source identities.

See the [dated correction report](2026-10-14-trace-link-day3-corrections.md),
[runbook](../runbooks/trace-link-detection-day3.md) and
[migration ledger](../architecture/trace-link-migration-ledger.md).
