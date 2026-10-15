"""Contract checks independent of optional ML packages or model deserialization."""
from copy import deepcopy
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import sys
from uuid import uuid4

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'services/ml-service'))
from app.features.trace_link_features import build_features, contract, validate
from jsonschema import Draft202012Validator


def validate_ml_contracts():
    for path in ('features/trace-link-feature-window-v1.schema.json','ml-shadow/trace-link-request-v1.schema.json',
                 'ml-shadow/trace-link-response-v1.schema.json','ml-shadow/trace-link-result-v1.schema.json'):
        Draft202012Validator.check_schema(contract(path))
    rows=contract('fixtures/trace-link/ml-feature-cases-v1.json')['cases']
    for row in rows:
        features=build_features(row['context'])
        if features['eligible']!=row['eligible']:raise ValueError('Wrong eligibility: '+row['id'])
        values=dict(zip(features['featureNames'],features['featureValues']))
        for name,expected in row['expectedFeatures'].items():
            actual=values[name]
            if (actual is None)!=(expected is None) or (expected is not None and abs(actual-expected)>1e-9):
                raise ValueError('Wrong hand-calculated feature: '+row['id']+':'+name)
        request={'requestId':str(uuid4()),'requestedAt':datetime.now(timezone.utc).isoformat().replace('+00:00','Z'),
                 'requestedModelVersion':'trace-link-shadow-v1','features':features}
        validate('ml-shadow/trace-link-request-v1.schema.json',request)
        for patch in ({'requestedModelVersion':'wrong'},{'expectedLabel':'FAULT'}):
            try:validate('ml-shadow/trace-link-request-v1.schema.json',dict(request,**patch))
            except ValueError:pass
            else:raise ValueError('Invalid request accepted')
    pin=contract('ml-shadow/trace-link-model-pin-v1.json')
    folder=ROOT/'services/ml-service/candidate-models/trace-link-shadow-v1'
    manifest=json.loads((folder/'manifest.json').read_text())
    if pin['manifestSha256']!=sha256((folder/'manifest.json').read_bytes()).hexdigest():raise ValueError('Manifest pin differs')
    if manifest['featureContractSha256']!=sha256((ROOT/'contracts/features/trace-link-feature-order-v1.json').read_bytes()).hexdigest():raise ValueError('Feature contract pin differs')
    for domain,entry in manifest['domains'].items():
        if pin['domains'][domain]!=sha256((folder/entry['modelFile']).read_bytes()).hexdigest():raise ValueError('Model pin differs')
    print(f'PASS: Trace Link ML schemas, {len(rows)} hand-calculated feature fixtures and model pins')


if __name__=='__main__':validate_ml_contracts()
