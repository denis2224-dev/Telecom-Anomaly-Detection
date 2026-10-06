# Foundations and service overview

The earlier work supplies shared data formats, service cards, observation
validation, storage and security. This guide groups those foundations by purpose;
dated evidence records retain the original implementation checkpoints.

- [Service overview screen](../../../apps/dashboard/src/app/features/service-overview) — the first dashboard after login.
- [Shared contracts](../../../contracts/README.md) — the agreed observation, KPI and incident formats.
- [Contract evidence](../../evidence/2026-09-11-day1-contracts.md)
- [Detection foundations evidence](../../evidence/2026-09-18-sergiu-days-1-4.md)
- [Streaming checkpoint](../../evidence/2026-09-17-g0-streaming.md)
- [Observation ingestion](../../evidence/2026-09-18-day04-ingestion.md)
- [Backend ingestion evidence](../../evidence/2026-09-18-day4-backend.md)
- [Responsibilities](../../architecture/owner-map.md)

Open <http://telecom.test:8080/dashboard> after login to see service cards.
The overview now reads the configured inventory and actual stored windows.
Scopes with no measurements show missing data.
