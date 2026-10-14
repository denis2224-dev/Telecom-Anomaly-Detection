"""Validate the frozen producer message independently of the pending consumer mapping."""
import csv
import hashlib
import io
import json
from datetime import datetime, timezone
from decimal import Decimal, localcontext
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / 'contracts/kpi/trace-link-primitive-projection-v1.schema.json'
FIXTURE = ROOT / 'contracts/fixtures/trace-link/primitive-projection-cases-v1.json'


def sha(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def validate_message(message):
    """Schema plus identities/arithmetic that JSON Schema cannot express."""
    Draft202012Validator(json.loads(SCHEMA.read_text(encoding='utf-8')),
                         format_checker=FormatChecker()).validate(message)
    city = message['scope']['id']
    if message['scope']['contractCityId'] != city.upper():
        raise ValueError('Wrong city translation')
    window = message['window']
    start, end = (datetime.fromisoformat(window[k]) for k in ('from', 'to'))
    if start.microsecond or start.timestamp() % 300 or (end - start).total_seconds() != 300:
        raise ValueError('Unaligned or incompatible native window')
    canonical = start.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')
    prefix = [message['datasetId'], message['sourceNamespace']]
    natural = sha('|'.join(prefix + ['CITY', city, '', '', '', canonical, '300', 'trace-link-native-source-v1']))
    projection = sha('|'.join(prefix + ['VOLTE', 'volte.setup.success', 'CITY', city, canonical, '300',
                                       'trace-link-primitive-projection-v1', 'trace-link-kpi-v1',
                                       message['topologyVersion'], 'revision=0']))
    if message['projectionId'] != projection or message['provenance']['sourceNaturalId'] != natural:
        raise ValueError('Projection or source natural identity mismatch')
    n, d = (message['primitives'][k] for k in ('numerator', 'denominator'))
    if n > d:
        raise ValueError('Success exceeds attempts')
    with localcontext() as ctx:
        ctx.prec = 34
        expected = Decimal(100) * Decimal(n) / Decimal(d) if d else None
    actual = message['value']
    if (actual is None) != (expected is None) or (actual is not None and Decimal(str(actual)) != expected):
        raise ValueError('Computed value differs from primitives')


def native_example():
    source = ROOT / 'contracts/fixtures/trace-link/native-slice'
    manifest_bytes = (source / 'manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    lines = (source / 'city_kpi_5m.csv').read_text(encoding='utf-8').splitlines()
    row = next(csv.DictReader(io.StringIO('\n'.join(lines))))
    start = datetime.fromisoformat(row['ts']).astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')
    dataset, namespace = manifest['dataset_id'], 'trace-link-reference-v1'
    native = sha('|'.join([dataset, namespace, 'CITY', 'chi', '', '', '', start, '300', 'trace-link-native-source-v1']))
    identity = sha('|'.join([dataset, namespace, 'VOLTE', 'volte.setup.success', 'CITY', 'chi', start, '300',
                             'trace-link-primitive-projection-v1', 'trace-link-kpi-v1', 'trace-link-links-v1', 'revision=0']))
    with localcontext() as ctx:
        ctx.prec = 34
        value = Decimal(100) * Decimal(row['voice_success']) / Decimal(row['voice_attempts'])
    return {
        'schemaVersion': 1, 'messageType': 'trace-link-primitive-projection-v1', 'projectionId': identity,
        'datasetId': dataset, 'sourceNamespace': namespace, 'synthetic': True, 'domain': 'VOLTE',
        'metricId': 'volte.setup.success', 'unit': 'PERCENT', 'aggregation': 'RATIO_OF_SUMS',
        'policyVersion': 'trace-link-kpi-v1', 'topologyVersion': 'trace-link-links-v1', 'revision': 0, 'finalized': True,
        'scope': {'type': 'CITY', 'id': 'chi', 'contractCityId': 'CHI'},
        'window': {'from': start, 'to': '2026-09-10T09:05:00Z', 'sourceResolutionSeconds': 300},
        'primitives': {'numerator': int(row['voice_success']), 'denominator': int(row['voice_attempts'])},
        'value': value, 'nullReason': None,
        'coverage': {'expectedSlots': 1, 'receivedSlots': 1, 'ratio': 1},
        'sourceQuality': None, 'sourceCoverage': None,
        'quality': {'state': 'INSUFFICIENT_EVIDENCE', 'reason': 'SOURCE_QUALITY_UNAVAILABLE'},
        'provenance': {'sourceNaturalId': native, 'sourceFile': 'city_kpi_5m.csv', 'sourceRowNumber': 1,
                       'sourceRowSha256': sha(lines[1]), 'manifestSha256': hashlib.sha256(manifest_bytes).hexdigest(),
                       'generatorVersion': manifest['generator_version'], 'seed': manifest['seed'],
                       'catalogueVersion': 'trace-link-geography-v2', 'importPolicyVersion': 'trace-link-native-source-v1'}
    }


def validate_projection_contracts():
    schema = json.loads(SCHEMA.read_text(encoding='utf-8'))
    Draft202012Validator.check_schema(schema)
    fixture = json.loads(FIXTURE.read_text(encoding='utf-8'), parse_float=Decimal)
    if fixture['consumerAgreement'] != 'PENDING_REVIEW':
        raise ValueError('Consumer approval must not be inferred')
    validate_message(fixture['nativeExample'])
    if fixture['nativeExample'] != native_example():
        raise ValueError('Producer fixture differs from actual native CSV primitives/provenance')
    print('PASS: producer projection schema and independent native CSV projection; consumer PENDING_REVIEW')


if __name__ == '__main__':
    validate_projection_contracts()
