# Validate the Day 01 contract

Requirements: Python 3.11 or newer plus the two packages in requirements-dev.txt.
Day 01 was checked with Python 3.13.7, jsonschema 4.25.1 and referencing 0.37.0
already installed locally. These are development-only tools; no Java or Kafka
runtime is needed. A package installation requires network access once; checks
then run offline.

From the repository root, on an environment with the dependencies installed:

```text
python -m unittest discover -s tests -v
git diff --check
```

For a fresh Windows checkout, use a project-local environment without changing
PowerShell execution policy:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

On Linux/macOS, use `.venv/bin/python` for the last two commands.

## What is checked

- All project JSON parses, with duplicate property names and non-JSON numeric
  constants (NaN/Infinity) rejected by the fixture loader.
- All seven schemas pass Draft202012Validator.check_schema.
- Nine complete EventV1 examples validate and cover all six event types.
- Required fields, type dispatch, supported versions and entity kinds are enforced.
- Identifiers reject whitespace, including trailing newlines that an end-anchored
  regular expression alone can accept and that would alter Kafka keys or joins.
- Timestamp tests accept UTC Z with up to millisecond precision, and reject offsets,
  missing zones, invalid calendar dates and excessive precision.
- Integer byte/money/duration/count fields reject fractions, negatives, strings,
  booleans and values outside the documented exact JSON integer range.
- Network ratio/rate/latency ranges, service enums and failed-call semantics hold.
- Unknown fields and anomaly labels are rejected in both envelope and payload.
- Node/link payload boundaries and optional trace metadata behave as documented.
- Fixture eventIds are distinct; the repeated billing pair shares business identity,
  with a normal control carrying another transactionRef.
- Network fixtures use matching entity identities, consistent byte/rate values and
  nullable latency for complete probe loss. These are fixture semantic checks,
  not a runtime detector or a claim that JSON Schema compares fields.
- Local documentation links resolve and every schema field has a description and
  an entry in the contract's field tables.

The validator explicitly registers all local schemas using their logical $id,
so resolving relative $ref values requires no remote schema service. FormatChecker
is enabled. Its date-time check uses Python's datetime.fromisoformat for calendar
validity, combined with the schema's strict UTC spelling pattern; this avoids an
optional format dependency. The check is scoped to our documented timestamp subset,
not a general implementation of every RFC 3339 representation.

## Scope of this evidence

Passing these checks proves the current contract and fixtures pass these structural
and selected semantic checks. It does not prove a runtime generator produces unique
IDs, a scenario is reproducible in code, a producer delivers records correctly, or
a detector avoids metadata leakage. Those components do not exist in Day 01.
Future Java runtime validation must enforce date-time formats and documented
cross-field relationships. ISO country membership and topology consistency remain
producer/profile responsibilities; the schema only validates country-code shape.

Before each commit, inspect `git diff --cached`, run `git diff --cached --check`
and the checks relevant to that stage. The early schema commits were checked using
Python json.loads and Draft202012Validator.check_schema; examples were validated
against the complete local schema bundle when added. This committed suite makes
the final checks repeatable.

References: [jsonschema validation and formats](https://python-jsonschema.readthedocs.io/en/v4.25.1/validate/)
and [local schema registries](https://python-jsonschema.readthedocs.io/en/v4.25.1/referencing/).
