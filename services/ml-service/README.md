# Service features — Sergiu days 1–4

This module calculates independent Python VOLTE/SMS features. It does not yet
train models, serve HTTP inference, or provide anomaly ranks.

From the repository root, using Python 3.11+:

```text
python -m venv .venv
# Activate .venv, then:
python -m pip install -r requirements-dev.txt
python scripts/check-contracts.py
python -m unittest discover -s services/ml-service/tests -v
```

## Builder interface

With `services/ml-service` on the Python import path:

```python
from app.features.service_features import build_features

feature_window = build_features(service_observation, node_observations, baseline_context)
```

`service_observation` and each node use the current observation schema. The
baseline context has the same fields as Java `BaselineRegistry.Lookup`:

```json
{
  "baselineVersion": "baseline-v2",
  "status": "DIRECT",
  "scopeId": "VOLTE-MD-CENTRAL",
  "sourceScopeId": "VOLTE-MD-CENTRAL",
  "service": "VOLTE",
  "hourOfWeek": 32,
  "values": {"cssrPct": 99.3, "rrcSrPct": 99.5, "bearerSrPct": 99.0}
}
```

Hour 32 means Tuesday 08:00 UTC, matching the shared fixtures. The caller supplies
the resolved catalogue context, not arbitrary raw user input. `PEER` identifies
the approved same-service source scope; `BASELINE_MISSING` requires an empty
`values` object and null `sourceScopeId`. The catalogue version remains populated
even when that scope/hour has no coverage. Python rejects mismatched scope,
service/hour, impossible observations and nonfinite measurements.

Exact node roles for the current demo are IMS-A CPU, TRANSPORT-A loss and SMSC-A
queue gauges. Invalid sources fail validation. Valid nodes from another scope or
minute are ignored; incomplete/missing nodes cannot supply measurements. Identical
retries do not duplicate evidence, and conflicting inputs fail. There is no join
across service scopes and no averaging of rates or percentiles.

The returned `ServiceFeatureWindowV2` is schema-validated. Service quality remains
the observation's quality; missing node evidence independently disables ML. Rates
with zero denominators are null. Required missing inputs produce empty feature
arrays. Measurements outside the canonical model-vector range also disable ML
while retaining their KPIs. ML feature availability is distinct from deterministic rule volume gates.
Window identity is SHA-256 of compact ASCII-escaped JSON
`[scopeId, windowStart, featureVersion]`; input/output collections are not mutated.

## Shared arithmetic fixtures

`contracts/fixtures/features/parity-v2.json` contains 12 independent cases. Load
the named raw fixture files, then shallow-merge the case's envelope and metric
patches. The raw files use current authoritative field names. Cases are alternative
inputs, not a stream to concatenate. Integer expectations match exactly; floating
expectations have absolute tolerance `1e-9`.

Normal voice uses 1,000 eligible calls, 1,200 RRC procedures and 1,100 bearers.
Normal SMS has 100 successful attempts out of 102; fault SMS has 100 out of 120.
The worked voice case changes technical successes to 940 and failures to 60 while
retaining the 20 excluded user outcomes. Its exported feature window is checked
against the live builder and consumed by the Java voice-rule tests. These checks
do not claim independent Java feature-builder parity; Ion owns that later work.

See [detection contracts](../../docs/detection-contracts.md) for policy and the
handoff boundaries, and [verification evidence](../../docs/evidence/2026-09-18-sergiu-days-1-4.md)
for the tested commands and remaining work.
