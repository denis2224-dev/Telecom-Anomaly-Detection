# PR #78 review remediation

**READY_FOR_REREVIEW for this test/evidence PR, subject to exact-head publication and CI recorded in the PR description. Shared G5 remains BLOCKED.**

Existing branch `devops/day4-day5-verification` was continued from reviewed head `38dd434754605ed05c5313ffd1a8d6b5034d6fee`. Remote main and the exact PR base were verified as `1b675c64938474e74c79efcecb6e051c4d460943`. The regression-tested source commit is `825df4c11a263fedafc7e1f4b4ea5ef14c860d0a`. The following evidence commit contains documentation/assets only; the final focused run, pushed head and completed exact-head CI are recorded in the PR description and the environment-specific local publication record. No merge, deployment or final-release candidate is created by this remediation.

## Findings and correction

The attached Sergiu review identified a P2 false-positive assertion in `absentOrStaleRequiredNodeCannotReplaceMeasuredServiceDegradation` and two P3 metadata/runbook inaccuracies. GitHub's reviews and inline-comments APIs returned empty before editing; the attached findings were independently checked against the reviewed source and exact PR base.

Jackson's `NullNode.asDouble()` returns 0.0. The previous `<95` assertion therefore accepted a lost SERVICE measurement. The existing `kpi` helper already fails if the named KPI is absent. The corrected assertion also requires `observed.isNumber()`, then compares the result to a rate independently calculated from the modified SERVICE input before ingestion and feature generation:

```text
VoLTE: eligibleAttempts = attempts - userOutcomes
       expectedCssrPct = 100.0 * technicalSuccesses / eligibleAttempts
       assert eligibleAttempts > 0
SMS:   expectedDeliverySrPct = 100.0 * deliverySuccesses / deliveryAttempts
       assert deliveryAttempts > 0
```

Equality uses an absolute tolerance of `1e-9` percentage points. The current builder computes the ratio with DECIMAL128 precision and serializes a double without decimal-place rounding; this tolerance accommodates floating-point conversion without masking a material rate error. The original `<95` check is retained. Existing received/usable SOURCE coverage, COMPLETE SERVICE quality, null dependency KPIs, stale/never-seen dependency activity, ML ineligibility and no-POWER_OFF assertions are unchanged. Fixtures, event identities and production code are unchanged.

## Six dependency cases and mutation proof

| Invocation | Case | Input-derived expected rate (%) | Normal production | Discarded SERVICE metrics | Fabricated numeric zero |
| --- | --- | ---: | --- | --- | --- |
| 1 | VoLTE / IMS / missing | 91.071428571429 | PASS | numeric assertion FAIL | equality assertion FAIL |
| 2 | VoLTE / IMS / stale | 91.071428571429 | PASS | numeric assertion FAIL | equality assertion FAIL |
| 3 | VoLTE / transport / missing | 91.071428571429 | PASS | numeric assertion FAIL | equality assertion FAIL |
| 4 | VoLTE / transport / stale | 91.071428571429 | PASS | numeric assertion FAIL | equality assertion FAIL |
| 5 | SMS / SMSC / missing | 50 | PASS | numeric assertion FAIL | equality assertion FAIL |
| 6 | SMS / SMSC / stale | 50 | PASS | numeric assertion FAIL | equality assertion FAIL |

All 23 original coverage invocations falsely passed with SERVICE metrics discarded whenever a required dependency was absent, reproducing the review finding. The same isolated mutation with the corrected assertions reported 23 tests: 17 PASS, **6 expected FAIL**, 0 ERROR/SKIP. All six fail on the numeric assertion. A second mutation retained a numeric type but replaced measured rates with zero: 17 PASS, **6 expected FAIL**, all on equality. These deliberately failing runs are negative controls, not successful normal test output.

Mutations ran in a separate Git-archive export of the reviewed head under `C:/OrangeSystems/Program/pr78-remediation-evidence-20261008/mutation-checkout`; no new branch or worktree was created. Each temporary production mutation was restored byte-for-byte. Restoration SHA-256 records and source hashes are in [the new execution evidence](assets/ion-day4-day5-integration/pr78-review-remediation.json). The PR worktree's production files were never mutated; all 190 tracked production-source hashes are verified separately.

## P3 corrections

The four stale runbook passages now recognize current-main disabled-geography rejection and `activeAt(startAt)`. The remaining obligation is authenticated public-to-private scenario verification on the final release candidate. Geographic-history provenance, deployment configuration, security/roles and non-destructive rollback requirements remain; authenticated acceptance is not claimed.

Both inventory copies now place `restartCannotMultiplyWorkersWhileOldSubmissionIgnoresInterruption`, `competingFinalizersCommitOneMatchingWinningSnapshot` and `normalMinuteReconcilesFiftyOffersAcksAndDurableReceipts` in `reusedExistingMethods`, after checking each against exact base `1b675c64938474e74c79efcecb6e051c4d460943`. `scheduler` is identified as an extracted helper with no additional test invocation. The six imported methods retain 10+6+1+1+1+1 = 20 invocations. No old source SHA, raw count, capture time or historical checksum was changed.

## Fresh normal verification

Java 21.0.12.1, Maven 3.9.16, repository Python 3.13.7 and Docker 29.3.1 were used. Coverage ran first, then all three PR classes. Those pre-commit runs are explicitly recorded as reviewed HEAD plus the test-patch digest; their test bytes equal the committed regression source. Full processor/generator and Python runs use `825df4c11a263fedafc7e1f4b4ea5ef14c860d0a`. Exact argv, UTC intervals, input/log/XML digests, actual suite discovery and skips are recorded in the new JSON and local archive. Separate runs overlap and must not be summed.

| Separate execution | Reported | PASS | FAIL | ERROR | SKIP | UTC interval |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| coverage | 23 | 23 | 0 | 0 | 0 | `2026-10-08T14:49:50.612733+00:00` to `2026-10-08T14:50:29.451456+00:00` |
| focused | 44 | 44 | 0 | 0 | 0 | `2026-10-08T14:50:29.652709+00:00` to `2026-10-08T14:51:36.548760+00:00` |
| processor | 919 | 915 | 0 | 0 | 4 | `2026-10-08T14:52:26.656330+00:00` to `2026-10-08T15:00:54.466979+00:00` |
| generator | 206 | 206 | 0 | 0 | 0 | `2026-10-08T15:01:10.647116+00:00` to `2026-10-08T15:01:38.525183+00:00` |
| python-root | 57 | 57 | 0 | 0 | 0 | `2026-10-08T14:53:24.414294+00:00` to `2026-10-08T14:53:32.261325+00:00` |

Contracts passed all nine suites. Three-class discovery remains GeographicPublicationTest 17, GeographicCoverageTest 23 and GeographicLoadAccountingTest 4; no focused case is skipped. Upstream modules without the requested classes use the existing `failIfNoSpecifiedTests=false` option; required target discovery is independently asserted from Surefire XML.

```powershell
& 'C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd' '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2' '--batch-mode' '--no-transfer-progress' '-pl' 'services/processor' '-am' '-Dtest=GeographicCoverageTest' '-Dsurefire.failIfNoSpecifiedTests=false' 'verify'
& 'C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd' '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2' '--batch-mode' '--no-transfer-progress' '-pl' 'services/processor' '-am' '-Dtest=GeographicPublicationTest,GeographicCoverageTest,GeographicLoadAccountingTest' '-Dsurefire.failIfNoSpecifiedTests=false' 'verify'
& 'C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd' '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2' '--batch-mode' '--no-transfer-progress' '-pl' 'services/processor' '-am' 'verify'
& 'C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.cmd' '-Dmaven.repo.local=C:/OrangeSystems/Program/.tools/m2' '--batch-mode' '--no-transfer-progress' '-pl' 'services/event-generator' '-am' 'verify'
& 'C:\OrangeSystems\Program\Telecom-Anomaly-Detection\.venv\Scripts\python.exe' '-B' 'scripts/check-contracts.py'
& 'C:\OrangeSystems\Program\Telecom-Anomaly-Detection\.venv\Scripts\python.exe' '-B' '-m' 'unittest' 'discover' '-s' 'tests' '-v'
```

The processor reactor's conditional skips are retained exactly:

* `md.utm.telecom.processing.GeographicDetectionTest.pinnedHttpScorerAcceptsCityVectorsAndRejectsChangedBaselineWithoutBlockingRules(String)`: Environment variable [ML_SERVICE_URL] does not exist
* `md.utm.telecom.processing.kpi.SmsDeliveryTest.livePackagedModelEnrichesRecoveredSmsEpisode`: Surefire's message attribute is empty; the source guard requires `ML_SERVICE_URL`.
* `md.utm.telecom.processing.kpi.SmsShadowReplayTest.freshObservationsReachDurableShadowStorageWithoutChangingRuleIncidents`: Environment variable [SMS_SHADOW_REPLAY_DIR] does not exist
* `md.utm.telecom.processing.kpi.VoiceDeliveryTest.livePackagedModelEnrichesRecoveredVoiceEpisode`: Surefire's message attribute is empty; the source guard requires `ML_SERVICE_URL`.

Real disposable PostgreSQL and Kafka paths run in the integration suites. Three-minute scheduling and clocks are controlled; producer failure/restart tests use controlled futures. This does not establish deployed performance or authenticated API/UI acceptance. Feature construction did not change, so no new parity execution is required or claimed; contract reference checks are not fresh Java/Python parity evidence.

## Evidence integrity and changed files

Historical validation reconfirmed all 38 unique shared-plan IDs and unchanged statuses: six PASS, 31 BLOCKED, one NOT_IN_SCOPE; mandatory final-release statuses remain BLOCKED. Six historical asset hashes, 40 artifact references, 78 historical source hashes, four historical parity exports and ten historical log hashes remain valid. `verification.json` preserves every historical field outside its corrected inventory. Fresh evidence is separate. JSON syntax, relative links, secret patterns, source scope and `git diff --check` are checked before publication. Other worktrees and pre-existing untracked graph output are checked against their original snapshots; refreshed AST graph output goes only to the new local archive.

Exactly these remediation files change:

* `docs/evidence/2026-10-08-ion-day4-day5-post-merge-integration.md`
* `docs/evidence/2026-10-08-pr78-review-remediation.md`
* `docs/evidence/assets/ion-day4-day5-integration/pr78-review-remediation.json`
* `docs/evidence/assets/ion-day4-day5-integration/selective-inventory.json`
* `docs/evidence/assets/ion-day4-day5-integration/verification.json`
* `docs/runbooks/ion-day5-release-verification.md`
* `services/processor/src/test/java/md/utm/telecom/processing/kpi/GeographicCoverageTest.java`

## Remaining handoffs

* Sergiu (`SergiuTOP`): re-review the strengthened six-case P2 and the mutation/equality proof. Sergiu/Rusu retain detector/explanation and scorer acceptance on the final candidate.
* Stanislav (`stasikkk777`): retain integration review, select the final release candidate, align exclusive legacy/geographic history configuration and activation, obtain final-SHA CI/live/resource/backup/rollback evidence and two mentor reproductions.
* Denis: authenticated verification of the existing activation fix; trusted geographic-history provenance/replay/conflict/fault preservation and projection acceptance; measured command-transaction latency investigation.
* David: independent PR #76 review/integration and authenticated all-city API/UI, freshness/missing/zero, SSE/refresh/expiry/logout acceptance.

**READY_FOR_REREVIEW for the targeted remediation; Shared G5 BLOCKED.**
