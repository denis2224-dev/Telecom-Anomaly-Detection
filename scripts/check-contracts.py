"""Validate JSON schemas, OpenAPI, and independent v2 fixture alternatives."""

import argparse
from observation_contract import (
    ROOT, Draft202012Validator, ObservationBatch, read_json, validate_observation,
)

JSON_SCHEMAS = sorted((ROOT / "contracts").glob("**/*.schema.json"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch", type=str, help="JSON array of observations; reject conflicts")
    args = parser.parse_args()
    for path in JSON_SCHEMAS:
        Draft202012Validator.check_schema(read_json(path))
    print(f"PASS: {len(JSON_SCHEMAS)} JSON schemas")

    try:
        import yaml
        from openapi_spec_validator import validate_spec
    except ImportError as error:
        raise RuntimeError(
            "OpenAPI checks require PyYAML and openapi-spec-validator; "
            "install the development dependencies first"
        ) from error
    with (ROOT / "contracts/openapi/incident-api.yaml").open(encoding="utf-8") as stream:
        validate_spec(yaml.safe_load(stream))
    print("PASS: incident API OpenAPI document")

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
