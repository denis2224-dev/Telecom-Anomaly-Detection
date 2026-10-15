"""Independent receiving checks over exact source bytes and decimal arithmetic; no delivery activation."""
import base64
import hashlib
import json
from decimal import Decimal, localcontext
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
from trace_link_scenario_contract import identity, no_truth

ROOT = Path(__file__).resolve().parents[1]
DIMENSIONS = ('scopeNamespace', 'domain', 'scopeType', 'scopeId', 'contractCityId', 'linkId',
              'directionId', 'partnerId', 'countryId', 'direction', 'procedure', 'accessTechnology')


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'), parse_float=Decimal)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def validate_receiving_contracts():
    schemas = {}
    for folder, name in [('kpi', 'trace-link-domain-measurement-v1'),
                         ('detections', 'trace-link-live-evaluation-v2'),
                         ('detections', 'trace-link-live-result-v1')]:
        schema = read(ROOT / f'contracts/{folder}/{name}.schema.json')
        Draft202012Validator.check_schema(schema)
        schemas[name] = Draft202012Validator(schema, format_checker=FormatChecker())
    results = read(ROOT / 'contracts/fixtures/trace-link/live-consumer-results-v1.json')
    expected = [('ROAMING', 'BREACH'), ('MOBILE_DATA', 'BREACH'), ('TRANSPORT', 'CANDIDATE'),
                ('TRANSPORT', 'CANDIDATE'), ('MOBILE_DATA', 'UNKNOWN')]
    assert len(results) == len(expected)
    manifest = sha((ROOT / 'contracts/datasets/trace-link-live-synthetic-v1.json').read_bytes())
    sources = 0
    with localcontext() as ctx:
        ctx.prec = 34
        for result, (domain, verdict) in zip(results, expected):
            schemas['trace-link-live-result-v1'].validate(result)
            assert (result['domain'], result['verdict']) == (domain, verdict)
            assert result['causeStatus'] == 'UNDETERMINED' and result['approvalStatus'] == 'PENDING_REVIEW'
            no_truth(result['input'])
            for index, measurement in enumerate(result['input']['measurements']):
                schemas['trace-link-domain-measurement-v1'].validate(measurement)
                source = base64.b64decode(measurement['sourcePayloadBase64'], validate=True)
                assert sha(source) == measurement['sourcePayloadSha256'] == result['sourcePayloadHashes'][index]
                assert measurement['provenance']['manifestSha256'] == manifest
                observation = json.loads(source, parse_float=Decimal)
                no_truth(observation)
                assert identity(observation) == observation['eventId'] == result['sourceEventIds'][index]
                assert observation['sourceResolutionSeconds'] == 60 and observation['finalized'] and observation['revision'] == 0
                # Verify these bytes really occur in frozen producer artifacts, without rewriting time or duration.
                matches = [p for p in (ROOT / 'docs/evidence/assets/trace-link-day6/run-1').glob('*.jsonl')
                           if source in p.read_bytes().splitlines()]
                assert matches, 'Source is not an exact archived producer observation'
                if observation['metricId'] == result['metricId']:
                    p = observation['primitives']
                    if observation['quality'] == 'MISSING':
                        actual = None
                    elif observation['aggregationType'] == 'RATIO_OF_SUMS':
                        actual = Decimal(100) * p['numerator'] / p['denominator'] if p['denominator'] else None
                    else:
                        actual = p['weightedSum'] / p['validSeconds'] if p['validSeconds'] else None
                    assert result['actual'] == actual, 'Decimal source arithmetic differs'
                    assert result['scope'] == {k: observation[k] for k in DIMENSIONS if k in observation}
                sources += 1
    offline = read(ROOT / 'contracts/policies/trace-link-domain-rules-v1.json')
    live = read(ROOT / 'contracts/policies/trace-link-live-synthetic-rules-v1.json')
    assert offline['windowSeconds'] == 300 and live['windowSeconds'] == 60
    assert offline['policyVersion'] != live['policyVersion'] and offline['baselineVersion'] != live['baselineVersion']
    assert live['ownerDecision'] == 'PENDING_REVIEW' and live['delivery'] == 'DISABLED'
    print(f'PASS: {len(results)} independent receiving results; {sources} byte-exact source observations; decimal arithmetic and separate policies')
    return len(results)


if __name__ == '__main__':
    validate_receiving_contracts()
