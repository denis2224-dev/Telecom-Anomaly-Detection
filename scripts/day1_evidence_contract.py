"""Validate G1 DTO examples. No cause inference, publisher or storage is activated."""

from datetime import datetime, timedelta
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

from geography_contract import validate_catalogue

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8'))


def require(valid, message):
    if not valid:
        raise ValueError(message)


def validator(path):
    schema = read(path)
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def validate_cause_projection(value):
    validator('contracts/explanations/geographic-cause-projection-v1.schema.json').validate(value)
    catalogue = read('contracts/geography/demo-geography-v1.json')
    authority = read('contracts/topology/geographic-scopes-v2.json')
    validate_catalogue(catalogue, authority)
    scope = next((s for s in authority['scopes'] if s['scopeId'] == value['scopeId']), None)
    require(scope is not None and scope['service'] == value['service'], 'Cause scope/service mismatch')
    require(value['topologyVersion'] == authority['topologyVersion']
            and value['catalogueVersion'] == catalogue['catalogueVersion'], 'Cause version mismatch')
    start, end = (datetime.fromisoformat(value[k]) for k in ('windowStart', 'windowEnd'))
    require(start.second == 0 and start.microsecond == 0 and end - start == timedelta(seconds=60), 'Cause window alignment')
    binding = next(s for s in catalogue['scopes'] if s['scopeId'] == value['scopeId'])
    nodes = {n['nodeId']: n['sourceId'] for n in scope['nodes']}
    roles = {r['role']: r['nodeId'] for r in binding['roles']}
    for ref in value['supportingEvidence'] + value['contradictoryEvidence']:
        require(all(ref[k] == value[k] for k in ('scopeId', 'windowStart', 'windowEnd', 'topologyVersion', 'catalogueVersion')),
                'Evidence scope/window/version mismatch')
        if ref['role'] == 'SERVICE':
            require(ref['nodeId'] is None and ref['sourceId'] == scope['serviceSourceId'], 'Evidence SERVICE authority mismatch')
        else:
            require(ref['role'] in roles and ref['nodeId'] == roles[ref['role']]
                    and ref['sourceId'] == nodes[ref['nodeId']], 'Evidence role/reporter mismatch')


def validate_day1_evidence_contracts():
    suite = read('contracts/fixtures/geography/cause-projection-cases-v1.json')
    ids = set()
    for case in suite['cases']:
        require(case['id'] not in ids, 'Duplicate cause example')
        ids.add(case['id'])
        validate_cause_projection(case['value'])
    auxiliary = validator('contracts/auxiliary/auxiliary-network-evidence-v1.schema.json')
    samples = read('contracts/fixtures/geography/auxiliary-evidence-cases-v1.json')
    require(samples['status'] == 'PLANNED', 'Auxiliary evidence must remain planned until G2 readiness')
    for sample in samples['cases']:
        auxiliary.validate(sample)
    print(f'PASS: {len(ids)} cause DTO examples and {len(samples["cases"])} PLANNED auxiliary shape samples; no runtime correlation claim')
    controls = read('contracts/fixtures/geography/day4-auxiliary-boundary-cases.json')
    require(controls['status'] == 'PLANNED'
            and controls['coverage'] == 'CONTRACT_AND_INGESTION_BOUNDARY_ONLY', 'Auxiliary control status changed')
    require(len(controls['cases']) == 2 and {c['type'] for c in controls['cases']}
            == {'PING_RESULT', 'PROBE_WORKER_HEALTH'}, 'Missing ping/probe boundary control')
    for sample in controls['cases']:
        auxiliary.validate(sample)
        require(sample['status'] == 'FAILURE', 'Boundary examples must represent failures')
    print('PASS: 2 PLANNED failed-ping/probe schema examples; ingestion-boundary coverage only')
