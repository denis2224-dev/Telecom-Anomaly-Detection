# Session, replay and live-update implementation status

Scope: the session-security, replay-ordering and live-investigation backend guide for tasks 12–14. The removed network-map experiment was not restored. Existing analyst-workflow UI changes were preserved when adding the necessary stream consumer and audit refresh.

| Work | Branch | Pull request | Status |
| --- | --- | --- | --- |
| Session identity, CSRF, deadlines and logout | `fix/session-security-lifecycle` | [#40](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/40) | Merged with original commits; merge `42253e9` |
| Proxy/runtime integration | `fix/proxy-runtime-integration` | [#41](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/41) | Merged with original commits; merge `57fc003` |
| Episode locking, ordered replay and gap reconciliation | `fix/incident-replay-ordering` | [#42](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/42) | Merged with original commits; merge `b05e7fc` |
| Protected directory, committed stream hints and live investigation | `feat/session-bound-incident-stream` | [#45](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/45) | Implementation and real-browser acceptance passed; final CI required before regular merge |

## Verification

The integrated backend passed 129 unit/controller and 25 PostgreSQL/Kafka integration tests, with no skips. The dashboard passed 61 unit tests and its production build. The controlled browser suite contains 38 passing cases; real-environment cases run separately. CI covers backend, dashboard, image, model and processor verification.

- [Session evidence](../docs/evidence/2026-09-30-session-security.md)
- [Replay evidence](../docs/evidence/2026-10-01-replay-ordering.md)
- [Live investigation evidence and reproduction](../docs/evidence/2026-10-02-g3-backend.md)

Both public scenarios recovered with actual model status `OK`. Real browser checks completed the VoLTE and SMS investigations, including comments, cross-tab updates, proxy restart/reconnect, resolution and logout without server completion errors.

## Follow-up constraints for a Codex agent

1. Read the linked evidence and inspect current Git history before changing code. Resume existing branches when applicable; do not repeat completed merges or rewrite history.
2. Use normal merge commits, never squash. Keep `day` out of new branch names and commit/merge messages.
3. Preserve the distinction between implementation checks and release acceptance. Actual HTTPS cookie behavior, an isolated process-kill/ack drill, wall-clock session expiry measurements and independent teammate comprehension require their own recorded evidence.
4. The live browser test uses a temporary account and actual public scenarios. Never print secrets or save authenticated browser storage. It disables its retained local audit actor after deleting the temporary provider account.
5. A failed browser run can resume its existing scenarios with `LIVE_RESUME_RESULTS` pointing to the saved non-secret JSON result. Inspect the scenario and incident states first; do not resume an already resolved investigation. Do not overwrite another user's work or manufacture recovery.
