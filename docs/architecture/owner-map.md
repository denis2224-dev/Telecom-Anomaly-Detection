# Owner Map

| Area | Folder | Owner |
| --- | --- | --- |
| Streaming and simulator | `services/event-generator/` | M1 Zavtoni Ion |
| Processor ingestion/features | `services/processor/` | M1 Zavtoni Ion |
| Processor detection packages | `services/processor/` | M3 Rusu Sergiu |
| ML service | `services/ml-service/` | M3 Rusu Sergiu |
| Incidents and APIs | `services/incident-service/` | M2 Moroz Denis |
| Dashboard | `apps/dashboard/` | M4 Nenita David |
| Infrastructure, scripts, CI, observability | `infra/`, `scripts/`, `.github/workflows/`, `compose.yaml` | M5 Bradu Stanislav |

Shared contracts must land in `contracts/` before dependent implementation. Each handoff should include a PR or commit, exact fixture, startup/config change, passing check, and known limitation.
