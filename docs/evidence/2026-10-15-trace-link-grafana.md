# Trace Link Grafana evidence

Status: **actual isolated provisioning/query checks passed; full-archive parity pending**.

The [actual query record](assets/trace-link-completion/grafana-queries.json) confirms Grafana 11.2.0 imported fourteen panels, executed twenty targets and four variable queries in two contexts against a real isolated PostgreSQL source. Its population was an empty controlled database. Independent backend/SQL integration checks cover all 41 metric definitions and four resolutions with bounded controlled rows at tolerance `1e-6`; they do not establish full-archive parity.

Queries retain separate populations, pool compatible histograms and aggregate primitives instead of percentages/p95. The reader has explicit SELECT grants and bounded read-only session defaults; analyst/private notification access and writes are denied. Incident annotation views omit private analyst/comment data. Links carry absolute visible time, dataset, city, directional link, country/direction, resolution and timezone; credentials are rejected and stale base variables cleared.

## Pending populated parity

Provide the approved reference/oracle pack and checksum-valid full archive. Import through the existing owner/read-model path, provision the candidate datasource with approved TLS/credentials outside evidence, query each panel/variable at matching bounds and compare actual backend values at the declared tolerance. Include all/focused populations, inbound/outbound, snapshots/source age, explicit missing buckets and partial edges. Record source/definition hashes, absolute query bounds and raw redacted comparison results. Missing primitives must remain unavailable. Follow the [Grafana runbook](../runbooks/trace-link-completion.md#grafana).

No full-source comparison, shared deployment or receiving-owner signature is asserted.
