# Day 5 checklist

## PR #86 review reconciliation (2026-10-10)

- [x] Fetch main `9e8501b627a00d59f995e410e35d08ceb0ada149`; back up original `7694512` and rebase.
- [x] Preserve main authority, historical baseline, FeatureContext, worker fencing and migration safeguards.
- [x] Add all-city historical DB transition, receipt-boundary and CONTRACT_ONLY startup regressions.
- [x] Keep canonical parity and add distinct Day 5 persisted parity mode; require explicit geographic Java exports.
- [ ] Complete focused tests and historical-guard mutation checks.
- [ ] Run fresh full Java/Python/contracts/parity and packaged-model acceptance at an integrated code SHA.
- [ ] Publish a new manifest and review resolution report; update the same PR with a guarded push.

## Original candidate (superseded for integrated acceptance)

- [x] Record initial state and invariant hashes; configure test runtime.
- [x] Reproduce and fix city detector/baseline authority gaps.
- [x] Implement validated Python geography context and fresh Java parity.
- [x] Verify all-city policy, episode, replay and cause controls.
- [x] Run full Java/Python/contract and packaged/fallback ML checks.
- [x] Publish acceptance ledger, reproduction steps and limitations.
- [ ] BLOCKED: shared final-SHA API/browser evidence and human owner sign-off;
      protected application endpoints are unavailable and owner review is pending.
