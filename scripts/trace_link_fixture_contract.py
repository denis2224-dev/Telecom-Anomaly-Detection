"""Validate the independent Trace Link arithmetic suite, not exported implementation output."""
from datetime import datetime
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUITE = ROOT / 'contracts/fixtures/trace-link/arithmetic-cases.json'
OPERATIONS = {'ratio', 'histogram', 'utilization', 'queue', 'city_summary', 'link_snapshot', 'roaming_summary'}
RESULT_FIELDS = {'primitives', 'values', 'quality', 'nullReason', 'coverage', 'snapshotAt'}
SHAPES = {
    'ratio': ('numerator denominator', 'value', 'numerator denominator'),
    'histogram': ('counts edges sampleCount', 'p95', 'sampleCount'),
    'utilization': ('validSeconds trafficTime utilizationTime', 'meanPct peakPct trafficMeanGbps transferredGbEstimate', ''),
    'queue': ('openingQueue submitted delivered terminalNegative closingQueue durationSeconds', 'growth growthPerSecond finalizedSuccessPct', 'openingQueue submitted delivered terminalNegative closingQueue'),
    'city_summary': ('attempts success drops holding setupSum smsSubmitted smsDelivered smsExpired dataAttempts dataSuccess dnsAttempts dnsSuccess hoAttempts hoSuccess voiceCounts smsCounts throughputSum rowCount slotCount queue oldest',
                     'voiceSR dropRate erlang setupMeanSeconds setupP95Seconds smsP95Ms smsSR dataSR dnsSR hoSR throughputMbps',
                     'attempts success drops smsSubmitted smsDelivered smsExpired dataAttempts dataSuccess dnsAttempts dnsSuccess hoAttempts hoSuccess rowCount slotCount queue'),
    'link_snapshot': ('trafficGbps effectiveCapacityGbps', 'utilizationPct', ''),
    'roaming_summary': ('regAtt regOk dataAtt dataOk voiceAtt voiceOk smsAtt smsOk users', 'users regSR dataSR voiceSR smsSR', 'regAtt regOk dataAtt dataOk voiceAtt voiceOk smsAtt smsOk users'),
}
UNIT_OVERRIDES = {
    'utilization': {'trafficMeanGbps': 'GBPS', 'transferredGbEstimate': 'GIGABITS'},
    'queue': {'growth': 'COUNT', 'growthPerSecond': 'COUNT_PER_SECOND'},
    'city_summary': {'erlang': 'ERLANG', 'setupMeanSeconds': 'SECONDS', 'setupP95Seconds': 'SECONDS',
                     'smsP95Ms': 'MILLISECONDS', 'throughputMbps': 'MBPS'},
    'roaming_summary': {'users': 'COUNT'},
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_suite():
    return json.loads(SUITE.read_text(encoding='utf-8'))


def finite_json(value):
    if isinstance(value, dict):
        for item in value.values():
            finite_json(item)
    elif isinstance(value, list):
        for item in value:
            finite_json(item)
    elif isinstance(value, float):
        require(math.isfinite(value), 'Non-finite JSON number')


def utc(value):
    require(isinstance(value, str) and value.endswith('Z'), 'Expected UTC timestamp')
    try:
        return datetime.fromisoformat(value)
    except ValueError as error:
        raise ValueError('Invalid UTC timestamp') from error


def validate_aggregates(operation, expected):
    primitive_names, value_names, counters = (set(words.split()) for words in SHAPES[operation])
    primitives, values = expected['primitives'], expected['values']
    require(isinstance(primitives, dict) and set(primitives) == primitive_names, 'Invalid primitive fields')
    require(isinstance(values, dict) and set(values) == value_names, 'Invalid calculated value fields')
    nullable = {'city_summary': {'queue', 'oldest'}, 'link_snapshot': primitive_names,
                'roaming_summary': {'users'}}.get(operation, set())
    for key, value in primitives.items():
        if value is None:
            require(key in nullable, 'Unexpected null primitive')
        elif key in {'counts', 'voiceCounts', 'smsCounts', 'edges'}:
            require(isinstance(value, list) and len(value) == 9, 'Expected nine histogram bins')
            for item in value:
                require(type(item) in (int, float) and item >= 0, 'Invalid histogram number')
                if key != 'edges':
                    require(type(item) is int and item <= 9007199254740991, 'Invalid histogram count')
        else:
            require(type(value) in (int, float) and value >= 0, 'Invalid numeric primitive')
            if key in counters:
                require(type(value) is int and value <= 9007199254740991, 'Invalid integer primitive')
    for key, value in values.items():
        require(value is None or type(value) in (int, float), 'Invalid calculated number')
        if value is not None and ((operation == 'queue' and key == 'growth')
                                  or (operation == 'roaming_summary' and key == 'users')):
            require(type(value) is int and abs(value) <= 9007199254740991, 'Invalid integer result')


def validate_suite(suite):
    require(suite.get('suiteVersion') == 'trace-link-arithmetic-v1', 'Unknown suite version')
    require(isinstance(suite.get('cases'), list) and suite['cases'], 'Empty arithmetic suite')
    ids, covered, invalid = set(), set(), set()
    for case in suite['cases']:
        base = {'id', 'operation', 'scope', 'window', 'policyVersion', 'units', 'input'}
        fields = set(case)
        require(fields == base | {'expected'} or fields == base | {'expectedError'}, 'Invalid case shape')
        require(isinstance(case['id'], str) and case['id'] and case['id'] not in ids, 'Duplicate/empty case ID')
        ids.add(case['id'])
        op = case['operation']
        require(op in OPERATIONS, 'Unknown operation')
        require(isinstance(case['units'], dict) and set(case['units']) == set(SHAPES[op][1].split()), 'Missing output unit metadata')
        require(all(unit in {'PERCENT', 'SECONDS', 'MILLISECONDS', 'COUNT', 'COUNT_PER_SECOND', 'GBPS', 'GIGABITS', 'ERLANG', 'MBPS'}
                    for unit in case['units'].values()), 'Unknown output unit')
        if op == 'histogram':
            require(case['units']['p95'] in {'SECONDS', 'MILLISECONDS'}, 'Invalid histogram unit')
        else:
            require(case['units'] == {key: UNIT_OVERRIDES.get(op, {}).get(key, 'PERCENT')
                                      for key in SHAPES[op][1].split()}, 'Operation unit mismatch')
        require(isinstance(case['policyVersion'], str) and bool(case['policyVersion']), 'Missing policy')
        require(set(case['scope']) == {'type', 'id'} and all(isinstance(v, str) and v for v in case['scope'].values()), 'Invalid scope')
        require(set(case['window']) == {'from', 'to'}, 'Invalid window')
        require(all(isinstance(v, str) for v in case['window'].values()), 'Invalid timestamp type')
        # Deliberately malformed windows are calculator-negative inputs, not valid contexts.
        if 'expectedError' not in case:
            if op == 'histogram':
                require(case['units']['p95'] == case['input'].get('outputUnit'), 'Histogram output unit mismatch')
            start, end = (utc(case['window'][key]) for key in ('from', 'to'))
            require(start < end and start.minute % 5 == end.minute % 5 == 0
                    and start.second == end.second == start.microsecond == end.microsecond == 0,
                    'Expected positive aligned five-minute window')
        require(isinstance(case['input'], dict), 'Missing primitive input')
        finite_json(case)
        if 'expectedError' in case:
            require(case['expectedError'] == 'INVALID_INPUT', 'Unknown error')
            invalid.add(op)
            continue
        covered.add(op)
        expected = case['expected']
        require(set(expected) == RESULT_FIELDS, 'Unexpected result fields')
        validate_aggregates(op, expected)
        require(expected['quality'] in {'VALID', 'PARTIAL', 'STALE', 'MISSING', 'INSUFFICIENT_DATA'}, 'Invalid quality')
        require(type(expected['coverage']) in (int, float) and 0 <= expected['coverage'] <= 1, 'Invalid coverage')
        require(expected['nullReason'] is None or isinstance(expected['nullReason'], str), 'Invalid null reason')
        require((expected['quality'] == 'VALID') == (expected['nullReason'] is None), 'Quality/reason mismatch')
        if expected['snapshotAt'] is not None:
            utc(expected['snapshotAt'])
    require(covered == OPERATIONS, 'Missing positive operation coverage')
    require(invalid == OPERATIONS, 'Missing negative operation coverage')
    return len(ids)


def validate_trace_link_fixture_contracts():
    suite = read_suite()
    count = validate_suite(suite)
    histogram = json.loads((SUITE.parent / 'histogram-parity.json').read_text(encoding='utf-8'))
    require(histogram['suiteVersion'] == 'trace-link-histogram-v1'
            and histogram['sourceSuite'] == SUITE.name, 'Histogram suite mismatch')
    require(histogram['caseIds'] == [c['id'] for c in suite['cases'] if c['operation'] == 'histogram'],
            'Missing histogram cases')
    require(histogram['voiceEdgesSeconds'] == [.5, 1, 2, 3, 5, 8, 15, 30, 60]
            and histogram['smsEdgesSeconds'] == [1, 2, 5, 10, 20, 40, 80, 160, 320], 'Unversioned histogram edges')
    print(f'PASS: {count} independent Trace Link goldens, all seven operations and negative controls')


if __name__ == '__main__':
    validate_trace_link_fixture_contracts()
