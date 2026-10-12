# Independent review of Day 2 coordination and evidence publication

Reviewer: rules/lifecycle worker, separate from coordinator implementation.
Reviewed: verification/publication scripts, combined offline integration tests,
Java/Python shared result boundaries. No Maven invocation or production edits
were performed by this reviewer during the shared reproduction run.

## Findings and disposition

- **P2 — source identity:** publisher records `git rev-parse HEAD` as the tested
  implementation revision. At review, HEAD is the integration base `99adf9e`
  while implementation files are uncommitted. Before final publication, commit
  and reproduce the implementation or record its dirty status and complete
  tested source hashes. A base commit alone cannot identify the tested source.
  **Disposition:** source hashes now cover contracts, Day 2 implementation,
  tests, policies and verification scripts. Coordinator will commit the final
  implementation before the final reproduction, so its manifest also pins that
  tested commit. Final publication remains conditional on this reproduction.
- **P2 — fresh test totals:** publisher aggregates every existing Surefire XML
  without checking report modification time against reproduction `startedAt`.
  Stale reports can inflate claimed fresh totals or introduce an obsolete
  failure. Count only fresh reports and require the current mandatory suites.
  **Resolved in source:** reports older than reproduction `startedAt` are
  excluded, and all eight Trace Link suites must have fresh reports before
  successful publication. Failure/error totals are checked on that fresh set.
- **P2 — result semantics:** the original Java/Python result validators accept
  arbitrary anomaly-kind strings independently of domain/procedure. A
  non-mutating Python probe confirmed a TRANSPORT result labeled MOBILE_DATA_DNS
  passes. Coordinator is correcting the versioned kind/domain/procedure map;
  the worker added focused Java rejection tests before that correction.
  **Resolved in source:** the result schema enumerates all twelve supported
  anomaly kinds. Java and Python enforce domain and roaming procedure agreement,
  including mapped service scopes; mapped kinds require their complete service
  scope. Java raw roaming metric evidence also requires its matching procedure.
  Focused tests reject wrong-domain, unsupported mapped and wrong-procedure
  results. These corrections require the final rerun below.
- **Optional — publication staging:** exports are copied before frozen hashes,
  Java totals and runtime metadata are fully checked. Failure correctly stops
  the command, but leaves partially refreshed assets alongside an older
  manifest. Validate first or publish through a staging directory.
  **Resolved in source:** export validation, frozen-file checks, fresh test
  totals, source hashing and external runtime discovery now run before the first
  published-file write. Filesystem failure during the copy itself is still not
  an atomic directory swap; that optional robustness improvement is not a
  blocker for the approved offline workflow.

## Confirmed behavior

- Native commands propagate failure through checked exit codes; the nested Day 1
  script uses terminating failures under Stop error handling.
- Day 2 exports must be newer than the reproduction start, and both independent
  compressed historical builds must equal the published artifact checksum.
- Integration tests exercise hand-derived training centers, direct lookup,
  three-domain breaches, two-window opening, identical replay and supported versus
  unresolved attribution. Separate tests exercise actual published-artifact
  direct/static/unavailable lookup behavior.
- Reference eligibility output is explicitly labeled a negative fixture, not a
  claim that historical source measurements have live freshness.
- JSON result validation checks exact five-minute intervals, explicit evaluation
  time, scope namespace, breach evidence and severity consistency. Mapped service
  results preserve complete service scope for episode identity.

## Verdict

**Static review approved, contingent on a final GREEN reproduction of the final
implementation commit.** The three blocking findings are addressed in the
reviewed source or, for the commit pin, by the explicitly required final commit
and reproduction. Algorithm integration has no additional blocking finding.
This review does not claim that the in-progress broad run includes source
changes made after its compilation, and does not replace the final test result.
