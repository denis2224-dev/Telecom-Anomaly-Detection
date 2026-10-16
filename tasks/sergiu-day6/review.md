# Day 6 tooling review

Scope: new receipt verifier/tests, demonstration and handoff documents, input
freeze and local execution evidence. This is an author review, not an independent
human signature or review of the whole application.

- Correctness: two distinct sequential connected runs must bind to identical
  common-manifest bytes, pinned detector files, approved initial state and expected
  outcomes. Actual outcomes must match expectations, not merely each other.
  Gate and trace evidence files must exist and match hashes. Missing evidence
  fails closed. Seventeen focused tests cover these boundaries.
- Regression strength: an in-memory mutation removed the distinct-run guard;
  `test_incomplete_or_mismatched_runs_rejected` failed for duplicate run identity.
  Source files were not modified by that experiment; retain the mutation log.
- Architecture: additive offline tooling; existing producer verifier and frozen
  archives remain unchanged. No migration, model promotion or public API change.
- Security: receipt file paths cannot escape the checkout, outputs cannot be
  overwritten, and no credentials or signatures are generated. Raw receipts still
  require human inspection; a hash cannot establish authorship or live execution.
- Performance: artifact hashing is bounded by the recorded inventory; no daemon,
  new dependency or production execution path is introduced.

Review improvement: bind expected outcomes and initial-state identity to the
common manifest, preventing a receipt from redefining success after execution.
The first detection freeze is retained as `detection-inputs-initial.json`; the
current `detection-inputs.json` pins the resulting verifier/test revision.
The later type review rejected boolean measurements/exit codes and string
command arguments. Three failing-before/passing-after tests cover the correction;
the prior freeze is retained as `detection-inputs-before-type-review.json`.
The fixture no longer shadows `unittest.TestCase.run`. Repository Python then
passed 199 tests, including all sixteen verifier tests.
A further failing-before/passing-after regression preserves valid empty argument
values while rejecting an empty executable. Final repository Python passes 200
tests, including all seventeen verifier tests. The prior freeze is retained as
`detection-inputs-before-argv-review.json`.

Known operational failure: repeated held-out scoring exceeded its unchanged
250 ms response bound during overlapping local checks. Preserve the failed log
and verify an unchanged-input serial repeat before closing reproducibility.
This does not justify relaxing the response contract or retuning any model.
The unchanged-input serial repeat on 16 October passed and all three report
files match the first successful evaluation byte-for-byte. This supports
reproducibility under that execution condition; it does not erase the observed
latency-bound failure or establish performance under load.

Unresolved acceptance: common release manifest, connected environment, two
connected receipts, displayed-value trace and actual human acceptance.

Final verification: repository Python 200/200; full processor/dependency reactor
zero failures/errors; 17 explicit native-archive tests and 10 fresh-Java feature
tests pass. The completed earlier bootstrap suite contributes separate 12-test
archive evidence. Two external processor cases remain unverified and documented.
Raw failing attempts and every skip are retained; overlapping counts are not added.
Immutable evidence uses the repository's existing CRLF-aware whitespace attribute
pattern. This preserves raw hashes and keeps genuine trailing-space checks active.
