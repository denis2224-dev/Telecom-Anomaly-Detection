# Day 09 — Java/Python service feature parity

- Starting main: `8fa0c34857aab61cdfc3aa51492539407ffeb9d7`
- Branch: `feature/service-feature-parity`
- Java implementation: `ServiceFeatureBuilder.java`; `WindowFinalizer.java` now calls it for VoLTE. The separate `VoiceFeatureBuilder.java` formula path was removed. Java parity coverage is in `ServiceFeatureBuilderTest.java` and the updated Day 08 finalizer tests.
- VoLTE order: `cssrDeltaPp`, `sip503Ratio`, `rrcDeltaPp`, `bearerDeltaPp`, `packetLossRatio`, `imsCpuPct`.
- SMS order: `p95DelayRatio`, `p95DeliveryMs`, `queueDepth`, `oldestPendingAgeSec`, `deliverySrDeltaPp`, `deliveredMessages`. The SMSC observation field remains `oldestPendingAgeSeconds`.
- Parity: all 7 VoLTE and all 8 SMS cases matched the unchanged Python reference across complete canonical payloads. Integers were exact; maximum observed absolute float difference was **0**. Java exports are generated in `services/processor/target/` and are not committed.
- Maven 3.9.16: processor **159**, streaming-support **58**, event-generator **16** tests; zero failures or errors. Python unittest discovery: **16** tests. Contract checks, both parity scripts, and `git diff --check` passed. Installed Maven was used because the Windows wrapper bootstrap has a known PowerShell error.
- Graphify code graph after implementation: **2,843 nodes, 6,580 edges**. `WindowFinalizer` references `ServiceFeatureBuilder`, which references `EvidenceJoiner` and `BaselineRegistry`. Source inspection found one Java formula authority, no dead new builder, no Spring constructor cycle, and no processor dependency on incident-service or UI.
- Boundary: SMS feature payload construction is available, but SMS finalization and episode processing are not wired in this task. No training or HTTP model inference was added.
- Handoff to Sergiu: review the unified Java formulas, full-payload parity exports, and retained Day 08 VoLTE finalization behavior before opening a PR.
