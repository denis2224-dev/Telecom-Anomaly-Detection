# Geography contract merge check

**Checked 5 October 2026.** This is a contract integration check, not geographic runtime acceptance.

| Input | Commit |
| --- | --- |
| `origin/main` base | `c539b67a40ac776c693e9301fac91b035e4037bc` |
| Geography backend contracts | `2b1dad03598b8e887e1abe58d807fde4ed075911` |
| PR #48 geographic authority with exact `windowId` validation | `1bdd01e5028e1fcbb13c24a0cbd49af93f026d57` |

The two feature heads share the listed `main` base and merged cleanly in a disposable local verification branch. That branch was removed after checks. No combined commit was published or merged to `main`.

| Check on the combined tree | Result |
| --- | --- |
| Existing and geography contract checker | Pass: ten cities, twenty city scopes, two legacy scopes, 30 coverage cases. |
| Python unit suite | Pass: 26 tests. |
| Streaming-support Java suite | Pass: 105 tests. |
| Processor topology `ScopeRegistryTest` and `EvidenceJoinerTest` | Pass: 29 and 12 tests respectively. |
| OpenAPI 3.0 validation and unmeasured-city response example | Pass. |
| `git diff --check origin/main...HEAD` | Pass. |
| Full processor Java suite | Incomplete locally: 118 tests could not initialize PostgreSQL Testcontainers because no Docker daemon was running. The earlier sandbox attempt also blocked embedded Kafka from binding a local socket. This is an environment limitation, not a passing regression. |

PR #48's Java and Python validators now reject a well-formed but incorrect 64-character `windowId`; its source branch was advanced by the fix commit above. The new PR CI run and requested reviews must be checked at the final head. PR #43's current net diff against `main` contains no backend stream Java changes; retain the single `ready`/`incident-upsert` stream already on `main`.

## Remaining merge gate

1. Confirm PR #48 CI is green and reviewers accept the updated head, including role/source and model/baseline compatibility.
2. Review the backend OpenAPI and null semantics with David and Rusu. The five routes remain proposals; no controller is activated by this branch.
3. Agree on the coverage consumer, catalogue activation and migration order with Ion and Stanislav. The SQL design is not a Flyway migration.
4. On the chosen integration SHA, run the combined processor and incident-service regressions with Docker/Testcontainers, then record the accepted SHA and owner decisions. Do not interpret the local environment failure as a code failure or a pass.
