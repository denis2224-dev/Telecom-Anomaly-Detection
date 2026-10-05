# Day 1 DevOps Baseline

This record captures the baseline inspection and runtime attempts for the Day 1
infrastructure handoff. It distinguishes repository facts from checks that were
blocked by the local environment.

## Baseline

| Field | Value |
| --- | --- |
| Repository | `denis2224-dev/Telecom-Anomaly-Detection` |
| Branch | `main` |
| Revision | `c539b67a40ac776c693e9301fac91b035e4037bc` |
| Revision message | `Merge dark operations UI into main` |
| Captured at | `2026-10-05T10:20:30Z` |
| Working tree | Dirty: one untracked file |
| Existing local changes | `scripts/failure-drill.py` is untracked and was preserved |
| Related PR 43 | Open, `feature/volte-sms-service-assurance`, head `90a7273`; not merged |
| Related PR 46 | Merged, `bound-history-resources`; included in the current mainline history |

The Day 1 changes are isolated on `feature/devops-day1-baseline`. No existing
tracked changes were reset, staged, or overwritten.

## Environment inspection

The repository's documented startup path is:

```text
npm --prefix apps/dashboard ci
npm --prefix apps/dashboard run build
./scripts/up
```

The default path starts PostgreSQL, Kafka, database provisioning, Keycloak,
the local proxy, Kafka topic initialization, the event generator, processor and
private ML service. The host incident service is then started separately, unless
the opt-in `./scripts/up --with-incident-service` path is used.

| Check | Status | Observation |
| --- | --- | --- |
| `.env` present | PASS | Existing local configuration was detected; secret values were not printed |
| Compose configuration | BLOCKED | Docker daemon socket was unavailable |
| Docker daemon | BLOCKED | `docker info` could not connect to the local Docker socket |
| Startup | BLOCKED | `scripts/up` was not run because its required Docker prerequisite failed |
| Local proxy | BLOCKED | `localhost:8080` refused the connection |
| Canonical proxy hostname | BLOCKED | `telecom.test:8080` refused the connection |
| Host incident readiness | BLOCKED | `localhost:8082/actuator/health/readiness` refused the connection |
| Authenticated browser access | NOT VERIFIED | No live proxy or incident service was available |

These are current observations, not fixture or historical results.

## Safety and integration decisions

- The existing Docker, proxy, Keycloak, Kafka, database and host-backend
  architecture is preserved.
- PR 43 is not automatically merged or modified.
- PR 46 is treated as already integrated because it is merged into the current
  mainline ancestry.
- The geographic service-assurance feature remains outside this Day 1 change.
- The feature-toggle boundary and bounded probe contract are documented separately
  in the Day 1 runbook; no dashboard or telecom business logic was changed.
- Database backup and disposable restore are marked `BLOCKED` until Docker is
  available. No development volume or database was reset.

## Handoff status

The repository baseline is reproducible and documented, but runtime acceptance is
not established on this machine. The next operator should start Docker Desktop,
rerun the documented startup path, run `scripts/verify`, perform the authenticated
browser check, and complete the backup/restore rehearsal using the runbook.
