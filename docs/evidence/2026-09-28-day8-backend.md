# Day 8 backend evidence history and audit

Date: 28 September 2026

Branch: `feat/day8-evidence-audit`

Implementation commits: `d91d22c`, `e9d8a59`, `a090168`

## Delivered boundary

- `GET /api/incidents/{id}/detections?page=0&size=20` returns immutable detection payloads ordered by episode sequence. The payload is returned as stored, including cause, source IDs, KPI numerator/denominator, and null impact fields.
- `GET /api/incidents/{id}/timeline?page=0&size=20` returns system and analyst audit entries ordered by timestamp and ID. It remains distinct from the detection sequence.
- `POST /api/incidents/{id}/comments` accepts `text`, incident `version`, and `requestId`. The authenticated issuer/subject supplies the actor. A same-actor, same-text retry succeeds without another audit row; conflicting reuse returns 409. An incident row lock serializes concurrent attempts, with the existing database uniqueness constraint as a second guard.
- There is no database migration or configuration change. Existing `app.incident_audit` insert-only runtime grants remain in force.

## Reproducible checks

- `./mvnw -Dtest=IncidentControllerTest test`: passed, 10 tests.
- `./mvnw -Dtest=WorkflowTest,IncidentControllerTest test`: passed, 19 tests.
- `./mvnw -Dtest=CommentConcurrencyIT test`: passed, 1 concurrent retry test.
- `./mvnw verify`: passed, including PostgreSQL and Kafka Testcontainers integration tests.
- `./.venv/bin/python scripts/check-contracts.py`: passed the observation, detection, voice parity, and SMS parity checks.
- `git diff --check`: clean at each implementation commit.

The read API test fixture `episode-recovered-history` stores sequences 1 `OPEN`, 2 `UNKNOWN`, and 3 `RECOVERY` and verifies all three remain readable after the incident is resolved. Its source event ID is `00000000-0000-0000-0000-000000000001`; the test also checks cause text, KPI numerator `940`, denominator `1000`, and `impact.uniqueSubscribers: null`. The concurrency test uses `concurrent-comment-episode` and a generated `requestId`, then confirms two simultaneous HTTP requests create one `COMMENT` row.

## Handoff and remaining gate

Sergiu should compare a real detector episode's cause, source IDs, KPI counts, and impact caveat with the two API pages. David should connect the evidence and audit pages to the investigation UI and handle 400/401/403/404/409 and CSRF responses. The backend tests use synthetic fixtures; a live Day 8 telemetry-gap/topology/recovery run and both teammates' confirmation remain integration acceptance work. Do not merge solely on this backend evidence.
