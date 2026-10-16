# Trace Link five-minute detection

Assignment: 12 October 2026; original implementation and measurements completed on
13 October. Review corrections dated 14 October are documented separately in
[the correction report](../evidence/2026-10-14-trace-link-day3-corrections.md).

## Runtime path

`TraceLinkWindowStore.save` validates and persists an immutable finalized synthetic
window and its job. `TraceLinkDetectionWorker` claims the earliest unfinished
window in a complete scope, freezes its first evaluation time, evaluates the
existing domain rules and commits outcomes, episode state and outgoing events
atomically. `TraceLinkDeliveryPublisher` publishes per-episode sequence order.
The incident service validates the independent wire contract and applies only
contiguous committed evidence to its projection.

The input is `trace-link-persisted-window-v1`; the output is
`trace-link-detection-v1` on `telecom.detections.trace-link.v1`, keyed by episode
ID. Input includes dataset, namespace, complete scope, revision, primitive
populations and original source checksum. It contains no scenario truth.
Accepted cause support and rejected raw diagnostics remain separate.

## Configuration and activation

Both services default `TRACE_LINK_ENABLED=false`. The processor defaults to
four workers (allowed 1–16), `LIVE` evaluation and the versioned detection topic.
`TRACE_LINK_EVALUATION_MODE=EVENT_TIME_REPLAY` explicitly selects historical
experiments; it must not be presented as live source freshness. In `LIVE` mode,
the first actual database claim fixes the evaluation timestamp across retries.
Source observation timestamps and original persisted input remain immutable.

Use `TRACE_LINK_DETECTIONS_TOPIC` consistently for both services. New read APIs
require an authenticated enabled analyst and enforce limit 1–100 (default 50),
offset 0–100000:

```text
GET /api/v2/trace-link/incidents
GET /api/v2/trace-link/incidents/{episodeId}
GET /api/v2/trace-link/incidents/{episodeId}/evidence
```

List items and detail responses are `TraceLinkIncidentView` objects containing
`episodeId`, `firstObservedAt`, `detectedAt`, `latestWindowEnd`, `latestSequence`,
`technicalState`, `severity` and `latestDetection`. `latestDetection` is the
unchanged strict detection envelope; evidence-page items remain strict envelopes
without the incident-view wrapper. List/evidence pagination retains
`{items,total,limit,offset}`.

`firstObservedAt` is the first breached window's start, derived from the validated
OPEN anchor: OPEN window start minus `(openAfterBreachedWindows - 1) *
windowSeconds`. `detectedAt` is the initial OPEN context's `evaluatedAt`.
Both remain stable through updates, UNKNOWN and recovery. `latestWindowEnd`
follows the most recent contiguous window end. For the regression example,
onset is `2026-10-10T08:35:00Z`, OPEN starts at `08:40:00Z`, and initial detection
is `08:45:01Z`. Later evaluation/commit timestamps do not replace initial detection.

These are read-only technical incident and evidence routes. The hash-ID episodes
have no analyst assignment, analyst status, investigation, resolution or workflow
audit. No mapping to legacy UUID incidents is introduced. Technical recovery
records service health; analyst workflow integration needs a separate approved
identity and workflow contract.

Learned baseline lookup requires both JVM properties
`telecom.trace-link.baseline-artifact` and
`telecom.trace-link.baseline-sha256`. The adapter checksum-verifies the configured
artifact and resolves its own lookup; submitted learned metadata is not an
artifact. Configured static demonstration fallbacks and baseline-unavailable
states retain explicit provenance. Missing, stale, partial and low-volume inputs
remain distinct from healthy service.

The correction pins main `9db3e0e5a61a8062310e560e734a5395758f70b7`: processor
V001–V016 and incident V001–V010 are retained unchanged. Apply processor V017
after V016 and the five-minute receiver V011 after incident V010. Main's V006
owns upstream read models; V011 owns the dedicated detector evidence, incident
projection and receipt audit, including non-null `detected_at`. The branch's old
alternate V006 was used only in disposable tests and is not an applied deployment
migration. Do not repair, replace checksums, reset databases or enable out-of-order
migration as an integration workaround. See the [migration ledger](../architecture/trace-link-migration-ledger.md)
and fresh/upgrade evidence in the correction report. If a non-disposable alternate
V006 is discovered, stop that deployment and define its transition explicitly.
No deployment or personal receiving-owner approval is recorded here.

## Reproduction

Use Python with `requirements-dev.txt` and the ML requirements installed, JDK 21,
working Docker, Node and the unchanged reference integration pack. Run from the
repository root with PowerShell:

```powershell
./scripts/verify-trace-link-day3.ps1 `
  -Python ./.venv/Scripts/python.exe `
  -JavaHome ./tmp/jdk21-sergiu-days7-8/jdk-21.0.12.1+1 `
  -ReferenceRoot ../Trace_Link_Integration_Pack
```

The entrypoint records actual commands and failures, validates the frozen pins,
runs root/ML Python and the full Java reactor, then verifies the separate incident
service against fresh producer output. Integration failures remain failures;
unavailable Docker is a blocked gate. Existing environment-dependent skips are
reported by their test suites, not counted as passes.

For an explicitly requested long measurement, add `-MeasureRealtime` and
`-HardwareManifest <host.json>` containing actual description, cpuCount and
physical memoryBytes. The manual
harness waits through eight genuine five-minute intervals and concurrently runs
120 seconds warmup, 900 seconds steady load, 60 seconds burst and bounded drain.
Its default rates are four finalized windows/s and eight during burst, with
four workers and a pending-work bound of 1000. This is persisted-window injection
capacity; source ingestion and collection throughput are outside that boundary.

The entrypoint subsequently replays that exact artifact through the receiver
and runs the converter/report pipeline. It records this separate receiving run
explicitly. For manual use, feed the raw JSON, physical host manifest, executed profile and actual
receiving receipt into `scripts/trace_link_day3_capacity_input.py`, then into
`services/ml-service/training/benchmark.py`. A later receiver replay must be
labeled separately; it does not establish simultaneous incident-delivery latency.
Empty stage samples are unmeasured, not zero. The report retains exact input,
source-tree and receipt hashes.

`execution.json` reports whether the entrypoint commands completed. Inspect the
benchmark report's capacity gate separately: command completion can coexist
with `INCOMPLETE` capacity when the receiving run is subsequent or collection
and finalization remain outside the measured boundary.

## Failure diagnosis

- Pending jobs: inspect `app.trace_link_job`, the earliest scope window and
  `evaluation_at`; expired leases are reclaimable and stale tokens cannot commit.
- Source gaps: inspect persisted quality/freshness and immutable quiet outcomes
  in `app.trace_link_evaluated_window.results`. UNKNOWN never advances recovery.
- Delivery backlog: inspect `app.trace_link_delivery` in episode/sequence order;
  Kafka acknowledgement precedes publication bookkeeping. Retries keep the ID.
- Receiver gaps: immutable evidence can precede its contiguous projection.
  Inspect episode sequences and audit; replay must add no duplicate rows.
- Attribution: check approved event-valid topology, primitive support, matching
  scope/time and healthy controls. Rejected support cannot erase a valid symptom.
- Recovery: two adjacent eligible bad windows open an episode; three healthy
  windows and any required drainage recover it. Severity remains at its peak.
  Technical recovery updates only technical state; analyst workflow is unavailable
  for these episodes and remains a separate integration gate.

See `docs/evidence/2026-10-12-detection-capacity.md` for measured limits and gates.
