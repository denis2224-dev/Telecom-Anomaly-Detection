# Ion Day 4/5 post-merge selective integration

**Verdict: READY_FOR_REVIEW for this test/evidence PR. Shared G5: BLOCKED.**

Current remote main was refreshed and verified as `1b675c64938474e74c79efcecb6e051c4d460943`. Branch `devops/day4-day5-verification` starts directly from it in `C:/OrangeSystems/Program/ion-day4-day5-pr`. The fully tested source commit is `7fb812b1a094428595744d8f28844c3437abcc0b`. The subsequent evidence commit contains documentation/assets only; the final pushed HEAD, final-head focused results and exact-head CI state are recorded in the PR description and final local publication record. No final release candidate, merge or deployment is authorized by this evidence.

## Source provenance and preservation

PR #73 is MERGED at head `05f4f088244d3728e15cc18c233b67f3b45def5d`, merge `9f67c6d5a78d047840a13d5f7650acfd4feae4f7`. PR #74 is MERGED at head `29c1a7b4736c8433918a48e9304b4b602d4c9bcb`, merge `1b675c64938474e74c79efcecb6e051c4d460943`. Both heads are ancestors of main. PR #76 remains OPEN/DRAFT at `091e695521e34b92d8da0b599fe49e43085697be` and is untouched.

Verified local chain: `cd0f98d24bb97d08be838bfa6d4fa5ea86b4dd28` → `d7bbaece74e9019841c615115a6178a2bee7716f` → `6b830fd616c0a5da1a644bd9a14a0cd157c95a92` → `6433e793a99b5eac92157aa498db31cdbeb40164` → `e68381c1de8f074c83dadefb44a83763aa976d50` → `25a5278793bb5da0b96d95974a861f3f0b5945e9`. Day 4/5 merge base with current main is `cd0f98d...`. Reconciliation is not an ancestor of current main and was not merged/cherry-picked. The three relevant test files at its baseline equal main's blobs exactly, permitting a test-only patch. Final ported files equal the actual historical tested Day 5 blobs. Production and unrelated historical tree differences were excluded.

All 26 original worktrees were inventoried before branch creation and preserved: HEAD, branch, full tracked/untracked status and dirty tracked-file hashes. Day 4's untracked graph output, Day 5 and the dirty primary checkout were retained. Full before/after records and execution XML/logs remain in the environment-specific local directory `C:/OrangeSystems/Program/ion-day4-day5-integration-evidence-20261008`.

## Selective inventory

| Candidate | Classification / disposition |
| --- | --- |
| Two coverage matrices, two producer controls, mixed-city transport test | REQUIRED_UNIQUE_CHANGE / INCLUDED; 19 Day 4 invocations |
| Three-minute test and unchanged shared scheduler helper | REQUIRED_UNIQUE_CHANGE / INCLUDED; one Day 5 invocation |
| Day 4/5 reports, manifest, runbook and six sanitized historic assets | DOCUMENTATION_ONLY / INCLUDED |
| PR #73/#74 production/history/scenario implementation | ALREADY_IN_MAIN; preserved |
| Existing uncertain-ACK/duplicate, fairness/order/deadline, optional SMS transport, replay/race/closure, outage/history suites | ALREADY_IN_MAIN; rerun, no duplicate scenarios |
| Reconciliation ancestry, full historical tree diff and obsolete constructors/interfaces | SUPERSEDED; excluded |
| Audit label `degradedMissingOrStaleTelemetryPreservesTruthWithoutFabricatedPowerOff` | SUPERSEDED label; actual 10-case SERVICE method below ported |
| Historical public activation mismatch | SUPERSEDED at source level in current main; archived results/statuses preserved, authenticated acceptance pending |
| DEF-01, DEF-02, DEF-03, PR #76 and branding/detector/auth/UI work | OUT_OF_SCOPE / OWNER_HANDOFF |

Exact inventory is [machine readable](assets/ion-day4-day5-integration/selective-inventory.json). The independent Gemini document was not found locally or as another attachment; the findings supplied in the request were checked against Git and source.

## Exact ported methods

| Class | Method | Invocations | Original commit |
| --- | --- | --- | --- |
| `GeographicCoverageTest` | `serviceTruthPreservesIndependentNodesAndDistinguishesActivityFromMeasurements` | 10 | `6b830fd616c0a5da1a644bd9a14a0cd157c95a92` |
| `GeographicCoverageTest` | `absentOrStaleRequiredNodeCannotReplaceMeasuredServiceDegradation` | 6 | `6b830fd616c0a5da1a644bd9a14a0cd157c95a92` |
| `GeographicPublicationTest` | `synchronousFailureInOrheiIsBoundedAndDoesNotStarveOtherCitiesOrTheNextMinute` | 1 | `6b830fd616c0a5da1a644bd9a14a0cd157c95a92` |
| `GeographicPublicationTest` | `geographicRestartAfterDowntimePublishesOnlyTheNextWholeUtcMinute` | 1 | `6b830fd616c0a5da1a644bd9a14a0cd157c95a92` |
| `GeographicLoadAccountingTest` | `mixedOrheiFaultAndEdinetServiceGapKeepAllTenCitiesIndependentOnKafkaAndPostgres` | 1 | `6b830fd616c0a5da1a644bd9a14a0cd157c95a92` |
| `GeographicLoadAccountingTest` | `threeConsecutiveMinutesReconcileOneProducerWithSameWindowKpiAndCoverageFacts` | 1 | `e68381c1de8f074c83dadefb44a83763aa976d50` |

The SERVICE matrix is VoLTE/SMS × ABSENT, REPORTED_MISSING, HEARTBEAT_ONLY, STALE and COMPLETE_ZERO. The dependency matrix is IMS/VoLTE transport/SMSC × absent/stale. Real independent NODE measurements survive SERVICE gaps; measured service degradation survives absent dependencies. Missing metrics remain null and never imply HEALTHY/POWER_OFF. Source received/usable counts, actual minute ingestion and immutable re-finalization are asserted. Existing optional SMS transport, uncertain-ACK and duplicate tests remain.

## Fresh executions and boundaries

All full executions below use the verified source SHA above, Java 21.0.12.1, Maven 3.9.16 and repository Python 3.13.7. Exact argv, UTC times, log hashes, module/class counts and focused per-invocation names are in [verification.json](assets/ion-day4-day5-integration/verification.json). Upstream `failIfNoSpecifiedTests=false` permits modules without the named classes; required target suite discovery is explicitly checked. Compile-only is not test PASS evidence.

| Separate execution | Reported / PASS / FAIL / ERROR / SKIP, or comparison | UTC interval |
| --- | --- | --- |
| compile | PASS; compile only, zero executed tests | `2026-10-08T13:55:00.939335+00:00` to `2026-10-08T13:55:26.039101+00:00` |
| focused | 44 / 44 / 0 / 0 / 0 | `2026-10-08T13:55:45.336850+00:00` to `2026-10-08T13:57:00.056575+00:00` |
| generator | 206 / 206 / 0 / 0 / 0 | `2026-10-08T13:58:53.529783+00:00` to `2026-10-08T13:59:19.278361+00:00` |
| processor | 919 / 915 / 0 / 0 / 4 | `2026-10-08T13:59:57.250956+00:00` to `2026-10-08T14:08:28.272910+00:00` |
| missing-it | 9 / 9 / 0 / 0 / 0 | `2026-10-08T14:09:03.828867+00:00` to `2026-10-08T14:09:24.585826+00:00` |
| contracts | PASS; nine contract/reference suites | `2026-10-08T13:59:24.939147+00:00` to `2026-10-08T13:59:28.706889+00:00` |
| python-root | 57 / 57 / 0 / 0 / 0 | `2026-10-08T13:59:28.817962+00:00` to `2026-10-08T13:59:35.075436+00:00` |
| voice-parity | PASS: 7 persisted Java/Python payloads, all fields compared | `2026-10-08T14:09:02.106430+00:00` to `2026-10-08T14:09:03.483021+00:00` |
| sms-parity | PASS: 12 persisted Java/Python payloads, all fields compared | `2026-10-08T14:09:03.828947+00:00` to `2026-10-08T14:09:04.422987+00:00` |
| geographic-voice-parity | PASS: 30 persisted Java/Python payloads, all fields compared | `2026-10-08T14:09:03.933191+00:00` to `2026-10-08T14:09:05.243351+00:00` |
| geographic-sms-parity | PASS: 32 persisted Java/Python payloads, all fields compared | `2026-10-08T14:09:04.092157+00:00` to `2026-10-08T14:09:05.014454+00:00` |

Do not add overlapping executions. Processor reactor module counts: `{"event-generator": {"errors": 0, "failures": 0, "skipped": 0, "tests": 95}, "processor": {"errors": 0, "failures": 0, "skipped": 4, "tests": 713}, "streaming-support": {"errors": 0, "failures": 0, "skipped": 0, "tests": 111}}`. The updated-main source baseline is 899 discovered reactor invocations, derived by subtracting exactly 20 imported invocations from this fresh run; it is a discovery comparison, not a separate main execution. The historical Day 5 count of 739 is not the current-main baseline. Root Python now executes 57 cases.

Conditional skips are listed exactly in the machine-readable evidence. They require `ML_SERVICE_URL` for the three existing live model tests and `SMS_SHADOW_REPLAY_DIR` for shadow replay. No imported/focused or explicit missing-window case is skipped. Packaged-model and shadow acceptance remain owner-controlled.

Four parity scripts compare freshly exported Java payloads from this processor run against current Python reference values, with integers exact and maximum numeric difference zero. Export production UTC timestamps and SHA-256 digests are recorded separately; historical exports are not reused as fresh evidence. Original fixture, policy, baseline, model and feature ordering are unchanged by this PR.

## Kafka / PostgreSQL results

[Fresh reconciliation](assets/ion-day4-day5-integration/day15-load-reconciliation.json) uses real embedded Kafka ACK/consumer offsets and disposable PostgreSQL with a controlled scheduler/clock. One continuous producer spans three UTC windows, 10 cities / 20 scopes: each has 50 offers = 50 ACKs = 50 wire observations = 50 durable receipts, 20 finalized KPIs and 20 matching coverage facts; total 150/60/60, zero legacy receipts, no rejected observations. Key/payload/scope, deterministic seed 42, exact source authority, event identity and feature/coverage window IDs agree. It is controlled integration evidence, not sustained deployment performance or public projection/UI acceptance.

The preserved uncertain-ACK test transports 51 wire records after an identical retry while retaining 50 logical offers/receipts and 20 KPIs. The mixed ORH degradation / EDI SERVICE gap transports 49 receipts and finalizes all 20 scope windows/coverage facts. CHI/BAL controls retain their own healthy bytes and sources; EDI retains independent SMSC evidence and null service metrics. Producer failure/restart tests use real worker threads with controlled Kafka futures and clock.

The full reactor also executes actual dependency outage/restoration and standalone history runtime suites with disposable real PostgreSQL/Kafka. Readiness checks and injected failures are labeled separately in their existing tests. No shared/demo/production database or existing Docker volume was modified.

## Imported evidence and validation

The Day 4 and Day 5 report bodies, original UTC times/source SHAs, old results, limitations and blocked cases remain historical. Prefix notes and the manifest's `evidenceContext` distinguish current integration. The runbook preserves original commands and adds portable wrapper/Python alternatives; old Windows raw paths are labeled environment-specific. All 38 authoritative shared-plan IDs are unique and matched; historic statuses remain six PASS, 31 BLOCKED and one NOT_IN_SCOPE. All mandatory final-release statuses remain BLOCKED. Six asset digests, 40 case artifact references, 78 historic source hashes, four old parity exports and ten historic log hashes were validated. Windows-byte hashes and portable Git-normalized LF hashes are explicitly distinguished.

New supporting assets contain exact fresh executions/counts and streaming outcomes. Large raw logs, XML and parity exports remain in the local evidence archive, with digests. JSON parsing, relative links, secret-pattern scan, size limits, `git diff --check` and source-only selection are checked before publication. No credentials/session/CSRF material is included.

## Remaining owner handoffs

* Denis: investigate geographic `bootstrapId` provenance. Consumer trusts only literal `initial-demo-v1`; ordinary ingestion permits identical replay, so duplicate failure is not assumed. Verify exact replay, changed-body conflict, existing fault preservation, forged headers, trusted provenance and no unauthorized overwrite. Measure synchronous generator HTTP calls inside reconciliation/stop transactions before assigning 503/504 causes or changing pool size/state machine.
* Sergiu/Rusu: final-candidate detector/explanation onset/recovery, source/cause controls and required scorer exclusions. No model/policy/threshold changes here.
* David: independently finish/review PR #76, branding if required, and authenticated all-city API/UI, freshness/missing/zero, SSE/refresh/expiry/logout acceptance.
* Stanislav: review this selective PR and select the final candidate; wire exclusive legacy/geographic history configuration with aligned activation in Compose; obtain final-SHA CI, full live GEO/STR/HIS/COV, resource/backup/rollback evidence and two mentor reproductions. This PR neither creates that candidate nor merges/deploys anything.

## Complete changed-file inventory

* `docs/evidence/2026-10-08-ion-day4-day5-post-merge-integration.md`
* `docs/evidence/2026-10-08-ion-day4-negative-controls-reliability.md`
* `docs/evidence/2026-10-08-ion-day5-streaming-manifest.json`
* `docs/evidence/2026-10-08-ion-day5-streaming-release-readiness.md`
* `docs/evidence/assets/ion-day4-day5-integration/day15-load-reconciliation.json`
* `docs/evidence/assets/ion-day4-day5-integration/selective-inventory.json`
* `docs/evidence/assets/ion-day4-day5-integration/verification.json`
* `docs/evidence/assets/ion-day5-streaming/golden-identities.json`
* `docs/evidence/assets/ion-day5-streaming/history-producer-recovery.json`
* `docs/evidence/assets/ion-day5-streaming/pre-edit-gap-audit.md`
* `docs/evidence/assets/ion-day5-streaming/public-future-activation-probe.json`
* `docs/evidence/assets/ion-day5-streaming/streaming-reconciliation.json`
* `docs/evidence/assets/ion-day5-streaming/test-results.json`
* `docs/runbooks/ion-day5-release-verification.md`
* `services/event-generator/src/test/java/md/utm/telecom/generator/continuous/GeographicPublicationTest.java`
* `services/processor/src/test/java/md/utm/telecom/processing/GeographicLoadAccountingTest.java`
* `services/processor/src/test/java/md/utm/telecom/processing/kpi/GeographicCoverageTest.java`

Zero production files change. Incident-service, dashboard, Compose, CI, migrations, auth, contracts and ML/detection policies remain byte-for-byte current main. **READY_FOR_REVIEW; G5 BLOCKED.**
