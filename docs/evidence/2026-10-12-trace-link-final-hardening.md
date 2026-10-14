# PR #92 final producer hardening and consumer handoff

Producer source: `bb52660f762ae696c1de37aa400cef589b06e17d`.
Audited starting head: `25bb17974a5267bd06b2f2d734f813ad2dd86218`.
Stack base #91: `1e67e61ce3f2c46f26818b42c17fc3d50481aae4`.
Consumer approval: **PENDING_REVIEW**. PR #92 stays draft; delivery stays held.

The final publication adds documentation/evidence and an LF rule for these JSON
artifacts. Runtime, schemas, tests and verification scripts are identical to
the producer source SHA above. PR #92 records the exact publication head and
its six live CI job results separately from local verification. The fresh
reactor below supersedes the original report's combined regression coverage.

## IMPLEMENTED

The audit read the actual stacked diff, complete projection schema, Java
projection/rollup services, V014/V015/V016, isolated entrypoint, migration and
replay tests, importer and both runbooks. It retained the existing producer,
immutable held outbox and native rollup implementation.

Focused improvements reject MISSING source quality with measured primitives
and explicit zero source coverage with positive observed population, while
permitting legitimately retained STALE measurements. Schema conditionals now
bind effective quality/reason to source metadata and computability. Independent
Python validation recomputes native/projection identities, city translation,
window duration/alignment, primitive ordering and DECIMAL128 arithmetic.

Zero denominator remains null with ZERO_DENOMINATOR and cannot retain effective
VALID/PARTIAL, including a partially populated rollup bucket. Unknown metadata
keeps its unavailable reason; retained STALE evidence has precedence. New
database-backed regressions cover duplicate finalized natural slots,
conflicting held partition keys, contributing provenance in partially missing
buckets, and zero-denominator/stale precedence.

The [authoritative Day 1 state rules](../contracts/trace-link-day1.md#evidence-state-consistency)
and [producer boundary](../architecture/trace-link-projection-v1.md#quality-coverage-and-time)
distinguish source labels from effective quality. Source VALID plus missing
coverage is deliberately retained as metadata and produces insufficient
evidence; partial coverage produces effective PARTIAL. Neither may claim
effective VALID. Measured zero remains a value when its denominator supports
it. Missing slots remain query results with null primitives, never invented
persisted measurements. Historical age does not establish live freshness.

V014/V015/V016, source import implementation, historical fixtures/manifests and
incident-service files are unchanged from the audited head. V016 still binds
JSONB to exact payload text, has ID/natural-slot uniqueness and hash-bound
outbox foreign keys, and grants runtime only SELECT/INSERT. Java validates
message semantics and compares immutable bytes/hash on retry. No new migration,
publisher, delivery-state transition, topic or consumer integration was added.

## VERIFIED

Full machine-readable commands, per-suite counts, skip names/reasons, log hashes,
artifact hashes and previous-output comparisons are in
[verification.json](assets/trace-link-final-hardening/verification.json).
Raw XML was archived before any separate invocation overwrote reports.

| Invocation / check | Result |
| --- | --- |
| Fresh complete root `mvn clean verify`, committed producer source | PASS: 1,205 tests, 0 failures, 0 errors, 6 skips; 1,199 passed |
| streaming-support, same reactor | 117 tests, 0 failures/errors/skips |
| event-generator, same reactor | 95 tests, 0 failures/errors/skips |
| processor, same reactor | 993 tests, 0 failures/errors, 6 skips |
| Contract validation | PASS: complete contracts including 43 dictionary metrics, 54 arithmetic goldens, schema and CSV-derived native producer fixture |
| Repository Python discovery | PASS: 118 tests, 0 failures/errors/skips |
| Independent Java/Python/PostgreSQL/JavaScript arithmetic | PASS: 54 cases per implementation, maximum numeric difference 0; same fixture SHA-256 |
| Existing VoLTE/SMS parity | PASS: 7 voice and 12 SMS payloads, all fields compared |
| Existing geographic parity | PASS: 30 voice and 32 SMS payloads, all fields compared |
| Fresh persisted geographic parity | PASS: 90 voice and 90 SMS payloads, all fields compared |
| Supplied reference engine | PASS: 708,498 checks, 8,640 slots, 18 scenarios |
| V015 -> V016 and fresh install | PASS in projection IT: source preservation, one migration, idempotent rerun, matching schema/grants, legacy minute constraint retained |
| Source/import/projection replay, explicit archive selection | Separate PASS: 27 tests (11 importer, 16 projection), 0 failures/errors/skips; never added to reactor totals |
| Immutable input hashes | PASS: 29 pinned repository/reference files plus frozen arithmetic fixture; checked again after archive tests |
| Isolated #90 compatibility | PASS reproducing INCOMPATIBLE constraints; no incident database/API or Kafka delivery invoked |

The six default reactor skips are two opt-in full archive tests, the HTTP scorer
without ML_SERVICE_URL, the voice and SMS packaged-model assumptions without
that service, and SMS shadow replay without SMS_SHADOW_REPLAY_DIR. Both archive
tests are separately selected in the source/import/projection invocation. Those
separate results do not erase the six skips from the complete reactor or turn
the remaining optional local gates into passes.

The explicit supplied archive run imports 362,880 native rows and persists
8,640 city projections plus 8,640 held outbox rows. Exact retry changes no
identity or payload. All 121 UTC 6h buckets match independent native SQL sums;
the two history-edge buckets remain partial. Independently validate the fresh
[native message](assets/trace-link-final-hardening/trace-link-projection-native-example.json)
and [full archive results](assets/trace-link-final-hardening/trace-link-projection-full-result.json).
The verification manifest compares both with prior evidence, retaining exact
hashes and distinguishing byte equality from JSON equality.
Copied JSON reports use LF to match committed evidence bytes; original Windows
output paths and raw hashes are retained separately. This changes only report
formatting, not persisted projection payload bytes or source inputs.
The fresh [import result](assets/trace-link-final-hardening/trace-link-native-import-result.json)
also matches the prior measured import report. The prior evidence added a
`nativeBoundsUtc` annotation that the test output does not emit; that annotation
and the resulting hash/JSON difference are recorded explicitly.

Verification uses a new detached checkout with `core.autocrlf=false`, Java 21,
cached Maven 3.9.16, cached Python dependencies and disposable Docker services.
The complete root reactor contains streaming-support, event-generator and
processor, as declared by the root POM; it is not incident/API acceptance.

Reproduce from the producer source SHA in a fresh checkout:

```powershell
& $mavenPath -o --batch-mode --no-transfer-progress `
  '-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2' clean verify
python scripts/check-contracts.py
python -m unittest discover -s tests -v
python scripts/verify-trace-link-pins.py --reference-root $referenceRoot

& $mavenPath -o --batch-mode --no-transfer-progress `
  '-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2' -pl services/processor -am `
  '-Dtest=TraceLinkDatasetBootstrapIT,TraceLinkProjectionIT' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  "-DtraceLink.referenceRoot=$referenceRoot" `
  "-DtraceLink.projectionReferenceRoot=$referenceRoot" test
```

The manifest records exact arguments for all four independent arithmetic
exports and each legacy/geographic parity command. PostgreSQL arithmetic used
an explicitly labelled disposable container with network disabled; it was
stopped afterward. The source archive was selected explicitly, not regenerated.
Existing demo data, accounts and volumes were not changed.

Preliminary selection/environment failures are retained separately: module-style
Python selection resolved to a non-repository package; discovery succeeded.
The sandbox could not access Docker or the cached dependencies; authorized
reruns passed. The preliminary focused Docker-enabled run had 24 tests, no
failures/errors and one archive skip before the final added regression. No
overlapping invocation contributes to the fresh complete reactor's totals.

## PENDING_REVIEW

The [consumer decision package](../architecture/trace-link-consumer-handoff-v1.md)
contains the exact source SHA, schema/fixtures, dataset/catalogue versions,
canonical KPI/population, domain translation, policy/topology checks, city and
partner scope semantics, quality/coverage/null distinctions, primitives and
provenance, immutable deduplication, topic/key proposal, additive consumer
migration requirements and authenticated acceptance examples.

[Read-only compatibility evidence](assets/trace-link-final-hardening/consumer-compatibility.json)
pins five #90 source blobs. Current metric/policy identifiers differ; wire VOLTE
needs an explicit approved API translation; VARCHAR(16) and its enum reject
INSUFFICIENT_EVIDENCE. Current queries collapse quality states, fail to compare
source/catalogue topology, use source coverage instead of independent slot
counts, include slots outside requested partial edges and omit required
provenance/null reasons from read DTOs. These are isolated constraint checks
and source findings, not proof of consumer ingestion.

Denis's #89/#90 branches remain unchanged. Their green CI and this producer's
green checks do not imply Denis's agreement. No approval is asserted.

## BLOCKED

Kafka delivery/ACK/mark recovery, approved incident-owned ingestion and additive
storage, unauthorized/authenticated imported-source API results, receiving-owner
reproduction and shared activation remain blocked on the consumer decision and
subsequent approved implementation/connected verification. No production topic
was created, no held outbox row released, no incident-service table modified,
and no automatic delivery or merge enabled.

After Denis records the contract decision, the smallest proposed consumer step
is one isolated incident-owned fixture acceptance test against an additive
read-model migration, including identical/conflicting retries, with an inactive
catalogue and no production topic. This step has not been started.
