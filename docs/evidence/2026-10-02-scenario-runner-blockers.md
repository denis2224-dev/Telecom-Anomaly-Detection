# Scenario Runner connectivity and workflow wording audit — 2026-10-02

> Historical pre-acceptance snapshot. The live blockers below are superseded by
> [Authenticated live acceptance](2026-10-02-authenticated-acceptance.md):
> ACCEPTED / PASS. Live SSE updates passed; intentional reconnect remains
> **NOT DIRECTLY VERIFIED**. See [the final repository audit](2026-10-02-final-repository-audit.md).

Both requested fixes are implemented and running locally. The fresh authenticated
acceptance pass is **BLOCKED** because this session has no connected browser.
The successful verification supplied by the user remains the accepted prior
baseline; it is not represented here as a fresh post-fix verification.

| # | Requested result | Status and direct evidence |
| --- | --- | --- |
| 1 | Root cause confirmation | **PASS.** The runbook and proxy require a host-run incident-service. Its configuration imports root `.env`, whose known generator entry used the Docker-only alias `event-generator:8081`. Compose previously only exposed 8081 internally. The client maps transport failure to 503 `GENERATOR_UNAVAILABLE`. No environment/profile override elsewhere in tracked configuration corrected this. |
| 2 | Files changed | **PASS.** Six implementation/configuration/runbook files listed below, plus this evidence document and the required refresh of the already-untracked knowledge graph. Existing feature work was preserved. |
| 3 | Generator connectivity fix | **PASS for connectivity.** Compose publishes `127.0.0.1:${GENERATOR_HOST_PORT:-8081}:8081`. The incident-service default uses loopback and supports that host port; `.env.example` now supplies the correct host URL. Docker inspection reports only `HostIp=127.0.0.1`, `HostPort=8081`. Host readiness is 200/UP. A request from processor to `http://event-generator:8081/actuator/health/readiness` also returns UP. Fresh authenticated scenario dispatch remains unverified. |
| 4 | UI semantics fix | **PASS in controlled rendering; live authenticated UI NOT VERIFIED.** The overview says “open analyst incidents” and “Awaiting analyst resolution; technical health may have recovered.” A directly inspected controlled screenshot shows NORMAL health alongside a recovered incident with this wording. No incident counts, technical-state rules, or analyst workflow semantics were changed by this fix. |
| 5 | VoLTE scenario live result | **BLOCKED.** No connected authenticated Scenario Runner; no new public-API run ID, accepted dispatch, full-minute start, or live transition sequence captured. No post-fix claim of successful authenticated dispatch or absence of 503. |
| 6 | SMS scenario live result | **BLOCKED.** No fresh authenticated launch, KPI/evidence changes, degradation, or recovery observation. |
| 7 | Telemetry gap live result | **BLOCKED.** The runner supports TELEMETRY_GAP, but no fresh authenticated run or live MISSING/UNKNOWN/null/no-false-recovery verification occurred. Controlled missing-source rendering passed. |
| 8 | Recovery result | **NOT VERIFIED live after this fix.** Controlled VoLTE and SMS recovered trajectories passed; these do not establish fresh real-minute recovery. |
| 9 | REST result | **NOT VERIFIED live after this fix.** Controlled REST reload tests passed. No authenticated scenario-page/service reload was performed. |
| 10 | SSE result | **NOT VERIFIED live after this fix.** Controlled incident notification/reconnect/expiry tests passed. Anonymous stream access remains 401. Neither real scenario-transition delivery nor live reconnect was directly tested. |
| 11 | Incident result | **NOT VERIFIED for newly generated live incidents.** Controlled cause/confidence/impact/subscriber-unavailable rendering passed. No new live incident detail or evidence was inspected. |
| 12 | Test results | **PASS.** 23 frontend tests across six files; 14 Scenario Runner backend tests; 24 generator tests; 17 controlled browser tests; production dashboard build; incident-service package; Bash syntax; quiet Compose validation; `scripts/verify` after restart; `git diff --check`; isolated patch reverse-applicability check. |
| 13 | Git status | **PASS for preservation.** Working tree remains dirty with existing legitimate changes. Nothing staged, committed, pushed, reset, discarded, or merged. `scripts/up` was not edited in this task; its pre-existing interpreter fix retains SHA-256 `E867115F49253AE217F043C61301BD226B39A91BBBE394DD5152B4B2F8523C45`. |
| 14 | Remaining issues | **BLOCKED:** connect the authenticated ion-supervisor browser and complete the fresh live acceptance pass. Fresh identity/SUPERVISOR-role confirmation is also blocked. The host-service restart may require application sign-in again. A Windows test-server teardown hang was observed separately; the final controlled run completed with explicit server reuse, and its temporary server/processes were cleaned up. No unrelated test-tooling change was bundled. |
| 15 | Ready for PR? | **NO.** Required fresh authenticated acceptance is incomplete. |

The six scoped files are:

- [compose.yaml](../../compose.yaml): loopback-only generator port publication; existing private network retained.
- [.env.example](../../.env.example): host port and host-reachable generator URL, with separate container-address guidance.
- [application.yaml](../../services/incident-service/src/main/resources/application.yaml): loopback default with configurable host port.
- [scripts/verify](../../scripts/verify): bounded host readiness probe and actionable failure guidance.
- [local-dev.md](../runbooks/local-dev.md): addresses, migration, custom-port behavior, recreate/restart steps, and cross-platform local setup.
- [service-overview.component.html](../../apps/dashboard/src/app/features/service-overview/service-overview.component.html): workflow wording only, within the pre-existing feature changes.

Operationally, Docker Desktop and the preserved existing stack were restarted.
Only event-generator was recreated to apply the port mapping. Local `.env` was
migrated by an exact replacement of the known generator URL entry; a byte-level
assertion verified that reversing this one replacement reproduced the original
file. No secret values were printed or inspected. The host service was rebuilt
and restarted from its own directory to reload configuration; readiness is 200.
The initial package attempt encountered the existing process's Windows JAR lock;
stopping the verified host service allowed the final package to succeed.

Authentication and authorization configuration were untouched. Fresh anonymous
checks returned 401 for `/api/auth/me`, scenario POST, and the incident SSE stream.
Backend controller tests passed their role/CSRF rejection cases. These checks do
not verify ion-supervisor's current authenticated identity or role.

Evidence artifacts outside the repository, under `C:/OrangeSystems/Program/`:

- `scenario-blockers-fix.patch`: only the six scoped files; the HTML hunk excludes all pre-existing feature edits. `git apply --check --reverse` passed without changing files.
- `scenario-blockers-frontend-tests.log`, `scenario-blockers-backend-tests.log`, `scenario-blockers-generator-tests.log`: focused test results.
- `scenario-blockers-ui-final.log`: final controlled browser run, 17 passed, exit 0.
- `scenario-blockers-build.log`, `scenario-blockers-package.log`, `scenario-blockers-verify.log`: successful build/package/infrastructure checks.
- `scenario-blockers-git-status.txt`: working-tree audit snapshot before adding this report.
- `scenario-blockers-graphify.log`: completed AST graph update through the installed Python module after the CLI shim failed; optional SQL parser warning retained.

Controlled screenshots are in `apps/dashboard/test-results/dashboard/`. The
inspected recovered VoLTE overview is
`service-assurance-Assurance-volte-recovered-at-1366px/controlled-volte-recovered-overview-1366.png`.
Its telemetry and identity are fixtures, not authenticated live evidence.

The remaining acceptance requires authenticated Scenario Runner launches for
VoLTE, SMS, and TELEMETRY_GAP; server run IDs and next-minute schedules; real
degradation/detection/recovery and missingness observations; REST reconstruction;
real SSE transition delivery; and newly generated incident evidence with cause,
confidence, recommended checks, impact, and null subscriber counts. Reconnect must
remain NOT VERIFIED unless directly exercised.
