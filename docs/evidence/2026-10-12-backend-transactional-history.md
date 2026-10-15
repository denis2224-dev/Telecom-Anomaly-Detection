# Trace Link transactional history acceptance

Planning work package: Monday 12 October 2026, Europe/Chisinau. Actual execution: 13 October 2026.
Base: main f7364d3. Branch: feat/trace-link-transactional-history. Draft PR: https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/103.

The backend implementation is complete for the documented slice. Shared delivery/owner release gates remain open, so the PR remains draft and has not been merged or deployed to an operator environment.

The current main already reserved V008 for immutable primitive projections. This branch preserves V001–V008 and adds V009 for separate five-minute technical episodes. It retains volte.setup.success and the merged primitive consumer’s provenance and unavailable-quality semantics. Full-archive transport, roaming and LTE ratio reads report INSUFFICIENT_EVIDENCE when source quality is unavailable; importing rows never manufactures healthy quality.

| Gate | Result | Evidence |
| --- | --- | --- |
| Fresh V001–V009, V004 upgrade, repeat migration, immutable grants and denied DDL | PASS | Full incident-service suite and verification.json |
| Transactional opening/equal update/escalation/UNKNOWN/recovery/replay/gaps/rollback/new episode | PASS | NotificationPersistenceIT, EvidenceServiceTest, ReplayOrderingIT |
| Authentication, actor isolation, CSRF missing/invalid/valid, disabled/expired sessions, unread/read repeats | PASS | NotificationControllerTest and real OIDC receipts |
| Actual Kafka fixture lifecycle, protected REST and two session-bound SSE clients | PASS, FIXTURE-DRIVEN | live-notification-checks.json; SSE evidence after capacity completes |
| Real assignment and privileged reassignment, new assignee only | PASS | live-assignment-checks.json |
| Three new-domain ratio series and snapshots, native missing-quality qualification | PASS | TraceDomainSliceIT and authenticated-read-checks.json |
| Full archive read-model import and exact retries | PASS | 362,880 rows: 103,680 transport; 172,800 roaming; 86,400 LTE data; history-import.json |
| Separate domain episode, replay, pending gaps, source/history bounds and unsupported cause | PASS, CURATED BACKEND FIXTURES | TraceDomainSliceIT; adapter consumer defaults off |
| Grafana actual read-only connection and one value comparison | PASS, ISOLATED | grafana-readonly-check.json: 57.318505 agrees; all four forbidden privileges false |
| Full new-domain producer positive/control delivery | BLOCKED | Pending producer agreement and usable source quality; no held outbox released |
| Receiving frontend owner sign-off and 14-panel Grafana release receipt | PENDING | Real backend/SSE/REST proofs are not substituted for owner release receipts |
| Restart and durable REST restoration | PASS, ISOLATED; RELEASE OWNER PENDING | restart-check.json preserves exact history/read state and ids across the final-build restart |

Full service verification reported 270 tests, zero failures/errors and one pre-existing skip. Final focused dataset/snapshot regressions passed seven tests; the latest per-suite results represent 272 unique Java tests including one skip. Python contract/measurement verification passed 148 tests with one pre-existing PowerShell skip. OpenAPI validation, canonical/dashboard equality and Angular type generation pass. CI passed the core code commit; final-head CI is tracked on the PR.

Actual commands: mvn -o -q -f services/incident-service/pom.xml verify; PATH="$PWD/.venv/bin:$PATH" .venv/bin/python -m unittest discover -s tests -v; .venv/bin/python scripts/check-contracts.py; .venv/bin/python scripts/check-trace-link-domain-contract.py; npm --prefix apps/dashboard run generate:api; cmp contracts/openapi/incident-api.yaml apps/dashboard/api/incident-api.yaml.

All runtime reproduction used the isolated codex-trace-history-review Compose project, a separate database volume, temporary real Keycloak identities and a pinned JAR. The archive’s 14 actual capacity intervals were derived from the checksum-verified fixture in that review database. Source quality and coverage remain unavailable, while slot coverage and primitive values are recorded separately. Scenario examples prove hashing and backend behavior; they are not evidence of new-domain detector delivery.

Artifacts under assets/backend-transactional-history contain counts, requests/results, query plans, capacity intervals, hashes and Grafana parity. No cookie, OIDC token, password or subscription material is committed. Capacity output and measured scope/limits are in 2026-10-12-backend-capacity.md. The full baseline failed and no healthy operating limit is claimed; the harness repair is covered by a slow-telemetry regression.
