# Trace Link capacity evidence

Status: **controlled browser checks passed; coordinated populated capacity pending**.

Five production-build Chromium checks passed at the source recorded in [verification](assets/trace-link-completion/verification.json). Raw records cover [overview](assets/trace-link-completion/capacity-overview.json), [service](assets/trace-link-completion/capacity-service.json), [investigation](assets/trace-link-completion/capacity-investigation.json) and [retained resources](assets/trace-link-completion/browser-resources.json).

The records include controlled request counts/timing, response load, hint frequency, frame gaps/long tasks, action latency, retained heap/DOM and bounded state. The initial DOM-bound failure remains disclosed; focused, full and production repetitions passed with the threshold unchanged. These are actual browser measurements using controlled REST/SSE input. They do not establish processor throughput, shared backlog recovery, cross-service latency or release-host capacity.

## Required coordinated measurement

On the approved populated candidate, record one manifest SHA256, hardware/OS/browser/viewport, offered/acknowledged/accepted/persisted rates, Kafka lag, outbox/retry backlog, errors, CPU/memory, REST percentiles and browser render/action timings across warmup, steady, burst and recovery. Separate backend wait from browser rendering. Record exact UTC boundaries, scope/run/event/window/detection/episode identities and repeatable commands. Do not represent absent measurements as zero.

The full source archive, coordinated target and actual run are required. No numeric limits or success result are invented here.
