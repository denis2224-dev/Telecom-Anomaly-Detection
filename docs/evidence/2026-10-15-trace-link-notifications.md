# Trace Link notification verification

Status: **durable history/worker/API checks and real stream/session integration pass; secure push remains disabled**.

The final backend suite passes 210 unit checks and 154 integration checks with one unit skip. It covers meaningful transactional/idempotent transitions, replay/rollback, private recipients, enabled identity, native/public incident identity, subscription encryption/ownership, bounded leases, stale-owner fencing, retry timing and revocation. See [exact verification](assets/trace-link-published/verification-summary.json).

The real [connected cases](assets/trace-link-published/verification.json) exercise OIDC/roles/CSRF, fresh fault/control/gap incidents, one authenticated stream, owned proxy interruption, REST repair, signed-out protected reads and private-state cleanup. [Source trace](assets/trace-link-published/scenarios.json) retains ten immutable detections for two recovered fault incidents; normal/gap controls create no false incident and recovery leaves analyst workflow open. Controlled tests additionally cover bounded history/read retry and cross-tab toast suppression.

Keep `app.notifications.push-enabled=false`. Actual permission/background/foreground delivery, generic lock-screen presentation, signed-out clicks and device revocation still require an approved secure origin/browser/device. In-app history is independent of that acceptance. Never retain endpoints, subscription keys, VAPID private material, cookies or CSRF values in release evidence. No secure delivery, shared activation or owner approval is asserted.
