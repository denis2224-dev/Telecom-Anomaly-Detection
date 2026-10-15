"""Validate isolated native producer artifacts and independent source arithmetic."""
import csv
import hashlib
import json
import math
from datetime import datetime, timedelta
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / 'contracts'
METRICS = {m['metricId']: m for m in json.loads((CONTRACTS / 'kpi/trace-link-kpi-definitions-v1.json').read_text(encoding='utf-8'))['metrics']}
DOMAIN = json.loads((CONTRACTS / 'observations/trace-link-domain-window-v1.schema.json').read_text(encoding='utf-8'))
SCHEMA = json.loads((CONTRACTS / 'observations/trace-link-domain-primitive-result-v1.schema.json').read_text(encoding='utf-8'))
VALIDATOR = Draft202012Validator(SCHEMA, registry=Registry().with_resource(DOMAIN['$id'], Resource.from_contents(DOMAIN)), format_checker=FormatChecker())


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def validate_result(result):
    VALIDATOR.validate(result)
    e = result['observation']
    m = METRICS[e['metricId']]
    require(result['availability'] == m['reference']['availability'] and result['referenceLimitations'] == m['reference']['reason'], 'Reference support changed')
    require(e['domain'] == m['domain'] and e['unit'] == m['unit'] and e['population'] == m['population'] and e['aggregationType'] == m['aggregation'], 'Dictionary mismatch')
    require(set(e['primitives']) == set(m['primitiveFields']), 'Primitive shape mismatch')
    require(e['quality'] in {'MISSING', 'INSUFFICIENT_EVIDENCE'}, 'Unproven source quality promoted')
    require(result['referenceOnly'] == (e['metricId'] == 'mobileData.dns.success'), 'DNS reference-only boundary changed')
    require(result['procedureEligibility'] == ('UNPROVEN' if e['domain'] == 'MOBILE_DATA' else 'NOT_APPLICABLE'), 'Procedure eligibility promoted')
    require(result['weightingBasis'] == ('MODELED_CITY_SLOT; no genuine user/session weighting' if e['metricId'] == 'mobileData.throughput' else None), 'User weighting invented')
    require(result['validityAssumption'] == ('300 seconds per native slot; not source-provided temporal validity.' if e['domain'] == 'TRANSPORT' else None), 'Temporal validity promoted')
    start = datetime.fromisoformat(e['windowStart'].replace('Z', '+00:00'))
    end = datetime.fromisoformat(e['windowEnd'].replace('Z', '+00:00'))
    require(e['sourceResolutionSeconds'] == 300 and end - start == timedelta(seconds=300) and start.timestamp() % 300 == 0, 'Native window changed')
    require(e['emittedAt'] == e['windowEnd'] and e['finalized'] and e['revision'] == 0, 'Non-deterministic final state')
    fields = ('datasetId sourceId scopeNamespace domain scopeType scopeId directionId direction partnerId countryId procedure accessTechnology metricId windowStart sourceResolutionSeconds schemaVersion catalogueVersion topologyVersion policyVersion').split()
    parts = [str(e.get(f, '-')) for f in fields]
    require(e['eventId'] == hashlib.sha256('|'.join(parts).encode()).hexdigest(), 'Observation identity mismatch')
    p = e['primitives']
    if e['quality'] == 'MISSING':
        require(result['value'] is None and result['nullReason'] == 'NO_SOURCE_SLOT' and e['coverage'] == 0, 'Missing source fabricated')
        require(all(result['provenance'][f] is None for f in ('naturalId', 'sourceFile', 'sourceRowNumber', 'sourceRowSha256')), 'Missing source provenance fabricated')
        return
    require(e['coverage'] == 1 and result['provenance']['naturalId'] is not None, 'Observed slot absent')
    if 'denominator' in p:
        require(0 <= p['numerator'] <= p['denominator'], 'Invalid counters')
        expected = None if p['denominator'] == 0 else 100 * p['numerator'] / p['denominator']
    elif 'validSeconds' in p:
        require(p['validSeconds'] == 300, 'Native validity approximation changed')
        expected = p['weightedSum'] / p['validSeconds']
    elif 'count' in p:
        require(p['count'] == 1, 'Modeled sample count changed')
        expected = p['sum'] / p['count']
    else:
        require(p['snapshotAt'] == e['windowStart'], 'Snapshot timestamp changed')
        expected = p['value']
    require(result['nullReason'] == ('ZERO_DENOMINATOR' if expected is None else 'NONE'), 'Null reason mismatch')
    require(result['value'] is None if expected is None else result['value'] is not None and math.isclose(expected, result['value'], abs_tol=1e-9, rel_tol=0), 'Primitive arithmetic mismatch')


def validate_producer_contracts():
    Draft202012Validator.check_schema(SCHEMA)
    examples = json.loads((CONTRACTS / 'fixtures/trace-link/domain-primitive-results-v1.json').read_text(encoding='utf-8'))
    source = CONTRACTS / 'fixtures/trace-link/native-slice'
    manifest_hash = hashlib.sha256((source / 'manifest.json').read_bytes()).hexdigest()
    observed = 0
    for result in examples:
        validate_result(result)
        e, provenance = result['observation'], result['provenance']
        require(provenance['manifestSha256'] == manifest_hash, 'Wrong manifest checksum')
        if e['quality'] == 'MISSING':
            require(e['directionId'] == 'b-to-a', 'Expected reverse-direction control')
            continue
        observed += 1
        raw = (source / provenance['sourceFile']).read_text(encoding='utf-8').splitlines()[provenance['sourceRowNumber']]
        require(hashlib.sha256(raw.encode()).hexdigest() == provenance['sourceRowSha256'], 'Wrong source row checksum')
        row = next(csv.DictReader([(source / provenance['sourceFile']).read_text(encoding='utf-8').splitlines()[0], raw]))
        metric = e['metricId']
        if metric == 'transport.link.utilization':
            expected = 100 * float(row['traffic_gbps']) / float(row['effective_capacity_gbps'])
        elif metric in ('transport.traffic', 'transport.effectiveCapacity', 'transport.rtt.snapshot', 'transport.jitter', 'mobileData.throughput'):
            field = METRICS[metric]['reference']['fields'][0]
            expected = float(row[field])
        else:
            numerator, denominator = METRICS[metric]['reference']['fields']
            expected = 100 * int(row[numerator]) / int(row[denominator]) if int(row[denominator]) else None
        require(expected is None if result['value'] is None else math.isclose(expected, result['value'], abs_tol=1e-9, rel_tol=0), 'Source-to-primitive mismatch')
    require(observed == 11 and len(examples) == 16, 'Producer metric/control matrix changed')
    print('PASS: 11 native producer metrics and 5 absent reverse-direction controls; source/checksum/arithmetic parity')


if __name__ == '__main__':
    validate_producer_contracts()
