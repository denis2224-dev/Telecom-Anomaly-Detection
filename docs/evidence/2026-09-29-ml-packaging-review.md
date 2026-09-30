# PR 27 packaging verification

Verified on 29 September 2026 on `feat/ml-model-packaging`, code commit
`5f22d14`, including main `908f431` and Stanislav's artifacts from `28f6e12`.

## Changes

- Write calibration and manifest JSON as UTF-8 bytes with LF endings; enforce
  LF in Git and mark joblib artifacts binary.
- Check packaged model/calibration hashes and dataset-manifest provenance before
  regeneration. Document Python 3.13 and install ML dependencies in root setup.
- Preserve Stanislav's model files, manifest and frozen 0.99 threshold.
- Fix the pre-existing dashboard sample-volume placeholder: match its caller's
  selector/input and display known, missing, zero and low sample counts. Existing
  SMS tests failed compilation before this fix and pass afterwards.

## Verification

- Contract validation: PASS, including seven voice and eleven SMS parity cases.
- Python: 17 repository tests and 13 ML tests passed.
- Fresh checkout with `core.autocrlf=true`: all 13 ML tests passed without
  rebuilding the committed models.
- Root Maven `verify`: 58 streaming-support, 43 event-generator and 164 processor
  tests passed. Processor missing-window integration tests were also run explicitly.
- Incident-service Maven `verify`: 99 tests and two integration tests passed with
  Docker running and `KAFKA_BOOTSTRAP_SERVERS=localhost:9094` in the test process.
  The first run inherited `kafka:9092`, which is not resolvable on the host, and
  failed when a cached Spring test context restarted its Kafka listeners.
- Dashboard: 32 unit tests, production build and nine Playwright smoke tests
  passed. The real-Keycloak test is intentionally skipped by that mocked-API suite;
  this run does not claim a fresh authenticated end-to-end acceptance.
- `git diff --check`: PASS.

## Reproduction and limits

Using Python 3.13.9 and the pinned ML dependencies, generation reproduced the
committed split manifest byte-for-byte (24,208 rows, 14 runs). Two local training
runs produced identical model, calibration and manifest bytes. Their bytes differ
from Stanislav's Python 3.13.7 build; calibration differences were at most
1.11e-16. Sampled normal/fault ranks matched between the packaged and rebuilt
models. The SMS fault remains 0.9890873015873016, below 0.99.

Generated data and verification logs are local under `tmp/pr27-*`. No model was
replaced to conceal a packaged-file failure. Day 11 integration/evaluation and
Day 12 bounded HTTP inference remain separate work; teammate sign-off is pending.
