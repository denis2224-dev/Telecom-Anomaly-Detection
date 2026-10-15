"""Checksum-verified optional candidate; scores are ranks, never probabilities."""
from bisect import bisect_right
from datetime import datetime, timezone
from hashlib import sha256
import json
import math
from pathlib import Path
import sys
from time import perf_counter

import joblib
import numpy as np
import sklearn
from app.features.trace_link_features import ROOT, build_features, contract, validate

MODEL='trace-link-shadow-v1'
DEFAULT=ROOT/'services/ml-service/candidate-models'/MODEL


def load(path=DEFAULT):
    path=Path(path)
    raw_manifest=(path/'manifest.json').read_bytes()
    if sha256(raw_manifest).hexdigest()!=contract('ml-shadow/trace-link-model-pin-v1.json')['manifestSha256']:
        raise ValueError('MODEL_MANIFEST_PIN_MISMATCH')
    manifest=json.loads(raw_manifest)
    if (manifest['modelVersion']!=MODEL or manifest['featureSchemaVersion']!='trace-link-features-v1'
            or manifest['featureContractSha256']!=sha256((ROOT/'contracts/features/trace-link-feature-order-v1.json').read_bytes()).hexdigest()
            or manifest['pythonVersion']!=sys.version.split()[0] or manifest['numpyVersion']!=np.__version__
            or manifest['sklearnVersion']!=sklearn.__version__ or manifest['joblibVersion']!=joblib.__version__
            or manifest['nJobs']!=1 or manifest['synthetic'] is not True
            or manifest['splitManifestSha256']!=sha256((path/'split-manifest.json').read_bytes()).hexdigest()):
        raise ValueError('MODEL_MANIFEST_MISMATCH')
    loaded={}
    for domain,spec in contract('features/trace-link-feature-order-v1.json')['domains'].items():
        entry=manifest['domains'][domain]
        if (entry['featureNames']!=spec['featureNames']
                or entry['modelSha256']!=contract('ml-shadow/trace-link-model-pin-v1.json')['domains'][domain]):
            raise ValueError('MODEL_FEATURE_OR_PIN_MISMATCH')
        for kind,suffix in [('model','.joblib'),('calibration','-calibration.json'),('baseline','-baselines.json')]:
            name=domain+suffix
            if entry[kind+'File']!=name or sha256((path/name).read_bytes()).hexdigest()!=entry[kind+'Sha256']:
                raise ValueError('MODEL_CHECKSUM_MISMATCH')
        package=joblib.load(path/entry['modelFile'])
        for key in ('impute','center','scale'):
            if package[key].shape!=(len(spec['featureNames']),) or not np.isfinite(package[key]).all(): raise ValueError('INVALID_PREPROCESSING')
        if (package['scale']<=0).any() or package['model'].n_jobs!=1: raise ValueError('INVALID_PREPROCESSING')
        calibration=json.loads((path/entry['calibrationFile']).read_text())
        if not calibration or calibration!=sorted(calibration) or not all(type(x) in (int,float) and math.isfinite(x) for x in calibration):
            raise ValueError('INVALID_CALIBRATION')
        loaded[domain]=(entry,package,calibration)
    return manifest,loaded


def score(request,loaded):
    started=perf_counter()
    validate('ml-shadow/trace-link-request-v1.schema.json',request)
    features=request['features']
    context={k:features[k] for k in ('datasetId','domain','scope','window','revision','topologyVersion','evaluatedAt','evidence','baselines')}
    context.update(schemaVersion='trace-link-evaluation-v1',namespace=features['scope']['namespace'],backlog={'required':False,'drained':None})
    rebuilt=build_features(context)
    for key in ('eligible','reasonCodes','featureNames'):
        if features[key]!=rebuilt[key]: raise ValueError('FEATURE_MISMATCH')
    for a,b in zip(features['featureValues'],rebuilt['featureValues']):
        if (a is None)!=(b is None) or (a is not None and abs(a-b)>1e-9): raise ValueError('FEATURE_MISMATCH')
    if not features['eligible']: raise ValueError('INELIGIBLE')
    domain=features['domain'];entry,package,calibration=loaded[1][domain]
    if features['scope']!=entry['scope']:raise ValueError('UNSUPPORTED_CANDIDATE_SCOPE')
    values=np.asarray([np.nan if v is None else v for v in features['featureValues']])
    vector=(np.where(np.isnan(values),package['impute'],values)-package['center'])/package['scale']
    strength=-float(package['model'].score_samples([vector])[0])
    if not math.isfinite(strength): raise ValueError('NONFINITE_SCORE')
    result={k:features[k] for k in ('datasetId','domain','scope','window','revision','topologyVersion','featureSchemaVersion')}
    result.update(requestId=request['requestId'],modelVersion=MODEL,modelSha256=entry['modelSha256'],inferenceStatus='OK',
      anomalyScore=bisect_right(calibration,strength)/len(calibration),label=domain+'_DEGRADATION',featureContributions=[],
      latencyMs=(perf_counter()-started)*1000,generatedAt=datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z'))
    validate('ml-shadow/trace-link-response-v1.schema.json',result)
    return result
