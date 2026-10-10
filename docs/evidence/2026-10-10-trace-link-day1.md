# Rusu Sergiu — Trace Link Day 1 evidence

Assignment date: **10 October 2026**. Implementation and local verification:
**11 October 2026**, Europe/Chisinau. Branch: `codex/trace-link-day1`.
Implementation base: `30c9a47b7f58d46f7fbfab08f2fa2d5959da5cea`.

This bundle implements the Day 1 measurement contracts and independent offline
arithmetic. Receiver reproduction and shared acceptance remain pending; see
`tasks/trace-link-day1/handoffs.md`. No receiver acceptance is inferred from tests.

## Delivered scope

- 43 domain-qualified KPI definitions with population, unit, primitives,
  aggregation, scope, quality, policy and reference availability; strict dictionary
  and additive evidence schemas, six API-ready evidence examples and negative tests.
- Independent Java, Python, JavaScript and PostgreSQL calculations for seven
  operations: ratio-of-sums, pooled finite-bin p95, city summaries, valid-time
  transport mean/peak utilization, queue conservation, link snapshots and
  country/partner/direction roaming summaries.
- 54 frozen hand-derived cases, fresh export envelopes preserving exact
  scope/window/policy context, mandatory four-language comparison and immutable
  input checks. No expected value is passed into a calculator.
- Shared validation registration and test-only Maven fixture resources; a
  reproduction script and receiver-specific handoff ledger.

Four agents worked concurrently with exclusive ownership: coordinator (goldens,
registration/integration/evidence), contracts worker, Java/Python worker and
SQL/JavaScript worker. Workers independently reviewed arithmetic and contracts.
Only the coordinator ran Maven and Git integration.

## Frozen inputs

`assets/trace-link-day1/input-manifest.json` records 17 frozen repository files
(V2 rules/schema, feature order/schema, detection schema, baselines and six model
artifacts, plus observation/baseline schemas and topology catalogues), 11 external
reference files, assignment/implementation dates and base. The final four extra
schema/topology pins were checked unchanged against the implementation base.
The seven reference data files also match their supplied manifest hashes and sizes.

| Input | SHA-256 |
| --- | --- |
| Hand-derived arithmetic suite | `8fbeebc97c4ee7a2ba21d6ec4db10aac116aa9e031716b8d6191605f195eb357` |
| Reference `source/engine.js` | `a150367b50474afaf4d0016dfa467d38140c5216e390ef16d27c5098362f62a9` |
| Reference `sql/001_schema.sql` | `1376a0842ce74dcb4b92e36621398b88cfc711fd1af17874f47f8c58e222ae2f` |
| Reference `sql/grafana_queries.sql` | `b4af5cd1bdba806ded200baeabfd6a76d82f72dbff5e767b3e2a86725316c024` |
| Reference `data/manifest.json` | `e3033f025f6b5866edc0ed34f3078cfa9f9c01cb126eac4577055ef30247ee1b` |

The reference pack was read without modification. Exact-byte Git attributes
preserve the frozen suite and archived JSON evidence across checkout line-ending
conversion. Prior untracked material and historical acceptance records were preserved.

## Numerical controls and review

| Operation | Cases | Controls |
| --- | ---: | --- |
| Ratio | 10 | 990/1000 + 9/10 = 98.91089108910892%; zero, negative/impossible/unsafe counters, booleans and truth/future fields |
| Histogram | 7 | Pool before interpolation, voice/SMS edges, seconds→milliseconds, empty, mismatched/unordered bins and late invalid counts |
| Valid-time utilization | 7 | Capacity reduction and changing segments, partial/stale/missing capacity, zero capacity, physical and coverage bounds |
| Queue | 3 | Conservation, signed growth, pending excluded from finalized outcomes |
| City summary | 8 | Last snapshots, disjoint simultaneous cities, partial/missing data, mixed latest times, impossible histograms, duplicates and boundary timestamps |
| Link snapshot | 10 | Period end exclusive, point inclusive, endpoint selection, stale/missing capacity, dataset bounds and physical limit |
| Roaming | 9 | Country/direction isolation, round each allocation then sum, safe integer limit, stale precedence, mixed snapshots and truth rejection |

The submillisecond-window rejection is counted in the ratio group. Counters and
metadata compare exactly; finite derived floats use absolute tolerance **1e-9**.
Missing a language, stale fixture SHA, extra/omitted results, altered context or
non-finite output fails verification. Supplemental PostgreSQL boundary controls
are saved separately and are not counted as four-language goldens.

Cross-review fixed physical capacity bounds, point selection outside dataset
coverage, common millisecond timestamp precision, mixed-time snapshot aggregates,
maximum-safe-integer allocation rounding, safe evidence counters, irrelevant
scope dimensions, SMS procedure labels and exclusive-end evidence snapshots.
Coordinator review additionally hardened fixture output types and ensured the
reproduction script deletes only its known prior Java export/report and requires
fresh successful Java tests. An in-memory boundary mutation (`< to` → `<= to`)
was detected by the golden tests; source files were not mutated.

The final interface audit added explicit per-value unit metadata to fixture and
export contexts. Numerical expected bodies stayed unchanged; all exports were
regenerated against the final suite SHA above.

## Reproduction

Prerequisites: JDK 21, Maven wrapper dependencies, Python with `requirements-dev.txt`
and `services/ml-service/requirements-ml.txt`, Node.js, Docker and PostgreSQL
`postgres:16.4-alpine`. Docker must be usable by the invoking shell. The reference
root defaults to the pinned local path and can be supplied explicitly.

From the repository root, use actual local interpreter/JDK paths:

```powershell
./scripts/verify-trace-link-day1.ps1 `
  -Python ./tmp/pr78-review/python/Scripts/python.exe `
  -JavaHome './tmp/tools/jdk21/jdk-21.0.12.1+1' `
  -ReferenceRoot 'D:/Univer/Anul2/Orange_intership/project/Trace_Link_Integration_Pack'
```

The default runs contracts, repository and ML Python suites, sequential reactor
verification, legacy/geographic parity, fresh language exports and four-way parity.
`-Focused` runs the same contract/repository tests and Day 1-specific Java/Python
tests, omitting the broad ML/reactor/legacy regression. It is suitable for receiver
arithmetic reproduction; it is not a claim that the broad suites ran again.

PostgreSQL runs only in a new labelled, network-none disposable container with
no published ports. The script installs repository-owned `tl_parity` functions
there and stops the container in `finally`. It does not import the pack's demo
schema into an application database. Python transports JSON to PostgreSQL;
PostgreSQL itself performs all SQL calculations.

To inspect archived results without claiming freshness:

```powershell
python scripts/check-trace-link-parity.py `
  --java-output docs/evidence/assets/trace-link-day1/java.json `
  --python-output docs/evidence/assets/trace-link-day1/python.json `
  --sql-output docs/evidence/assets/trace-link-day1/sql.json `
  --javascript-output docs/evidence/assets/trace-link-day1/javascript.json
python scripts/verify-trace-link-pins.py
```

## Reference adaptations and limitations

`reference-compatibility.json` records 75 concordance checks against raw `getRow`
inputs and named adaptations. Period snapshots exclude `to`; explicit point
queries include `at`; outside coverage returns missing rather than clamping.
Mean utilization is separate from the engine's peak. SQL city user estimates
round each partner/direction allocation before summing, matching selected JS
behavior. Procedure counters stay raw. SMS p95 explicitly converts seconds to
milliseconds under the new metric policy.

The separate CSV check samples nine deterministic rows and checks 159 primitives.
The maximum primitive difference is `4.879813268743316e-7`, within six-decimal
quantization. Maximum utilization difference is `2.2918716950925955e-6` percentage
points, within the derived bound `2.500000070345902e-6`. This is sampled agreement,
not a full CSV import/completeness test. It does not relax the 1e-9 golden tolerance.

The engine's 708,498 checks and the 23-check DOM shim smoke test pass. The DOM shim
is not real-browser verification. Reference SQL files are pinned and reviewed;
the executed independent SQL is `scripts/trace-link-reference.sql`. This does not
claim Grafana queries or runtime persistence integration have been exercised.

The pack cannot establish post-activation DNS eligibility, exact SMS mean delay,
sample-weighted RTT/loss, raw byte volume or link/LAG state. These target metrics
are defined with honest missing-primitive availability, not fabricated measurements.
Roamer city allocations are synthetic estimates, not measured distinct people.
Mixed latest snapshot times cannot produce a coherent queue/users snapshot.
Source history is `[2026-09-10T09:00Z,2026-10-10T09:00Z)`, at five-minute resolution
and A→B direction only. It is historical data at verification time.

Existing V2 raw-sample SMS percentiles, one-minute behavior, policies, feature
order and model artifacts retain their original bytes. Seasonal baselines,
thresholds, detectors, ingestion, API/UI implementation and ML remain later work.
Scenario events and incident lists are evaluator/demo material and do not prove
independent detection or causality. Dependency claims require later authoritative
topology and controls; schema-valid metadata alone does not establish a cause.

## Local command results

| Verification | Result |
| --- | --- |
| Registered contracts | 43 metrics, six evidence examples, 54 goldens pass |
| Fresh four-language reproduction | 54/54 per language; maximum difference **0** in Java, Python, SQL and JavaScript |
| Final repository Python suite | **97 tests pass** |
| Full ML Python regression | **61 tests pass** at the earlier checkpoint; final helper-only changes verified by **nine focused tests** |
| Java reactor | **1,165 tests reported**, zero failures/errors, four existing environment-gated skips; `BUILD SUCCESS` |
| Final Java arithmetic rerun | **Three tests pass**, all 54 cases exported with final units/hash |
| Legacy persisted Java/Python parity | Seven voice and 12 SMS cases pass, maximum difference zero |
| Geographic persisted Java/Python parity | 30 voice and 32 SMS cases pass, maximum difference zero |
| Frozen input verification | 28 reference/V2 files and golden SHA unchanged |

The four existing Java skips require `ML_SERVICE_URL` (three live HTTP/model tests)
or `SMS_SHADOW_REPLAY_DIR` (one shadow replay). They were preserved and are listed
in `maven-reactor.json`; this report does not claim those live integrations ran.
The broad reactor/ML runs preceded the final additive calculator review. The final
focused reproduction recompiled the calculator and reran its full golden suite,
plus the complete repository Python suite, after all helper and metadata changes.

Final machine-readable counts, commands, export hashes and statuses are in
`assets/trace-link-day1/verification.json`. Saved language exports allow audit;
the reproduction script regenerates them rather than trusting archived results.
Receiver gates remain explicitly pending in the handoff ledger.
