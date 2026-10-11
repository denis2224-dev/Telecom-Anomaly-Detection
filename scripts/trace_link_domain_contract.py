"""Additive reference inventory/domain contracts; no legacy runtime activation."""
import hashlib
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
CITIES = {'CHI', 'BAL', 'CAH', 'UNG', 'ORH', 'SOR', 'COM', 'EDI', 'HIN', 'CAU'}
LINKS = {'chi-orh', 'orh-sor', 'sor-bal', 'bal-edi', 'bal-ung', 'ung-chi',
         'chi-hin', 'hin-com', 'com-cah', 'chi-cau', 'cau-com', 'orh-bal'}


def read(path):
    return json.loads((ROOT / 'contracts' / path).read_text(encoding='utf-8'))


def require(value, reason):
    if not value:
        raise ValueError(reason)


def validate_inventory(geography, topology):
    require(geography['scopeNamespace'] == topology['scopeNamespace'] == 'trace-link-reference-v1', 'Reference namespace required')
    require(geography['activation'] == {'status': 'CONTRACT_ONLY', 'effectiveFrom': None}, 'Activation needs a separate approved contract')
    require(geography['catalogueVersion'] == topology['catalogueVersion'] == 'trace-link-geography-v2', 'Catalogue mismatch')
    cities = geography['cities']
    require(len(cities) == 10 and {c['contractCityId'] for c in cities} == CITIES, 'Exactly ten approved cities')
    require(len({c['referenceCityId'] for c in cities}) == 10, 'Duplicate reference city')
    approved = {c['contractCityId']: c['referenceCityId'] for c in read('kpi/trace-link-kpi-definitions-v1.json')['cityIdMap']}
    require(all(approved[c['contractCityId']] == c['referenceCityId'] and c['active'] and 'runtimeCityId' not in c for c in cities), 'Explicit contract city mapping')
    require(abs(sum(c['demandShare'] for c in cities) - 1) < 1e-12, 'Demand shares must sum to one')
    links = topology['links']
    require(len(links) == 12 and {l['linkId'] for l in links} == LINKS, 'Exactly twelve approved links')
    refs = {c['contractCityId']: c['referenceCityId'] for c in cities}
    for link in links:
        a, b = link['endpointAContractCityId'], link['endpointBContractCityId']
        require(a in CITIES and b in CITIES and a != b and link['nominalCapacityGbps'] > 0, 'Invalid link endpoints/capacity')
        forward, reverse = link['directions']
        require(forward == {'directionId': 'a-to-b', 'sourceReferenceCityId': refs[a], 'destinationReferenceCityId': refs[b], 'sourceAvailable': True}, 'Forward direction mismatch')
        require(reverse == {'directionId': 'b-to-a', 'sourceReferenceCityId': refs[b], 'destinationReferenceCityId': refs[a], 'sourceAvailable': False}, 'Reverse direction must not claim supplied evidence')


def validate_domain_contracts():
    validate_inventory(read('geography/trace-link-geography-v2.json'), read('topology/trace-link-links-v1.json'))
    schema = read('observations/trace-link-domain-window-v1.schema.json')
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    for metric in read('kpi/trace-link-kpi-definitions-v1.json')['metrics']:
        if metric['domain'] == 'QUALITY':
            continue  # Quality metadata is not a sixth observation domain.
        require(len(metric['population']) <= schema['properties']['population']['maxLength'], 'Dictionary population does not fit producer contract')
        require(metric['unit'] in schema['properties']['unit']['enum'], 'Dictionary unit does not fit producer contract')
        require(metric['aggregation'] in schema['properties']['aggregationType']['enum'], 'Dictionary aggregation does not fit producer contract')
    cases = read('fixtures/trace-link/domain-window-cases-v1.json')['cases']
    for case in cases:
        errors = list(validator.iter_errors(case['observation']))
        require(bool(errors) != case['valid'], f"Unexpected fixture schema result: {case['id']}")
    schema = read('datasets/trace-link-reference-manifest-v1.schema.json')
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    validator.validate(read('datasets/trace-link-md-30d-v1.manifest.json'))
    fixture = ROOT / 'contracts/fixtures/trace-link/native-slice'
    manifest = json.loads((fixture / 'manifest.json').read_text())
    validator.validate(manifest)
    for file in manifest['files']:
        data = (fixture / file['name']).read_bytes()
        require(len(data) == file['bytes'] and hashlib.sha256(data).hexdigest() == file['sha256'], 'Bounded source fixture checksum')
    print(f'PASS: Trace Link reference inventory, {len(cases)} domain examples and bounded native archive checksums')
