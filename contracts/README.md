# TelecomObservationV2

The [JSON Schema](observations/telecom-observation-v2.schema.json) uses Draft
2020-12. Observations describe synthetic VoLTE/SMS service measurements, node
metrics and source activity for one completed minute.

The separate [legacy EventV1 reference](../docs/streaming/event-v1-contract.md)
covers subscriber events and incident API samples. These formats are not
interchangeable; do not send v2 observations to `telecom.events.v1` or the legacy
incident API.

## Envelope and time

| Field | Meaning |
| --- | --- |
| schemaVersion | JSON integer `2`, not `"2"` or `"2.0"`. |
| eventId | Lowercase UUID; identifies one logical observation and remains unchanged across technical retries. |
| sourceId | Authoritative adapter identity from the inventory. A node reporter identifies one node per scope. |
| scopeId | Monitored service group; opaque, case-sensitive inventory key. |
| kind | `SERVICE`, `NODE` or `HEARTBEAT`; selects the permitted fields. |
| windowStart / windowEnd | Real UTC dates with `Z`, exact minute strings such as `2026-09-15T08:00:00Z`; seconds `00`, no fractions or offsets. |
| emittedAt | UTC `Z`, seconds or 1-3 fractional digits, at or after `windowEnd`. Preserve the original value on retry. |
| quality | `COMPLETE`, `INCOMPLETE`, `MISSING`; describes telemetry, not a service health verdict. |
| service | Required only on SERVICE: `VOLTE` or `SMS`. |
| nodeId | Required only on NODE, matched to source and scope inventory. |
| metrics | Type-specific measured input; absent on HEARTBEAT and on MISSING. |

All envelope fields through `quality` are required. Unknown fields are rejected.
SERVICE and NODE require metrics unless quality is MISSING; a NODE metrics object
must contain at least one measurement.
Source, scope and node IDs are at most 64 characters, start with an uppercase
letter, and contain uppercase letters/digits separated by single hyphens.

Every document covers exactly **60 seconds, `[windowStart, windowEnd)`**. A result
at the exact end belongs to the next minute. Counts are interval counts, never
cumulative totals. COMPLETE zero traffic is measured zero; MISSING has no metrics
and must never be turned into healthy zeros. INCOMPLETE service intervals carry
the same internally consistent counters, but coverage is insufficient for a
complete KPI. They must not be silently upgraded to COMPLETE. NODE reports may
contain a relevant subset of metrics; omitted metrics are unknown, not zero.

HEARTBEAT records activity for a completed minute and has no service, nodeId or
metrics fields. It does not prove that SERVICE/NODE data was received. Multiple
different heartbeats from the same source and scope in one minute conflict.
There is no pulse scheduler, source stale timer or KPI finalizer.

## Authoritative source and scope

[Minimal inventory](topology/demo-scopes-v2.json), version `2-baseline`:

| scopeId | service | SERVICE source | NODE sourceId = nodeId |
| --- | --- | --- | --- |
| VOLTE-MD-CENTRAL | VOLTE | VOLTE-ADAPTER | IMS-A, TRANSPORT-A |
| SMS-MD-ROUTE-A | SMS | SMS-ADAPTER | SMSC-A, TRANSPORT-A |

Only these publishers are authoritative for the corresponding scope and kind.
Any declared source may report HEARTBEAT for its scope. Names are synthetic, contain
no subscriber identities, and cannot contain whitespace. The common transport
dependency is not authorization to join or merge service incidents. The registry
must be expanded explicitly before adding independent load scopes.

## VoLTE counters and denominators

All counters are nonnegative integers up to `2^53 - 1`.

| Metric | Definition |
| --- | --- |
| attempts | All finalized call setup attempts, including user outcomes. |
| technicalSuccesses | Call setup successfully established a session. |
| technicalFailures | Final technical setup failures; excludes busy/no-answer. |
| userOutcomes | Final user busy/no-answer outcomes; excluded from technical eligibility. |
| rrcAttempts / rrcSuccesses | RRC setup procedure counts with their own denominator. |
| bearerAttempts / bearerSuccesses | Bearer setup procedure counts with their own denominator. |
| sip503Count | Final technical failures mapped to SIP 503; subset of technicalFailures, not protocol retransmissions. |

Enforce `attempts = technicalSuccesses + technicalFailures + userOutcomes`.
Eligible attempts are `attempts - userOutcomes`. Every success count must be <=
its own attempt count; RRC and bearer counts need not equal call counts. SIP busy
is a user outcome; authentication challenges and every arbitrary 4xx response are
not automatically technical failures.

### Derived KPI formulas

These definitions do not imply runtime KPI calculation or finalization.

- CSSR percent = `100 * technicalSuccesses / (attempts - userOutcomes)`.
- RRC SR percent = `100 * rrcSuccesses / rrcAttempts`; bearer uses bearer counters.
- SIP 503 ratio = `sip503Count / eligibleAttempts`.
- A zero denominator gives **null/undefined**, never 0% or 100% success.
- Percentages use `0..100`, ratios `0..1`; percentage-point delta is observed minus expected.
- Combine compatible rates by summing their numerators and denominators. Do not
  average percentages or multiply component rates into end-to-end CSSR.

`normal-volte.json` has 1,020 attempts, 20 user outcomes, 995 successes and five
technical failures: eligible 1,000, CSSR 99.5%. `degraded-volte.json` keeps eligible
1,000, with 900 successes, 100 failures and 80 final 503s: CSSR 90%, SIP ratio 0.08.
The radio/bearer denominators remain 1,200/1,100.

## SMS completion windows and backlog

| Metric | Definition / unit |
| --- | --- |
| deliveryAttempts | Finalized terminating attempts ending in the minute; distinct retry attempts count, retransmission of the observation does not. |
| deliverySuccesses | Successful finalized terminating attempts, <= deliveryAttempts. |
| deliveredMessages | Distinct messages successfully completed in this minute, <= deliverySuccesses. |
| deliveryDelayMs | One nonnegative elapsed submission-to-delivery delay in milliseconds per distinct completed message; array length equals deliveredMessages; maximum 10,000. |

Messages may have been submitted before this minute. Delays belong to the minute
of successful completion, not submission or ingestion. Failed attempts do not
create successful-delivery delay samples. Adapters must deduplicate logical messages
before aggregation; aggregate arrays cannot prove underlying message identity.
Equal delay values for different messages are valid. Arrays are not truncated to
pretend complete coverage. Reject input exceeding the 10,000-sample demo bound;
a different representation requires a versioned contract change.

SMS attempt SR = `100 * deliverySuccesses / deliveryAttempts`, null at zero.
Nearest-rank p95 = sorted samples at index `ceil(0.95 * n) - 1`, null for no samples.
Do not average p95s. The normal/degraded fixtures contain 100 samples of 2,000 /
45,000 ms respectively, so those are also their p95 values.

Pending messages have not completed and are reported independently by SMSC NODE
metrics at interval end. `sms-no-completions.json` pairs with `degraded-smsc.json`:
zero delivered messages, empty sample array, **250 pending, oldest age 90 seconds**.
No completed messages does not imply an empty queue.

## NODE metrics and units

| Metric | Range / unit |
| --- | --- |
| cpuPct | Percentage `0..100`. |
| packetLossRatio | Lost / attempted packets or probes, ratio `0..1`; adapter must retain a consistent measurement method. |
| throughputMbps | Nonnegative decimal megabits/second. |
| queueDepth | Nonnegative integer pending message count at interval end. |
| oldestPendingAgeSeconds | Nonnegative seconds, reported with queueDepth. An empty queue has age 0; nonempty queues may have age 0 for newly pending work. |

CPU/loss/throughput summarize the minute; queue values are end-of-window snapshots.
Queue count and age must be present together. Missing queue data is unknown.
Field names carry units: alternate keys such as `cpuRatio` or `delaySeconds` fail
schema validation. Validators cannot detect a producer using the wrong unit while
still supplying a numerically plausible value; adapters own unit conversion.

## Retries, duplicates and conflicts

Natural key: **`sourceId + scopeId + kind + windowStart`** (a tuple, not ambiguous
string concatenation). It permits one authoritative observation per interval.

1. Schema checks structure, dispatch, formats and ranges.
2. Per-document semantic checks enforce time arithmetic, authority and identities.
3. Cross-event checks compare both event ID and natural interval key before adding
   an observation. A second identical document returns `DUPLICATE`, never extra traffic.
   Changed content under either key is `CONFLICT`, including a new UUID for the same
   natural interval or a changed emittedAt. The previous receipt remains unchanged.

Comparison is parsed JSON value equality: object key order, whitespace and equivalent
JSON number spellings are insignificant; array order is significant. Metadata such
as seed, scenario name, labels and run ID lives outside observation/model input.
No corrective overwrite protocol is defined. A JSON Schema cannot detect conflicts
across documents.

## Checks and fixture usage

From repository root, using the existing Python development dependencies:

```text
python scripts/check-contracts.py
python -m unittest discover -s tests/reference -v
python -m unittest discover -s tests -v
python scripts/check-contracts.py --batch observations.json
```

The optional batch is a JSON array and reports duplicate counts or exits nonzero
on conflict. [ObservationBatch](../scripts/observation_contract.py) is a finite,
in-memory reference checker, not a production durable receipt implementation.
The [validation cases](fixtures/validation/observation-cases-v2.json) are executable
mutations shared by reference and Java tests; sample-bound tests allocate arrays
at runtime. Normal/degraded fixture files are **alternative scenarios for the same
minute**. Validate them individually; do not concatenate alternatives as traffic.
