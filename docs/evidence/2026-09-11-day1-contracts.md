# Day 01 contract validation

Date: 2026-09-11

Scope: Revision 2 database, identity and incident API design

Starting repository commit: `c0af59c`

Result: Local contract checks passed; teammate review and runtime integration pending

## Deliverables

- [Database and identity ADR](../adr/002-databases-identity.md), including architecture,
  database relationships, application-role permissions and exact OIDC URLs.
- [Canonical incident API](../../contracts/openapi/incident-api.yaml): 18 operations
  covering OIDC/session/CSRF/logout, incident read/write/history, analyst directory,
  dashboard, session-bound SSE and simulator controls.
- [Analyst record schema](../../contracts/identity/analyst-directory.schema.json) and
  [synthetic analyst example](../../contracts/fixtures/identity/analyst.json).
- [Updated incident example](../../contracts/fixtures/incidents/incident-open.json).

The API YAML moved from its original location into `contracts/openapi/incident-api.yaml`.
The incident JSON belongs in `contracts/fixtures/incidents`, not the OpenAPI folder.
No README was created or modified. Infrastructure files and database volumes were
not changed.

## Observed validation

Validation used a temporary Python environment outside the repository with:

| Package | Version |
| --- | --- |
| openapi-spec-validator | 0.9.0 |
| openapi-schema-validator | 0.9.0 |
| PyYAML | 6.0.3 |
| jsonschema | 4.26.0 |

Passed checks:

1. The OpenAPI 3.0.3 document is valid and its references resolve.
2. The analyst JSON satisfies its Draft 2020-12 schema with UUID/URI format checks.
3. The incident JSON satisfies the OpenAPI Incident schema, including formats,
   required fields, enums, nullability and numeric/array bounds.
4. All 15 parameter/media examples satisfy their declared schemas.
5. Detail, list and SSE examples contain the same incident.
6. The deterministic detection hash matches both this fixture and the test vector
   on common-guide page 20.
7. The revised positive scenario is consistent: 60 calls, 54 international, unusual
   country, ML unavailable; contributions 60 + 10 + 10 = 80, severity HIGH. One
   evidence sample is included; evidenceCount is 60.
8. Event/run/incident/audit identifiers agree and timestamps fall in the correct
   60-second window. Unavailable anomalyRank is null.
9. Invalid UUIDs, risk 101, unknown mlStatus, the superseded ML_DEVIATION enum and
   missing evidenceCount fail schema validation. Analyst records reject email,
   app_roles and other undeclared fields. Mutation bodies reject caller-supplied
   actorId/roles; blank comments/resolution notes are rejected when supplied.
10. Public OIDC/CSRF bootstrap routes are explicit; protected reads require a
    session; JSON mutations require session plus CSRF; logout requires the form
    CSRF field. Revised assigned-analyst permissions appear in the contract.
11. There is one canonical API file and all existing README contents are unchanged.

## Reproduce document and fixture validation

Run from the repository root. Use a temporary environment so this does not add
runtime dependencies to a service:

```bash
python3 -m venv /tmp/telecom-contract-check
/tmp/telecom-contract-check/bin/python -m pip install openapi-spec-validator==0.9.0 openapi-schema-validator==0.9.0 PyYAML==6.0.3 jsonschema==4.26.0
/tmp/telecom-contract-check/bin/python -m openapi_spec_validator contracts/openapi/incident-api.yaml
```

Then validate the local fixtures, resolving schema references within the API:

```bash
/tmp/telecom-contract-check/bin/python - <<'PY'
import json
from pathlib import Path
import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_schema_validator import OAS30Validator

api = yaml.safe_load(Path('contracts/openapi/incident-api.yaml').read_text())

def expand(value):
    if isinstance(value, dict):
        if '$ref' in value:
            assert value['$ref'].startswith('#/')
            target = api
            for key in value['$ref'][2:].split('/'):
                target = target[key.replace('~1', '/').replace('~0', '~')]
            return expand(target)
        return {key: expand(item) for key, item in value.items()}
    if isinstance(value, list):
        return [expand(item) for item in value]
    return value

incident = json.loads(Path('contracts/fixtures/incidents/incident-open.json').read_text())
OAS30Validator(expand(api['components']['schemas']['Incident']),
               format_checker=FormatChecker()).validate(incident)
schema = json.loads(Path('contracts/identity/analyst-directory.schema.json').read_text())
analyst = json.loads(Path('contracts/fixtures/identity/analyst.json').read_text())
Draft202012Validator.check_schema(schema)
Draft202012Validator(schema, format_checker=FormatChecker()).validate(analyst)
print('Incident and analyst fixtures: PASS')
PY
```

## Limits and handoff

These are contract checks, not proof of implemented security or business logic.
No Spring Boot service, PostgreSQL migration, Keycloak login or browser/SSE test
was run. Database UNIQUE(issuer, subject), permissions, CSRF enforcement, session
expiry and transactional guarantees still need runtime implementation/tests.

Sergiu must review detection fields, reason codes and immutable identity. Ion must
review EventV1 payloads/run IDs and simulator inputs. Stanislav and David must
review issuer/redirect URLs, role claims, cookie/CSRF choices and the browser DTOs.
Examples are synthetic; the analyst subject does not claim a provisioned account.

The nested EventV1 payload remains permissive in this draft. Request limits and
new dashboard/history projections are documented proposals, not team-approved
contracts. The ADR records all remaining decisions. This evidence does not claim
Day 01 handoff signoff or the Day 03 integration gate is complete.
