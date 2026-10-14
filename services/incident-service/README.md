# Incident service

The service is the authenticated query/workflow boundary for Trace Link. Existing VoLTE/SMS APIs and ordered one-minute evidence remain compatible. Versioned inventory/history routes use additive read models with native five-minute history and explicit synthetic provenance.

Run verification from the repository root:

`./mvnw -f services/incident-service/pom.xml --batch-mode --no-transfer-progress verify`

Notification history is durable per analyst. SSE emits after-commit refresh hints; reconnect restores state with authenticated REST. Browser delivery is optional and disabled by default. Never store OIDC tokens, VAPID private keys, encryption keys, or subscription payloads in public API responses or evidence.

See `../../docs/runbooks/incidents-and-identity.md` for actor/session/notification operations and `../../docs/runbooks/trace-link.md` for history, CSV, migration, and release operations.
