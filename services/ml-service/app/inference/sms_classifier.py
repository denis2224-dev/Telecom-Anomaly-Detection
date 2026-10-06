"""Trusted SMS classifier runtime. Scores are uncalibrated; no training imports."""
from hashlib import sha256
from io import BytesIO
import json
import math
from pathlib import Path
import sys
import joblib
import numpy as np
import sklearn
from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from app.inference.scoring import ORDER

FEATURES = ORDER['models']['SMS']
VIEWS = {'six': list(range(6)), 'without-absolute-delay': [0, 2, 3, 4, 5]}

TYPES = {HistGradientBoostingClassifier: 'hist-gradient-boosting', RandomForestClassifier: 'random-forest'}


def classifier_scores(model, values):
    classes = model.classes_.tolist()
    if len(classes) != 2 or set(classes) != {0, 1}:
        raise ValueError('Require normal class 0 and fault class 1')
    probabilities = np.asarray(model.predict_proba(values))
    if (probabilities.shape != (len(values), 2) or not np.isfinite(probabilities).all()
            or (probabilities < 0).any() or (probabilities > 1).any()):
        raise ValueError('Invalid classifier scores')
    return probabilities[:, classes.index(1)]


def load_classifier(root, *, expected=None):
    """Load only trusted local artifacts, after compatibility and integrity checks."""
    root = Path(root).resolve()
    manifest = json.loads((root / 'manifest.json').read_bytes())
    if expected is not None and tuple(manifest.get(field) for field in ('modelVersion','modelSha256','threshold')) != expected:
        raise ValueError('Classifier package does not match the frozen identity and cutoff')
    if (manifest.get('packageKind') != 'sms-classifier-v1' or manifest.get('service') != 'SMS'
            or manifest.get('featureVersion') != 2 or manifest.get('baselineVersion') != 'baseline-v2'
            or manifest.get('inputFeatureNames') != FEATURES):
        raise ValueError('Incompatible classifier input contract')
    view = manifest.get('featureView')
    if (view not in VIEWS or manifest.get('projection') != VIEWS[view]
            or manifest.get('estimatorFeatureNames') != [FEATURES[i] for i in VIEWS[view]]):
        raise ValueError('Invalid feature projection')
    threshold = manifest.get('threshold')
    if type(threshold) not in (int, float) or not math.isfinite(threshold) or not 0 < threshold <= 1:
        raise ValueError('Invalid classifier threshold')
    for field, current in (('numpyVersion', np.__version__), ('sklearnVersion', sklearn.__version__),
                           ('joblibVersion', joblib.__version__)):
        if manifest[field] != current:
            raise ValueError('Incompatible classifier library version')
    if manifest['pythonVersion'].split('.')[:2] != sys.version.split()[0].split('.')[:2]:
        raise ValueError('Incompatible classifier Python version')
    path = (root / manifest['modelFile']).resolve()
    if not path.is_relative_to(root):
        raise ValueError('Classifier artifact escapes package')
    artifact = path.read_bytes()
    if sha256(artifact).hexdigest() != manifest['modelSha256']:
        raise ValueError('Classifier artifact checksum mismatch')
    model = joblib.load(BytesIO(artifact))
    if (type(model) not in TYPES or TYPES[type(model)] != manifest['modelType']
            or model.n_features_in_ != len(VIEWS[view]) or model.classes_.tolist() != manifest['classes']
            or set(model.classes_.tolist()) != {0, 1}):
        raise ValueError('Classifier type, class or feature mismatch')
    if isinstance(model, RandomForestClassifier):
        model.n_jobs = 1
    return manifest, model


def score(window, loaded):
    manifest, model = loaded
    if any(key in window for key in ('label', 'groundTruth', 'scenario', 'operatingProfile', 'faultFamily', 'severity', 'runId', 'episodeId', 'seed')):
        raise ValueError('Labels and evaluation metadata are not runtime inputs')
    values = window.get('featureValues')
    if (window.get('service') != 'SMS' or window.get('quality') != 'COMPLETE'
            or window.get('mlEligible') is not True or window.get('featureVersion') != 2
            or window.get('baselineVersion') != manifest['baselineVersion']
            or window.get('featureNames') != FEATURES or not isinstance(values, list) or len(values) != 6
            or not all(type(v) in (int, float) and math.isfinite(v) for v in values)):
        raise ValueError('Require a complete finite canonical SMS feature window')
    projected = np.asarray([values], dtype=float)[:, manifest['projection']]
    value = float(classifier_scores(model, projected)[0])
    return dict(classifierScore=value, detection=value >= manifest['threshold'], modelVersion=manifest['modelVersion'])


