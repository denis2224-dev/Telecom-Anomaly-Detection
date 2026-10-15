# Connected dashboard demonstration

## Start the supported local stack

Use a Node version supported by `apps/dashboard/package.json` (this verification used Node 24), Docker Compose, and an existing enabled analyst account. Do not reset database volumes or copy credentials into screenshots.

From the repository root:

```sh
./scripts/up --with-incident-service
./scripts/verify
```

Open `http://telecom.test:8080/dashboard` and sign in through the organization login. The Docker proxy serves the production build. LIVE mode reads protected REST endpoints and the existing incident stream. The configured geographic footprint and generated telemetry are synthetic; this does not represent real subscriber counts or municipal coverage.

Startup builds the browser and application services from this checkout and applies their additive migrations. Existing volumes are retained. The live overview opens both service graphs; roaming history has its own link. Selecting a service hides the other service panel. The headline measurements follow the service filter: selecting SMS shows delivered messages, delivery success, delivery p95 and queue depth. For a populated Trace Link workspace, pass the supplied full archive's **data directory**:

```sh
./scripts/up --with-incident-service --trace-link-reference /absolute/Trace_Link_Integration_Pack/data
```

The archive is validated before startup and imported after the backend migrations. Exact repeats are safe. This imports recorded history without activating a live catalogue or optional detection flags. The same importer is available separately as `python3 scripts/import-trace-link-reference.py /absolute/Trace_Link_Integration_Pack/data --compose`; a host PostgreSQL client is unnecessary. Trace Link selects the registered archive and its recorded period. The headline Backhaul history and Roaming history links open the corresponding investigation; they do not represent live measurements.

After a restart, allow two complete minutes for fresh synthetic telemetry to appear. Service readiness alone does not establish fresh measurements. `./scripts/verify` rejects a database missing any migration from the checkout. Use `python3 scripts/verify-continuous-telemetry.py live --from START_UTC --to END_UTC --output /private/tmp/telemetry-check.json` with an aligned UTC interval after startup to inspect publication, processing and persisted measurements separately.

To verify actual sign-in, fresh VoLTE/SMS headlines and populated history in all five domains with a temporary local analyst:

```sh
E2E_DASHBOARD_LIVE=1 E2E_REAL_LOGIN=1 E2E_BASE_URL=http://telecom.test:8080 \
  npm --prefix apps/dashboard run test:e2e -- dashboard-data-live.spec.ts --workers=1
```

This test removes its temporary identity afterward, disables its analyst record, and does not start fault scenarios or intercept API responses.

## Navigation guide

1. Check the overview's live timestamp, current counts and two service graphs around the map. This layout was retained at the user's request. The global From/To range applies to history; it does not remove a current incident.
2. Enter **Orhei** in Region. Check the selected map marker and **Orhei investigation**, then expand **Orhei · City coverage and history**. The authoritative service scopes are `VOLTE-MD-ORH` and `SMS-MD-ORH`. Open either service using its navigation button. Change KPI chips or select a KPI table row; hover or use arrow keys on the graph for UTC values and the baseline.
3. Open **Incidents (N)**. Use severity, technical state and service filters. **Open incident evidence** opens the actual incident page. An empty Orhei queue means there is no supplied incident; do not substitute a legacy incident or create a cause from a failed ping.
4. On the incident page, read compact updates and expand source evidence only when needed. **Details & workflow** contains assignment and analyst actions. Technical recovery is separate from analyst resolution. **Awaiting analyst resolution** remains visible for a recovered incident with unresolved workflow. Internal evidence IDs remain available under **Troubleshooting**.
5. Open **Scenario runner** from the sidebar. For the implemented fault profiles, choose `VOLTE_IMS_OVERLOAD` with `VOLTE-MD-CENTRAL`, or `SMS_QUEUE_DELAY` with `SMS-MD-ROUTE-A`. Use a nonnegative seed (verification used 42), then **Start scenario**. **Refresh status** reports the authoritative run status; **Stop telemetry** stops a running command. A stop is not proof of recovery. Conflicts or an unavailable generator must remain error states.
6. Wait for the eight-minute fault profile to complete. Open the actual incident for its scope and scheduled window. Verify technical **RECOVERED** while unresolved analyst work stays open, then **Refresh incident**. Return to the overview and verify refresh retains the chosen city and history range.

The compatible scenario backend also accepts catalogue-authorized VoLTE/SMS city scopes. New commands for RIB/TIR are rejected; stored history remains readable. HIN/CAU are reference inventory identities and are not fabricated as legacy VoLTE/SMS scope memberships. New-domain controls use their own complete directional/partner/city tuples and durable command ledger.

## Reversible overview rollout

The existing authenticated `/dashboard` route supports two presentation modes:

- Connected overview: `/dashboard` or `/dashboard?view=connected`.
- Existing scope inventory: `/dashboard?view=scopes`. This mode does not instantiate the connected map or city/history component. It retains authentication, current service evidence, service selection, refresh, incident links and service detail routes.

In the connected view, expand **Service scopes and definitions** and choose **Open scope overview**. The fallback exposes **Open connected overview** to return. Both links preserve the service query filter. A shared fallback URL can include `&service=VOLTE` or `&service=SMS`. This is a presentation switch on the existing route, not a second API or bypass of authorization.

## Verification commands

```sh
npm --prefix apps/dashboard run test
npm --prefix apps/dashboard run test:branding
npm --prefix apps/dashboard run check:fixtures
npm --prefix apps/dashboard run test:e2e
npm --prefix apps/dashboard run build
```

Explicit fixture preview, in a separate terminal:

```sh
npm --prefix apps/dashboard run start:fixtures -- --port 4201
E2E_REAL_LOGIN=1 E2E_CITY_FIXTURE=1 E2E_BASE_URL=http://127.0.0.1:4201 \
  npm --prefix apps/dashboard run test:e2e -- --grep 'fixture city selection' \
  --output /private/tmp/dashboard-fixture-checks
```

For this command, `E2E_REAL_LOGIN=1` only disables Playwright's automatic development server; this is still an explicitly labeled fixture test, not real authentication or live acceptance. Keep the **SYNTHETIC FIXTURE PREVIEW** banner visible.

Authenticated local-stack verification, after the production build and stack are ready:

```sh
E2E_DEMO_LIVE=1 npm --prefix apps/dashboard run test:e2e -- \
  --config playwright.demo.config.ts
```

This opt-in test provisions and deletes a temporary local identity, runs the two real eight-minute legacy scenarios concurrently, checks protected geography/history requests without interception, and captures 1366/768/390px screenshots. It briefly restarts the local proxy to test an actual SSE transport interruption and authoritative REST refresh. Run it on the local demonstration stack. A backend restart ends its in-memory authentication sessions; the proxy reconnect test intentionally leaves the backend and identity provider running. Test output contains public scenario/evidence metadata, with traces, videos and credential screenshots disabled.

The backend now gives each SSE connection a one-minute lease. This bounds abandoned transport slots instead of retaining them for 30 minutes. The existing five-connection limit remains in place. A normal lease ending uses native EventSource retry; a terminal rejection retries after a session check. Each successful reconnect refetches authoritative REST data. Stream connection requests themselves do not update the session's last-activity timestamp; existing REST activity policy and the 15-minute idle / 30-minute absolute deadlines remain in place. Temporary capacity or transport failures remain visible until a connection succeeds; they do not change telecom technical health.

## Unsupported capabilities and handoff

- City → aggregation node → site/eNodeB → cell drill-down and local device measurements have no implemented backend API. The UI labels these **Unavailable** and keeps inherited city/service context separate.
- The documented geography topology endpoint is proposed. Do not claim it is implemented or infer navigable relationships from footprint IDs.
- An Orhei fault scenario cannot be demonstrated through the current scenario command API. Orhei's protected catalogue, service mappings, history and navigation can be verified independently.
- Further topology panels, local measurement panels and additional classified/affected-path evidence remain planned work until the backend supplies authoritative relationships and measurements. Missing cause evidence remains **Cause undetermined**.

Give the [verification evidence](../evidence/assets/dashboard-demonstration/README.md) to Stanislav for capture review and Denis for API integration review. Another teammate should follow this guide unaided. These human reviews are pending; no teammate approval is implied by automated results, and fixture captures are not live acceptance.

## Persisted Trace Link rehearsal

Use the pinned-release procedure in [Trace Link completion runbook](trace-link-completion.md). Two populated rehearsals remain pending; controlled browser fixtures do not satisfy them. Show the registered full archive, approved topology, all five domain panels, qualified gaps, shared incident filtering, assignment/comment/recovery/resolution, exact CSV and absolute-time Grafana link. Record both runs against the same source/image/dataset hashes.
