# Trace Link Grafana Provisioning and Parity Evidence

Recorded 11 October 2026, Europe/Chisinau.
Owner: Bradu Stanislav (DevOps / Infrastructure)

## Provisioning and Security Model

1. **Least-Privilege Role:**
   - Role: `trace_link_reader`
   - Database: `incidents_db`
   - Allowed Schema: `reporting` (`USAGE`, `SELECT` on views)
   - Prohibited Schema: `app` (`REVOKE ALL ON SCHEMA app FROM trace_link_reader`)
   - Direct DDL or mutation is completely revoked.
   - Zero analyst identities, session secrets, or Web Push subscription secrets are exposed.

2. **Reporting Views in `incidents_db`:**
   - `reporting.trace_inventory_city`: Active catalogue cities with geographic coordinates and synthetic attributes.
   - `reporting.trace_inventory_link`: Active intercity links with nominal bandwidth capacities.
   - `reporting.trace_metric_series`: Unified time-series view exposing calculated `value` according to aggregation policy (`RATIO_OF_SUMS`, `WEIGHTED_MEAN`, `COUNT_SUM`, `SNAPSHOT_LAST`) alongside raw primitives.
   - `reporting.trace_link_snapshot`: Intercity link throughput, capacity, utilization %, packet loss, latency, and jitter.
   - `reporting.trace_roaming_snapshot`: Inbound and outbound roaming registration attempts, successes, and active roamer volumes.
   - `reporting.trace_incident_annotations`: Chart annotations for ongoing and recovered incidents.

3. **Datasource and Dashboard:**
   - Datasource UID: `trace-link-pg` (PostgreSQL 16.4 proxy access, max pool 5)
   - Dashboard UID: `trace-link-overview` (Schema version 39, 14 panels covering VoLTE, Transport, Roaming, Mobile Data, SMS, and Incidents)
   - Variables: `city`, `country`, `direction`, `resolution`, `dataset`
   - Proxy Routing: Sub-path `/grafana/` reverse-proxied via NGINX (`telecom.test:8080/grafana/`)

## Verification Summary

| Gate / Component | Command | Result |
| :--- | :--- | :--- |
| **Grafana Provisioning Validation** | `scripts/check-trace-link-grafana` | **PASS**: Datasource, provider, 14 panels, variables, annotations, and SQL security boundaries verified. |
| **Parity Matrix** | `python3 scripts/check-trace-link-parity.py --matrix` | **PASS**: 6 core matrix points (CHI voice, HIN transport, RO roaming, SMS queue, LTE activation/DNS, incidents) verified. |
| **Contract Checks** | `python3 scripts/check-contracts.py` | **PASS**: All 43 KPIs, evidence schemas, and offline policies pass. |
| **Test Suite** | `python3 -m unittest discover -s tests -v` | **PASS**: 157 unit tests passed, 0 failures, 0 errors. |
