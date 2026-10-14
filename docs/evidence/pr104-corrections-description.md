# PR #104 description — review corrections

**Title:** Fix Trace Link integration, incident timestamps and technical-only scope

Ready for independent review and manual publication. This file does not publish
or approve the PR.

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

Verified implementation `67aa3a50cd742719def549d33590a1fc60ccffe9` against pinned main `9db3e0e`:

- Root Python 174 tests and ML Python 74 tests pass, with no skips.
- Root Maven: 1380 tests, 0 failures, 0 errors, 8 skips. Separate receiver: 188 tests, 0 failures, 0 errors, 1 skip in unit tests and 114 tests, 0 failures, 0 errors, 0 skips in integration tests. Skips are existing external ML/archive/shadow opt-ins, listed in the correction report.
- Real PostgreSQL fresh/populated upgrades preserve 26 prior main migrations, rows and grants; repeat migration is a no-op.
- Timestamp, API/auth/bounds, restart/reordered replay and real Kafka ACK/mark recovery regressions pass. Fresh replay reconciles 21 unique detections across 42 deliveries/offsets into three recovered episodes.
- Contract/frozen-pin/API-generation checks pass; dashboard 181 unit tests, build and 13 affected browser tests pass; Compose structure and whitespace pass.

The initial reactor failure from stale latest-migration assertions and all
corrected results are preserved in the [correction report](2026-10-14-trace-link-day3-corrections.md).
The receiving evidence is a separate subsequent replay, not connected capacity.
No new GitHub CI result or independent approval is claimed.

## Acceptance boundaries

Activation stays disabled. Upstream 60/300-second compatibility, simultaneous
source-to-incident capacity, analyst workflow integration and human approvals
remain separate gates. No long capacity campaign was rerun. Original archived
measurements remain unchanged and tied to their recorded source identities.

See the [dated correction report](2026-10-14-trace-link-day3-corrections.md),
[runbook](../runbooks/trace-link-detection-day3.md) and
[migration ledger](../architecture/trace-link-migration-ledger.md).
