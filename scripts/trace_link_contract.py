"""Strict, offline Trace Link Day 1 contracts; does not activate ingestion or rules."""
from datetime import datetime
from decimal import Decimal
import json
import math
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).resolve().parents[1]
MAX_SAFE = 9_007_199_254_740_991
CITY_IDS = ('CHI', 'BAL', 'CAH', 'UNG', 'ORH', 'SOR', 'COM', 'EDI', 'HIN', 'CAU')
VOICE_EDGES = [.5, 1, 2, 3, 5, 8, 15, 30, 60]
SMS_EDGES = [1, 2, 5, 10, 20, 40, 80, 160, 320]
REQUIRED_METRICS = {
    'volte.' + n for n in ('attempts', 'established', 'setup.success', 'traffic.erlang',
                           'setup.mean', 'setup.p95', 'abnormalReleases', 'drop.rate', 'handover.success')
} | {
    'transport.' + n for n in ('traffic', 'volume', 'effectiveCapacity', 'link.utilization',
                               'link.utilization.peak', 'packetLoss', 'rtt.mean', 'rtt.snapshot', 'jitter', 'interfaceErrors',
                               'queueDrops', 'link.state')
} | {
    'roaming.' + n for n in ('activeOutbound', 'activeInbound', 'registration.success',
                             'data.success', 'voice.success', 'sms.finalizedSuccess', 'partnerRtt')
} | {
    'sms.' + n for n in ('submitted', 'delivered', 'terminalFailed', 'expired', 'finalizedSuccess',
                         'queue', 'oldestPending', 'delay.mean', 'delay.p95', 'queueGrowth')
} | {'mobileData.activation.success', 'mobileData.dns.success', 'mobileData.throughput',
     'quality.coverage', 'quality.freshness'}
PRIMITIVE_TYPES = dict(COUNT_SUM='COUNT', RATIO_OF_SUMS='RATIO', WEIGHTED_MEAN='SUM_COUNT',
                       TIME_WEIGHTED_MEAN='TIME_WEIGHTED', HISTOGRAM_P95='HISTOGRAM',
                       SNAPSHOT_LAST='SNAPSHOT', STATE_TIMELINE='STATE',
                       SNAPSHOT_DIFFERENCE='SNAPSHOT_DIFFERENCE', MAXIMUM='MAXIMUM')
PRIMITIVE_FIELDS = dict(COUNT_SUM={'count'}, RATIO_OF_SUMS={'numerator', 'denominator'},
                        WEIGHTED_MEAN={'sum', 'count'}, TIME_WEIGHTED_MEAN={'weightedSum', 'validSeconds'},
                        HISTOGRAM_P95={'histogram'}, SNAPSHOT_LAST={'value', 'snapshotAt'},
                        STATE_TIMELINE={'state', 'at'}, SNAPSHOT_DIFFERENCE={'start', 'end'}, MAXIMUM={'max'})
METRIC_UNITS = {
    'ERLANG': {'volte.traffic.erlang'},
    'SECONDS': {'volte.setup.mean', 'volte.setup.p95', 'sms.oldestPending', 'quality.freshness'},
    'GBPS': {'transport.traffic', 'transport.effectiveCapacity'},
    'BYTES': {'transport.volume'},
    'MILLISECONDS': {'transport.rtt.mean', 'transport.rtt.snapshot', 'transport.jitter',
                     'roaming.partnerRtt', 'sms.delay.mean', 'sms.delay.p95'},
    'MBPS': {'mobileData.throughput'},
    'STATE': {'transport.link.state'},
    'PERCENT': {'volte.setup.success', 'volte.drop.rate', 'volte.handover.success',
                'transport.link.utilization', 'transport.link.utilization.peak', 'transport.packetLoss',
                'roaming.registration.success', 'roaming.data.success', 'roaming.voice.success',
                'roaming.sms.finalizedSuccess', 'sms.finalizedSuccess', 'mobileData.activation.success',
                'mobileData.dns.success', 'quality.coverage'},
    'COUNT': {'volte.attempts', 'volte.established', 'volte.abnormalReleases',
              'transport.interfaceErrors', 'transport.queueDrops', 'roaming.activeOutbound',
              'roaming.activeInbound', 'sms.submitted', 'sms.delivered', 'sms.terminalFailed',
              'sms.expired', 'sms.queue', 'sms.queueGrowth'},
}


def read(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8'))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def finite(value):
    if isinstance(value, float):
        require(math.isfinite(value), 'Non-finite numeric value')
    elif isinstance(value, dict):
        for item in value.values():
            finite(item)
    elif isinstance(value, list):
        for item in value:
            finite(item)


def shape(path, value):
    finite(value)
    schema = read(path)
    Draft202012Validator.check_schema(schema)
    try:
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(value)
    except ValidationError as error:
        raise ValueError(error.message) from error


def instant(value):
    require(value.endswith('Z'), 'UTC Z timestamp required')
    return datetime.fromisoformat(value)


def same_number(actual, expected):
    if expected is None or isinstance(expected, str):
        require(actual == expected, 'Actual disagrees with primitive aggregate')
    else:
        require(type(actual) in (int, float)
                and abs(Decimal(str(actual)) - expected) <= Decimal('1e-9'),
                'Actual disagrees with primitive aggregate')


def safe_count(value):
    require(type(value) in (int, float) and 0 <= value <= MAX_SAFE and value == int(value),
            'Count must be an integer in the interoperable 0..2^53-1 range')


def validate_dictionary(value):
    shape('contracts/kpi/trace-link-kpi-definitions-v1.schema.json', value)
    cities = value['cityIdMap']
    require({c['runtimeCityId'] for c in cities} == set(CITY_IDS), 'Incorrect runtime city map')
    require(all(c['referenceCityId'] == c['runtimeCityId'].lower() for c in cities),
            'Incorrect reference/runtime city mapping')
    metrics = value['metrics']
    require(len({m['metricId'] for m in metrics}) == len(metrics), 'Duplicate metric ID')
    require({m['metricId'] for m in metrics} == REQUIRED_METRICS, 'Incomplete Day 1 metric inventory')
    for metric in metrics:
        require(metric['metricId'] in METRIC_UNITS[metric['unit']], 'Metric unit changed without a new policy')
        require(set(metric['primitiveFields']) == PRIMITIVE_FIELDS[metric['aggregation']],
                'Primitive fields do not match aggregation')
        reference = metric['reference']
        if reference['availability'] == 'SUPPORTED':
            require(reference['reason'] is None and bool(reference['fields']),
                    'Supported reference needs source fields and no availability limitation')
        else:
            require(bool(reference['reason']), 'Approximation/unavailable reference needs a reason')
        expected_policy = ('trace-link-sms-delay-ms-v1' if metric['metricId'].startswith('sms.delay.')
                           else 'trace-link-kpi-v1')
        require(metric['policyVersion'] == expected_policy, 'Incorrect metric policy version')
        require(metric['minimumVolume'] is None, 'Day 1 does not establish detector minimum volume')
        histogram = metric['histogramSchema']
        if metric['aggregation'] == 'HISTOGRAM_P95':
            expected = VOICE_EDGES if metric['metricId'] == 'volte.setup.p95' else SMS_EDGES
            require(histogram is not None and histogram['edges'] == expected,
                    'Incorrect finite-bin metric policy')
        else:
            require(histogram is None, 'Unexpected histogram policy')


def _quantile(histogram):
    counts = histogram['counts']
    total = sum(counts)
    if not total:
        return None
    target, running, lower = Decimal(total) * Decimal('.95'), 0, Decimal(0)
    for count, edge in zip(counts, histogram['edges']):
        upper = Decimal(str(edge))
        if count and running + count >= target:
            return lower + (upper - lower) * (target - running) / count
        running += count
        lower = upper
    raise ValueError('Histogram quantile not reachable')


def validate_evidence(value, dictionary=None):
    shape('contracts/evidence/trace-link-evidence-v1.schema.json', value)
    dictionary = dictionary or read('contracts/kpi/trace-link-kpi-definitions-v1.json')
    metric = next((m for m in dictionary['metrics'] if m['metricId'] == value['metricId']), None)
    require(metric is not None, 'Unknown metric ID')
    require(value['unit'] == metric['unit'] and value['policyVersion'] == metric['policyVersion'],
            'Metric unit/policy mismatch')
    provenance = value['provenance']
    if provenance['origin'] != 'HAND_CALCULATED':
        require(provenance['sourceChecksum'] is not None, 'Reference evidence requires a source checksum')
        if metric['reference']['availability'] == 'REQUIRES_ADDITIONAL_PRIMITIVES':
            require(value['primitives'] is None and value['actual'] is None,
                    'Reference source cannot supply unavailable target primitives')
    window = value['window']
    start, end = instant(window['from']), instant(window['to'])
    duration = (end - start).total_seconds()
    require(duration > 0 and duration % window['sourceResolutionSeconds'] == 0,
            'Invalid half-open measurement window')
    require(start.microsecond == 0 and start.timestamp() % window['sourceResolutionSeconds'] == 0,
            'Window must align with source resolution')
    freshness = value['freshness']
    as_of = instant(freshness['asOf'])
    require(instant(value['generatedAt']) >= end and instant(value['generatedAt']) >= as_of,
            'Evidence generated before window/evaluation time')
    if freshness['state'] == 'UNKNOWN':
        require(freshness['observedAt'] is None and freshness['sourceAgeSeconds'] is None,
                'Unknown freshness cannot claim a source age')
    else:
        require(freshness['observedAt'] is not None and freshness['maxAgeSeconds'] is not None,
                'Known freshness requires observed time and declared age policy')
        age = (as_of - instant(freshness['observedAt'])).total_seconds()
        require(age >= 0 and freshness['sourceAgeSeconds'] == age, 'Incorrect source age')
        fresh = age <= freshness['maxAgeSeconds']
        require((freshness['state'] == 'FRESH') == fresh, 'Freshness state/policy mismatch')
    quality = value['quality']
    if quality['state'] == 'VALID':
        require(quality['reason'] is None and freshness['state'] == 'FRESH' and value['coverage'] == 1,
                'Valid evidence requires complete, fresh measurements and no quality reason')
    else:
        require(bool(quality['reason']), 'Unavailable/degraded quality needs explicit reason')
    require(quality['state'] != 'STALE' or freshness['state'] == 'STALE', 'Stale quality/freshness mismatch')
    require(freshness['state'] != 'STALE' or quality['state'] == 'STALE', 'Stale evidence cannot be healthy')
    scope = value['scope']
    require(all(c in CITY_IDS for c in scope['cityIds']), 'Unknown runtime city ID')
    if scope['type'] == 'CITY':
        require(scope['id'] in CITY_IDS and scope['cityIds'] == [scope['id']], 'City scope mismatch')
        require(scope['directionId'] is None and scope['partnerId'] is None,
                'City measurement has irrelevant direction/partner dimensions')
    if metric['domain'] == 'TRANSPORT':
        require(scope['type'] == 'LINK_DIRECTION' and scope['directionId'] in ('a-to-b', 'b-to-a'),
                'Transport requires explicit directional link scope')
        parts = scope['id'].split(':')
        require(len(parts) == 2 and parts[1] == scope['directionId'], 'Link identity/direction mismatch')
        endpoints = parts[0].split('-')
        require(len(endpoints) == 2 and endpoints[0] != endpoints[1]
                and all(city.upper() in CITY_IDS and city == city.lower() for city in endpoints)
                and scope['cityIds'] == [city.upper() for city in endpoints], 'Link endpoint scope mismatch')
        require(scope['partnerId'] is None and scope['procedure'] is None,
                'Link measurement has irrelevant partner/procedure dimensions')
    if metric['domain'] == 'ROAMING':
        require(scope['type'] == 'PARTNER_DIRECTION' and scope['directionId'] in ('out', 'in')
                and scope['partnerId'] is not None and scope['procedure'] is not None,
                'Roaming requires partner/direction/procedure scope')
    if metric['domain'] in ('VOLTE', 'SMS', 'MOBILE_DATA'):
        require(scope['type'] == 'CITY', 'Service metric requires explicit city scope')
    if metric['metricId'].startswith('sms.delay.'):
        require(scope['procedure'] == 'DELIVERED_MESSAGE_DELAY', 'SMS delay requires delivered-message population')
    if metric['metricId'] in ('sms.queue', 'sms.oldestPending'):
        require(scope['procedure'] == 'PENDING_MESSAGE_SNAPSHOT', 'SMS queue requires pending-message snapshot population')
    require((value['baseline'] is None) == (value['baselineVersion'] is None),
            'Baseline value/version mismatch')
    expected_deviation = (Decimal(str(value['actual'])) - Decimal(str(value['baseline']))
                          if type(value['actual']) in (int, float) and value['baseline'] is not None else None)
    same_number(value['deviation'], expected_deviation)
    primitives = value['primitives']
    if primitives is None:
        require(value['actual'] is None and quality['state'] in ('MISSING', 'INSUFFICIENT_EVIDENCE'),
                'Absent primitives require null actual and explicit unavailable quality')
    else:
        kind = primitives['type']
        require(kind == PRIMITIVE_TYPES[metric['aggregation']], 'Incorrect primitive aggregation type')
        expected = None
        if kind == 'RATIO':
            safe_count(primitives['numerator'])
            safe_count(primitives['denominator'])
            require(primitives['numerator'] <= primitives['denominator'], 'Numerator exceeds population')
            if primitives['denominator']:
                expected = Decimal(100) * Decimal(str(primitives['numerator'])) / Decimal(str(primitives['denominator']))
        elif kind == 'SUM_COUNT':
            if metric['metricId'] != 'volte.traffic.erlang':
                safe_count(primitives['count'])
            if primitives['count']:
                expected = Decimal(str(primitives['sum'])) / Decimal(str(primitives['count']))
            else:
                require(primitives['sum'] == 0, 'Zero observations cannot carry elapsed sum')
        elif kind == 'TIME_WEIGHTED':
            require(primitives['validSeconds'] <= duration, 'Valid seconds exceed measurement window')
            if primitives['validSeconds']:
                expected = Decimal(str(primitives['weightedSum'])) / Decimal(str(primitives['validSeconds']))
            else:
                require(primitives['weightedSum'] == 0, 'Zero valid time cannot carry weighted sum')
        elif kind == 'HISTOGRAM':
            histogram = primitives['histogram']
            for count in histogram['counts']:
                safe_count(count)
            safe_count(sum(histogram['counts']))
            policy = metric['histogramSchema']
            require(histogram['schemaVersion'] == policy['version'] and histogram['edges'] == policy['edges']
                    and len(histogram['counts']) == len(histogram['edges']), 'Incompatible histogram schema/edges')
            expected = _quantile(histogram)
            if expected is not None and metric['unit'] == 'MILLISECONDS':
                expected *= 1000
        elif kind == 'SNAPSHOT':
            if metric['unit'] == 'COUNT':
                safe_count(primitives['value'])
            require(instant(primitives['snapshotAt']) < end
                    and primitives['snapshotAt'] == freshness['observedAt'], 'Snapshot time/freshness mismatch')
            expected = Decimal(str(primitives['value']))
        elif kind == 'COUNT':
            safe_count(primitives['count'])
            expected = Decimal(str(primitives['count']))
        elif kind == 'MAXIMUM':
            expected = Decimal(str(primitives['max']))
        elif kind == 'SNAPSHOT_DIFFERENCE':
            safe_count(primitives['start'])
            safe_count(primitives['end'])
            expected = Decimal(str(primitives['end'])) - Decimal(str(primitives['start']))
        elif kind == 'STATE':
            require(start <= instant(primitives['at']) < end, 'State event outside half-open window')
            expected = primitives['state']
        same_number(value['actual'], expected)
        if expected is None:
            require(quality['state'] == 'INSUFFICIENT_EVIDENCE', 'Zero population requires insufficient evidence')
        if value['unit'] == 'PERCENT' and expected is not None:
            require(0 <= expected <= 100, 'Percentage outside 0..100')
    dependency = value['dependency']
    if dependency is None:
        require(value['dependencyRelationId'] is None, 'Unbacked dependency relation')
    else:
        require(value['dependencyRelationId'] == dependency['relationId']
                and value['topologyVersion'] == dependency['topologyVersion'], 'Dependency identity/version mismatch')
        dep_start, dep_end = instant(dependency['from']), instant(dependency['to'])
        require(dep_start < dep_end and dep_start < end and dep_end > start,
                'Dependency evidence does not overlap event time')
        require(dependency['quality'] == 'VALID' and dependency['fresh'], 'Ineligible dependency evidence')


def validate_trace_link_contracts():
    dictionary = read('contracts/kpi/trace-link-kpi-definitions-v1.json')
    validate_dictionary(dictionary)
    suite = read('contracts/fixtures/trace-link/evidence-cases.json')
    require(set(suite) == {'schemaVersion', 'status', 'cases'} and suite['schemaVersion'] == 1
            and suite['status'] == 'CONTRACT_ONLY', 'Incorrect evidence fixture envelope')
    ids, evidence_ids = set(), set()
    for case in suite['cases']:
        require(set(case) == {'id', 'value'} and case['id'] not in ids, 'Duplicate/malformed evidence case')
        ids.add(case['id'])
        require(case['value']['evidenceId'] not in evidence_ids, 'Duplicate evidence ID')
        evidence_ids.add(case['value']['evidenceId'])
        validate_evidence(case['value'], dictionary)
    require(bool(ids), 'Missing Trace Link evidence fixtures')
    print(f'PASS: Trace Link {len(dictionary["metrics"])} KPI definitions and {len(ids)} evidence cases; CONTRACT_ONLY')


if __name__ == '__main__':
    validate_trace_link_contracts()
