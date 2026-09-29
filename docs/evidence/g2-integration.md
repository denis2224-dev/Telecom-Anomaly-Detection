# Service integration evaluation

## Scope

The integration path evaluates the frozen packaged model candidate through the
private HTTP service and the Java processor. It covers:

- three independent fault replays for each service profile;
- one normal control and one telemetry-gap control for each profile;
- aligned service and node windows;
- deterministic episode state and recovery;
- model rank enrichment when inference is available;
- explicit unavailable-model behavior when inference is not available.

The model candidate remains `isoforest-v2-synthetic-1` with a `0.99` anomaly
rank threshold. The known SMS test miss remains an evaluation result and is not
hidden by changing the threshold.

## Reproducibility

The test replay uses independent seeds `29092026`, `29092027`, `29092028` for
fault runs, `29092029` for the normal control, and `29092030` for the telemetry
gap. These identifiers are test inputs, not public simulator run IDs.

Record the following with each run:

| Field | Value |
| --- | --- |
| model version | `isoforest-v2-synthetic-1` |
| model threshold | `0.99` |
| feature version | `2` |
| baseline version | `baseline-v2` |
| topology version | `2-baseline` |
| Python runtime | `3.13` |
| NumPy | `2.4.6` |
| scikit-learn | `1.9.1` |
| joblib | `1.5.3` |
| VoLTE model and calibration hashes | `services/ml-service/models/manifest.json` |
| SMS model and calibration hashes | `services/ml-service/models/manifest.json` |

## Acceptance assertions

Each fault replay must produce one episode with one `OPEN`, the expected
`UPDATE` events, and one `RECOVERY`. Normal and telemetry-gap controls must not
create an additional episode. Gap windows must retain same-window node
provenance and expose empty ML vectors rather than fabricated service values.
Every enriched detection must report `mlStatus=OK`, the manifest model version,
and a non-null rank. The evaluator must report the SMS miss without changing
the candidate threshold.

## Commands

```bash
python scripts/check-contracts.py
python -m unittest discover -s tests -v
python -m unittest discover -s services/ml-service/tests -v
python services/ml-service/training/generate_history.py
python services/ml-service/training/evaluate.py --api-url http://127.0.0.1:8090
ML_SERVICE_URL=http://127.0.0.1:8090 \
  ./mvnw -pl services/processor -am test \
  -Dtest=VoiceDeliveryTest,SmsDeliveryTest -DfailIfNoTests=false
```

The workflow in `.github/workflows/integration.yml` runs the same checks in a
clean Python 3.13 and JDK 21 environment, builds the private inference image,
checks local-versus-HTTP parity, and then replays both processor profiles.
