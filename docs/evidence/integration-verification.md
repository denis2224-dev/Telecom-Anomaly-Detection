# Integration verification record

## Local checks

- Workflow YAML parsed successfully.
- Contract validation passed.
- All 15 ML service tests passed.
- The frozen model threshold remains `0.99`.
- The workflow builds a private ML image, checks local-versus-HTTP scoring
  parity, and runs the focused VoLTE/SMS processor replay tests.

## Runtime acceptance

The processor replay matrix is intentionally kept in the focused test classes
so each run asserts durable episode state, recovery, telemetry-gap provenance,
and model enrichment. The workflow runs those tests with `ML_SERVICE_URL`
pointing at the freshly built private inference container.

The SMS candidate miss remains an explicit evaluation result. No threshold,
calibration distribution, or model artifact is changed by the integration
workflow.

## Reproduction

Use `.github/workflows/integration.yml` for the clean runner path. Local
reproduction uses the commands in `docs/evidence/g2-integration.md` and
requires Docker plus JDK 21 for the processor replay.
