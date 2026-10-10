"""Independent Python features for the frozen geographic raw-input matrix."""
import json
from datetime import datetime
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


def persisted_cases(java_output, service):
    """Rebuild the broader Day 5 export from its raw inputs and frozen peer donors."""
    def read(path):
        return json.loads((ROOT / path).read_text(encoding='utf-8'))
    context = FeatureContext(read('contracts/topology/geographic-scopes-v2.json'),
                             read('contracts/geography/demo-geography-v1.json'))
    catalog = read('contracts/baselines/geographic-peer-baseline-v2.json')
    export = read(java_output)
    expected, actual = {}, {}
    for name, case in export['cases'].items():
        raw = case['serviceObservation']
        if raw['service'] != service:
            continue
        at = datetime.fromisoformat(raw['windowStart'])
        hour = at.weekday() * 24 + at.hour
        peers = [p['peerScopeId'] for p in catalog['peerFallbacks'] if p['scopeId'] == raw['scopeId']]
        if len(peers) != 1:
            raise ValueError('Expected one reviewed geographic peer mapping')
        donor = peers[0]
        matches = [b for b in catalog['baselines'] if b['scopeId'] == donor and hour in b['hours']]
        if len(matches) != 1:
            raise ValueError('Expected one reviewed peer donor')
        missing = name.rsplit('/', 1)[1] == 'missing-baseline'
        baseline = dict(baselineVersion=catalog['baselineVersion'], scopeId=raw['scopeId'],
                        sourceScopeId=None if missing else donor, service=service, hourOfWeek=hour,
                        status='BASELINE_MISSING' if missing else 'PEER',
                        values={} if missing else matches[0]['values'])
        expected[name] = build_features(raw, case['nodes'], baseline, context=context)
        actual[name] = case['feature']
    if not expected:
        raise ValueError('Persisted geographic export contains no cases for ' + service)
    return expected, actual
