# Evidence projection and failure checks

Checked on `feature/evidence-projection-hardening` after the local geographic-investigation merge. This record distinguishes protected PostgreSQL/MockMvc checks from the earlier real-login integration trace. The optional auxiliary publisher and pure cause-correlation contract were unavailable, so no auxiliary table, power diagnosis or derived cause field was shipped.

## Required negative controls

| Input or failure | Checked result |
| --- | --- |
| Malformed detection and wrong Kafka episode key | No detection, incident or audit row. `ReplayOrderingIT` checks both before any projection. |
| Exact duplicate and changed body under one detection identity | One immutable detection and logical incident; conflicting body rejected. Existing replay and SSE tests also check no second notification. |
| Future sequence before a missing predecessor, late overlap, post-recovery update | Saved pending evidence applies only in sequence; overlapping or post-recovery changes are rejected; recovered technical state keeps analyst `OPEN`. |
| Rollback and lost Kafka acknowledgement | Rolled-back writes leave no evidence or SSE hint; exact redelivery after committed work adds no incident or audit row. |
| Partial coverage and missing baseline | Orhei history reports `PARTIAL`; city read reports stale coverage and `BASELINE_MISSING`, with no invented baseline or delta. |
| Wrong-city coverage key and conflicting coverage body | Rejected; stored row/hash and Balti's independent history remain unchanged. |
| Older window arriving after a newer window | It remains historical. The current city read keeps the newer window. |
| Future window | It remains stored for audit/history but is excluded from the current city summary until its end time is reached. |
| Missing service observation | The city read reports `NEVER_SEEN` and null metrics rather than a healthy default. |

The [negative-control API and row/hash fixture](assets/evidence-projection-hardening/geographic-negative-controls.json) is a transactional PostgreSQL and protected MockMvc result. It records four KPI rows, three coverage rows, the immutable partial-coverage ID/hash and the Orhei response. The independent [bounded-query plans](assets/evidence-projection-hardening/query-plans.txt) use 92,780 live local KPI windows, 1,501 coverage facts and 12 incidents. The 24-hour Orhei history uses `service_kpi_scope_history_idx` and the coverage lookup index (0.277 ms in that run). The city-filtered incident candidate uses the exact-window coverage unique index (0.286 ms); its tiny incident set warrants no new index or migration.

## Verification

- Focused replay, geography, SSE and security suites: 35 tests, zero failures/errors/skips.
- Full clean incident-service verification: 202 reported, 201 executed, zero failures/errors, one existing optional `SmsShadowReplayTest` skip requiring `SMS_SHADOW_REPLAY_DIR`.
- Dashboard units: 113 passed in 23 files, including reconnect and authoritative reload behavior.
- Rebuilt local Compose stack: `./scripts/verify` passed application, identity and routing checks. The [real-login incident regression trace](assets/evidence-projection-hardening/connected-regression-trace.json) passed again for Orhei VoLTE and SMS against the rebuilt service.
- `IncidentStreamIT` proves after-commit notification and no notification on rollback or duplicate replay. `SessionSecurityTest` and `AuthSecurityTest` cover expired/forbidden sessions and CSRF; the earlier real-login geographic smoke also checked anonymous and missing-CSRF responses.
- No new Flyway migration; V001–V004 remain unchanged. Existing source DTOs and historical detection cause remain untouched.

The real-login G3 smoke and incident trace are in [geographic investigation checks](geographic-investigation-checks.md). They do not exercise a live SSE network interruption or a fresh explicit power alarm. Such a power result requires an authoritative auxiliary publisher and a reviewed correlation contract; neither is present in this integration baseline. The incident detector continues to expose its original source evidence and cause text without promoting a gap, ping or missing data to confirmed power failure.
