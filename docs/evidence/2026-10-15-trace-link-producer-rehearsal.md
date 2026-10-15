# Trace Link Day 6 producer rehearsals

Milestone: **15 October 2026**. Execution: **12 October 2026, Europe/Chisinau**.
Decision: **PRODUCER_READY_FOR_REVIEW**. Overall integrated release: **BLOCKED**.

The Day 6 branch starts at reviewed #96 head
`8fb0596b415b4fef137b29b38774948d3112c1a0`. Existing Java production and test
sources, migrations and frozen contracts are unchanged. The work adds pinned
fixtures, offline verification drivers, runbooks and evidence, plus a narrowly
scoped Windows checkout fix to the immutable-input verifier.

## Rehearsal identity and results

Configuration SHA-256:
`3d387504a364eb91f5cc3f03b19e5f0e0d69d2fe45f71edead76c7d07e082a51`.
Rehearsal source commit and tree are recorded in
[rehearsals.json](assets/trace-link-day6/rehearsals.json).

| Independent run | Scoped schedules | New-domain measurements | Legacy measurements | Result |
| --- | --- | --- | --- | --- |
| 1, fresh JVM | 14 x eight minutes | 240 | 105 across six profiles | VERIFIED |
| 2, fresh JVM | 14 x eight minutes | 240 | 105 across six profiles | VERIFIED; every generated file hash matches run 1 |

The logical scheduler verifies no early release or duplicate tick output,
concurrent independent directions/cities/partners, all normal/fault/recovery
slots, exact artifact/checkpoint replay with stable timestamps, completed restore,
conflicting reservation rejection and an interrupted restore preserving three
windows while emitting zero. Independent Python arithmetic verifies all measured
primitives, quality, coverage, source/scope/time/version identities and absence
of evaluator truth. LTE activation remains independently measured during DNS
failure. Missing recovery minutes remain null/MISSING with coverage zero.

| Matching artifact, both runs | SHA-256 |
| --- | --- |
| Transport fault | `98a3c1e3a05d5f78a20d9d66c228fc9730cdd8cbd0b179093d88ba0254f8687e` |
| Roaming fault | `885126778ed7a82b9f17e05b98c98d6129e3ca874410833aa9bb1ffcb9b45dd4` |
| LTE DNS/activation | `83c788c4b1581dd9d83a9e678329bd54b9069f6c8d9f571a6a1b00b31279614f` |
| Completed checkpoint | `a5bedef4567d1a8bccdf303636318fa1d7e6b59fba6e63cb726f1d7002a6ac2a` |
| Interrupted checkpoint | `4258e66ca94f9d7717fa23764cf9e0c21a45db23b4707f520c08ebadc42248b8` |

These are packaged producer-only logical-time rehearsals, not two connected
release rehearsals. No live detector, consumer, incident, notification or API/UI
transition is claimed. Historical data remains native 300s; generated live-format
data remains synthetic 60s with a separate identity. All measurement/checkpoint
files, summaries and manifests are archived under `assets/trace-link-day6/`.

## Verification and regression repair

The single complete producer Java reactor and the separate merged-candidate
checks are recorded with suite-level counts, skip reasons, exact commands and
original log hashes in [verification.json](assets/trace-link-day6/verification.json).
Overlapping invocations are never added together. The final repository Python
run passed **123 tests**. Earlier 119-test invocations are separately recorded.
Contracts and all **29** frozen repository/reference input pins passed.

| Invocation | Reported | Passed | Skipped | Failures/errors |
| --- | --- | --- | --- | --- |
| Complete clean producer reactor, 22m43s | 1,250 | 1,246 | 4 | 0 / 0 |
| Streaming support module | 120 | 120 | 0 | 0 / 0 |
| Event generator module | 138 | 138 | 0 | 0 / 0 |
| Processor module | 992 | 988 | 4 | 0 / 0 |
| Separate #91/main resolution candidate Java reactor | 52 | 51 | 1 | 0 / 0 |
| Final producer repository Python | 123 | 123 | 0 | 0 / 0 |
| Separate #91/main resolution candidate Python | 137 | 137 | 0 | 0 / 0 |

The four producer skips are the GeographicDetectionTest, VoiceDeliveryTest and
SmsDeliveryTest optional live HTTP cases without `ML_SERVICE_URL`, and
SmsShadowReplayTest without `SMS_SHADOW_REPLAY_DIR`. Archive/import/V016 tests
ran with their opt-in inputs. The merge-candidate archive-input skip is separate.

The fresh worktree reproduced a pre-existing verifier defect: repository JSON
Git blobs were LF-pinned but a normal Windows checkout contained CRLF. The red
regression fails on unchanged checkout JSON; the fix permits only CRLF-to-LF
comparison for repository-owned frozen JSON. Four new tests cover unchanged
checkout content, changed content rejection, external-reference raw-byte
rejection and binary raw-byte rejection. The four existing portability/integrity
tests are retained. External archive, binary and golden-suite hashes remain
strict. No legacy contract or frozen manifest was edited.

The full reactor explicitly selects the supplied native archive and frozen V016
in disposable databases. It covers importer retry/conflict/resume/concurrency,
source immutability, primitive parity, scenario replay/restart, retention, legacy
VoLTE/SMS, geographic isolation, finalizer races and real dependency outage tests.
The V016 compatibility case verifies held-outbox bytes/row count/runtime grants
survive domain generation and retention; #92 remains absent from this branch.

Reproduction used Java 21 and cached Maven 3.9.16:

```powershell
$maven = 'C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd'
$env:JAVA_HOME='C:/OrangeSystems/Program/.tools/jdk21/jdk-21.0.12.1+1'
& $maven -B -ntp '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2' -pl services/streaming-support,services/event-generator,services/processor -am '-Dlogging.level.org.springframework.jdbc.core=INFO' '-DtraceLink.referenceRoot=C:/OrangeSystems/task/Trace_Link_Integration_Pack' '-Dtrace.link.archive=C:/OrangeSystems/task/Trace_Link_Integration_Pack/data' '-Dtrace.link.v016.directory=C:/OrangeSystems/Program/ion-domain-producer-evidence/v016' '-Dtrace.link.v016.sha256=bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848' clean verify
python scripts/check-contracts.py
python -m unittest discover -s tests -v
python scripts/verify-trace-link-pins.py --reference-root C:/OrangeSystems/task/Trace_Link_Integration_Pack
python scripts/rehearse-trace-link-producer.py --java-home C:/OrangeSystems/Program/.tools/jdk21/jdk-21.0.12.1+1 --output '<new-directory>'
python scripts/verify-trace-link-day6-evidence.py
```

Four-language numerical verification freshly generates all **54 goldens per
language**; PostgreSQL uses a labelled, network-none disposable container. Legacy
and geographic persisted parity, scenario arithmetic and the external reference
engine are separate verification records, not reactor test additions.

Initial verification attempts are retained: incorrect Java-home launch (no tests
ran); CRLF pin-check failure and its red regression; a structured GitHub query
quoting correction; and a compatibility driver corrected to read test fixtures
from their explicit file path. No failed attempt is counted as a passing run.

## Integration, operations and handoff

The [preflight](../architecture/trace-link-release-preflight.md) records complete
#89-#96 dependencies, exact #91 conflict hunks and tested resolution patch,
parallel #92/#96 conflicts, immutable main policy preservation, the compatibility
matrix and receiving-owner execution checklist. The unchanged main interface
rejects raw producer evidence and one-minute evaluation windows. Existing main
offline fixtures pass independently; this does not establish a connected adapter.

| Gate | Status |
| --- | --- |
| Producer fixtures, scheduler, primitive/replay/restart and regression evidence | IMPLEMENTED / VERIFIED |
| Proposed #91 conflict resolution | VERIFIED candidate patch; PENDING_REVIEW; no active branch changed |
| New-domain detector/consumer mapping | PENDING_REVIEW; exact mismatches archived |
| Kafka/topics/held V016 release | BLOCKED; Kafka disabled and V016 held |
| Connected incidents, notifications, authenticated API/UI and Grafana | BLOCKED / NOT_VERIFIED |
| Connected warmup/steady/burst capacity and resource limits | BLOCKED; no connected benchmark executed |
| Backup/restore, clean install/upgrade and deployment acceptance | NOT_VERIFIED / BLOCKED; client binaries alone are not restore proof |
| Receiving-owner reproduction and five-owner sign-off | NOT_VERIFIED; no approval fabricated |
| One authoritative team release manifest and two connected rehearsals | BLOCKED |

Sergiu receives measured primitives, controls and versioned interface proposals;
Denis receives dataset/payload identity, quality/provenance and consumer failure
cases; David receives the five executable types and truthful timing/status;
Stanislav receives archive/V016 pins, restart/retention evidence and operational
prerequisites. Each receiver must record actual commands, identities and results.
The [producer release fragment](assets/trace-link-day6/producer-release-fragment.json)
is a proposal for the shared manifest. It does not replace or approve that manifest.
