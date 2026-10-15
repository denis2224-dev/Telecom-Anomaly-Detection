# Trace Link Grafana verification

Status: **full-source isolated provisioning/query parity PASSED; shared deployment and owner acceptance pending**.

[Actual results](assets/trace-link-published/grafana.json) record Grafana 11.2.0, fourteen panels, twenty targets, four variable queries, focused/all contexts and 3,500 non-null cells compared against restricted PostgreSQL queries over the complete pinned archive. [Backend parity](assets/trace-link-published/full-reference.json) covers all 41 dictionary metrics at four resolutions with 16,933 bucket comparisons, tolerance `1e-6`, and preserved gaps/quality/coverage.

A full-data regression exposed a partial queue-opening mismatch: SQL returned zero where the backend could not establish the full-bucket opening within selected context. The additive correction preserves an explicit unavailable result and existing function grants. Earlier migrations retain their bytes. The actual full-source rerun passes.

Queries retain independent populations and use primitive arithmetic/pooled histograms. The reader is denied private analyst data and writes. Links propagate dataset, city, directional link, country/direction, resolution, timezone and absolute visible bounds without credentials or stale base variables. Unsupported primitives remain gaps.

These are real Grafana/PostgreSQL checks in disposable services, not screenshots alone. Shared TLS/configuration and receiving-owner approval are not implied. Follow the [Grafana procedure](../runbooks/trace-link-completion.md#grafana) against the approved target and unchanged manifest.
