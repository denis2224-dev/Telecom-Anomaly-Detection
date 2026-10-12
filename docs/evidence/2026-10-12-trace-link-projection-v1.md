# Trace Link Phase C producer slice

This is the original implementation evidence, retained for provenance. The
[final hardening report](2026-10-12-trace-link-final-hardening.md) supersedes its
combined regression coverage with a fresh complete reactor on committed source.

Disposition: **PARTIAL**. Producer projection/primitive rollups are implemented
and independently verified. Consumer agreement is **PENDING_REVIEW**; connected
Kafka ingestion and authenticated historical API acceptance are **BLOCKED**.
Phase C is not complete and this is not release approval.

## Revision and dependencies

Implementation commit: `af045a50dc0092a77517ad427faadadbc86f967d`.
Test inventory correction: `633feb5319127018bd351df65c8eed8e96c0870a`;
production code is identical to the implementation commit.
The following evidence-only commit records its results; it changes no runtime
code. Branch: `feature/ion-trace-link-projection-v1`, stacked on Ion PR #91.

| Upstream | Inspected SHA | Result |
| --- | --- | --- |
| main, merged KPI/evidence #88 | `cbb94d37f7593e99737ee4200e91b14e0512f76f` | Dictionary retained |
| #91 native import | `1e67e61ce3f2c46f26818b42c17fc3d50481aae4` | Draft, six checks now passed |
| #89 backend contracts | `fec8905d7ba5563a8ebfe0aa541c16b1cb93b75b` | Open, six checks passed |
| #90 backend persistence | `923a9f009ce402b84cc26882466e7c2e3989a136` | Open, stacked on #89, six checks passed |

No approval or inline review mapping was present on #89/#90/#91. The requester
confirmed no Denis approval and authorized producer-only continuation. No
incident-service, OpenAPI, dashboard or owner branch was modified; no PR was
merged. The dirty original checkout and existing demo were left untouched.

## Implemented boundary

- Frozen [producer agreement](../architecture/trace-link-projection-v1.md), strict
  message schema, native CSV-derived fixture and independent Python validator.
- Additive processor V016: immutable `trace_metric_projection` plus
  `trace_projection_outbox`, with runtime SELECT/INSERT grants only. Exact
  payload bytes and their SHA-256 are retained alongside checked JSONB.
- Explicit native projector: validates dictionary, dataset completion, scope,
  duration, integrity and integer primitives; atomically inserts projection and
  delivery intent. Stable natural identity, concurrent retry and conflict checks
  prevent duplicate or changed finalized facts.
- Local 5m/15m/1h/6h primitive rollups: whole UTC buckets, half-open intervals,
  whole-source-slot inclusion at partial query edges, explicit missing points,
  null zero-volume values, version rejection and provenance projection IDs.
- Isolated `TRACE_LINK_PROJECTION_MODE` entrypoint and
  [runbook](../runbooks/trace-link-primitive-projection.md). It starts no live
  workers or Kafka publisher.

All delivery intent is `PENDING_REVIEW`. No new topic is introduced. Existing
one-minute V2 feature/outbox constraints and V014/V015 migrations are unchanged.
Existing importer/ingestion migration tests account for V016 and verify the
new tables' migration ownership and immutable runtime grants.

## Persisted actual reference examples

The explicitly selected supplied archive imported **362,880 native rows** into
disposable PostgreSQL. The projector then persisted **8,640 Chișinău projections
and 8,640 held outbox rows** for
`[2026-09-10T09:00:00Z,2026-10-10T09:00:00Z)` at 300 seconds. Exact retry changed
zero identities or payloads. This is actual imported source, not an API fixture.

Dataset: `trace-link-md-30d-v1`; manifest SHA-256:
`e3033f025f6b5866edc0ed34f3078cfa9f9c01cb126eac4577055ef30247ee1b`.

| Example | Numerator | Denominator | Percent | Slot coverage | Quality |
| --- | ---: | ---: | ---: | ---: | --- |
| Native chi 09:00–09:05 UTC, 10 Sep | 7,900 | 7,957 | 99.2836496166897 | 1/1 | INSUFFICIENT_EVIDENCE |
| First partial 6h bucket, 06:00–12:00 UTC | 277,984 | 279,910 | 99.3119216891144 | 36/72 | INSUFFICIENT_EVIDENCE |
| First whole 6h bucket, 12:00–18:00 UTC | 297,917 | 299,992 | 99.3083148883970 | 72/72 | INSUFFICIENT_EVIDENCE |

Every 6h bucket's numerator and denominator were checked against independent
SQL SUMs over native `voice_success`/`voice_attempts`. The full month returned
8,640 native points and 121 whole UTC 6h buckets; first and last buckets are
partial because history begins/ends at 09:00 UTC. No source slot was upsampled.
Both source quality and source-supplied coverage remain null. Archive integrity
and complete slot coverage do not turn unknown measured quality into VALID.

The bounded independently imported slice also projects all ten approved cities.
Its first CSV source row SHA-256 is
`7fdcd4963c7c76d121a6fed0685a3f299ac82eef69f2457b881308cb6ceff63c`.
See [full persisted results](assets/trace-link-projection-v1/full-archive-result.json)
and [bounded persisted example](assets/trace-link-projection-v1/bounded-native-example.json).

## Executed verification

Commands and artifact hashes are retained in
[verification.json](assets/trace-link-projection-v1/verification.json).

| Check | Outcome |
| --- | --- |
| `python scripts/check-contracts.py` | PASS; 43 KPI definitions, all 54 arithmetic goldens, source archive/domain and new producer schema/fixture |
| Repository Python discovery with cached OpenAPI dependencies | PASS; 115 tests |
| Focused projection/arithmetic PostgreSQL tests | PASS; 20 tests, 0 failures/errors, 1 opt-in full archive skip |
| Explicit full supplied archive projection test | PASS; 1 test, 0 skips; 362,880 source / 8,640 projection / 8,640 outbox rows |
| Initial clean committed full Maven reactor | FAIL; 1,200 tests, one stale table-count assertion (24 expected, 26 actual), 6 skips; all other cases passed |
| Corrected affected ingestion class and reactor packaging | PASS; 28 tests, 0 skips, BUILD SUCCESS on `633feb5` |
| Combined regression coverage across those runs | PASS; 1,194 passed, 6 skipped; the full reactor was not rerun after the test-only inventory/grant correction |
| Legacy VoLTE/SMS Java/Python parity | PASS; 7 voice, 12 SMS, 30 geographic voice, 32 geographic SMS; maximum numeric difference 0 |
| Reference engine | PASS; 708,498 checks, 8,640 slots, 18 evaluator scenarios |
| Committed immutable inputs and actual reference files | PASS; 29 files and independent fixture checksum |
| Raw worktree immutable-byte verifier | FAIL; existing Windows CRLF checkout representation differs from committed pinned text bytes; no source input was changed |
| Kafka send failure/recovery and replayed consumer messages | BLOCKED; no approved compatible consumer/topic |
| Unauthorized and authenticated imported-source series requests | BLOCKED; producer-only branch has no new API; #90 mapping is unapproved |
| Receiving-owner reproduction, deployment/shared release acceptance | PENDING / NOT VERIFIED |

The initial focused run reproduced a real JSON numeric representation retry
bug (Jackson Int/Long/Decimal node equality). It was fixed by retaining and
comparing immutable serialized bytes. The first run had two failures and two
errors; an intermediate rerun had three errors, including a constraint-name
test assumption. The final focused run passed. A real PostgreSQL connection
termination before commit raises `TransactionSystemException` because rollback
cannot use the closed connection; tests verify both rows are absent and exact
retry succeeds. PostgreSQL connection denial immediately after commit retains
both durable rows and exact replay. These are disposable database failure
checks, not mocks or claims of Kafka acceptance.

The full reactor's sole failure was the existing hard-coded table inventory.
The corrected test expects both additive tables, checks their migration owner,
and checks SELECT/INSERT while rejecting UPDATE/DELETE/TRUNCATE privileges.
Its entire 28-test class and packaging were rerun successfully. All remaining
full-run suite results are retained from `af045a5`; only test assertions and
the architecture handoff changed at `633feb5`. This is combined suite evidence,
not a claim that the initial full Maven invocation was green.

The six default reactor skips were the two explicitly selected archive tests,
`ML_SERVICE_URL` for the HTTP scorer, `SMS_SHADOW_REPLAY_DIR`, and the two live
packaged-model delivery assumptions. The full projection archive test was
verified separately with the supplied archive explicitly selected; optional
ML/shadow gates remain skipped.

The first Python invocation lacked OpenAPI dependencies; a subsequent sandbox
attempt could not read the existing dependency cache. The authorized cached
dependency run passed all 115 tests. The raw pin verifier's line-ending failure
was retained separately; the unchanged verifier passed on exact committed blob
bytes in an isolated snapshot against the actual external reference files.
Graphify was updated through its Python module after its launcher failed; SQL
AST extraction was partial because `tree_sitter_sql` is not installed.

## Denis handoff and remaining acceptance

The [agreement table](../architecture/trace-link-projection-v1.md#proposed-consumer-agreement-requiring-denis-review)
explicitly separates `voice.success_rate` from `volte.setup.success`, API `volte`
from wire `VOLTE`, city metric scope from observation `CITY_SERVICE`, dictionary
policy from #90's policy, and source quality from slot presence. There are no
silent aliases. The proposed topic/key and message semantic validation are
documented, but are not activated.

After approving the mapping, Denis must own additive incident-side validation,
dataset/inventory registration, immutable insertion/content deduplication and
read DTO changes. Supply the existing pinned dataset manifest and geography
contracts together with these projection messages; processor runtime never
connects to `incidents_db`.

Proposed authenticated query after approval (not a currently verified response):

```http
GET /api/v2/metrics/series?domain=volte&metric=volte.setup.success&scopeType=CITY&scopeId=chi&from=2026-09-10T09:00:00Z&to=2026-09-10T09:05:00Z&resolution=5m&datasetId=trace-link-md-30d-v1
```

Expected primitives/value are 7900/7957 and approximately 99.2836496166897;
dataset must remain synthetic with the stated policy, topology, source row
provenance and half-open five-minute bounds. Slot coverage is 1, source quality
and source coverage are null, and effective quality is INSUFFICIENT_EVIDENCE.
The lowercase API domain is an explicit proposed boundary translation requiring
approval, not a producer alias. Then reproduce a larger native interval and the
whole 12:00–18:00 UTC rollup above, plus unauthorized requests, Kafka send/ACK
failure, restart/replay and conflicting retries. Only connected evidence can
close Phase C; shared owner acceptance remains a separate release gate.
