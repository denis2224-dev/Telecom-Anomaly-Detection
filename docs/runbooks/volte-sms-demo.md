# Limited VoLTE/SMS demonstration

This candidate demonstrates synthetic VoLTE and SMS in Orhei using real Kafka,
PostgreSQL, Keycloak, backend APIs and the production dashboard. It does not
activate new-domain scenarios or browser push and does not grant full release or
human acceptance.

## Build and verify

Use the supported Java 21, Node 24 and Python environment. Docker Desktop must be
running. Install the locked dashboard dependencies and Chromium:

```powershell
npm.cmd --prefix apps/dashboard ci
npm.cmd --prefix apps/dashboard exec -- playwright install chromium
```

Commit intended source changes first. The runner requires a clean candidate and
refuses ignored source contamination, occupied dedicated ports and preexisting
Docker resources for the requested project. Choose new private/public output
directories outside the repository; their parent directories must exist.

```powershell
node scripts/pr77-connected-verification.cjs --project pr77-volte-sms-demo-unique --private-dir C:/approved/private-demo --output-dir C:/approved/demo-results --mode volte-sms-demo --retain-stack true
```

Use `--python C:/absolute/path/python.exe` when selecting a particular Python
environment. `--reference-pack C:/approved/Trace_Link_Integration_Pack` additionally
validates and imports full native history; the limited live demonstration does
not require that separate archive. Existing installations and repository `.env`
are not used. Ports are loopback proxy 18080, PostgreSQL 25432, Kafka 29094,
generator 18081 and incident service 18082. The other service ports remain private.

The runner builds source-labelled application images and a production frontend,
starts owned volumes and provisions private disposable identities. It writes
`candidate.json` with source, runtime image IDs, asset and configuration hashes.
No credentials are written to public evidence.

## Automated acceptance

The suite requires exactly four passing cases and no filtered/skipped success:

1. Complete Orhei VoLTE/SMS repetition one.
2. Complete repetition two, on the same candidate and running stack.
3. Actual fifteen-minute inactive-session expiry.
4. Actual thirty-minute absolute expiry despite trusted input.

The repetition pair runs serially. Session checks run concurrently in separate
real browser sessions; the suite takes at least thirty minutes. Each repetition
starts eight-minute VoLTE/SMS faults plus normal and telemetry-gap controls from
the supervisor UI, verifies identical command retry, checks ongoing incidents,
claims them as an analyst, saves investigation/comments, rejects premature
resolution, reconnects SSE after an owned proxy restart, observes recovery, then
explicitly resolves both incidents. It compares ordered API/database detections,
KPI payloads and durable source/Kafka receipts. Normal/gap controls must not create
false incidents. Desktop/phone captures, run IDs, trace rows and UTC times are
saved. Human acceptance remains pending.

`verification.json` and `runtime.json` must both report PASSED and identify the
same source. Both `demo-repetition.json` records must have the same candidate
SHA256. Retaining a stack is permitted only after successful verification and
configuration/resource ownership validation. On failure, only owned resources
are cleaned up and the attempt remains recorded; use fresh paths for a retry.

## Open and operate the accepted local demo

The explicit retention option keeps the verified containers, data and private
identity context. Credentials remain local. Open an authenticated supervisor
browser without printing them:

```powershell
node scripts/volte-sms-demo.cjs open C:/approved/private-demo/stack-context.json
```

The browser resolves `telecom.test` locally without requiring a hosts-file edit.
It opens the Orhei dashboard. Use Scenario runner to start a fresh Orhei fault;
then open the generated incident, claim it, start investigation and add a note.
After technical recovery, enter a resolution note and resolve. The checked runs
remain in incident history. A cell/device measurement is not inferred from a
service KPI. Estimated failed attempts are not unique subscribers.

```powershell
node scripts/volte-sms-demo.cjs status C:/approved/private-demo/stack-context.json
node scripts/volte-sms-demo.cjs stop C:/approved/private-demo/stack-context.json
node scripts/volte-sms-demo.cjs start C:/approved/private-demo/stack-context.json
```

These operations validate the ownership marker and every existing container.
Stop/start retain data. Do not edit generated private configuration after a run:
its hashes belong to the candidate. Changes require fresh verification. A human
walkthrough, receiving-machine installation and complete release gates remain
separate from this automated limited demonstration.
