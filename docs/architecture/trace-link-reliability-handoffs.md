# Trace Link Day 5 receiving-owner handoffs

Prepared on 12 October 2026 for the 14 October milestone. Author verification is
recorded in [reliability evidence](../evidence/2026-10-14-trace-link-reliability.md).
No receiving owner has independently approved or reproduced this delivery.

| Receiver | Contract and reproduction to perform | Gate |
| --- | --- | --- |
| Sergiu | Use strict detector-visible JSONL from the five executable definitions. Keep evaluator truth under `tests/evaluation/trace-link/` separate. Reproduce wrong city/time, stale snapshot, opposite direction, missing recovery minute and new valid recovery evidence. Numeric archive results remain INSUFFICIENT_EVIDENCE; missing/stale cannot establish technical recovery. | Detector transitions and qualification PENDING_REVIEW; author evaluator expectations are not detector accuracy. |
| Denis | Preserve dataset/source, duration, scope/direction/procedure, policy/topology, revision/final state and exact payload identity. Reproduce exact retry, same identity/different payload, changed run parameters, consumer commit-before-ACK, and immutable ingestion. Generation COMPLETED and UNKNOWN_NOT_TRACKED publication are independent. Own durable command and delivery reconciliation. | No mapping alias, consumer edit, topic activation or held-outbox release is authorized here. |
| David | Expose only TRANSPORT_LINK_OVERLOAD, ROAMING_REGISTRATION_FAILURE, MOBILE_DATA_DNS_FAILURE, NORMAL_CONTROL and TELEMETRY_GAP as executable. Parameter bounds remain in the versioned catalogue; 13 definitions are unsupported. Display STOPPED/INTERRUPTED with retained history, unknown delivery and telemetry gaps. Technical RECOVERED is independent of analyst RESOLVED. | Public API/UI wiring and authenticated reproduction NOT VERIFIED. |
| Stanislav | Reproduce read-only completed-import job, source/manifest rejection, safe import resume and local checkpoint recovery. Runtime roles have no DDL/cross-database privileges. Proposed keys are link+direction, partner+direction+procedure, city+service; source topic names are unassigned. Collect host/container limits, offered/ACK/persisted/finalized rates, lag, CPU/memory, latency, duplicate/invalid/error counts, recovery and drain under the shared load profile. | New transport and connected capacity BLOCKED; production deployment, backup/restore and environment approval remain separate. |

Future approved integration acceptance must inject send failure before ACK,
uncertain ACK, database commit followed by ACK failure, sender mark failure after
ACK, consumer crash before/after commit, unknown publication checkpoint, and
bounded queue exhaustion. Preserve original event/payload bytes on retry and
measure offered, acknowledged, persisted and finalized counts over the same
interval. Unknown delivery requires reconciliation; do not blindly regenerate
completed history. These are planned connected tests, not results of this PR.

The runbook must be sufficient for each receiver to reproduce normal, Transport,
Roaming and telemetry-gap examples with hashes and status output. Record receiver,
commit/configuration, commands, expected/actual result and reproduction date in a
follow-up. Five-owner release sign-off remains pending.
