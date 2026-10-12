# Event generator

The existing VoLTE/SMS generator and live scenario behavior are unchanged by the
Day 3 native domain producer work. Historical Transport, Roaming and LTE-data
inputs come from actual persisted five-minute archive rows, rather than a new
live scenario adapter. The [domain producer runbook](../../docs/runbooks/trace-link-domain-producers.md)
documents the isolated job and deterministic controls. Test controls use a
separate dataset identity and never add scenario truth to detector measurements.

The additive [Trace Link scenario runbook](../../docs/runbooks/trace-link-scenarios.md)
documents the isolated 60-second Transport/Roaming/LTE compiler and pure scheduler.
It uses a separate synthetic dataset/source namespace and no archive upsampling.
Public API changes and Kafka activation remain PENDING_REVIEW; the legacy
VoLTE/SMS entrypoint is unchanged.
