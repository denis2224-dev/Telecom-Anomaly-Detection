"""Validate v2 schema and independent fixture alternatives; optionally check a batch."""

import argparse
from jsonschema import FormatChecker
from observation_contract import (
    ROOT, SCHEMA, Draft202012Validator, ObservationBatch, read_json, validate_observation,
)


def validate_detection_contracts():
    pairs = [
        ('ml-shadow/sms-shadow-evidence-v1.schema.json', ['fixtures/ml-shadow/sms-healthy-v1.json']),
        ('policies/service-rules-v2.schema.json', ['policies/service-rules-v2.json']),
        ('baselines/baseline-catalogue-v2.schema.json', ['baselines/demo-baseline-v2.json', 'baselines/geographic-peer-baseline-v2.json']),
        ('features/service-feature-window-v2.schema.json',
         [str(p.relative_to(ROOT / 'contracts')) for p in (ROOT / 'contracts/fixtures/features').glob('*.json')
          if p.name not in ('parity-v2.json', 'voice-parity-v2.json', 'sms-parity-v2.json')]),
        ('detections/service-detection-v2.schema.json',
         [str(p.relative_to(ROOT / 'contracts')) for p in (ROOT / 'contracts/fixtures/detections').glob('*.json')
          if p.name != 'service-explanation-cases.json']),
    ]
    count = 0
    for schema_path, paths in pairs:
        schema = read_json(ROOT / 'contracts' / schema_path)
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        if not paths:
            raise ValueError('Missing fixtures for ' + schema_path)
        for path in paths:
            validator.validate(read_json(ROOT / 'contracts' / path))
            count += 1
    print(f'PASS: detection schemas, policy, baseline and {count} payloads')
    validate_explanation_cases(read_json(ROOT / 'contracts/fixtures/detections/service-explanation-cases.json'))
    # A parity suite references observations; it is not itself a feature payload.
    # Execute its cases against the canonical reference instead of skipping validation.
    import importlib.util
    spec = importlib.util.spec_from_file_location('voice_parity', ROOT / 'scripts/check-voice-parity.py')
    parity = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parity)
    print(f'PASS: {len(parity.reference_cases())} voice parity cases with unchanged shared expectations')
    spec_sms = importlib.util.spec_from_file_location('sms_parity', ROOT / 'scripts/check-sms-parity.py')
    sms_parity = importlib.util.module_from_spec(spec_sms)
    spec_sms.loader.exec_module(sms_parity)
    print(f'PASS: {len(sms_parity.reference_cases())} SMS parity cases with unchanged shared expectations')


def validate_explanation_cases(suite):
    # This named file is a test collection; all other detection JSON files remain standalone payloads.
    expected = {'type': 'object', 'required': ['status', 'breached', 'confidence', 'phase'],
                'additionalProperties': False, 'properties': {
                    'status': {'enum': ['EVALUATED', 'INSUFFICIENT_DATA', 'BASELINE_MISSING']},
                    'breached': {'type': 'boolean'}, 'confidence': {'enum': ['LOW', 'MEDIUM']},
                    'phase': {'enum': [None, 'OPEN', 'UPDATE', 'UNKNOWN', 'RECOVERY']}}}
    step = {'type': 'object', 'required': ['feature', 'node', 'expected', 'mlStatus'],
            'additionalProperties': False, 'properties': {
                'feature': {'type': 'object'}, 'node': {'type': 'object'}, 'expected': expected,
                'mlStatus': {'enum': ['OK', 'INSUFFICIENT_DATA']}}}
    case_schema = {'type': 'object', 'required': ['id', 'service', 'windows', 'detections'],
                   'additionalProperties': False, 'properties': {
                       'id': {'type': 'string', 'minLength': 1}, 'service': {'enum': ['VOLTE', 'SMS']},
                       'detections': {'type': 'array'},
                       'windows': {'type': 'array', 'minItems': 1, 'items': step}}}
    Draft202012Validator({'type': 'object', 'required': ['description', 'cases'],
                         'additionalProperties': False, 'properties': {
                             'description': {'type': 'string'},
                             'cases': {'type': 'array', 'minItems': 1, 'items': case_schema}}}).validate(suite)
    feature_validator = Draft202012Validator(read_json(ROOT / 'contracts/features/service-feature-window-v2.schema.json'), format_checker=FormatChecker())
    detection_validator = Draft202012Validator(read_json(ROOT / 'contracts/detections/service-detection-v2.schema.json'), format_checker=FormatChecker())
    ids = set()
    for case in suite['cases']:
        if case['id'] in ids:
            raise ValueError('Duplicate explanation case: ' + case['id'])
        ids.add(case['id'])
        for step in case['windows']:
            feature_validator.validate(step['feature'])
            validate_observation(step['node'])
            if step['feature']['service'] != case['service']:
                raise ValueError('Explanation case service mismatch')
        for detection in case['detections']:
            detection_validator.validate(detection)
            if detection['service'] != case['service']:
                raise ValueError('Explanation detection service mismatch')
    print(f'PASS: {len(ids)} explanation trajectories with validated nested features, nodes and detections')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch", type=str, help="JSON array of observations; reject conflicts")
    args = parser.parse_args()
    Draft202012Validator.check_schema(SCHEMA)
    fixtures = sorted((ROOT / "contracts/fixtures/observations").glob("*.json"))
    if not fixtures:
        raise ValueError("Missing observation fixtures")
    for path in fixtures:
        validate_observation(read_json(path))
    print(f"PASS: v2 schema and {len(fixtures)} independent observation fixtures")
    validate_detection_contracts()
    from geography_contract import validate_day1_contracts
    validate_day1_contracts()
    from geographic_numeric_contract import validate_day1_numeric_contracts
    validate_day1_numeric_contracts()
    from day1_evidence_contract import validate_day1_evidence_contracts
    validate_day1_evidence_contracts()
    if args.batch:
        batch = ObservationBatch()
        events = read_json(args.batch)
        if not isinstance(events, list):
            raise ValueError("Batch must be a JSON array")
        results = [batch.accept(event) for event in events]
        print(f"PASS: {results.count('ACCEPTED')} accepted, {results.count('DUPLICATE')} duplicates")


if __name__ == "__main__":
    main()
