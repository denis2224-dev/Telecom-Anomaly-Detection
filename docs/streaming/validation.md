# Validate the Day 01 contract

Use Python 3.11 or newer and the packages in `requirements-dev.txt` in a virtual
environment. Shared contract validation uses jsonschema 4.26.0, referencing 0.37.0,
PyYAML 6.0.3 and the OpenAPI validators 0.9.0. The checks do not need Java or Kafka
and run offline after the packages are installed.

From the repository root:

```text
python -m unittest discover -s tests -v
python -m openapi_spec_validator contracts/openapi/incident-api.yaml
docker compose --env-file .env.example config --quiet
git diff --check
```

For a fresh Windows checkout:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m openapi_spec_validator contracts/openapi/incident-api.yaml
```

On Linux/macOS, use `.venv/bin/python` for the three virtual-environment commands.

## What is checked

- All JSON files parse. The loader rejects duplicate fields, NaN and Infinity.
- All six EventV1 schemas and the analyst directory schema pass `Draft202012Validator.check_schema`.
- Six EventV1 examples validate and cover all five event types.
- Required fields, type dispatch, supported versions and entity kinds are enforced.
- Identifiers reject whitespace, including trailing newlines that would change
  Kafka keys or joins.
- Timestamps accept UTC `Z` with up to millisecond precision. Tests reject offsets,
  missing zones, invalid dates and extra precision.
- Integer byte/duration/count fields reject fractions, negatives, strings,
  booleans and values outside the documented exact JSON integer range.
- Network ranges, service enums and failed-call rules are checked.
- Unknown fields and anomaly labels are rejected in both envelope and payload.
- Node/link payload boundaries and optional trace metadata behave as documented.
- Fixture event IDs are distinct.
- Network fixtures have matching entity IDs, consistent byte/rate values and null
  latency when all probes fail. These checks run on fixtures, not runtime events.
- Local documentation links resolve and every schema field has a description and
  an entry in the contract's field tables.
- The OpenAPI specification and its examples validate; incident and analyst
  fixtures satisfy their schemas with format checking.
- Every EventV1 record in JSON fixtures, API examples and SSE evidence satisfies
  the canonical producer schema. The API envelope fields agree with that schema.
- Incident detail, list and SSE examples match the fixture, including the detection
  hash, entity/run IDs, event-time window and synthetic call evidence.

Compose validation checks configuration only. `scripts/verify` additionally needs
the running local PostgreSQL/Kafka stack; it is not part of the offline unit suite.

The test registers all schemas locally by `$id`, so `$ref` resolution does not use
the network. `FormatChecker` uses `datetime.fromisoformat` to check calendar dates,
while the schema pattern enforces the project's UTC format. This covers the format
used by EventV1 rather than every valid RFC 3339 form.

## Scope of this evidence

These checks cover the contract and fixtures. They do not test a generator, Kafka
delivery, scenario reproducibility or detection code because those parts do not
exist yet. Java runtime validation will also need to check timestamps and cross-field
rules. The producer must check ISO country membership and topology consistency.

Before committing, inspect the staged diff and run `git diff --cached --check` plus
the relevant tests. The committed suite repeats the JSON, schema and fixture checks
used during Day 01.

References: [jsonschema validation and formats](https://python-jsonschema.readthedocs.io/en/v4.25.1/validate/)
and [local schema registries](https://python-jsonschema.readthedocs.io/en/v4.25.1/referencing/).
