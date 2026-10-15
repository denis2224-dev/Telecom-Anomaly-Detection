# Incident and identity operations

Preserve backend sessions, CSRF, enabled-analyst checks, private recipients and optimistic workflow versions. Native public incident UUIDs are additive: do not rewrite stable episode/detection identities or immutable technical evidence. Technical ONGOING/UNKNOWN/RECOVERED remains separate from analyst OPEN/INVESTIGATING/RESOLVED. Resolve only with required reviewed evidence and a note.

Use the [analyst guide](../user-guide/trace-link-analyst-guide.md) for assignment/comments and the [completion runbook](trace-link-completion.md) for restore/reconciliation. Review [incident retention](incident-evidence-retention.md) before maintenance and [local identity setup](local-dev.md) before provisioning an analyst. Avoid recording credentials, cookies, tokens or subscription secrets in evidence.

After isolated restore, validate unchanged public/technical identities, workflow versions/audit, private recipient ownership, pending notification leases, replay idempotency and runtime grants. Reapply approved secrets separately. Identity-secret restore and real signed-out notification click-through remain pending release checks; a successful data-only restore cannot certify them.
