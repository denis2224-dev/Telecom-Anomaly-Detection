# Ion Trace Link PR consolidation — 14 October 2026

Scope: only PRs authored by `itsjohnyoff`. Starting main:
`ce9569e41f1756fca56a90996a6f7cf7bfb1b1b1` (merged PR #101).
PRs #103 and #104 are read-only dependencies. Their unmerged implementations,
migrations, notification changes, incident receiver, chronology fixes and capacity
harness are excluded from this candidate. The earlier broad candidate and its
logs are preserved separately; its test results do not qualify this candidate.

## Disposition and lineage

| PR | Exact original HEAD | Disposition | Evidence |
|---|---|---|---|
| #93 | e9a79c1f2fa2c5a59b366336e74b4e3b2b94bbb8 | Already included | HEAD is an ancestor of starting main; native producer/source subtree retained |
| #95 | e3d0a41da141f2a836edbd35258ba878efc335dd | Already included | HEAD is an ancestor; scoped scenario engine, exact command codec and controls retained |
| #96 | 8fb0596b415b4fef137b29b38774948d3112c1a0 | Already included | HEAD is an ancestor; replay/checkpoint/restart verification retained |
| #97 | 5d2b6d102ac2914ede7bfc812ad51ebb3b20dc2c | Already included | HEAD is an ancestor; producer preflight/rehearsal evidence retained |
| #100 | db6c0e9bb6d034383b8b109725462396496d1b8f | Already included | HEAD is an ancestor; local runtime, protected scenario APIs and dashboard retained |
| #102 | 96209596986e70737be87b68b43a768fa8882fe9 | Complete on current main | Only two commit patches remained: accepted-render browser regression and evidence; both retained |
| #101 | 9666696dc6289b0644ab480ee1bf4a56543d7240 | Already merged | GitHub merge ce9569e41f1756fca56a90996a6f7cf7bfb1b1b1; preserved as candidate ancestor |

Producer lineage is #93 → #95 → #96 → #97 → merged #99 → #100.
PR #101 reconciled those contributions with merged #98. Current main already
contains the runtime and contract implementation from that stack. The remaining
#102 test now preserves both supported cases: opening a persistent dialog during
loading, and keyboard expansion after accepted reset buckets render. Neither test
requires the older assumption that the opener disappears while loading.

## Narrow compatibility corrections

The supplied review pack pinned consumer #98 at `6152d13f` and producer #99 at
`94950a35`. Its historical VoLTE regressions are preserved as
`ReviewAcceptanceIT` and exercised through actual parser, PostgreSQL and query
boundaries:

- Select contributions with native start ≥ requested start and native end ≤
  requested end. Keep expanded UTC output buckets and expected slot counts.
  The 09:00–09:05 slot cannot contribute to a 09:01–09:04 request.
- Preserve source STALE/unknown-quality reasons independently of arithmetic
  `nullReason=ZERO_DENOMINATOR`.
- Expose `sourceNamespace` from stored original payload bytes in contributor
  provenance, both OpenAPI copies and generated Angular types.
- Name the canonical population precisely: eligible established calls / eligible
  network setup attempts. Keep `volte.setup.success` distinct from
  `voice.success_rate`.
- Label the static compatibility checker `LEGACY_V006_ONLY`; it does not decide
  canonical V008 ingestion/API acceptance.

No applied migrations change. Processor, generator, shared-support, notifications
and every migration subtree remain equal to starting main. In particular, the
V016 held outbox and all historical identities/bytes remain intact. New teammate
V009/V006 definitions and V017/V011 detector/receiver migrations are absent.

## Acceptance gates

| Gate | Status | Exact boundary |
|---|---|---|
| Connected delivery | BLOCKED | Own runtime creates local output and reports DISABLED_PENDING_REVIEW with offered=acknowledged=0. V016 allows PENDING_REVIEW only. No approved connected publisher/listener exists in this scoped candidate. Transaction/idempotency component checks cannot prove broker ACK, offset commit or crash recovery of an absent connection. |
| Trusted runtime freshness | NOT VERIFIED | Synthetic-minute and native-history validation remain distinct. Missing/stale/partial evidence and forged results are component controls. Fixed-clock/replay cases do not establish genuine deployed observation freshness. No untrusted freshness may establish operational health or recovery. |
| Simultaneous end-to-end capacity | INCOMPLETE | The approved scoped candidate lacks the connected path required for matching generator→Kafka→collection/finalization→detector→receiver→PostgreSQL intervals. Prior persisted-window injection and subsequent receiver replay are not simultaneous capacity evidence. |
| Live activation | NOT APPROVED | Scenario and native read-model flags remain false; Kafka delivery remains disabled and V016 held. No infrastructure, credentials, profiles, catalogues or shared data are changed. |

Implementing the missing connection or importing the teammate detector/receiver
would cross the corrected ownership boundary. Those PRs remain untouched. These
activation gates do not authorize weakening code, security, test assertions or
repository protections to obtain a merge.

## Verification and merge record

The exact candidate must pass incident-service verification, producer/contract
tests, Python/ML checks, mirrored API generation, dashboard unit/build/browser
checks and normal GitHub CI before merge. Required repository approval count was
zero at inspection; normal PR merge remains mandatory and bypass is unavailable.
No required failed check may be bypassed.

Fresh command/revision/duration/exit records, XML reports and logs are retained in
`C:/OrangeSystems/Program/ion-pr-consolidation-evidence-20261014`. Earlier broad
attempt evidence remains in `final-consolidation-evidence-20261014` and is
explicitly separate. The final verification result and main merge SHA belong to
[PR #102](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/102)'s
same-head checks and completion receipt; this document does not predeclare them.

Close only the six listed own PRs whose unique work is demonstrably present after
successful integration. Keep #103/#104 and their branches untouched. Do not delete
remote branches. Feature-off rollback preserves all existing schema/history;
normal PR revert is available for code rollback after compatibility review.
