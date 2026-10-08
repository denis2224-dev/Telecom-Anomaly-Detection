# PR #77 supported connected verification

Use this workflow to verify a fresh integrated candidate against real services. It
covers the combined dashboard/session/geography behavior requested by the PR review;
it is not a replay of every historical G4 audit. The
[2026-10-08 acceptance report](../evidence/2026-10-08-sergiu-g4-connected-acceptance.md)
and its sealed assets describe their original runtime only. Archived audit drivers
are historical, non-executable from their committed paths; do not modify them.

## Prerequisites and candidate

- Start from a clean tracked checkout of a committed candidate containing current
  main. Record the candidate SHA and integrated main SHA before the run. Recheck remote main before publication;
  integrate and rerun required compatibility gates if it advances.
- Use Node.js 24, Python 3.13, Git, and Docker with Compose v2 and a running Linux
  container daemon. Application images build with Java 21 in Docker; Java 21 is
  also needed locally if running the Maven verification gates outside Docker.
- Allow at least 60 minutes after build/startup, with capacity for a 75-minute browser
  suite. Actual idle expiry takes 15 minutes plus grace; absolute expiry takes 30
  minutes despite continued trusted input. Keep the machine awake and its clock
  stable throughout. A clock discontinuity invalidates timing acceptance.
- Install dashboard dependencies and Chromium using the tracked lockfile:

```powershell
npm.cmd --prefix apps/dashboard ci
Push-Location apps/dashboard
npx.cmd playwright install chromium
Pop-Location
```

On Linux, install the browser's system dependencies with
`npx playwright install --with-deps chromium` from `apps/dashboard`.
The browser runs against built production assets, without response interception or
fixture fallback. Authenticated assertions use native same-origin browser fetch;
Chromium hostname mapping handles the test origin without changing the system hosts
file. Do not substitute fixture-mode dashboard or auth tests for these
connected checks.

## Isolated run

From the repository root, choose a unique project name and two fresh absolute
paths outside the repository whose parent directories already exist. Project names
must start with `pr77-`, use lowercase letters, digits and hyphens, and be at most
54 characters. If directories are placed inside the checkout they must be ignored
by Git. The private directory contains generated credentials and configuration; the output directory is for reviewable, sanitized evidence.
The paths in this example are Windows examples; substitute fresh absolute paths on
other hosts.

```powershell
node scripts/pr77-connected-verification.cjs --project pr77-20261009-run1 --private-dir C:/Temp/pr77-20261009-run1-private --output-dir C:/Temp/pr77-20261009-run1-evidence
```

The tracked runner derives repository paths from its own location, runs the dashboard
production build and `docker compose build` application builds from the candidate,
and creates fresh private identities and isolated database/Kafka resources. It
disables historical bootstrap and aligns geography activation. Its generated proxy, OIDC issuer/redirects and browser origin
refer to this stack. Startup uses Compose `up --wait` with a 420-second readiness
timeout. Fixed host ports are 18080 (proxy), 25432 (PostgreSQL), 29094 (Kafka),
18081 (generator) and 18082 (incident service), all bound to loopback. Do not use
`scripts/up`, default provisioning commands, the historical Compose files, or an
existing stack for this procedure.

The runner refuses existing project resources, reused directories and occupied
ports. Every Docker operation includes the explicit project, Compose files and
private environment file. Outages and cleanup must verify ownership before operating;
never replace these with unqualified `docker compose stop` or `down` commands.

The runner invokes the required browser suite automatically and, on success or
failure, removes only the owned stack with `down --volumes --remove-orphans` after
checking ownership. Temporary users disappear with its isolated database volume.
Credential cleanup verifies the real private-directory identity and its
`ownership.json` project/nonce marker. On normal stack cleanup, or setup failure
before a stack exists, it removes `stack.env`, `realm.json` and `stack-context.json`.
It keeps sanitized `commands.log`, `compose.json`, `nginx.conf` and `ownership.json`;
it does not recursively delete the private directory. Generated credentials, JWTs
and cookies are redacted from the command log.

The runner returns nonzero on failure; retain that attempt and retry with a fresh
project and paths. If Docker ownership checks or stack teardown fail, it retains
private credentials/configuration for owned-stack recovery and records
`credentialCleanup: RETAINED_FOR_OWNED_STACK_RECOVERY`; it still redacts the log.
Keep that directory private and resolve the reported problem before retrying cleanup;
do not target unrelated resources. Public failure results remain available.

Image builds may reuse source-keyed Docker cache; recorded image IDs and source
SHA identify the built runtime. Dashboard production assets are rebuilt each run.

Private files include `stack.env`, `realm.json`, `nginx.conf`, `compose.json`,
`stack-context.json`, `ownership.json`, and `commands.log`. These contain credentials or sensitive
runtime details; keep the private directory access restricted and do not commit,
attach or publish it. Public `runtime.json` records runtime provenance alongside
sanitized browser `verification.json` and `scenarios.json`. Acceptance requires browser status
`PASSED` and a matching source SHA, not merely healthy containers.

## Required acceptance

The dedicated Playwright suite runs serially, with retries disabled and no opt-in
skips. Its three required cases cover:

| Case | Required result |
| --- | --- |
| Roles, reconnect and geography | Real ANALYST/SUPERVISOR OIDC login, role restrictions, session/CSRF rotation, token-free browser storage, protected-401 behavior and logout; owned proxy outage/recovery creates a fresh SSE connection and authoritative REST refresh while retaining selection, filters and focus; fresh Chișinău VoLTE/SMS faults, Bălți normal and Cahul gap controls agree with map, queue and detail/API evidence. |
| Open idle session | Ordinary REST/SSE traffic continues with no analyst input or test polling. After the service's 15-minute idle limit, expired UI closes the stream and stops background work; protected REST and a new SSE request return 401. |
| Absolute expiry | Trusted browser activity prevents idle expiry through the original 30-minute deadline, without extending it. Expired UI, stopped background traffic and protected REST/new SSE 401 are required. |

The geographic case compares city/service scope, source windows, units, impact,
cause/provenance, UNKNOWN/null behavior, and technical recovery versus analyst
resolution. Sanitized desktop/mobile captures supplement actual API assertions.
Backend time anchors expiry deadlines; browser/backend clock disagreement or timing
interruptions must be recorded as failed/invalid attempts, never converted to PASS.

The runner supplies `PR77_STACK_CONTEXT` as the absolute path to its private
`stack-context.json` and invokes this command from `apps/dashboard` internally:

```text
node node_modules/@playwright/test/cli.js test --config=playwright.pr77.config.ts
```

The private context supplies the owned stack, origin, fresh identities, source
SHA and output paths. Do not publish it or replace it with context from another
project. Per-test timeouts are 25, 18 and 33 minutes; suite timeout is 75 minutes.
Traces, videos and automatic screenshots are disabled to avoid credential/session
capture; only explicitly masked screenshots are public.

The optional GitHub workflow `.github/workflows/pr77-connected.yml` runs this same
runner and safety tests with a 90-minute job limit and publishes public artifacts.
A newly added `workflow_dispatch` workflow becomes available for UI dispatch only
once its workflow file is on the default branch. PR reviewers can run the CLI now
from a clean checkout of the candidate; the workflow is not evidence until it has
actually run against an identified checkout.

The existing `scripts/day3-geographic-live.cjs` is a narrower scenario/timeline and
identity runner. Alone it does not prove receipt-to-feature tracing, controlled ML
outages, durable duplicate replay, all browser presentation checks or security.
This focused procedure also does not replace the full G4 historical audit, owner
review, shared G5 gates, binary downgrade or database rollback verification.

## Evidence, failures and publication

Keep fresh compact verification material separate from
`docs/evidence/assets/sergiu-g4-pr77`. Retain bulky raw captures outside the committed
bundle and provide sanitized downloadable artifacts where available. Preserve all
55 historical index hashes, including archived drivers and configuration.

- Record actual runtime-tested commit and integrated main SHA, image IDs, dashboard
  asset/configuration hashes, backend/UTC timing, run and incident identifiers,
  expected/actual assertions and checksums of published evidence.
- Preserve each failed or interrupted attempt with its own timestamps and outcome.
  A fresh retry is a separate execution. Skipped or incomplete required checks mean
  incomplete acceptance. Record cleanup failures even if checks passed.
- Commit the compact report after runtime verification. Identify a later evidence-only
  publication head separately; it was not directly runtime-tested. An application or
  configuration fix requires fresh builds and rerunning affected connected checks.
- For local Python gates, install the tracked requirements into an isolated
  environment using `python -m pip install -r requirements-dev.txt -r services/ml-service/requirements-ml.txt`.
  Run all six existing candidate CI jobs: `dashboard-verification`, `deployment-config`,
  `model-evaluation`, `processor-evaluation`, `backend-verification`, and runtime-image
  `build`. Record each actual checkout SHA and job URL; distinguish a synthetic PR
  merge checkout from the branch head. Do not infer their PASS from historical CI.

A passing focused run makes the candidate ready for re-review. It does not approve
or merge PR #77. G5 release approval remains **BLOCKED** until the shared
final-candidate gates and required owner sign-offs pass.
