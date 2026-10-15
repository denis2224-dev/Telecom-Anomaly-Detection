# Trace Link release evidence index

Decision: **final release gates NOT PASSED; implementation and connected verification published**.

The [integrated candidate manifest](../release/trace-link-release-manifest.json) pins runtime source `280bdacf41d9db9cf69c8bfa4085940ece288bc6`, actual local images, migrations, API 0.5.0, the full archive, models/baselines/policies/topology, identity configuration, Grafana and built frontend assets. It records pending human/target acceptance. No registry image publication, shared deployment, main merge or owner signature is asserted.

Current results: [implementation](trace-link-completion-status.md), [six-phase record](trace-link-assignment-status.md), [frontend acceptance](final-ui-signoff.md), [verification JSON](assets/trace-link-published/verification-summary.json) and [artifact checksums](assets/trace-link-published/artifact-index.json).

The full archive is supplied, checksum-valid and imported idempotently into both owners. Full-source backend/SQL and actual Grafana checks, full restore, production browser history and fresh fault/control/gap lifecycles pass. Actual OIDC/roles/CSRF/SSE/logout and exact five-domain CSV checks pass in an owned temporary stack, which is cleaned up. The final backend gate passes 210 unit checks and 154 integration checks with one unit skip and no failures. The root Python suite passes 191 checks with one platform-runtime skip.

The local [offline manifest](assets/trace-link-published/offline-manifest.json) describes a completely assembled, integrity-verified package at `/private/tmp/trace-link-offline-candidate`. Target installation and receiving-person acceptance remain pending. The source package is pinned to the tested runtime commit; later evidence-only commits are recorded in the repository.

Prior evidence indexes remain unchanged. The earlier unaccepted backend fragment is preserved as [historical evidence](assets/trace-link-published/superseded-backend-manifest.json); its obsolete blockers are not current implementation findings.

Remaining gates: secure-browser acceptance or an accepted flag-off decision, timed session and browser/assistive-technology reviews, connected new-domain activation, coordinated capacity, identity/offline target verification, two complete populated rehearsals on one identity and five actual owner reviews. Current fresh lifecycles pass, but original failure causation still needs retained historical logs or a database snapshot. See the [demo procedure](../demo/david-script.md) and [operations runbook](../runbooks/trace-link-completion.md).
