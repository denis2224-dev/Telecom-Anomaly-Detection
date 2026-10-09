![TraceLink — Follow service evidence](../assets/tracelink-logo.svg)

# Rehearsal regression verification

## Scope and revision

Executed on 2026-10-08, on `feature/rehearsal-regressions`. Baseline: `738f1d2`; pagination repairs: `a06841f`. Existing local branding changes were preserved in `b506d47`. Current main was merged normally to retain the implemented geography, priority and scenario integrations. No backend contract or independent backend implementation was changed.

## R1 — Recovery hidden by a disappearing evidence page

**Reproduction:** open the second evidence page of an incident with 21 records, open Details & workflow, and enter a comment. Change the authoritative response to 20 records and incident version 8, with technical recovery and unresolved analyst workflow, then reconnect.

Before the repair, the second page was empty. The client rejected that page and kept incident version 7 as ONGOING, although REST supplied version 8 as RECOVERED. The error instructed the analyst to retry from the first page. Reproduced at 1366, 768 and 390 px.

**Repair:** when the selected page is beyond the authoritative total, make one bounded request for the first evidence page before publishing the refreshed incident. Keep response validation, generation/version guards and the open workflow draft. The current technical state now reads RECOVERED while the analyst workflow remains INVESTIGATING and displays **Awaiting analyst resolution**.

Incident: `81205a34-116c-5d90-ae54-d80e0dee4fb1`. Before/after versions: 7 → 8. The accepted request sequence changes from `0,1,1` to `0,1,1,0`.

- Before: [1366 state](assets/rehearsal-regressions/evidence-page-shrink-before-1366.json), [1366 capture](assets/rehearsal-regressions/evidence-page-shrink-before-1366.png), [768 capture](assets/rehearsal-regressions/evidence-page-shrink-before-768.png), [390 capture](assets/rehearsal-regressions/evidence-page-shrink-before-390.png).
- After: [1366 state](assets/rehearsal-regressions/evidence-page-shrink-after-1366.json), [1366 capture](assets/rehearsal-regressions/evidence-page-shrink-after-1366.png), [768 capture](assets/rehearsal-regressions/evidence-page-shrink-after-768.png), [390 capture](assets/rehearsal-regressions/evidence-page-shrink-after-390.png).

## R2 — Empty priority page despite available incidents

**Reproduction:** open the incident queue, select its second page with one remaining incident, focus the severity control, reduce the authoritative priority results from 21 to 20 and reconnect.

Before the repair, the queue remained on Page 2 with zero incidents, although 20 incidents were still available. The incident version was 4; only page membership changed.

**Repair:** an empty final priority page returns to the first page through the existing load effect. Service/state filter changes also reset pagination. The queue retains the open drawer, selected filters and keyboard focus; it renders 20 incidents on Page 1. No unbounded fetch or new endpoint was introduced.

- Before: [state](assets/rehearsal-regressions/priority-page-shrink-before.json), [capture](assets/rehearsal-regressions/priority-page-shrink-before.png).
- After: [state](assets/rehearsal-regressions/priority-page-shrink-after.json), [capture](assets/rehearsal-regressions/priority-page-shrink-after.png).

These two defects use controlled API responses to reproduce exact pagination transitions. Their JSON records contain UTC capture times and incident versions. Scenario timing is not applicable to these reproductions. They are not claims that the running backend deleted evidence records.

## Evidence wording and browser identity

The focused regression supplies a failed ping and a conflicting power-loss description. The dashboard retains **Cause undetermined**, rather than confirming power loss. Missing affected paths and evidence classifications remain Unavailable. [Recorded state](assets/rehearsal-regressions/missing-evidence-after.json), [capture](assets/rehearsal-regressions/missing-evidence-after.png). This is controlled evidence validation, not an assertion that local device measurements exist.

The TraceLink tab assets now include the actual multi-resolution root ICO and PNG touch icon, using the same glowing orange trace as the SVG logo. The shared redundant footer and expanded-sidebar workspace labels were removed. Real asset responses, image content types and ICO structure are checked; existing browser caches can retain an older icon until refreshed.

## Combined test isolation

Three older controlled browser setups did not intercept the newly integrated protected priority endpoint. Requests reached the local backend without the controlled session, correctly returned 401, and ended those artificial sessions. Five suite failures resulted, including resource measurements with zero refreshes after session expiry. The setups now explicitly return an unavailable priority response. Authentication behavior was not weakened and the performance assertions remain intact.

## Authenticated replay

The local stack was rebuilt from the integrated current source before this replay. The executed application is the production build. Backend telemetry is explicitly synthetic; the OIDC session, REST responses, EventSource connection, generator commands and persisted detector evidence are real.

- All ten catalogue cities supplied their authoritative VoLTE/SMS scope mappings, protected KPI histories and implemented topology responses. Orhei navigation opened its actual incident evidence.
- Implemented containment is city → aggregation → site → cell. Cells are terminal. A separate eNodeB level and local device measurements are not supplied; device measurements remain **Unavailable**. City/service impact is displayed as inherited context, with the catalogue's synthetic-footprint label.
- Native SSE lease reconnect increased successful catalogue REST reads while retaining Orhei selection, keyboard focus and scroll. No network response was intercepted.
- A prior aborted test stopped its own runs scheduled 19:01–19:09 UTC. The backend still reserved that original interval, and a premature new command correctly returned HTTP 409. The replay waited for that reservation to expire; neither the database nor conflict rules were changed.
- Accepted VoLTE run: `1aa74377-76c9-43c3-8ff9-bc632f787b5e`, scope `VOLTE-MD-ORH`, seed 42.
- Accepted SMS run: `0358f8da-c8f3-4e6e-af5d-987b3bf58346`, scope `SMS-MD-ORH`, seed 42.
- Both schedules: 2026-10-08 19:10:00–19:18:00 UTC. Both incidents were first observed at 19:12:00 UTC and detected after adjacent eligible fault windows. Incident IDs: VoLTE `96e0bb21-5246-4631-b864-d0fb7d39e74a`; SMS `8b980d05-50b2-456a-b778-c6138719a513`.
- The authenticated replay passed in 9.3 minutes on production revision `a06841f` with no tracked patch. Both public runs reached COMPLETED. Both incidents advanced from version 0 to version 4 through five evidence records: OPEN, three UPDATE records and RECOVERY. VoLTE recovery was detected at 19:18:12.009721130 UTC; SMS recovery at 19:18:11.458805755 UTC.
- Technical state became RECOVERED; workflow stayed OPEN. Both screens displayed **Awaiting analyst resolution** after recovery and manual refresh. The real timeline's final record was RECOVERY. Both incident pages passed horizontal-overflow checks at 1366, 768 and 390 px. Sign-out returned protected incident access to HTTP 401.
- The replay logged 279 successful non-authentication API responses. The capture also shows genuine temporarily missing coverage as MISSING, while retaining available historical measurements. Missing coverage was not filled with fixture values.

Evidence: [public run schedules, versions, request times and checks](assets/rehearsal-regressions/live/rehearsal-live.json), [connected Orhei](assets/rehearsal-regressions/live/orhei-connected.png), [VoLTE detection](assets/rehearsal-regressions/live/detected-VOLTE_IMS_OVERLOAD.png), [SMS detection](assets/rehearsal-regressions/live/detected-SMS_QUEUE_DELAY.png).

Recovered captures: VoLTE [1366](assets/rehearsal-regressions/live/recovered-VOLTE_IMS_OVERLOAD-1366.png), [768](assets/rehearsal-regressions/live/recovered-VOLTE_IMS_OVERLOAD-768.png), [390](assets/rehearsal-regressions/live/recovered-VOLTE_IMS_OVERLOAD-390.png); SMS [1366](assets/rehearsal-regressions/live/recovered-SMS_QUEUE_DELAY-1366.png), [768](assets/rehearsal-regressions/live/recovered-SMS_QUEUE_DELAY-768.png), [390](assets/rehearsal-regressions/live/recovered-SMS_QUEUE_DELAY-390.png).

The controlled disappearing-page cases above provide precise before/after failures. The authenticated replay separately checks real requests, navigation, detector progression and recovery; it does not manufacture a disappearing backend history.

## Verification results

- Unit suite: 122 passed across 23 files.
- Combined browser suite: 138 passed; 17 opt-in tests skipped. The six focused regression cases are included in those 138 passes.
- Separate authenticated replay: 1 passed, using real APIs, native reconnect and completed VoLTE/SMS scenarios.
- Production build passed. Deterministic branding, fixture/OpenAPI 0.3.0 validation, canonical dashboard contract equality and local stack/authentication routing checks passed.
- Before the production pagination repairs, the three evidence-page cases and the corrected priority-page case failed on baseline `738f1d2`. The same assertions pass after the repairs. Test-scaffolding mistakes are excluded from the defect count.

## Reproduce

Use Node 24 and the installed dependencies. From the repository root:

```sh
npm --prefix apps/dashboard test
npm --prefix apps/dashboard run build
npm --prefix apps/dashboard run check:fixtures
npm --prefix apps/dashboard run test:branding
E2E_PORT=4217 npm --prefix apps/dashboard run test:e2e -- --workers=2
E2E_PORT=4217 npm --prefix apps/dashboard run test:e2e -- tests/e2e/specs/rehearsal-regression.spec.ts --workers=1
```

For the real authenticated replay, use the existing local stack and an idle Orhei scenario interval. From `apps/dashboard`:

```sh
E2E_REHEARSAL_LIVE=1 npx playwright test --config playwright.rehearsal.config.ts
```

The replay provisions and removes a disposable local supervisor, starts approved VoLTE and SMS scenarios, and uses real OIDC, protected REST, native SSE and persisted detector evidence. No interception or fixture fallback is enabled. Traces, videos and automatic credential screenshots are disabled. Public run schedules, incident versions and UTC captures are written to `test-results/rehearsal-live`.

## Handoff

Sergiu reviews changed labels; Denis reviews changed API handling. These human reviews remain pending. The automated results and evidence are supplied for that review; no teammate approval is inferred.

The local processor also logged recurring receipt-retention `QueryTimeoutException` messages during this execution. KPI publication and coverage consumption continued. This is a backend operational warning for Denis to review, not a dashboard fix or a proposed API change; no database retention configuration was changed.
