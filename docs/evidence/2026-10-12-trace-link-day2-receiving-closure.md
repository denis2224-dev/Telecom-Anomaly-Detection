# Trace Link Day 2 corrected local reproduction

**Complete local reproduction: PASSED. Ion's updated receiving decision: PENDING.**

The corrected Windows entrypoint completed uninterrupted, including the PostgreSQL checks unavailable in Ion's receiving environment. This supplies new technical proof for his review; it does not rewrite his BLOCKED report or record receiving-owner acceptance. No push, merge, deployment or new live dispatch was performed. The supplied Day 3 document remains a proposal, not authorization to implement Day 3.

## Source and execution

| Item | Value |
|---|---|
| Branch | `feat/trace-link-day2-seasonal-policies` |
| Original reviewed head | `55bb638dbb2b941e53e13edc068c80f23b3ce7c9` |
| Main inspected by Ion | `cbb94d37f7593e99737ee4200e91b14e0512f76f` |
| Ion's supplied local patch | `3b4a4cc0dc12f7b67020066c5d49bf02b23e9307` |
| Corrected and tested source | `11a8dba27c5d5e26fa0d3fe95a06eb32a5f023b4` |
| Full process start, UTC | `2026-10-12T14:25:31.8601875Z` |
| Full process finish, UTC | `2026-10-12T14:46:53.4153514Z` |
| Exit code | `0` |

Ion's native Maven/Java launcher corrections were applied and reviewed. His new executable fixture was then corrected for Windows virtual environments: relocating the Python venv redirector fails with exit 106 because it cannot locate `pyvenv.cfg`. The regression now uses a native platform shell. All three portability tests pass, including wrapper invocation and exit 27 propagation before publication.

The publisher additionally hashes the corrected Day 1 verifier, its verification tools, Maven wrappers/configuration and attributes. An optional evidence directory preserves the original publication. A separate execution agent ran the complete entrypoint; source and archive construction received independent read-only review. The source fix patch and original Ion patch are included in the evidence directory.

Actual command, repository-root working directory:

```powershell
& 'C:\Users\Sergiu\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe' -NoProfile -File scripts/verify-trace-link-day2.ps1 `
  -Python .\.venv\Scripts\python.exe `
  -JavaHome tmp\jdk21-sergiu-days7-8\jdk-21.0.12.1+1 `
  -ReferenceRoot D:\Univer\Anul2\Orange_intership\project\Trace_Link_Integration_Pack `
  -EvidenceDirectory docs/evidence/assets/trace-link-day2-receiving-closure
```

## Fresh results

| Gate | Result |
|---|---|
| Contracts / root Python | 133 tests passed, zero skips |
| ML Python | 64 tests passed, zero skips |
| Full Maven reactor | 1,201 reported tests, zero failures/errors, four environment skips |
| Reactor breakdown | Streaming 111 + generator 95 + processor 995 |
| Eight mandatory Trace Link suites | 38 passed, zero failures/errors/skips |
| Rule / cause fixtures | 58 rules + 60 causes passed and published |
| Four-language arithmetic | 54 fresh cases per Java/Python/JavaScript/PostgreSQL; maximum reported difference 0 |
| Persisted legacy parity | Seven voice and 12 SMS payloads, all fields compared |
| Persisted geographic parity | 30 voice and 32 SMS payloads, all fields compared |
| Independently fitted baseline parity | Two Java/Python cells, maximum difference 0; both timezone rules 2026b |
| Complete history builds | Two fresh builds of 34,347 cells; both match the original compressed artifact |
| Frozen files | All 14 Day 2 V2/model pins unchanged; Day 1's 29-file frozen/reference check also passed |
| Publication | Complete entrypoint successfully published a separate manifest and seven fresh domain/lifecycle/cause exports |

The receiving reactor's 1,200 versus this run's 1,201 count is explained by `HistoryBootstrapRuntimeTest`: its unavailable before-all fixture was reported as one synthetic error testcase in Ion's environment; both actual tests passed here. No test was removed or weakened.

The four skips remain unavailable checks, not passes: geographic HTTP model scoring, live packaged voice scoring and live packaged SMS scoring require `ML_SERVICE_URL`; SMS shadow replay requires `SMS_SHADOW_REPLAY_DIR`. None belongs to the mandatory 38 Trace Link tests. Exact suite/test names and original skip messages are in the manifest and archived XML.

Both fresh history gzip SHA-256 values:

`767be86c7b55e22581d3d395dc12c39ac3b11d58d03d549dcbf612606c268d87`

Raw artifact SHA-256:

`3bff7c6229a8ec26caa26d68c5c861d6d0b73e3e002b604860d9ae258b68e5d2`

Runtime versions: Python 3.13.9, tzdata 2026.2/IANA 2026b, Temurin JDK 21.0.12.1+1, Maven 3.9.16, PowerShell 7.6.5, Node 24.13.1 and Docker 29.5.2. PostgreSQL is 16.4-alpine; its immutable image ID/repository digest is captured in `additional-runtimes.json`.

## Preserved and new evidence

The original `docs/evidence/assets/trace-link-day2/` files remain byte-identical to the pre-run snapshot. The three incoming attachments are identified by their original raw SHA-256 values; Ion's supplied patch SHA-256 is `b568a16327e1e51945c4ae6b5868ed416acf3b090586d075f9bc902ab32ac6db`. No historical report or manifest was rewritten.

The separate [closure record](assets/trace-link-day2-receiving-closure/closure.json) binds 122 current working-byte source hashes, 14 frozen files, both fresh history hashes, 12 additional fresh JSON files, 95 Surefire XML reports, the complete compressed entrypoint log, runtime metadata and source patches. The [execution ledger](assets/trace-link-day2-receiving-closure/execution.json) records the actual argv, working directory, source revision, UTC interval and exit status. The publisher's command strings are reproduction recipes; the execution ledger is the actual command record. Its internal run finish precedes publication; the outer execution ledger includes successful publication.

Original Windows/LF checkout differences remain disclosed by Ion's report. The new manifest pins raw Windows working bytes; `source-git-blob-hashes.json` separately pins canonical Git blob bytes for comparison across platforms. These are distinct byte representations, not a claim that historical raw mismatches passed. New archived evidence preserves raw bytes through directory attributes. The copied historical fit report describes baseline origin; `history-1.json` and `history-2.json` describe this run's fresh builds.

Final independent read-only audit approved the closure with no blocking findings: all 34 closure artifact hashes, 11 publisher artifact hashes, 122 working-byte source hashes, 122 Git blob hashes and 14 frozen pins matched. The auditor independently recounted the 95 XML reports, verified fresh log/parity/history evidence, confirmed all 13 original publication files unchanged and checked that Git attributes preserve the archived bytes.

The all-file Git whitespace check flags blank context lines and email-signature spaces in the two verbatim `format-patch` archives. Those original bytes are intentionally preserved for patch integrity. The check passes for the remaining change, including all executable source; this archive-format exception does not suppress any test or source diagnostic.

## Remaining receiving and Day 3 gates

Push the corrected branch and evidence, then give Ion the tested source revision and closure record for an updated receiving decision. If his receiving process requires another external run, use the complete entrypoint in an environment with working Docker/PostgreSQL; focused fixtures alone still do not replace that gate. No personal teammate sign-offs are recorded here.

Day 3 still requires real or explicitly synthetic target primitives and source quality, event-valid topology, persisted adapters, durable lifecycle/ordered outbox, and a versioned Kafka-to-incident/API/dashboard envelope. Current V2 cannot directly accept these envelopes. Historical freshness remains UNKNOWN, and unavailable reference target populations remain insufficient evidence. Live delivery and receiving-owner acceptance remain separate gates.
