# Validate the Day 01 contract

Use Python 3.11 or newer and the packages in `requirements-dev.txt`. Day 01 was
tested with Python 3.13.7, jsonschema 4.25.1 and referencing 0.37.0. The checks do
not need Java or Kafka and run offline after the packages are installed.

From the repository root:

```text
python -m unittest discover -s tests -v
git diff --check
```

For a fresh Windows checkout:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

On Linux/macOS, use `.venv/bin/python` for the last two commands.

## What is checked

- All JSON files parse. The loader rejects duplicate fields, NaN and Infinity.
- All seven schemas pass `Draft202012Validator.check_schema`.
- Nine EventV1 examples validate and cover all six event types.
- Required fields, type dispatch, supported versions and entity kinds are enforced.
- Identifiers reject whitespace, including trailing newlines that would change
  Kafka keys or joins.
- Timestamps accept UTC `Z` with up to millisecond precision. Tests reject offsets,
  missing zones, invalid dates and extra precision.
- Integer byte/money/duration/count fields reject fractions, negatives, strings,
  booleans and values outside the documented exact JSON integer range.
- Network ranges, service enums and failed-call rules are checked.
- Unknown fields and anomaly labels are rejected in both envelope and payload.
- Node/link payload boundaries and optional trace metadata behave as documented.
- Fixture event IDs are distinct. The billing pair shares a business identity and
  the control uses another `transactionRef`.
- Network fixtures have matching entity IDs, consistent byte/rate values and null
  latency when all probes fail. These checks run on fixtures, not runtime events.
- Local documentation links resolve and every schema field has a description and
  an entry in the contract's field tables.

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
