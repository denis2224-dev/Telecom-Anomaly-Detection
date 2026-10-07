"""Independent Python features for the frozen geographic raw-input matrix."""
import json
from pathlib import Path
from app.features.service_features import FeatureContext, build_features, build_missing_features

ROOT = Path(__file__).resolve().parents[1]

def reference_cases(service):
    def read(path):
        return json.loads((ROOT / path).read_text(encoding='utf-8'))
    context = FeatureContext(read('contracts/topology/geographic-scopes-v2.json'),
                             read('contracts/geography/demo-geography-v1.json'))
    suite = read('contracts/fixtures/features/geographic-parity-v2.json')
    results = {}
    for case in suite['cases']:
        if case['service'] != service: continue
        if case['observation'] is None:
            result = build_missing_features(case['scopeId'], case['windowStart'], case['windowEnd'],
                                            case['nodes'], case['baseline'], context=context)
        else:
            result = build_features(case['observation'], case['nodes'], case['baseline'], context=context)
        if case['id'] in results: raise ValueError('Duplicate geographic parity case')
        results[case['id']] = result
    return results
