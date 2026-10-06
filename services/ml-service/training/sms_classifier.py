"""Offline packaging/CLI using the application scorer (uncalibrated)."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import sys
import joblib
import numpy as np
import sklearn

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.inference.sms_classifier import TYPES, FEATURES, VIEWS, classifier_scores, load_classifier, score

def package(root, model, view, threshold, version, dataset_hash, dataset_version, settings, training_rows):
    if type(model) not in TYPES or view not in VIEWS or not 0 < threshold <= 1 or not version.strip():
        raise ValueError('Invalid experimental classifier settings')
    root = Path(root)
    root.mkdir(parents=True, exist_ok=False)
    artifact = root / 'SMS.joblib'
    joblib.dump(model, artifact)
    manifest = dict(packageKind='sms-classifier-v1', modelVersion=version, modelType=TYPES[type(model)],
                    service='SMS', status='EXPERIMENTAL_NOT_PROMOTED', featureVersion=2,
                    baselineVersion='baseline-v2', inputFeatureNames=FEATURES, featureView=view,
                    projection=VIEWS[view], estimatorFeatureNames=[FEATURES[i] for i in VIEWS[view]],
                    threshold=threshold, scoreSemantics='uncalibrated classifier score for fault class 1',
                    classes=model.classes_.tolist(), modelFile=artifact.name,
                    modelSha256=sha256(artifact.read_bytes()).hexdigest(), datasetManifestSha256=dataset_hash,
                    datasetVersion=dataset_version, trainingRows=training_rows, settings=settings,
                    pythonVersion=sys.version.split()[0], numpyVersion=np.__version__,
                    sklearnVersion=sklearn.__version__, joblibVersion=joblib.__version__)
    (root / 'manifest.json').write_bytes((json.dumps(manifest, indent=2) + '\n').encode())
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models', type=Path, required=True)
    parser.add_argument('--window', type=Path, required=True, help='Complete ServiceFeatureWindowV2 JSON')
    args = parser.parse_args()
    print(json.dumps(score(json.loads(args.window.read_bytes()), load_classifier(args.models)), allow_nan=False))
