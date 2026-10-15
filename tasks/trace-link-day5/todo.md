# Day 5 acceptance checklist

- [x] Create requested branch from merged main; preserve unrelated work.
- [x] Add frozen evaluation protocol, disjoint splits/seeds and external truth.
- [x] Test evaluator arithmetic, episode matching, integrity and leakage guards.
- [x] Export actual Java rules/lifecycle and independent cause controls.
- [x] Evaluate original normal holdout and labelled study without retraining.
- [x] Add all five ablations and retain unsupported metrics explicitly.
- [x] Add manifest mismatch and durable backlog/restart drill coverage.
- [x] Add verifier, CI integration and operator runbook.
- [x] Finish fresh regression/drill evidence and repeated-result verification.
- [ ] Second person rebuilds/evaluates and diagnoses missing model/data from runbook.
- [ ] Record Day 5 acceptance only when remaining required receipts exist.

## PR #111 platform correction

- [x] Separate portable seed isolation from explicit Windows byte reproduction.
- [x] Add scoped verifier outcomes and Windows gate failure propagation regressions.
- [x] Preserve the required CI chain and add Linux leakage/integrity checks.
- [x] Document Windows-full and Linux-runtime commands and pending acceptance.
- [x] Run Python 3.13.9 checks on both platforms and the strict Windows byte gate.
- [x] Publish correction evidence, verify frozen hashes, and push correction commits.
- [ ] Required CI checks pass on the corrected PR head.
- [ ] Review a real independent Windows/Java/Docker/reference-pack receipt with fresh Java export.

Platform commands, from the repository root with runbook prerequisites installed:

- Windows: `.venv/Scripts/python.exe scripts/verify-trace-link-day5.py --output tmp/day5-windows-full-new`.
  Expected technical success: `PASS`, scope `WINDOWS_FULL`, Windows byte reproduction `PASS`.
- Linux: `.venv/bin/python scripts/verify-trace-link-day5.py --output tmp/day5-linux-runtime-new`.
  Expected applicable success: `PASS`, scope `PORTABLE_RUNTIME`, Windows byte reproduction `NOT_RUN`.

Use a new output directory and `--reference-root` when needed. Applicable failures
must yield `INCOMPLETE` and nonzero exit. Both scopes keep independent reproduction
`PENDING`; the Windows byte gate, CI and reviewed human receipt remain mandatory.
