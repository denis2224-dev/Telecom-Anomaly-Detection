# Ion's pinned producer demonstration

Prepared and executed **12 October 2026, Europe/Chisinau**, for the **15 October**
milestone. Mode: **offline packaged producers with an injected logical clock**.
The same pinned [configuration](../../contracts/fixtures/demo/service-seeds.json)
drives two fresh JVM rehearsals. Seed 42 is for local synthetic verification.
This script does not constitute the final live team demonstration.

## Reproduce the package

From the Day 6 worktree, build with Java 21, Maven and Docker/Testcontainers, then:

```powershell
python scripts/check-contracts.py
python -m unittest discover -s tests -v
./mvnw.cmd -B -ntp -pl services/streaming-support,services/event-generator,services/processor -am clean verify
python scripts/rehearse-trace-link-producer.py --java-home '<Java-21-home>' --output '<new-output-directory>'
python scripts/verify-trace-link-day6-evidence.py
```

The rehearsal destination must be new. The driver extracts dependencies from the
freshly packaged event-generator JAR, compiles the checked-in verification driver,
runs **two separate JVMs**, independently checks measured primitives in Python,
and requires every generated filename/hash to match. `rehearsals.json` records
source/tree, config/input/JAR hashes and per-run counts. Rebuilt JAR byte hashes
can differ because packaging timestamps are not a reproducible-build promise;
the two rehearsals must use the same actual JARs and match measurement/checkpoint
bytes. Archived hashes are verified separately by the evidence checker.

Use the opt-in archive/V016 command in the Day 6 evidence document when those
approved disposable inputs are available. Omitting them causes explicit skips.
No bootstrap, reset or scenario command is sent to an existing demo environment.

## Demonstration order and timing

The configured windows below are **logical event times on 12 October**, not
assertions that a live system emitted at those times. Exported observations are
synthetic, finalized and schema validated. Each scenario has eight 60s windows:

| Minute | Relative source interval | Measured behavior |
| --- | --- | --- |
| 1-2 | [0,120s) | Normal |
| 3-5 | [120s,300s) | Fault primitives, or explicit missing data for a gap control |
| 6-8 | [300s,480s) | New healthy measured primitives; missing recovery slots remain missing |

1. At logical 09:00Z, run Transport `chi-hin:a-to-b`, Roaming
   `RO-DEMO-01 / RO / out / REGISTRATION`, and LTE `chi / LTE / DNS`
   faults concurrently. Opposite link direction, inbound partner direction and
   LTE `hin` remain independent healthy controls. The second Transport link
   `chi-orh:a-to-b` has high healthy utilization and supplies no outage claim.
2. At 09:08Z, run normal controls on the three primary scopes.
3. At 09:16Z, run telemetry-gap controls on those scopes. Minutes 3-5 contain
   null primitives/zero coverage, not healthy values.
4. At 09:24Z, repeat the Transport profile with missing minutes 6 and 7.
   Only minute 8 supplies new valid recovery evidence; two absent slots do not
   establish three consecutive healthy windows.
5. At 10:00Z, compile the existing approved legacy paths:
   `VoiceScenario.generateWindows` for VOLTE_IMS_OVERLOAD/NORMAL_CONTROL/TELEMETRY_GAP
   on VOLTE-MD-CENTRAL, and `SmsQueueScenario` fault/normal/gap methods on
   SMS-MD-ROUTE-A. Their V2 source identities and contracts remain unchanged.
6. Show concurrent history, exact replay with unchanged bytes/timestamps, and
   interrupted restart after minute 3: three retained windows, status INTERRUPTED,
   **zero new windows**, publication `UNKNOWN_NOT_TRACKED`.

Transport utilization rises with constant measured effective capacity. Roaming
registration success falls only for the selected direction. DNS success falls
while activation retains its independent measured population. Per-minute values
and stable event IDs are generated and independently verified in the archived
measurement summaries; they are not hard-coded expected output hashes.

## Explain the boundary honestly

The new catalogue has **five executable types**: TRANSPORT_LINK_OVERLOAD,
ROAMING_REGISTRATION_FAILURE, MOBILE_DATA_DNS_FAILURE, NORMAL_CONTROL and
TELEMETRY_GAP. The thirteen remaining definitions are unsupported. Legacy
VOLTE_IMS_OVERLOAD/SMS_QUEUE_DELAY use their existing approved paths and are not
new-catalogue aliases.

The historical archive remains `trace-link-md-30d-v1`, native **300s**, covering
[2026-09-10T09:00Z,2026-10-10T09:00Z). The synthetic minute generator uses
`trace-link-live-synthetic-v1`, **60s**. No archive row is subdivided, rewritten or
relabeled live. A generated population is aggregate attempts, not unique people.

Kafka is disabled. No detector, incident, notification or authenticated UI path
consumes these rehearsal outputs. Opening, escalation, incident recovery and
backlog-drain timing are **NOT_VERIFIED** for these runs. Existing merged offline
rules require five-minute contexts; do not narrate a minute schedule as their
incident transition. The supported offline policy fixtures are separate evidence.
Technical recovery and analyst resolution are different states.

For the later connected demonstration, Denis/Sergiu/David/Stanislav must execute
the [preflight checklist](../architecture/trace-link-release-preflight.md), record
actual window-to-detection/episode timing and sign one shared release identity.
Receiving-owner reproduction and all five signatures remain pending.
