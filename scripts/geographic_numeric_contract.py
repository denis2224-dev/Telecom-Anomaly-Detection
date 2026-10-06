"""Offline Day 1 arithmetic oracle. Does not activate producers, APIs or detection."""

from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def decimal(value):
    require(type(value) in (int, float, str, Decimal), 'Expected numeric measurement')
    try:
        result = Decimal(str(value))
    except InvalidOperation as error:
        raise ValueError('Expected numeric measurement') from error
    require(result.is_finite() and result >= 0, 'Expected finite nonnegative measurement')
    return result


def compatible(partitions, service, unit):
    require(bool(partitions), 'No partitions')
    first, footprints, scopes = partitions[0], set(), set()
    for p in partitions:
        require(p['service'] == service and p['unit'] == unit, 'Incompatible service/unit')
        require(all(p[k] == first[k] for k in ('windowStart', 'windowEnd', 'topologyVersion', 'catalogueVersion')),
                'Incompatible window/version')
        start, end = datetime.fromisoformat(p['windowStart']), datetime.fromisoformat(p['windowEnd'])
        require(p['windowStart'].endswith('Z') and p['windowEnd'].endswith('Z')
                and start.second == 0 and start.microsecond == 0 and end - start == timedelta(seconds=60),
                'Expected aligned UTC minute')
        members = p['footprintNodeIds']
        require(members and len(members) == len(set(members)) and not footprints.intersection(members)
                and p['scopeId'] not in scopes, 'Overlapping footprint/scope')
        footprints.update(members)
        scopes.add(p['scopeId'])


def aggregate_voice(partitions):
    compatible(partitions, 'VOLTE', 'PERCENT')
    successes, denominator, expected = 0, 0, Decimal(0)
    complete, baseline_available = True, True
    for p in partitions:
        require(p['quality'] in {'COMPLETE', 'INCOMPLETE', 'MISSING'}, 'Invalid measurement quality')
        if p['quality'] != 'COMPLETE':
            complete = False
            continue
        for field in ('attempts', 'userOutcomes', 'technicalSuccesses', 'technicalFailures'):
            require(type(p[field]) is int and 0 <= p[field] <= 9007199254740991, 'Expected bounded integer counter')
        d = p['attempts'] - p['userOutcomes']
        require(d >= 0 and d == p['technicalSuccesses'] + p['technicalFailures'], 'Inconsistent technical counters')
        successes += p['technicalSuccesses']
        denominator += d
        if p['baselinePct'] is None:
            baseline_available = False
        else:
            b = decimal(p['baselinePct'])
            require(b <= 100, 'Baseline outside 0..100')
            expected += Decimal(d) * b / 100
    observed = Decimal(100) * successes / denominator if denominator else None
    baseline_reason = ('PARTIAL_COVERAGE' if not complete else 'BASELINE_MISSING' if not baseline_available
                       else 'ZERO_DENOMINATOR' if not denominator else None)
    baseline = 100 * expected / denominator if baseline_reason is None else None
    delta = observed - baseline if observed is not None and baseline is not None else None
    reasons = {}
    if observed is None:
        reasons['observedPct'] = 'PARTIAL_COVERAGE' if not complete else 'ZERO_DENOMINATOR'
    if baseline_reason:
        reasons.update({k: baseline_reason for k in ('baselinePct', 'deltaPp', 'detectorDropPp', 'extraFailedAttempts')})
    return dict(coverage='COMPLETE' if complete else 'PARTIAL', numerator=successes, denominator=denominator,
                observedPct=observed, baselinePct=baseline, deltaPp=delta,
                detectorDropPp=-delta if delta is not None else None,
                extraFailedAttempts=max(Decimal(0), expected - successes) if baseline_reason is None else None,
                uniqueSubscribers=None, nullReasons=reasons)


def aggregate_sms_p95(partitions):
    compatible(partitions, 'SMS', 'MILLISECONDS')
    require(all(p['quality'] in {'COMPLETE', 'INCOMPLETE', 'MISSING'} for p in partitions), 'Invalid measurement quality')
    if any(p['quality'] != 'COMPLETE' for p in partitions):
        return dict(p95DeliveryMs=None, sampleCount=None, nullReason='PARTIAL_COVERAGE')
    if any(p['samples'] is None for p in partitions):
        if len(partitions) == 1 and partitions[0]['p95DeliveryMs'] is not None:
            p = partitions[0]
            decimal(p['p95DeliveryMs'])
            require(type(p['sampleCount']) is int and p['sampleCount'] > 0, 'Invalid sample count')
            return dict(p95DeliveryMs=p['p95DeliveryMs'], sampleCount=p['sampleCount'], nullReason=None)
        return dict(p95DeliveryMs=None, sampleCount=None, nullReason='NOT_AGGREGATABLE')
    samples = [decimal(x) for p in partitions for x in p['samples']]
    # Integer nearest-rank arithmetic avoids interpolation and float index drift.
    p95 = sorted(samples)[(95 * len(samples) + 99) // 100 - 1] if samples else None
    return dict(p95DeliveryMs=p95, sampleCount=len(samples), nullReason=None if samples else 'INSUFFICIENT_DATA')


def validate_day1_numeric_contracts():
    suite = json.loads((ROOT / 'contracts/fixtures/geography/numeric-cases-v1.json').read_text(encoding='utf-8'))
    ids = set()
    for cases, calculate in [(suite['voiceCases'], aggregate_voice), (suite['smsCases'], aggregate_sms_p95)]:
        for case in cases:
            require(case['id'] not in ids, 'Duplicate numeric case')
            ids.add(case['id'])
            actual = calculate(case['partitions'])
            require(actual.keys() == case['expected'].keys(), 'Expected fields mismatch: ' + case['id'])
            for key, expected in case['expected'].items():
                value = actual[key]
                valid = abs(value - Decimal(str(expected))) <= Decimal('1e-9') if isinstance(value, Decimal) and expected is not None else value == expected
                require(valid, 'Numeric golden mismatch: ' + case['id'] + '/' + key)
    print(f'PASS: {len(suite["voiceCases"])} geographic voice and {len(suite["smsCases"])} SMS numeric reference cases')


if __name__ == '__main__':
    validate_day1_numeric_contracts()
