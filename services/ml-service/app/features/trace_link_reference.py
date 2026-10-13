"""Independent Day 1 primitive arithmetic; no inference or V2 policy changes.

The calculator accepts only an operation, its primitive inputs and a UTC window.
The CLI reads expected values solely to detect infrastructure/validity mismatches;
it never uses them to calculate a result.
"""
import argparse
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re

MAX_SAFE = 9007199254740991
VOICE_EDGES = [.5, 1, 2, 3, 5, 8, 15, 30, 60]
SMS_EDGES = [1, 2, 5, 10, 20, 40, 80, 160, 320]
CITY_SUM_FIELDS = ('attempts', 'success', 'drops', 'holding', 'setupSum', 'smsSubmitted',
                   'smsDelivered', 'smsExpired', 'dataAttempts', 'dataSuccess',
                   'dnsAttempts', 'dnsSuccess', 'hoAttempts', 'hoSuccess')
CITY_VALUES = ('voiceSR', 'dropRate', 'erlang', 'setupMeanSeconds', 'setupP95Seconds',
               'smsP95Ms', 'smsSR', 'dataSR', 'dnsSR', 'hoSR', 'throughputMbps')
ROAM_COUNTERS = ('regAtt', 'regOk', 'dataAtt', 'dataOk', 'voiceAtt', 'voiceOk', 'smsAtt', 'smsOk')


class InvalidInput(ValueError):
    pass


def require(condition):
    if not condition:
        raise InvalidInput('INVALID_INPUT')


def keys(value, wanted):
    require(isinstance(value, dict) and set(value) == set(wanted))


def array(value, nonempty=False):
    require(isinstance(value, list) and (not nonempty or bool(value)))
    return value


def text(value):
    require(isinstance(value, str) and bool(value.strip()))
    return value


def number(value, positive=False):
    require(isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) and (value > 0 if positive else value >= 0))
    return value


def counter(value):
    number(value)
    require(value <= MAX_SAFE and value == math.floor(value))
    return int(value)


def safe_sum(values):
    value = sum(values)
    require(math.isfinite(value) and (not isinstance(value, int) or abs(value) <= MAX_SAFE))
    return value


def reject_truth(value):
    if isinstance(value, dict):
        for key, child in value.items():
            require(isinstance(key, str))
            normalized = re.sub('[^a-z]', '', key.lower())
            require(key in ('expectedSeconds', 'expectedRows') or
                    not any(word in normalized for word in ('truth', 'expected', 'future', 'scenario', 'outcome')))
            reject_truth(child)
    elif isinstance(value, list):
        for child in value:
            reject_truth(child)
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        require(math.isfinite(value))


def timestamp(value):
    text(value)
    require(bool(re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z', value)))
    try:
        return datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError as exc:
        raise InvalidInput('INVALID_INPUT') from exc


def aligned(value):
    ts = timestamp(value)
    fraction = value.partition('.')[2].removesuffix('Z')
    require(ts.second == 0 and ts.microsecond == 0 and ts.minute % 5 == 0
            and (not fraction or not int(fraction)))
    return ts


def ratio(numerator, denominator):
    return None if denominator == 0 else 100 * numerator / denominator


def quantile(counts, edges):
    total = sum(counts)
    if total == 0:
        return None
    target, accumulated, low = .95 * total, 0, 0
    for count, high in zip(counts, edges):
        if count and accumulated + count >= target:
            return low + (target - accumulated) / count * (high - low)
        accumulated += count
        low = high
    raise InvalidInput('INVALID_INPUT')


def result(primitives, values, quality='VALID', reason=None, coverage=1, snapshot=None):
    require(math.isfinite(coverage) and 0 <= coverage <= 1)
    return {'primitives': primitives, 'values': values, 'quality': quality,
            'nullReason': reason, 'coverage': coverage, 'snapshotAt': snapshot}


def calc_ratio(raw, start, end):
    keys(raw, ('partitions',))
    numerators, denominators = [], []
    for row in array(raw['partitions']):
        keys(row, ('numerator', 'denominator'))
        n, d = counter(row['numerator']), counter(row['denominator'])
        require(n <= d)
        numerators.append(n)
        denominators.append(d)
    n, d = safe_sum(numerators), safe_sum(denominators)
    return result({'numerator': n, 'denominator': d}, {'value': ratio(n, d)},
                  'VALID' if d else 'INSUFFICIENT_DATA', None if d else 'ZERO_DENOMINATOR')


def calc_histogram(raw, start, end):
    keys(raw, ('histograms', 'outputUnit'))
    require(raw['outputUnit'] in ('SECONDS', 'MILLISECONDS'))
    histograms = array(raw['histograms'], nonempty=True)
    reference, pooled = None, None
    for row in histograms:
        keys(row, ('counts', 'edges', 'unit', 'schemaVersion'))
        counts, edges = array(row['counts'], True), array(row['edges'], True)
        require(len(counts) == len(edges) and row['unit'] in ('SECONDS', 'MILLISECONDS'))
        text(row['schemaVersion'])
        validated_counts = [counter(x) for x in counts]
        validated_edges = [number(x, positive=True) for x in edges]
        require(all(a < b for a, b in zip(validated_edges, validated_edges[1:])))
        signature = (validated_edges, row['unit'], row['schemaVersion'])
        if reference is None:
            reference, pooled = signature, [0] * len(counts)
        require(signature == reference)
        pooled = [safe_sum((a, b)) for a, b in zip(pooled, validated_counts)]
    total = safe_sum(pooled)
    p95 = quantile(pooled, reference[0])
    if p95 is not None and reference[1] != raw['outputUnit']:
        p95 *= 1000 if raw['outputUnit'] == 'MILLISECONDS' else .001
    return result({'counts': pooled, 'edges': reference[0], 'sampleCount': total}, {'p95': p95},
                  'VALID' if total else 'INSUFFICIENT_DATA', None if total else 'EMPTY_HISTOGRAM')


def calc_utilization(raw, start, end):
    keys(raw, ('segments', 'expectedSeconds'))
    expected = number(raw['expectedSeconds'], positive=True)
    valid, traffic_time, utilization_time, peak, any_stale = 0, 0, 0, None, False
    for row in array(raw['segments']):
        keys(row, ('trafficGbps', 'effectiveCapacityGbps', 'validSeconds', 'quality'))
        traffic, seconds = number(row['trafficGbps']), number(row['validSeconds'], positive=True)
        capacity = row['effectiveCapacityGbps']
        if capacity is not None:
            number(capacity, positive=True)
            require(traffic <= capacity)
        require(row['quality'] in ('VALID', 'MISSING', 'STALE'))
        any_stale |= row['quality'] == 'STALE'
        if row['quality'] == 'VALID' and capacity is not None:
            pct = 100 * traffic / capacity
            valid += seconds
            traffic_time += traffic * seconds
            utilization_time += pct * seconds
            peak = pct if peak is None else max(peak, pct)
    require(all(math.isfinite(x) for x in (valid, traffic_time, utilization_time)))
    coverage = valid / expected
    primitives = {'validSeconds': valid, 'trafficTime': traffic_time, 'utilizationTime': utilization_time}
    values = {'meanPct': utilization_time / valid if valid else None, 'peakPct': peak,
              'trafficMeanGbps': traffic_time / valid if valid else None,
              'transferredGbEstimate': traffic_time if valid else None}
    if not valid:
        return result(primitives, values, 'STALE' if any_stale else 'INSUFFICIENT_DATA',
                      'STALE_SOURCE' if any_stale else 'MISSING_CAPACITY', 0)
    return result(primitives, values, 'PARTIAL' if coverage < 1 else 'VALID',
                  'PARTIAL_COVERAGE' if coverage < 1 else None, coverage)


def calc_queue(raw, start, end):
    fields = ('openingQueue', 'submitted', 'delivered', 'terminalNegative', 'closingQueue', 'durationSeconds')
    keys(raw, fields)
    primitives = {field: counter(raw[field]) for field in fields[:-1]}
    primitives['durationSeconds'] = number(raw['durationSeconds'], positive=True)
    require(raw['closingQueue'] == raw['openingQueue'] + raw['submitted'] - raw['delivered'] - raw['terminalNegative'])
    finalized = safe_sum((primitives['delivered'], primitives['terminalNegative']))
    growth = primitives['closingQueue'] - primitives['openingQueue']
    return result(primitives, {'growth': growth, 'growthPerSecond': growth / raw['durationSeconds'],
                               'finalizedSuccessPct': ratio(raw['delivered'], finalized)},
                  'VALID' if finalized else 'INSUFFICIENT_DATA', None if finalized else 'ZERO_DENOMINATOR')


def calc_city(raw, start, end):
    keys(raw, ('rows', 'expectedRows'))
    expected = counter(raw['expectedRows'])
    require(expected > 0)
    rows = array(raw['rows'])
    require(len(rows) <= expected)
    totals = {name: 0 for name in CITY_SUM_FIELDS}
    voice, sms, slots, unique, latest, throughput = [0] * 9, [0] * 9, set(), set(), {}, 0
    fields = set(CITY_SUM_FIELDS) | {'cityId', 'ts', 'smsQueue', 'smsOldest', 'throughput', 'voiceCounts', 'smsCounts'}
    for row in rows:
        keys(row, fields)
        city, ts = text(row['cityId']), aligned(row['ts'])
        require(start <= ts < end and (city, ts) not in unique)
        unique.add((city, ts))
        slots.add(ts)
        for name in CITY_SUM_FIELDS:
            value = float(number(row[name])) if name in ('holding', 'setupSum') else counter(row[name])
            totals[name] = safe_sum((totals[name], value))
        for good, attempts in (('success', 'attempts'), ('drops', 'success'), ('dataSuccess', 'dataAttempts'),
                               ('dnsSuccess', 'dnsAttempts'), ('hoSuccess', 'hoAttempts')):
            require(row[good] <= row[attempts])
        vc, sc = array(row['voiceCounts']), array(row['smsCounts'])
        require(len(vc) == 9 and len(sc) == 9)
        vc, sc = [counter(x) for x in vc], [counter(x) for x in sc]
        require(sum(vc) == row['success'] and sum(sc) == row['smsDelivered'])
        voice = [safe_sum((a, b)) for a, b in zip(voice, vc)]
        sms = [safe_sum((a, b)) for a, b in zip(sms, sc)]
        throughput += number(row['throughput'])
        counter(row['smsQueue'])
        number(row['smsOldest'])
        if city not in latest or ts > latest[city][0]:
            latest[city] = (ts, row)
    require(math.isfinite(throughput))
    totals.update(voiceCounts=voice, smsCounts=sms, throughputSum=throughput, rowCount=len(rows),
                  slotCount=len(slots), queue=safe_sum(item[1]['smsQueue'] for item in latest.values()),
                  oldest=max((item[1]['smsOldest'] for item in latest.values()), default=0))
    if not rows:
        return result(totals, dict.fromkeys(CITY_VALUES), 'MISSING', 'NO_OBSERVATIONS', 0)
    values = {'voiceSR': ratio(totals['success'], totals['attempts']),
              'dropRate': ratio(totals['drops'], totals['success']),
              'erlang': totals['holding'] / (len(slots) * 300),
              'setupMeanSeconds': totals['setupSum'] / totals['success'] if totals['success'] else None,
              'setupP95Seconds': quantile(voice, VOICE_EDGES),
              'smsP95Ms': quantile(sms, SMS_EDGES) * 1000 if sum(sms) else None,
              'smsSR': ratio(totals['smsDelivered'], totals['smsDelivered'] + totals['smsExpired']),
              'dataSR': ratio(totals['dataSuccess'], totals['dataAttempts']),
              'dnsSR': ratio(totals['dnsSuccess'], totals['dnsAttempts']),
              'hoSR': ratio(totals['hoSuccess'], totals['hoAttempts']),
              'throughputMbps': throughput / len(rows)}
    snapshot = max(latest.values(), key=lambda item: item[0])[1]['ts']
    coverage = len(rows) / expected
    if len({item[0] for item in latest.values()}) > 1:
        totals.update(queue=None, oldest=None)
        return result(totals, values, 'PARTIAL', 'SNAPSHOT_MISALIGNED', coverage, None)
    return result(totals, values, 'PARTIAL' if coverage < 1 else 'VALID',
                  'PARTIAL_COVERAGE' if coverage < 1 else None, coverage, snapshot)


def calc_link(raw, start, end):
    keys(raw, ('linkId', 'cityId', 'at', 'dataFrom', 'dataTo', 'maxAgeSeconds', 'rows'))
    link, city = text(raw['linkId']), text(raw['cityId'])
    data_from, data_to = timestamp(raw['dataFrom']), timestamp(raw['dataTo'])
    require(data_from < data_to)
    age_limit = number(raw['maxAgeSeconds'])
    at = timestamp(raw['at']) if raw['at'] is not None else None
    selected, selected_ts, unique = None, None, set()
    for row in array(raw['rows']):
        keys(row, ('linkId', 'a', 'b', 'ts', 'trafficGbps', 'effectiveCapacityGbps'))
        row_link, a, b, ts = text(row['linkId']), text(row['a']), text(row['b']), aligned(row['ts'])
        require(a != b and (row_link, ts) not in unique)
        unique.add((row_link, ts))
        number(row['trafficGbps'])
        if row['effectiveCapacityGbps'] is not None:
            number(row['effectiveCapacityGbps'], positive=True)
            require(row['trafficGbps'] <= row['effectiveCapacityGbps'])
        time_match = ts <= at if at is not None else start <= ts < end
        if row_link == link and (city == 'all' or city in (a, b)) and time_match and data_from <= ts < data_to:
            if selected_ts is None or ts > selected_ts:
                selected, selected_ts = row, ts
    outside = not data_from <= at < data_to if at is not None else start < data_from or end > data_to
    absent = {'trafficGbps': None, 'effectiveCapacityGbps': None}
    if outside:
        return result(absent, {'utilizationPct': None, 'ageSeconds': None}, 'MISSING', 'OUTSIDE_DATASET', 0)
    if selected is None:
        return result(absent, {'utilizationPct': None, 'ageSeconds': None}, 'MISSING', 'NO_OBSERVATIONS', 0)
    primitives = {key: selected[key] for key in absent}
    age = ((at or end) - selected_ts).total_seconds()
    values = {'utilizationPct': None, 'ageSeconds': age}
    if age > age_limit:
        return result(primitives, values, 'STALE', 'STALE_SOURCE', 0, selected['ts'])
    if selected['effectiveCapacityGbps'] is None:
        return result(primitives, values, 'INSUFFICIENT_DATA', 'MISSING_CAPACITY', 0, selected['ts'])
    values['utilizationPct'] = 100 * selected['trafficGbps'] / selected['effectiveCapacityGbps']
    return result(primitives, values, snapshot=selected['ts'])


def calc_roaming(raw, start, end):
    keys(raw, ('country', 'direction', 'cityShare', 'maxAgeSeconds', 'rows'))
    country = text(raw['country'])
    require(country == 'all' or bool(re.fullmatch('[A-Z]{2}', country)))
    require(raw['direction'] in ('out', 'in', 'both'))
    share, age_limit = number(raw['cityShare'], positive=True), number(raw['maxAgeSeconds'])
    require(share <= 1)
    totals = dict.fromkeys(ROAM_COUNTERS, 0)
    latest, unique = {}, set()
    for row in array(raw['rows']):
        keys(row, set(ROAM_COUNTERS) | {'countryId', 'partnerId', 'direction', 'ts', 'users'})
        require(bool(re.fullmatch('[A-Z]{2}', text(row['countryId']))) and row['direction'] in ('out', 'in'))
        partner, ts = text(row['partnerId']), aligned(row['ts'])
        key = (row['countryId'], partner, row['direction'])
        require((key, ts) not in unique)
        unique.add((key, ts))
        counter(row['users'])
        for field in ROAM_COUNTERS:
            counter(row[field])
        for procedure in ('reg', 'data', 'voice', 'sms'):
            require(row[procedure + 'Ok'] <= row[procedure + 'Att'])
        if (country == 'all' or row['countryId'] == country) and \
                (raw['direction'] == 'both' or row['direction'] == raw['direction']) and start <= ts < end:
            for field in ROAM_COUNTERS:
                totals[field] = safe_sum((totals[field], int(row[field])))
            if key not in latest or ts > latest[key][0]:
                latest[key] = (ts, row)
    totals['users'] = safe_sum(int(item[1]['users']) for item in latest.values())
    if not latest:
        return result(totals, dict.fromkeys(('users', 'regSR', 'dataSR', 'voiceSR', 'smsSR')),
                      'MISSING', 'NO_OBSERVATIONS', 0)
    values = {procedure + 'SR': ratio(totals[procedure + 'Ok'], totals[procedure + 'Att'])
              for procedure in ('reg', 'data', 'voice', 'sms')}
    stale = any((end - item[0]).total_seconds() > age_limit for item in latest.values())
    def allocate(users):
        product = users * share
        lower = math.floor(product)
        return lower + (1 if product - lower >= .5 else 0)
    values['users'] = None if stale else safe_sum(allocate(item[1]['users']) for item in latest.values())
    if not stale and len({item[0] for item in latest.values()}) > 1:
        totals['users'] = values['users'] = None
        return result(totals, values, 'PARTIAL', 'SNAPSHOT_MISALIGNED', 0, None)
    snapshot = max(latest.values(), key=lambda item: item[0])[1]['ts']
    return result(totals, values, 'STALE' if stale else 'VALID', 'STALE_SOURCE' if stale else None,
                  0 if stale else 1, snapshot)


OPERATIONS = {'ratio': calc_ratio, 'histogram': calc_histogram, 'utilization': calc_utilization,
              'queue': calc_queue, 'city_summary': calc_city, 'link_snapshot': calc_link,
              'roaming_summary': calc_roaming}


def calculate(operation, raw, window):
    """Return six-field result or exactly INVALID_INPUT; never consume truth."""
    try:
        reject_truth(raw)
        reject_truth(window)
        keys(window, ('from', 'to'))
        start, end = timestamp(window['from']), timestamp(window['to'])
        require(start < end and isinstance(operation, str) and operation in OPERATIONS)
        output = OPERATIONS[operation](raw, start, end)
        # Overflow of derived arithmetic is invalid rather than JSON Infinity.
        reject_truth(output)
        return output
    except (InvalidInput, OverflowError):
        return {'error': 'INVALID_INPUT'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixtures', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    content = args.fixtures.read_bytes()
    suite = json.loads(content)
    require(suite['suiteVersion'] == 'trace-link-arithmetic-v1' and bool(suite['cases']))
    results, contexts = {}, {}
    for case in suite['cases']:
        require(case['id'] not in results)
        calculated = calculate(case['operation'], case['input'], case['window'])
        require(('error' in calculated) == ('expectedError' in case))
        results[case['id']] = calculated
        contexts[case['id']] = {key: case[key] for key in ('scope', 'window', 'policyVersion', 'units')}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'fixtureSha256': hashlib.sha256(content).hexdigest(),
                                      'results': results, 'contexts': contexts}, indent=2, allow_nan=False) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
