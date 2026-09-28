# Service integration evidence

Use this template for a reproducible service-profile run. Replace every
placeholder with captured data or `not run`; do not infer live results from
static checks.

## Scope

| Field | Value |
| --- | --- |
| fixture | `tests/e2e/failures/telemetry-gap.md` |
| logical window | `<aligned UTC minute>` |
| topology version | `<captured value>` |
| profile version | `<captured value>` |
| ruleset version | `<captured value>` |
| baseline version | `<captured value>` |
| VoLTE seed | `<integer>` |
| SMS seed | `<integer>` |
| harness commit | `<commit>` |

## Environment fingerprints

Record the complete output, including command and algorithm:

```text
shasum -a 256:
<captured output>

git rev-parse HEAD:
<captured output>

docker compose config --no-interpolate | shasum -a 256:
<captured output>

container image digests:
<captured output>
```

## Execution record

| Phase | VoLTE scope | SMS scope | Same-window node evidence | Request/run IDs |
| --- | --- | --- | --- | --- |
| healthy control | `<status>` | `<status>` | `<IDs>` | `<IDs>` |
| degraded | `<status>` | `<status>` | `<IDs>` | `<IDs>` |
| missing service telemetry | `<status>` | `<status>` | `<IDs>` | `<IDs>` |
| ML failure | `<status>` | `<status>` | `<IDs>` | `<IDs>` |
| recovery | `<status>` | `<status>` | `<IDs>` | `<IDs>` |

The node rows must use `IMS-A` and `TRANSPORT-A` for
`VOLTE-MD-CENTRAL`, and `SMSC-A` and `TRANSPORT-A` for `SMS-MD-ROUTE-A`.
Confirm their `windowStart` and `windowEnd` exactly match the affected service
window. Missing service telemetry is represented by absence or
`quality: MISSING`, never by zero metrics.

## Expected-versus-observed

| Assertion | Expected | Observed | Evidence reference |
| --- | --- | --- | --- |
| VoLTE breach | `VOLTE_SETUP_DEGRADATION`, `OPEN` after configured count | `<value>` | `<event/detection ID>` |
| SMS breach | `SMS_DELIVERY_DELAY`, `OPEN` after configured count | `<value>` | `<event/detection ID>` |
| telemetry gap | service KPI unavailable; node evidence retained | `<value>` | `<event/detection ID>` |
| ML failure | `TIMEOUT` or `UNAVAILABLE`, null model fields | `<value>` | `<detection ID>` |
| recovery | `RECOVERY` after configured healthy count | `<value>` | `<detection ID>` |
| independent identities | VoLTE and SMS keys/episodes differ | `<value>` | `<detection IDs>` |

List source event IDs, KPI numerators and denominators, severity, technical
state, probable cause, and null impact fields for each detection. Do not add a
model score when ML is unavailable.

## Static and runtime checks

```text
<command> — <result>
<command> — <result>
```

Static contract checks and runtime acceptance are separate claims. Include
broker, simulator, detector, and ML health evidence when those components are
used. If a live result is unavailable, write `not run` and explain the
blocking condition rather than supplying an illustrative result.

## Limitations and follow-up

- `<unresolved limitation or none>`
- `<follow-up evidence link or none>`
