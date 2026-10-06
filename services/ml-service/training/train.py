"""Train and package one Isolation Forest per service from generated normal history."""

import argparse
from hashlib import sha256
import json
from pathlib import Path
import sys

import joblib
import numpy
import sklearn
from sklearn.ensemble import IsolationForest

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
MODELS = HERE.parent / "models"
ORDER = json.loads((ROOT / "contracts/features/feature-order-v2.json").read_text(encoding="utf-8"))


def vectors(data, runs, service, split):
    values = []
    for run in runs:
        if run["service"] != service or run["split"] != split:
            continue
        path = data / run["path"]
        if sha256(path.read_bytes()).hexdigest() != run["sha256"]:
            raise ValueError(f"Dataset checksum mismatch: {path}")
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                row = json.loads(line)
                if (row["service"] != service or row["runId"] != run["runId"]
                        or row["featureNames"] != ORDER["models"][service]
                        or "label" in row):
                    raise ValueError(f"Invalid {split} row in {path}")
                values.append(row["featureValues"])
    result = numpy.asarray(values, dtype=float)
    if result.ndim != 2 or result.shape[1] != 6 or not numpy.isfinite(result).all():
        raise ValueError(f"Missing or nonfinite {service} {split} vectors")
    return result


def train(data=HERE / "data", models=MODELS, model_version="isoforest-v2-synthetic-1",
          n_estimators=200, max_samples=None, threshold_rank=.99):
    split = json.loads((data.parent / "split_manifest.json").read_text(encoding="utf-8"))
    if split["featureVersion"] != ORDER["featureVersion"] or split["baselineVersion"] != "baseline-v2":
        raise ValueError("Incompatible dataset versions")
    if (type(n_estimators) is not int or n_estimators < 1
            or (max_samples is not None and (type(max_samples) is not int or max_samples < 1))
            or type(threshold_rank) not in (int, float) or not 0 < threshold_rank <= 1):
        raise ValueError('Invalid experimental model settings')
    experimental = n_estimators != 200 or max_samples is not None or threshold_rank != .99
    if split['datasetVersion'] != 'synthetic-v2-1' or experimental:
        if models.resolve() == MODELS.resolve():
            raise ValueError('Expanded data requires a separate candidate model directory')
        if model_version == 'isoforest-v2-synthetic-1':
            raise ValueError('Expanded data requires a distinct model version')
    if not isinstance(model_version, str) or not model_version.strip():
        raise ValueError('Model version must be nonempty')
    models.mkdir(parents=True, exist_ok=True)
    manifest = dict(modelVersion=model_version, observationSchemaVersion=2,
                    featureSchemaVersion=2, featureVersion=ORDER["featureVersion"],
                    baselineVersion=split["baselineVersion"], datasetVersion=split["datasetVersion"],
                    datasetManifestSha256=sha256((data.parent / "split_manifest.json").read_bytes()).hexdigest(),
                    pythonVersion=sys.version.split()[0], numpyVersion=numpy.__version__,
                    sklearnVersion=sklearn.__version__, joblibVersion=joblib.__version__,
                    randomState=15092026, nEstimators=n_estimators, thresholdRank=threshold_rank, services={})
    if max_samples is not None:
        manifest['maxSamples'] = max_samples
    for service, names in ORDER["models"].items():
        training = vectors(data, split["runs"], service, "train")
        calibration = vectors(data, split["runs"], service, "calibration")
        model = IsolationForest(n_estimators=n_estimators, max_samples=max_samples or 'auto',
                                random_state=15092026, n_jobs=-1).fit(training)
        strengths = sorted((-model.score_samples(calibration)).tolist())
        model_file = models / (service + ".joblib")
        calibration_file = models / (service + "-calibration.json")
        joblib.dump(model, model_file)
        calibration_file.write_bytes((json.dumps(strengths, separators=(",", ":")) + "\n").encode("utf-8"))
        manifest["services"][service] = dict(featureNames=names, trainingRows=len(training),
                                              calibrationRows=len(calibration),
                                              modelFile=model_file.name,
                                              modelSha256=sha256(model_file.read_bytes()).hexdigest(),
                                              calibrationFile=calibration_file.name,
                                              calibrationSha256=sha256(calibration_file.read_bytes()).hexdigest())
    (models / "manifest.json").write_bytes((json.dumps(manifest, indent=2) + "\n").encode("utf-8"))
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=HERE / "data")
    parser.add_argument("--models", type=Path, default=MODELS)
    parser.add_argument("--model-version", default="isoforest-v2-synthetic-1")
    parser.add_argument("--n-estimators", type=int, default=200)
    parser.add_argument("--max-samples", type=int)
    parser.add_argument("--threshold-rank", type=float, default=.99)
    args = parser.parse_args()
    result = train(args.data, args.models, args.model_version, args.n_estimators,
                   args.max_samples, args.threshold_rank)
    print(f"Packaged {', '.join(result['services'])} models as {result['modelVersion']}")
