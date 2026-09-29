# Sergiu Day 11-12 detector/model handoff

Date: 29 September 2026. Branch: `feat/sergiu-days-11-12`, based on merged
`main` commit `a6da77c`. The model candidate remains
`isoforest-v2-synthetic-1` at rank threshold `0.99`; no packaged model,
calibration distribution or split manifest was changed.

## Contract and model identity

- Java/Python feature comparison passed for 7 VoLTE and 12 SMS full payloads;
  exact integer/structure match and maximum absolute float difference 0.
- Both packaged models loaded in the Python 3.13.9 container with NumPy 2.4.6,
  scikit-learn 1.9.1 and joblib 1.5.3. Representative normal/fault HTTP ranks
  matched the local scorer for each service.
- VoLTE model SHA-256: `b8e0d421a971bd0154ef830553bac9919f8f5bd13d44789e493a8ca67ba7b2dd`.
  SMS model SHA-256: `ddc926945b8b4c44945d5e33a18b85eec9dbec5c8612640f9dfbe5f84e48f307`.
  The calibration checksums and feature names are in `models/manifest.json` and
  copied into the local `tmp/g2-model-evaluation.json` report.

## Frozen-model evaluation on untouched test runs

The evaluator verified every test-run checksum from `training/split_manifest.json`
and used only labelled **test** rows. Training and calibration remained separate.
Each service had 2,016 normal and 8 fault test windows.

| Service | TP | FP | FN | Precision | Recall | FP / 1,000 normal | Median / p95 local inference |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| VoLTE | 8 | 23 | 0 | 0.2581 | 1.0000 | 11.41 | 10.24 / 11.25 ms |
| SMS | 2 | 18 | 6 | 0.1000 | 0.2500 | 8.93 | 10.20 / 11.33 ms |

These times measure local single-window scoring on this workstation, not network
round-trip latency or production capacity. The synthetic SMS fault already
documented at rank `0.9890873015873016` remains below `0.99`; six of eight
untouched SMS fault windows miss the ML candidate. SMS episode opening is based
on the deterministic delay/backlog rule, not on this model rank. This synthetic
evaluation does not establish generalization to live telecom data.

## Processor replay through real HTTP inference and durable episode state

The two live-model integration methods replayed canonical raw observation fixtures
through ingestion, Java feature finalization, the containerized private HTTP
model, episode state and detection outbox. For each service, three independent
fault replays used seeds `29092026`, `29092027`, `29092028`, starting at
08:00, 08:10 and 08:20 UTC on 15 September. A normal control used seed
`29092029` at 08:30; a telemetry-gap control omitted SERVICE observations
for minutes 2-4 using seed `29092030` at 08:40. These are reproducible **test
replay identifiers**, not public simulator `runId` values. The voice profile
keeps the fixed worked-case measurements; SMS delays vary with seed.

Each service produced 15 detections over three distinct episodes: 3 OPEN,
9 UPDATE and 3 RECOVERY. Each OPEN was tied to the second bad window (minute 3)
and each RECOVERY to the third healthy window (minute 7). All 15 detections
per service reported `mlStatus=OK`, a non-null rank and the manifest model
version. VoLTE OPEN ranks were `1.0`; SMS OPEN ranks were
`0.9985119047619048`. Normal and gap controls created no additional episode;
each gap produced three `MISSING` feature windows with empty ML vectors.
Captured detection payloads are reproducible under
`services/processor/target/g2-voice-detections.json` and
`services/processor/target/g2-sms-detections.json` after the focused tests.
They are generated test artifacts, not committed fixtures.

With the ML service absent, the SMS processor integration still opened and
recovered one episode with `UNAVAILABLE` and null rank. An active SMS episode
holds `UNKNOWN` on a missing service window, including when a fresh healthy
SMSC node exists; a fresh breached backlog may still open without completed
messages. A delayed HTTP response maps to `TIMEOUT`, a refused connection to
`UNAVAILABLE`, and invalid/ineligible input to `INSUFFICIENT_DATA`. The Java
client permits at most eight in-flight calls, uses a 250 ms request budget,
and performs no inline retry. Its call runs before the episode transaction
and outside the ingestion/finalization lock.

## Reproduction

1. On Python 3.13, install `requirements-dev.txt` and
   `services/ml-service/requirements-ml.txt`. Run
   `python -m unittest discover -s services/ml-service/tests -v`,
   `python scripts/check-contracts.py`, and the root Python tests.
2. Run `./mvnw -pl services/processor -am test` with JDK 21, then
   `python scripts/check-voice-parity.py` and
   `python scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json`.
3. Build `docker build -f services/ml-service/Dockerfile -t telecom-ml-day12 .`
   and run it privately on port 8090. Set `ML_SERVICE_URL` to the container's
   reachable URL, then run the `livePackagedModelEnrichesRecoveredVoiceEpisode`
   and `livePackagedModelEnrichesRecoveredSmsEpisode` methods explicitly.
4. Run `python services/ml-service/training/evaluate.py --api-url <reachable-ML-URL>
   --output tmp/g2-model-evaluation.json` after generating the ignored training
   data, if absent, with `generate_history.py`.

Verification here: 15 ML tests, 17 root Python tests, 58 streaming-support,
171 processor tests and 9 explicit missing-window integration tests passed
(the two live-model methods skip in the default suite). The two live-model
methods passed separately with the HTTP container.
Contract validation and Compose configuration passed. A Kafka offset test first
encountered an intermediate null offset after the receipt committed; its await
assertion now retries that valid intermediate state, and the full suite passed.

The replay proves Sergiu's processor/ML boundary. The private generator's
scheduled eight-minute command path, public scenario dispatcher, browser/API
acceptance and all-owner sign-off were not run here, so the shared G2 gate is
not claimed complete. Day 13 durable job leases/replay safety remain separate.
