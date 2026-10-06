# Incident evidence retention

The incident service stores one incident per episode and immutable detection and audit history. The read API serves this history in pages of at most 100 items. It never embeds complete histories in an incident list row.

## Current policy

- Retain detection evidence and audit records for every incident, including resolved incidents. There is no automatic deletion or archival job.
- Never delete evidence for an incident whose analyst status is `OPEN` or `INVESTIGATING`, or whose technical state is `ONGOING` or `UNKNOWN`.
- Treat `RESOLVED` as a workflow state, not permission to discard evidence. A future time-based retention period requires a product/data-owner decision and must be added as a separate reviewed change.
- Backups must include incidents, detection evidence, analyst audit, and the Flyway history together. Restore into a disposable database and check incident-to-evidence references and row counts before any future archival procedure is enabled.

The current database schema also protects the latest detection with a foreign key from `incidents(episode_id, latest_sequence)` to `detection_evidence(episode_id, sequence)`. Runtime credentials have no `DELETE` privilege on detection evidence or incident audit. These are useful safeguards, but they do not replace this policy.

## Capacity review

Stanislav should track database size, detection and audit row growth, index size, available storage, and backup duration on the same release configuration used for load measurements. Alert when projected 30-day growth would exhaust the assigned storage budget; raise an earlier warning when less than 20% of that budget remains. Record the actual budget and measured ingestion rate in `docs/evidence/backend-query-baseline.md` before setting an operational alert.

If a finite retention period becomes necessary, first agree the period and legal/audit needs with the data owner. Then design an export with checksums and a tested restore, preserve active/unknown incidents, resolve foreign-key order, and test the procedure on a disposable copy. Do not introduce a deletion query as part of query tuning.
