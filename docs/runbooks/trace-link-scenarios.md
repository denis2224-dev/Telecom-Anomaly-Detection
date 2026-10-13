# Isolated Trace Link synthetic scenarios

This producer milestone compiles bounded one-minute synthetic measurements and
provides a pure scheduler. Kafka is **DISABLED**; consumer integration is
**PENDING_REVIEW**. It does not open incidents, serve public APIs, start a Spring
context, run background threads, read databases or activate geographic authority.
Existing VoLTE/SMS callers and their V2 topics/contracts keep their current behavior.

The later [protected API and local runtime adapter](trace-link-scenario-runtime.md)
wraps this unchanged pure milestone behind flags that default off. Its local
generation and checkpoint checks do not activate Kafka or approve live detection.

## Supported commands and measurements

`TraceLinkScenarioCatalogue` lists eighteen versioned public symptoms. Only these
five types execute in this version:

| Type | Scope | Generated measurements |
| --- | --- | --- |
| TRANSPORT_LINK_OVERLOAD | LINK_DIRECTION, e.g. chi-hin:a-to-b | Carried Gbit/s, effective Gbit/s capacity, time-weighted utilization percent |
| ROAMING_REGISTRATION_FAILURE | ROAMING_PROCEDURE: partner, country, in/out, REGISTRATION | Independent registration attempts/successes |
| MOBILE_DATA_DNS_FAILURE | CITY_SERVICE: reference city, LTE, DNS | Independent qualifying synthetic DNS and EPS bearer activation attempts/successes |
| NORMAL_CONTROL | Any of the three above | Healthy measured populations; high utilization can be selected explicitly |
| TELEMETRY_GAP | Any of the three above | Explicit null primitives/zero coverage in minutes 3-5 |

The other thirteen catalogue types are **UNSUPPORTED** and fail command validation.
They are VOLTE_SETUP_FAILURE, VOLTE_SETUP_DELAY, VOLTE_MEDIA_QUALITY,
VOLTE_HANDOVER_FAILURE, TRANSPORT_PACKET_LOSS, TRANSPORT_RTT_INCREASE,
TRANSPORT_CAPACITY_REDUCTION, ROAMING_DATA_FAILURE, ROAMING_PARTNER_DEGRADATION,
MOBILE_DATA_ACTIVATION_FAILURE, SMS_DELIVERY_DELAY, SMS_QUEUE_CONGESTION and
SMS_ROUTE_FAILURE. Legacy VOLTE_IMS_OVERLOAD and SMS_QUEUE_DELAY remain available
through the existing legacy caller, with unchanged V2 semantics. They are not
aliases for the new symptom catalogue.

## Compile a reviewable artifact

Use Java 21. Build from the repository root:

```powershell
./mvnw.cmd -B -ntp -pl services/event-generator -am package '-DskipTests'
java '-Dloader.main=md.utm.telecom.generator.tracelink.TraceLinkScenarioApplication' `
  -cp services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher `
  contracts/fixtures/trace-link/scenario-command-v1.json tmp/trace-link/transport.jsonl
```

The checked-in command uses seed **42**, catalogue `trace-link-scenarios-v1`,
topology `trace-link-links-v1`, scope `chi-hin:a-to-b`, and start
`2026-10-13T09:00:00Z`. Copy the command file to make changes. The CLI rejects
unknown/duplicate fields, noninteger counters, non-UTC/aligned starts and commands
over 16 KiB. For Roaming select `ROAMING_REGISTRATION_FAILURE` and scope
`{"domain":"ROAMING","id":"RO-DEMO-01","direction":"out","country":"RO","procedure":"REGISTRATION","topologyVersion":"trace-link-links-v1"}`.
For LTE select `MOBILE_DATA_DNS_FAILURE` and scope
`{"domain":"MOBILE_DATA","id":"chi","direction":null,"country":null,"procedure":"DNS","topologyVersion":"trace-link-links-v1"}`.

The output contains only schema-validated observations. It is a compiled synthetic
dataset, including when its window times are in the future; compilation is not
live delivery. The CLI reports the deterministic runId separately. Exact replay
leaves the artifact untouched; changed bytes at the same target path fail.
Publication uses an atomic no-clobber hard link on the same filesystem and fails
closed on filesystems without hard-link support. Choose a new output path for a
different request and retain previous evidence.

## Parameters and identity

Every accepted command binds type/version, seed, scope/topology, aligned UTC start,
exactly eight minutes, validated parameters and positive/negative control semantics
from its definition. Minutes 1-2 are NORMAL, 3-5 DEGRADED and 6-8 RECOVERY.
NORMAL_CONTROL remains measured healthy throughout; TELEMETRY_GAP replaces the
degraded phase with missing evidence. `missingMinutes` can remove any additional
zero-based minute, including recovery. Stop, missing and restart never create
healthy measurements.

| Parameter | Bounds and meaning |
| --- | --- |
| seed | Nonnegative signed long; deterministic scope/window variation |
| normalUtilizationBp | 0..10000 basis points |
| degradedUtilizationBp | normalUtilizationBp..10000 |
| capacityGbps | 1..1000, positive effective capacity constant within each minute |
| attempts | 0..1000000; a positive population gets a bounded 0..50 generated variation |
| healthySuccessBp | 0..10000 basis points |
| degradedSuccessBp | 0..healthySuccessBp |
| missingMinutes | Unique zero-based ordinals 0..7 |

Success counts are `floor(attempts * successBp / 10000)`, independently for
registration, DNS and activation. Zero attempts remain zero primitives with
INSUFFICIENT_EVIDENCE and a null calculated rate. DNS degradation never changes
activation bytes. Transport reduces the selected utilization by a deterministic
0..50 basis points, then independently calculates `traffic=capacity*bp/10000`
and `weightedSum=measurement*60`, with `validSeconds=60`. No loss counts,
RTT/jitter, LAG state, per-user throughput or customer outage is inferred.

The default runId is SHA-256 over canonical complete command content. An external
ledger may supply its own validated 64-hex runId; conflicting command reuse fails.
Observation identities use the existing domain identity, including source/seed,
dataset, domain/scope/direction/procedure, minute, resolution and schema/policy/
topology versions. Normal and fault commands competing for the same source/window
are conflicting measurements, not additive populations. Scenario type, runId,
expected labels and evaluator truth are excluded from observations.

The [source descriptor](../../contracts/datasets/trace-link-live-synthetic-v1.json)
binds `trace-link-live-synthetic-v1`, source `trace-link-minute-generator-v1-seed-N`
and **60 seconds**. `TraceLinkSyntheticSource` resolves this source explicitly.
The historical archive stays `trace-link-md-30d-v1`, native producer
`trace-link-native-primitives-v1`, **300 seconds**. The new generator never reads
or subdivides the archive. Reference scope namespace identifies inventory only;
it does not grant legacy V2 authority. The native builder is deliberately not
reused because it requires actual native 300-second source rows.

## Pure scheduling, stop and restart

Construct `TraceLinkScenarioEngine(clock, capacity)` with an injected clock and
capacity 1..128 retained runs. Call `start(command)`, then `advance()` after
advancing the clock. Each completed minute is returned once, never before its
window end. An exact command replay returns its snapshot. Same-scope overlapping
half-open intervals are rejected, including after stop/completion/interruption;
adjacent intervals and distinct scopes/directions/partners are independent.
The LTE DNS/activation pair reserves its city together. No queue or scheduler
thread is created. At capacity, further new commands fail; history is not evicted.

`stop(runId)` retains history and all eight expected window identities. It emits
no remaining windows. `checkpoint()` returns immutable command/history snapshots.
The caller owns checkpoint durability; this pure engine does not claim crash-safe
publication or Kafka acknowledgement tracking. `restore(clock,capacity,snapshots)`
checks identities/content and retains terminal states; unfinished runs become
INTERRUPTED with no automatic resume. Unknown runs delivered at/after their start
also become INTERRUPTED and release nothing. If a process loses its checkpoint,
reconcile the external history before a new future run; never restart a past run
from minute zero or infer recovery from absent output.

## Local checkpoints and safe restart

`TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, path)` writes a
versioned, checksummed local file through a flushed same-directory temporary
file and atomic no-clobber hard link. Each generation needs a new filename.
An exact retry preserves the original bytes and modification time. Conflicting
content fails without replacing evidence. Retain the command, checkpoint,
measurement JSONL and their SHA-256 hashes together. NTFS/hard-link support is
required; there is no fallback that overwrites an existing file.

The codec bounds files to 8 MiB, retained runs to 128, histories to eight windows,
and each window to three observations. It rejects duplicate JSON keys, trailing
documents, unknown fields, altered checksums, noncanonical run IDs, changed
parameters, invalid history prefixes, overlapping reservations and future
history. The checksum detects corruption; it is not a signature or access control.

Restore with `TraceLinkScenarioCheckpoints.restore(path, clock, capacity)` using
the same injected UTC clock policy as the scheduler. `Snapshot.publicationStatus()`
and the file envelope always say `UNKNOWN_NOT_TRACKED`. Missing publication
metadata is rejected. A local history entry establishes generation only. No
checkpoint establishes Kafka offer, acknowledgement, persistence or incident
acceptance, even when generation status is COMPLETED.

| Previous state | After file restore | Allowed output |
| --- | --- | --- |
| SCHEDULED or RUNNING | INTERRUPTED; retain exact history prefix | None |
| INTERRUPTED | INTERRUPTED | None |
| STOPPED before minute 1, in fault, or in recovery | STOPPED | None |
| COMPLETED | COMPLETED; all eight identities retained | Exact command retry returns existing status; no new windows |
| Unknown/missing checkpoint or status | Explicit failure; reconcile external history | None |

Stop retains the full reservation even when no window has been released. An
adjacent `[end,end+480s)` run is independent; overlapping same-scope runs remain
rejected after stop/completion. Opposite directions reserve separate scopes.
There is no automatic resume API, background thread, queue or eviction policy.
Schedule a new future command only after reconciling any external effects of the
old run. Do not edit a checkpoint to force continuation. Saving after local
release is not atomic with an external send; power-loss durability, a durable
command ledger, delivery reconciliation and exactly-once publication require a
separately approved integration.

`missingMinutes` is zero-based and may include recovery minutes 5 and 6. Such
windows carry MISSING, zero coverage and null primitives. Only a later new VALID
measurement supplies recovery evidence; phase names alone never prove recovery.
PARTIAL is incomplete observed coverage, STALE is retained snapshot evidence,
and INSUFFICIENT_EVIDENCE cannot be promoted to healthy. Measured numerator zero
with a positive denominator is a numeric zero; zero denominator yields no rate.
The native archive's unknown quality remains separate from live measured quality.

Reproduce stop/restart, concurrent replay, byte preservation and recovery-gap
controls without starting services:

```powershell
./mvnw.cmd -B -ntp -pl services/event-generator -am '-Dtest=TraceLinkCheckpointTest,TraceLinkReliabilityRegressionTest,TraceLinkScenarioEngineTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Get-FileHash -Algorithm SHA256 path/to/checkpoint.json,path/to/observations.jsonl
```

For a packaged, offline checkpoint rehearsal after `verify`, compile and run the
checked-in evidence driver. The output directory must be new. Its clock is
explicit logical time, and its command/checkpoint metadata stays separate from
measurement JSONL:

```powershell
javac -cp services/event-generator/target/event-generator-0.3.0-SNAPSHOT-library.jar -d target/checkpoint-classes docs/evidence/assets/trace-link-reliability/CheckpointRehearsal.java
java '-Dloader.path=target/checkpoint-classes' '-Dloader.main=CheckpointRehearsal' -cp services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher contracts/fixtures/trace-link/scenario-command-v1.json target/checkpoint-rehearsal
```

Expect three generated Transport windows/nine observations, exact byte/timestamp
checkpoint replay, INTERRUPTED restored running history, STOPPED restored stopped
history, zero new windows after restart, UNKNOWN_NOT_TRACKED publication and
Kafka DISABLED. The driver fails if any assertion differs.

Use the existing CLI commands above for normal, Transport, Roaming and telemetry
gap artifacts. A receiving owner must independently reproduce them; author tests
do not constitute that handoff approval.

## Controls, evaluator fixtures and verification

Opposite link/roaming directions and other cities/partners are separate generated
scopes. A healthy high-utilization control is not a customer outage. Capacity
alone proves no member failure. Wrong-city, nonoverlapping or stale transport
evidence cannot qualify as comparable service evidence. The evaluator test checks
these admission conditions; no runtime cause detector is implemented here.

Evaluator-only artifacts live under `tests/evaluation/trace-link/`. Measurement
JSONL and expected-transition JSON are separate. The schedule includes OPEN
candidate, equal-severity update, worsening/escalation, a missing minute, technical
recovery, exact replay and a later new-episode candidate. Its severe minute is
an explicit evaluator-only composition, not an overlapping runnable command.
All transition acceptance stays **PENDING** until an approved new-domain detector
and consumer execute it. Technical RECOVERED never means analyst RESOLVED.
No detector accuracy is claimed. There are no new detector features or persisted
projection messages. The strict artifact path rejects injected truth at both
envelope and primitive boundaries before creating a file.

```powershell
./mvnw.cmd -B -ntp -pl services/event-generator -am '-Dtest=TraceLink*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
./mvnw.cmd -B -ntp verify
python scripts/check-contracts.py
python -m unittest discover -s tests -v
python scripts/trace_link_scenario_contract.py --java-output services/event-generator/target/trace-link-evaluation/domain-measurements.json
python scripts/check-voice-parity.py
python scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json
```

See the [Day 4 evidence](../evidence/2026-10-13-trace-link-scenarios.md) for exact
commands, source identity, counts, skips and handoffs. Kafka, connected incident
generation, authenticated API/UI and owner reproduction remain separate gates.
