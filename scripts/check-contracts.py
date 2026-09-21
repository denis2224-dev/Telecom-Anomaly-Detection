"""Validate v2 schema and independent fixture alternatives; optionally check a batch."""

import argparse
from jsonschema import FormatChecker
from observation_contract import (
    ROOT, SCHEMA, Draft202012Validator, ObservationBatch, read_json, validate_observation,
)


def validate_detection_contracts():
    pairs = [
        ('policies/service-rules-v2.schema.json', ['policies/service-rules-v2.json']),
        ('baselines/baseline-catalogue-v2.schema.json', ['baselines/demo-baseline-v2.json']),
        ('features/service-feature-window-v2.schema.json',
         [str(p.relative_to(ROOT / 'contracts')) for p in (ROOT / 'contracts/fixtures/features').glob('*.json')
          if p.name not in ('parity-v2.json', 'voice-parity-v2.json')]),
        ('detections/service-detection-v2.schema.json',
         [str(p.relative_to(ROOT / 'contracts')) for p in (ROOT / 'contracts/fixtures/detections').glob('*.json')]),
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
    # A parity suite references observations; it is not itself a feature payload.
    # Execute its cases against the canonical reference instead of skipping validation.
    import importlib.util
    spec = importlib.util.spec_from_file_location('voice_parity', ROOT / 'scripts/check-voice-parity.py')
    parity = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parity)
    print(f'PASS: {len(parity.reference_cases())} voice parity cases with unchanged shared expectations')


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
    if args.batch:
        batch = ObservationBatch()
        events = read_json(args.batch)
        if not isinstance(events, list):
            raise ValueError("Batch must be a JSON array")
        results = [batch.accept(event) for event in events]
        print(f"PASS: {results.count('ACCEPTED')} accepted, {results.count('DUPLICATE')} duplicates")


if __name__ == "__main__":
    main()
