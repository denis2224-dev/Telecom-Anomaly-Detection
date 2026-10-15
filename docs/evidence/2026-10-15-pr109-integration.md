# PR #109 release verification integration

Observed 15 October 2026, Europe/Chisinau. Tested source: `93d2985e65e377ea2221f37c9a9c25d5a60ca78c`. Later evidence/documentation commits do not change the tested tools or Java sources. This PR adds release verification tooling and dated evidence; it does not deploy or accept a shared release.

## Changes and verification

The release capture records both canonical migration SQL and immutable historical compatibility SQL. It refuses to overwrite an existing output. The checker validates those files and the API against the pinned commit, verifies evidence checksums and safe paths, and rejects a superseded candidate, mismatched rehearsals, duplicate run IDs, or missing owner observations.

Review found that the effective push and new-domain scenario flags were absent from rehearsal/sign-off identities. Two regression tests failed across five cases before repair. Both booleans now belong to the identity; changing either invalidates prior rehearsals and all owner sign-offs. Capture defaults both to false without claiming that it has inspected a deployment. Operators must record the effective values before rehearsal.

- Ten release-tool tests pass, including canonical/historical SQL capture, changed activation flags, malformed scenario flags, altered evidence, and overwrite protection.
- Fourteen database migration/upgrade tests pass with no skips, failures, or errors. The populated canonical V004-to-V013 upgrade applies nine migrations, preserves legacy evidence/incident/audit/KPI fingerprints and V001–V004 checksums, validates thirteen total migrations, and applies zero on repeat. Runtime DDL remains denied with SQLSTATE 42501. Historical compatibility coverage retains its distinct migration histories.
- Repository Python checks: 186 tests, zero failures/errors, one existing platform-specific skip.
- Six referenced historical evidence checksums match. The backend candidate equals the shared manifest's backend fragment and remains rejected for eight expected reasons, including its superseded identity, missing capacity evidence, rehearsals, and owner sign-offs.

Counts, suite names and private-log checksums are in [verification.json](assets/backend-release/pr109-verification.json). Raw logs, credentials, cookies and database backups are not exported.

## Merge and release decisions

PR merge verification is separate from shared release acceptance. The repository owner authorized merging without coworker PR reviews. No operational owner acknowledgment is fabricated by that instruction. Full CI must pass on the final PR head before merge.

The old [candidate](../release/backend-release-candidate.json), [rehearsals](backend-rehearsals.md), [upgrade observations](assets/backend-release/upgrade.json), and [gate result](assets/backend-release/gate-check.json) retain their original source and results. A current shared candidate still needs fresh complete rehearsals and the pending capacity/Grafana/offline/browser/owner evidence documented in the [release checklist](../release/backend-checklist.md). Optional activation defaults remain off.
