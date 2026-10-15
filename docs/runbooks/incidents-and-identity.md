# Incidents, identity, and browser alerts

## Identity and authorization

Use the existing backend OIDC session, issuer/subject analyst lookup, application role mapping, and CSRF flow. Login must map to an enabled analyst. Analyst, supervisor, and admin can read protected data; supervisor/admin are required for scenario mutation. Another analyst's notification recipient or browser subscription returns 404 for direct mutation. An endpoint cannot be reassociated to another analyst.

Obtain CSRF from `/api/auth/csrf`; use its returned header name and token for mutations. Never paste tokens/cookies into committed logs. Idle and absolute session expiry plus logout must close private streams and deny later reads. A new login reconciles notification history through REST.

## Ordered evidence and workflow

An incident episode contains immutable detections. Sequence gaps wait; duplicates preserve identity; replay cannot create another alert. UNKNOWN means evidence is unusable, not healthy. Technical recovery and analyst resolution are separate. Assignment uses the flushed optimistic version and append-only audit. An optimistic conflict requires reloading current state before another command.

Notification keys are OPEN:<episodeId>, ESCALATED:<detectionId>, RECOVERED:<detectionId>, and ASSIGNED:<incidentId>:<committedVersion>. Routine updates, decreased/equal severity, UNKNOWN, comments, and nonassignment workflow changes create no item. Assignment targets the enabled new assignee; detection alerts target enabled analysts.

## Browser consent and secrets

The browser registers only after explicit permission/consent. POST `/api/notifications/push-subscriptions` returns the ID used for DELETE. DELETE disables the owned subscription and pending work; foreign ownership is never transferred. Five active subscriptions per analyst are allowed. Registration affects future alerts, not old history. A disabled feature still permits deleting an existing owned subscription.

Push is disabled by default. Enabling requires an agreed HTTPS origin, compatible secure session/proxy settings, a valid VAPID key pair and contact subject, and a Base64-encoded 32-byte AES key. Public configuration exposes only enabled and the public VAPID key. Endpoint, p256dh, and auth are encrypted together with a fresh GCM nonce. Keep private keys in the approved environment/secret store.

Endpoint hosts are an exact provider allowlist. Add a provider only after confirming its real browser origin and outbound policy. Redirects are not followed. A push body contains only generic title, severity, and safe relative incident URL. The service worker must enforce the same origin and must not place scope, cause, evidence, or subscriber details on a lock screen.

## Delivery and failure recovery

Each poll claims at most 50 rows, one at a time, and commits before the bounded network request. Lease expiry permits recovery; a unique owner fences completion. HTTP 404/410 or expiry disables a subscription. Timeout, 408, 429, and 5xx retry with increasing delays up to the configured six attempts; rejection/exhaustion is terminal. Provider acceptance is not browser receipt and delivery can repeat after an uncertain crash.

If keys are missing or mismatched with push on, startup fails. Push off requires no keys and preserves in-app history. A provider outage never rolls back an incident. Inspect delivery-state counts and sanitized error codes, not subscription contents. Fix global VAPID/egress failures before enabling again. A runtime-deactivated analyst is rechecked immediately before a send; a provider request already accepted cannot be revoked.

## Encryption and VAPID rotation

Current implementation writes AES key version 1 and loads one configured key. It does not implement seamless online key rotation. Do not overwrite the AES secret while persisted subscriptions still use it. Restore the old key to recover encrypted subscriptions. For this increment, use a controlled disable-and-reregister procedure for a key change: turn push off, revoke old subscriptions and queued work through approved maintenance, change the key, deploy, and obtain fresh browser consent. Preserve durable event/recipient history.

Changing VAPID application keys requires compatible browser re-registration. Record the key identifier and deployment time without recording private material. A future multi-key reader migration must keep old decrypting keys until every affected row has been re-encrypted or revoked.

## Retention and rollback

No automatic deletion policy is introduced by this increment. History/evidence are immutable; retention uses the existing privileged operational process with separately agreed limits. Soft-disabled subscription ciphertext remains in storage and backups until that process purges it. Never delete incident evidence to solve a delivery backlog.

Rollback optional behavior by setting NOTIFICATION_PUSH_ENABLED=false and TRACE_LINK_SCENARIOS_ENABLED=false, then redeploy a schema-compatible image. Do not edit or reverse applied migrations, run Flyway clean, or remove volumes. Keep in-app history and the validated VAPID/AES recovery secrets backed up according to the infrastructure runbook.

## Frontend completion procedures

# Incident and identity operations

Preserve backend sessions, CSRF, enabled-analyst checks, private recipients and optimistic workflow versions. Native public incident UUIDs are additive: do not rewrite stable episode/detection identities or immutable technical evidence. Technical ONGOING/UNKNOWN/RECOVERED remains separate from analyst OPEN/INVESTIGATING/RESOLVED. Resolve only with required reviewed evidence and a note.

Use the [analyst guide](../user-guide/trace-link-analyst-guide.md) for assignment/comments and the [completion runbook](trace-link-completion.md) for restore/reconciliation. Review [incident retention](incident-evidence-retention.md) before maintenance and [local identity setup](local-dev.md) before provisioning an analyst. Avoid recording credentials, cookies, tokens or subscription secrets in evidence.

After isolated restore, validate unchanged public/technical identities, workflow versions/audit, private recipient ownership, pending notification leases, replay idempotency and runtime grants. Reapply approved secrets separately. Identity-secret restore and real signed-out notification click-through remain pending release checks; a successful data-only restore cannot certify them.
