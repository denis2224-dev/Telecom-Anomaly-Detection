# Trace Link notification evidence

Status: **private history and protected delivery implementation verified in isolation; secure-origin acceptance pending**.

Meaningful OPEN, ESCALATED, RECOVERED and ASSIGNED transitions create transactional, idempotent per-recipient history. Routine updates, comments and duplicate delivery do not create extra events. Recipient isolation, rollback/idempotency, disabled analysts, native/public identity, protected subscription ownership, encrypted storage, bounded leased queues, stale-owner fencing, retries and revocation are covered by actual isolated database tests in the [incident-service results](assets/trace-link-completion/verification.json).

Controlled browser checks cover bounded REST reconciliation, one EventSource per authenticated tab, startup silence, session/user-switch cleanup, accessible history/read retry and duplicate-toast coordination. Controlled worker checks exercise generic payload and same-origin click handling. These are not claims of delivery through an external push service or certification of a real permission dialog/device.

## Pending secure acceptance

Keep `app.notifications.push-enabled=false`. An approved HTTPS origin/browser/device is needed for explicit opt-in, permission granted/denied/unsupported states, foreground suppression/reconciliation, generic background lock-screen content, signed-out click authentication, restart, unsubscribe and expired endpoint handling. Record the exact build, origin, browser version and redacted outcome; never retain subscription endpoints/keys, VAPID private material, cookies or CSRF values in evidence. Shared activation requires separate authorization after acceptance.

Complete the connected populated incident lifecycle and missed-stream/cross-tab checks during both final rehearsals. Technical recovery must leave analyst resolution separate. No actual secure-origin delivery or owner approval is recorded.
