"""Load approved local artifacts and score an eligible version-2 feature window."""

from bisect import bisect_right
from hashlib import sha256
import json
import math
from pathlib import Path
import sys

import joblib
import numpy
import sklearn

ROOT = Path(__file__).resolve().parents[4]
ORDER = json.loads((ROOT / "contracts/features/feature-order-v2.json").read_text(encoding="utf-8"))
DEFAULT_MODELS = ROOT / "services/ml-service/models"


def load(service, models=DEFAULT_MODELS):
    models = Path(models)
    manifest = json.loads((models / "manifest.json").read_text(encoding="utf-8"))
    if (service not in ORDER["models"] or manifest["featureVersion"] != ORDER["featureVersion"]
            or manifest["observationSchemaVersion"] != 2 or manifest["featureSchemaVersion"] != 2
            or manifest["pythonVersion"].split(".")[:2] != sys.version.split()[0].split(".")[:2]
            or manifest["sklearnVersion"] != sklearn.__version__
            or manifest["numpyVersion"] != numpy.__version__
            or manifest["joblibVersion"] != joblib.__version__):
        raise ValueError("Model service, feature, or library version mismatch")
    entry = manifest["services"][service]
    if entry["featureNames"] != ORDER["models"][service]:
        raise ValueError("Model feature order mismatch")
    model_file = models / (service + ".joblib")
    calibration_file = models / (service + "-calibration.json")
    if (entry["modelFile"] != model_file.name or entry["calibrationFile"] != calibration_file.name
            or sha256(model_file.read_bytes()).hexdigest() != entry["modelSha256"]
            or sha256(calibration_file.read_bytes()).hexdigest() != entry["calibrationSha256"]):
        raise ValueError("Model artifact checksum mismatch")
    strengths = json.loads(calibration_file.read_text(encoding="utf-8"))
    if (len(strengths) != entry["calibrationRows"] or strengths != sorted(strengths)
            or not all(type(s) in (int, float) and math.isfinite(s) for s in strengths)):
        raise ValueError("Invalid calibration distribution")
    return service, manifest, joblib.load(model_file), strengths


def score(window, loaded):
    loaded_service, manifest, model, strengths = loaded
    service = window.get("service")
    values = window.get("featureValues")
    if (service != loaded_service or window.get("featureVersion") != manifest["featureVersion"]
            or window.get("baselineVersion") != manifest["baselineVersion"]
            or window.get("quality") != "COMPLETE" or window.get("mlEligible") is not True
            or window.get("featureNames") != manifest["services"][service]["featureNames"]
            or not isinstance(values, list) or len(values) != 6
            or not all(type(v) in (int, float) and math.isfinite(v) for v in values)):
        raise ValueError("Ineligible or incompatible model input")
    strength = -float(model.score_samples([values])[0])
    rank = bisect_right(strengths, strength) / len(strengths)
    return dict(mlStatus="OK", anomalyRank=rank, modelVersion=manifest["modelVersion"],
                anomaly=rank >= manifest["thresholdRank"])
