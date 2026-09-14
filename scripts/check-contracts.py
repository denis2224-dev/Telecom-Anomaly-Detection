"""Validate v2 schema and independent fixture alternatives; optionally check a batch."""

import argparse
from observation_contract import (
    ROOT, SCHEMA, Draft202012Validator, ObservationBatch, read_json, validate_observation,
)


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
    if args.batch:
        batch = ObservationBatch()
        events = read_json(args.batch)
        if not isinstance(events, list):
            raise ValueError("Batch must be a JSON array")
        results = [batch.accept(event) for event in events]
        print(f"PASS: {results.count('ACCEPTED')} accepted, {results.count('DUPLICATE')} duplicates")


if __name__ == "__main__":
    main()
