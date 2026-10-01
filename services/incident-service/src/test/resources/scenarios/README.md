# G2 detection replay fixtures

These JSON arrays are generated outputs from the processor's VoLTE and SMS
`livePackagedModelEnrichesRecovered*Episode` tests, run against the packaged
`isoforest-v2-synthetic-1` HTTP model on 30 September 2026. Each array contains
three independent five-detection fault episodes. Normal and telemetry-gap
controls produced no detection messages in that replay.

`ServiceScenariosIT` uses fresh `services/processor/target/g2-*-detections.json`
files when present and these frozen captures in a clean checkout. The files are
synthetic test evidence, not manually constructed detector decisions. Regenerate
and review them deliberately when the detector contract or model version changes.
