# Service fixture verification

## Coverage

The integration fixture coordinates the VoLTE and SMS profiles on aligned
logical windows with independent seeds. It covers healthy controls, degraded
service, missing service telemetry with same-window node evidence, unavailable
ML output, and recovery.

The evidence template records the logical window, contract versions, exact
fixture hashes, container configuration fingerprint, request/run IDs, and
expected-versus-observed results. It explicitly separates static contract
validation from runtime acceptance and does not allow illustrative values to
stand in for unavailable results.

## Checks

- `./.venv/bin/python scripts/check-contracts.py` — passed
- `git diff --check` — passed

Runtime execution remains environment-dependent and should be recorded with
the supplied template when the simulator, broker, detector, and ML services
are available.
