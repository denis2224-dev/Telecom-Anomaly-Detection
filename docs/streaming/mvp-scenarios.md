# Three MVP simulator scenarios

Day 01 defines the scenarios only. Generator and detection code comes later.

## Reproducibility and separation

The generator will use a random seed (for example, 11092026), a replaceable Clock
and fixed synthetic profiles. These inputs make scenario runs repeatable. Normal
background traffic will use a separate random stream, so changing its rate does
not change the injected sequence. The values below describe simulator input, not
detection thresholds.

The same setup must reproduce relative times, entities and measurements. Each new
run gets new event, run and business IDs. Re-delivery keeps the original event data
and `eventId`.

Normal controls run before, during and after injection. If `scenarioRunId` is used,
both control and injected events receive it. Scenario names stay out of Kafka data,
and scenario metadata is never used for features or detection.

## 1. Account compromise / high call activity

**Target:** SUBSCRIBER:SUB-000001, synthetic home country MD, NODE-CHI-001.

**Control:** SUBSCRIBER:SUB-000002, same home country, independent normal activity.

Planned input:

| Period (UTC, 11 September 2026) | Target input | Normal control |
| --- | --- | --- |
| [08:15:00, 08:20:00) | Two completed domestic calls, ending 08:15:30 and 08:18:30; 120 seconds each. | Two completed MD calls ending 08:16:00 and 08:19:00; 30 seconds each. |
| [08:20:00, 08:21:00) | Exactly 60 OUTBOUND completed calls, one ending each second from :00 through :59; one second of connected talk each. | One OUTBOUND completed MD call ending 08:20:30, lasting 30 seconds. |
| [08:21:00, 08:26:00) | Return to two completed MD calls ending 08:23:30 and 08:25:30; 120 seconds each. | Two completed MD calls ending 08:22:00 and 08:25:00; 30 seconds each. |

The burst uses 30 RO, 18 DE, 6 JP and 6 MD destinations. Relative to home country
MD, 54 of 60 calls are international. JP is unusual only because it is absent from
this subscriber's setup history. Each call has a unique `callId` and `eventId`,
uses the same node and sets `roaming` to false. The short calls model scripted use.

**Raw events:** EventV1 CALL records. A normal record is available in
[normal-call.json](../../contracts/events/v1/examples/normal-call.json). The
60-event sequence has not been generated yet.

**Planned features:** higher `calls_count` and `international_call_ratio`, new
destinations and shorter average duration than the subscriber's history. M3 will
decide how these values affect detection; the events alone do not prove compromise.

## 2. Network link cut / network outage

**Target:** NETWORK_LINK:LINK-CHI-001, reporting node NODE-CHI-001,
REGION-CHI-CENTRAL, 500 associated synthetic subscribers.

The scenario uses one reporting endpoint, no alternative route and stable subscriber
associations. These are simulator settings, not facts inferred from measurements.

Each window lasts 60 seconds. Times are UTC `occurredAt` values on 11 September
2026 and mark the exclusive end of each window.

| Window end | status | packetLossRatio | latencyMs | throughputMbps | bytesTransferred | activeSubscriberCount |
| --- | --- | --- | --- | --- | --- | --- |
| 08:16:00Z | UP | 0.001 | 12.5 | 80 | 600000000 | 500 |
| 08:17:00Z | DEGRADED | 0.35 | 180 | 8 | 60000000 | 500 |
| 08:18:00Z | DOWN | 1 | null | 0 | 0 | 500 |
| 08:19:00Z | DOWN | 1 | null | 0 | 0 | 500 |
| 08:20:00Z | UP | 0.001 | 12.5 | 80 | 600000000 | 500 |

**Normal control:** LINK-CHI-002 / NODE-CHI-002 in REGION-CHI-NORTH, with 300
subscribers. At each listed time it stays UP with 0.001 loss, 12.5 ms latency,
40 Mbps and 300000000 bytes. It has no route or failure shared with the target.

**Raw events:** five target and five control NETWORK measurements. Healthy and
full-outage snapshots are available as
[normal-network.json](../../contracts/events/v1/examples/normal-network.json) and
[network-link-cut.json](../../contracts/events/v1/examples/network-link-cut.json).
The full sequence has not been generated yet.

**Planned features:** higher probe loss and latency, lower traffic, then no
successful probes or traffic during the outage. CALL outcomes may later support
the network measurements, but that generator is outside this scenario draft.

**Planned impact analysis:** report the link, node, region and exposed subscriber
count. Confirmed customer and service impact needs topology, service mappings and
observed outcomes. Estimate traffic loss from a comparable baseline for the same
link and window, then convert bytes to decimal GB. Node/link counters can overlap,
and `packetLossRatio * bytesTransferred` is not a valid traffic-loss calculation.

The healthy snapshot is only a demo reference. Historical generation still needs
hour, day/night and week-to-week patterns. Measurements show an outage, but they
cannot identify a physical cable cut without more evidence.

## 3. Mobile data traffic drop / abnormal data usage

**Target:** NETWORK_LINK:LINK-CHI-001 and the synthetic subscribers whose DATA
sessions use NODE-CHI-001.

During the normal period, the link and its subscribers transfer a stable volume of
mobile data in comparable time windows. During the abnormal period, raw DATA
session bytes and/or NETWORK `bytesTransferred` fall sharply. Control entities keep
their usual traffic pattern.

**Raw events:** EventV1 DATA session records and/or NETWORK measurement windows.
They contain only observed byte counts, timing and network measurements. Derived
loss estimates and detection results do not belong in raw events.

**Planned comparison:** later processing can compare current traffic volume with a
historical baseline, the previous week and the same hour and weekday. It may then
estimate the byte difference and convert it to decimal GB. The baseline and this
calculation are not implemented in Day 01.

Scenario metadata is shared across control and injected events and cannot be a
detection shortcut.
