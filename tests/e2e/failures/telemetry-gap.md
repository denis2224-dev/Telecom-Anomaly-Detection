# Telemetry-gap integration fixture

This fixture is a deterministic acceptance contract for the two core service
profiles. It does not contain live observations or claim that a run succeeded.
The runner supplies `WINDOW_START` as an aligned UTC minute and keeps that value
for every command.

## Inputs

| Input | Value |
| --- | --- |
| topology | `contracts/topology/demo-scopes-v2.json` (`2-baseline`) |
| observation profile | `services/event-generator/src/main/resources/seeded-intervals-v2.json` (`2-baseline`) |
| baseline | `contracts/baselines/demo-baseline-v2.json` (`baseline-v2`, UTC) |
| rules | `contracts/policies/service-rules-v2.json` (`service-rules-v2`) |
| VoLTE scope | `VOLTE-MD-CENTRAL` |
| SMS scope | `SMS-MD-ROUTE-A` |
| VoLTE seed | `41092801` |
| SMS seed | `41092802` |
| logical window | `${WINDOW_START}`, then consecutive one-minute windows |

The seeds are deliberately different. Repeating a run requires the same
profile, topology, rules, baseline, `WINDOW_START`, command order, and seeds.
Changing either seed must produce a distinct run while preserving event
identity rules.

## Coordinated run

Use the authenticated simulator API, or the equivalent generator command when
the API is unavailable. Keep the two service streams on the same logical
windows; do not substitute wall-clock sleeps for window polling.

```bash
: "${WINDOW_START:?Set WINDOW_START to an aligned UTC minute}"
export VOLTE_SEED=41092801
export SMS_SEED=41092802

# Establish independent healthy controls.
run_scenario NORMAL_CONTROL VOLTE-MD-CENTRAL "$VOLTE_SEED" "$WINDOW_START"
run_scenario NORMAL_CONTROL SMS-MD-ROUTE-A "$SMS_SEED" "$WINDOW_START"

# Breach both profiles in the same windows.
run_scenario VOLTE_IMS_OVERLOAD VOLTE-MD-CENTRAL "$VOLTE_SEED" "$WINDOW_START"
run_scenario SMS_QUEUE_DELAY SMS-MD-ROUTE-A "$SMS_SEED" "$WINDOW_START"

# Remove service telemetry while retaining same-window node evidence.
run_scenario TELEMETRY_GAP VOLTE-MD-CENTRAL "$VOLTE_SEED" "$WINDOW_START"
run_scenario TELEMETRY_GAP SMS-MD-ROUTE-A "$SMS_SEED" "$WINDOW_START"

# Return both service profiles to healthy values.
run_scenario NORMAL_CONTROL VOLTE-MD-CENTRAL "$VOLTE_SEED" "$WINDOW_START"
run_scenario NORMAL_CONTROL SMS-MD-ROUTE-A "$SMS_SEED" "$WINDOW_START"
```

`run_scenario` is a harness placeholder, not a command supplied by this
repository. A concrete harness must submit the scenario request with a unique
`requestId`, the supplied `seed`, `scopeId`, and aligned logical start, then
poll the run to a terminal state. Exact retries must reuse the request body.

The telemetry-gap phase must contain:

- no `SERVICE` observation for the affected scope and window;
- `NODE` observations for `IMS-A` and `TRANSPORT-A` in
  `VOLTE-MD-CENTRAL`, and for `SMSC-A` and `TRANSPORT-A` in
  `SMS-MD-ROUTE-A`;
- the node observations' `windowStart` and `windowEnd` equal to the missing
  service window;
- no replacement zero-valued service metrics.

The authoritative fixture names for constructing the stream are
`normal-volte.json`, `normal-ims.json`, `normal-sms.json`,
`normal-smsc.json`, `degraded-volte.json`, `degraded-ims.json`,
`degraded-sms.json`, `degraded-smsc.json`, and `missing-volte.json`.
Validate every emitted document against
`contracts/observations/telecom-observation-v2.schema.json` and topology
authority before sending it.

## Expected outputs

These are assertions for a harness, not recorded results.

| Window condition | VoLTE | SMS | Evidence and ML |
| --- | --- | --- | --- |
| healthy control | no detection | no detection | no node anomaly is inferred |
| degraded, same window | `VOLTE_SETUP_DEGRADATION`, `OPEN` after the configured breach count | `SMS_DELIVERY_DELAY`, `OPEN` after the configured breach count | evidence includes the same-window service and node event IDs |
| missing service telemetry | no synthetic service KPI or zero; technical state `UNKNOWN` or unavailable according to the detector contract | same | node evidence remains queryable; cause identifies missing telemetry |
| ML failure during an eligible detection | deterministic rule output remains available; `mlStatus` is `TIMEOUT` or `UNAVAILABLE`; `modelVersion` and `anomalyRank` are `null` | same | no fabricated model score |
| healthy recovery | `RECOVERY` only after `recoverAfterHealthyWindows` | `RECOVERY` only after `recoverAfterHealthyWindows` | recovery KPI values and node evidence use the same window |

For each detection assert `schemaVersion: 2`, the configured ruleset, baseline,
and topology versions, UTC half-open windows, non-empty source event IDs,
service-specific KPI numerator/denominator values, and the contract's null
versus zero semantics. Assert that the VoLTE and SMS correlation keys and
episode identities are independent.

## Environment fingerprint

Capture fingerprints before the run and attach the output to the evidence
record. Hash the exact files used by the harness; do not replace a hash with a
host name or a mutable tag.

```bash
shasum -a 256 \
  contracts/observations/telecom-observation-v2.schema.json \
  contracts/topology/demo-scopes-v2.json \
  contracts/baselines/demo-baseline-v2.json \
  contracts/policies/service-rules-v2.json \
  services/event-generator/src/main/resources/seeded-intervals-v2.json
git rev-parse HEAD
docker compose config --no-interpolate | shasum -a 256
```

Record the command output, container image digests, selected profile, logical
window, and both seeds. A missing fingerprint is a fixture failure, not a
live-result placeholder.

## Validation examples

```bash
./.venv/bin/python scripts/check-contracts.py
./.venv/bin/python -m pytest tests/reference/test_observation_contract.py tests/test_detection_contracts.py
git diff --check
```

The checks above validate repository contracts only. A passing static check is
not evidence that a live simulator, broker, detector, or ML service produced
the expected outputs.
