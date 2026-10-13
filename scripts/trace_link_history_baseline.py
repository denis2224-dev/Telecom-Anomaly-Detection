"""Reproducible, reference-only seasonal fitting; never runtime source freshness.

Scenario truth is consumed exclusively here to construct a conservative global
exclusion mask. Sanitized fit samples contain no labels, scenarios or outcomes.
"""
import argparse
from collections import Counter
import csv
from functools import lru_cache
import gzip
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from importlib.resources import files
import json
import math
from pathlib import Path
from zoneinfo import ZoneInfo
import tzdata

ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = ROOT / 'contracts/baselines/trace-link-baseline-policy-v1.json'
KEY_FIELDS = {'datasetId', 'domain', 'metricId', 'scope', 'unit', 'metricPolicyVersion', 'topologyVersion'}
SCOPE_FIELDS = {'namespace', 'type', 'id', 'cityIds', 'directionId', 'partnerId', 'procedure'}


def exact(value, fields):
    if not isinstance(value, dict) or set(value) != set(fields):
        raise ValueError('Unexpected or missing fields')


def instant(value):
    if not isinstance(value, str) or not value.endswith('Z'):
        raise ValueError('UTC Z timestamp required')
    try:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError as error:
        raise ValueError('Invalid timestamp') from error
    if result.microsecond % 1000:
        raise ValueError('Millisecond timestamp precision required')
    return result


def number(value, low=None, high=None):
    if type(value) not in (float, int) or not math.isfinite(value) or (low is not None and value < low) or (high is not None and value > high):
        raise ValueError('Invalid finite number')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False)


def median(values):
    ordered = sorted(values)
    n = len(ordered)
    if not n: raise ValueError('Empty median')
    result = ordered[n // 2] if n % 2 else ordered[n // 2 - 1] / 2 + ordered[n // 2] / 2
    number(result)
    return result


@lru_cache(maxsize=1)
def definitions():
    dictionary = json.loads((ROOT / 'contracts/kpi/trace-link-kpi-definitions-v1.json').read_text(encoding='utf-8'))
    return {v['metricId']: v for v in dictionary['metrics']}


def validate_key(key):
    exact(key, KEY_FIELDS)
    exact(key['scope'], SCOPE_FIELDS)
    scope = key['scope']
    if (key['datasetId'] != 'trace-link-md-30d-v1' or key['domain'] not in ('TRANSPORT', 'ROAMING', 'MOBILE_DATA')
            or scope['namespace'] != 'trace-link-reference-v1' or scope['type'] not in ('LINK_DIRECTION', 'PARTNER_DIRECTION', 'CITY')
            or any(not isinstance(key[k], str) or not key[k] for k in KEY_FIELDS - {'scope'})
            or not isinstance(scope['id'], str) or not scope['id']
            or not isinstance(scope['cityIds'], list) or len(set(scope['cityIds'])) != len(scope['cityIds'])
            or any(not isinstance(c, str) or not c for c in scope['cityIds'])):
        raise ValueError('Invalid baseline key')
    for field in ('directionId', 'partnerId', 'procedure'):
        if scope[field] is not None and (not isinstance(scope[field], str) or not scope[field]):
            raise ValueError('Invalid scope dimension')
    if key['domain'] == 'TRANSPORT' and (scope['type'] != 'LINK_DIRECTION' or scope['directionId'] not in ('a-to-b', 'b-to-a') or scope['partnerId'] is not None or scope['procedure'] is not None):
        raise ValueError('Invalid transport scope')
    if key['domain'] == 'ROAMING' and (scope['type'] != 'PARTNER_DIRECTION' or scope['directionId'] not in ('out', 'in') or scope['partnerId'] is None or scope['procedure'] not in ('REGISTRATION', 'DATA', 'VOICE', 'SMS')):
        raise ValueError('Invalid roaming scope')
    if key['domain'] == 'MOBILE_DATA' and (scope['type'] != 'CITY' or scope['directionId'] is not None or scope['partnerId'] is not None):
        raise ValueError('Invalid mobile data scope')
    cities = {'CHI', 'BAL', 'CAH', 'UNG', 'ORH', 'SOR', 'COM', 'EDI', 'HIN', 'CAU'}
    if any(c not in cities for c in scope['cityIds']): raise ValueError('Unknown city')
    if key['domain'] == 'TRANSPORT':
        parts = scope['id'].split(':')
        endpoints = parts[0].split('-')
        if (len(parts) != 2 or parts[1] != scope['directionId'] or len(endpoints) != 2
                or endpoints[0] == endpoints[1] or any(v != v.lower() for v in endpoints)
                or [v.upper() for v in endpoints] != scope['cityIds']): raise ValueError('Invalid link identity/endpoints')
    if key['domain'] == 'MOBILE_DATA' and (scope['id'] not in cities or scope['cityIds'] != [scope['id']]): raise ValueError('Invalid city identity')
    definition = definitions().get(key['metricId'])
    if definition is None or any(definition[field] != key[field] for field in ('domain', 'unit')) or definition['policyVersion'] != key['metricPolicyVersion']:
        raise ValueError('Unknown or incompatible metric')


def validate_policy(policy):
    exact(policy, {'schemaVersion', 'baselineVersion', 'policyVersion', 'datasetId', 'topologyVersion', 'timezone', 'slotMinutes',
                   'estimator', 'minimumDistinctDays', 'minimumRateAttempts', 'minimumCoverage', 'fallback', 'peerFallbacks', 'staticCenters'})
    if (policy['schemaVersion'] != 'trace-link-baseline-policy-v1' or policy['baselineVersion'] != 'trace-link-seasonal-v1'
            or policy['policyVersion'] != 'trace-link-baseline-policy-v1' or policy['datasetId'] != 'trace-link-md-30d-v1'
            or policy['topologyVersion'] != 'trace-link-md-topology-v1' or policy['timezone'] != 'Europe/Chisinau'
            or policy['slotMinutes'] != 5 or policy['estimator'] != 'median_mad'
            or policy['fallback'] != 'peer_then_static_then_unavailable'
            or type(policy['minimumDistinctDays']) is not int or policy['minimumDistinctDays'] < 3):
        raise ValueError('Incompatible baseline policy')
    number(policy['minimumCoverage'], .9, 1)
    if type(policy['minimumRateAttempts']) is not int or policy['minimumRateAttempts'] < 1: raise ValueError('Invalid rate volume')
    if not isinstance(policy['peerFallbacks'], list): raise ValueError('Invalid peers')
    seen = set()
    for peer in policy['peerFallbacks']:
        exact(peer, {'key', 'peerKey'})
        for name in ('key', 'peerKey'): validate_key(peer[name])
        left, right = peer['key'], peer['peerKey']
        if {k: v for k, v in left.items() if k != 'scope'} != {k: v for k, v in right.items() if k != 'scope'}:
            raise ValueError('Incompatible peer')
        if {k: v for k, v in left['scope'].items() if k not in ('id', 'cityIds')} != {k: v for k, v in right['scope'].items() if k not in ('id', 'cityIds')} or left['scope']['id'] == right['scope']['id']:
            raise ValueError('Incompatible peer dimensions')
        identity = canonical(left)
        if identity in seen: raise ValueError('Duplicate peer')
        seen.add(identity)
    allowed = {'roaming.registration.success', 'roaming.data.success', 'roaming.voice.success', 'mobileData.activation.success', 'mobileData.dns.success'}
    if not isinstance(policy['staticCenters'], dict) or set(policy['staticCenters']) - allowed:
        raise ValueError('Unsupported static metric')
    for center in policy['staticCenters'].values(): number(center, 0, 100)


def local_slot(timestamp, policy):
    local = instant(timestamp).astimezone(pinned_timezone(policy['timezone']))
    return ((local.hour * 60 + local.minute) // policy['slotMinutes'],
            'WEEKEND' if local.weekday() >= 5 else 'WEEKDAY', local.date().isoformat())


@lru_cache(maxsize=4)
def pinned_timezone(name):
    if tzdata.__version__ != '2026.2': raise ValueError('History fitting requires pinned tzdata==2026.2')
    with files('tzdata').joinpath('zoneinfo', *name.split('/')).open('rb') as handle:
        return ZoneInfo.from_file(handle, key=name)


def fit(request, policy):
    validate_policy(policy)
    exact(request, {'trainingFrom', 'trainingTo', 'evaluationFrom', 'sourceManifestSha256', 'topologySnapshotSha256', 'exclusions', 'samples'})
    start, end, evaluation = (instant(request[n]) for n in ('trainingFrom', 'trainingTo', 'evaluationFrom'))
    if not start < end <= evaluation: raise ValueError('Training/evaluation overlap')
    if any(t.microsecond or int(t.timestamp()) % 300 for t in (start, end)): raise ValueError('Unaligned training bounds')
    for field in ('sourceManifestSha256', 'topologySnapshotSha256'):
        if not isinstance(request[field], str) or len(request[field]) != 64 or any(c not in '0123456789abcdef' for c in request[field]): raise ValueError('Invalid checksum')
    exclusions = []
    for item in request['exclusions']:
        exact(item, {'from', 'to', 'reason'})
        a, b = instant(item['from']), instant(item['to'])
        if not a < b or item['reason'] not in ('DECLARED_INJECTION', 'MAINTENANCE'): raise ValueError('Invalid exclusion')
        exclusions.append((a, b))
    calendar, excluded_slots = {}, 0
    at = start
    while at < end:
        to = at + timedelta(minutes=5)
        if to <= end and not any(at < b and a < to for a, b in exclusions):
            slot, weekday, date = local_slot(at.isoformat().replace('+00:00', 'Z'), policy)
            calendar.setdefault((slot, weekday), set()).add(date)
        else:
            excluded_slots += 1
        at = to
    groups, identities, excluded, accepted, missing, invalid = {}, {}, 0, 0, 0, 0
    known_keys, supplied_windows = set(), set()
    for row in request['samples']:
        exact(row, {'measurementId', 'key', 'from', 'to', 'value', 'coverage', 'eligible'})
        validate_key(row['key'])
        if row['key']['topologyVersion'] != policy['topologyVersion']: raise ValueError('Historical topology mismatch')
        if not isinstance(row['measurementId'], str) or not row['measurementId']: raise ValueError('Invalid measurement identity')
        if row['measurementId'] in identities:
            if identities[row['measurementId']] != row: raise ValueError('Conflicting measurement identity')
            continue
        identities[row['measurementId']] = row
        known_keys.add(canonical(row['key']))
        a, b = instant(row['from']), instant(row['to'])
        if not start <= a < b <= end or b - a != timedelta(minutes=5) or int(a.timestamp()) % 300 or a.microsecond:
            raise ValueError('Invalid or future training window')
        window_identity = (canonical(row['key']), a)
        if window_identity in supplied_windows: raise ValueError('Duplicate training key/window')
        supplied_windows.add(window_identity)
        number(row['coverage'], 0, 1)
        if type(row['eligible']) is not bool: raise ValueError('Invalid eligibility')
        if row['value'] is not None: number(row['value'], 0, 100 if row['key']['unit'] == 'PERCENT' else None)
        if any(a < y and x < b for x, y in exclusions): excluded += 1; continue
        if row['value'] is None: missing += 1; continue
        if not row['eligible'] or row['coverage'] < policy['minimumCoverage']: invalid += 1; continue
        accepted += 1
        slot, weekday, date = local_slot(row['from'], policy)
        identity = (canonical(row['key']), slot, weekday)
        group = groups.setdefault(identity, {'key': row['key'], 'days': {}, 'rows': 0})
        group['days'].setdefault(date, []).append((row['value'], row['coverage']))
        group['rows'] += 1
    cells = []
    for (_, slot, weekday), group in sorted(groups.items()):
        days = group['days']
        expected = len(calendar.get((slot, weekday), set()))
        coverage = len(days) / expected if expected else 0
        if len(days) < policy['minimumDistinctDays'] or coverage < policy['minimumCoverage']: continue
        values = [median(p[0] for p in points) for points in days.values()]
        center = median(values)
        cells.append(dict(key=group['key'], slot=slot, weekdayClass=weekday, center=center,
                          mad=median(abs(v - center) for v in values), coverage=coverage,
                          sampleCount=group['rows'], distinctDays=len(days), expectedDays=expected))
    expected_count = int((end-start).total_seconds() // 300) * len(known_keys)
    excluded_count = excluded_slots * len(known_keys)
    # Declared exclusions classify grid slots even when no source row was supplied.
    missing += expected_count - excluded_count - (len(identities) - excluded)
    result = dict(schemaVersion='trace-link-seasonal-baseline-v1', baselineVersion=policy['baselineVersion'],
                policyVersion=policy['policyVersion'], trainingFrom=request['trainingFrom'], trainingTo=request['trainingTo'],
                evaluationFrom=request['evaluationFrom'], timezone=policy['timezone'], slotMinutes=5, estimator='median_mad',
                estimatorConfiguration=json.loads(canonical(policy)), timezoneRulesVersion=tzdata.IANA_VERSION,
                topologyVersion=policy['topologyVersion'], sourceManifestSha256=request['sourceManifestSha256'],
                topologySnapshotSha256=request['topologySnapshotSha256'], exclusions=sorted(request['exclusions'], key=canonical),
                sampleCount=len(identities), expectedCount=expected_count,
                acceptedCount=accepted, excludedCount=excluded_count, missingCount=missing,
                invalidCount=invalid, cells=cells)
    return json.loads(canonical(result))


def validate_artifact(artifact, policy=None):
    from jsonschema import Draft202012Validator
    schema = json.loads((ROOT / 'contracts/baselines/trace-link-seasonal-baseline-v1.schema.json').read_text(encoding='utf-8'))
    errors = list(Draft202012Validator(schema).iter_errors(artifact))
    if errors: raise ValueError(str(errors[0]))
    validate_policy(artifact['estimatorConfiguration'])
    if policy is not None and artifact['estimatorConfiguration'] != policy: raise ValueError('Estimator configuration mismatch')
    config = artifact['estimatorConfiguration']
    if any(artifact[field] != config[field] for field in ('baselineVersion','policyVersion','timezone','slotMinutes','estimator','topologyVersion')):
        raise ValueError('Artifact/configuration metadata mismatch')
    if not instant(artifact['trainingFrom']) < instant(artifact['trainingTo']) <= instant(artifact['evaluationFrom']): raise ValueError('Training/evaluation overlap')
    identities = set()
    for cell in artifact['cells']:
        validate_key(cell['key'])
        if cell['key']['topologyVersion'] != artifact['topologyVersion']: raise ValueError('Topology mismatch')
        for field in ('center', 'mad', 'coverage'): number(cell[field])
        number(cell['center'], 0, 100 if cell['key']['unit'] == 'PERCENT' else None)
        identity = (canonical(cell['key']), cell['slot'], cell['weekdayClass'])
        if identity in identities or cell['distinctDays'] > cell['sampleCount'] or cell['distinctDays'] > cell['expectedDays']: raise ValueError('Invalid seasonal cell')
        if abs(cell['coverage'] - cell['distinctDays'] / cell['expectedDays']) > 1e-12: raise ValueError('Forged coverage')
        if cell['distinctDays'] < config['minimumDistinctDays'] or cell['coverage'] < config['minimumCoverage']: raise ValueError('Cell violates estimator configuration')
        identities.add(identity)


def validate_contracts():
    from jsonschema import Draft202012Validator
    policy = json.loads(POLICY_PATH.read_text(encoding='utf-8'))
    schema = json.loads(POLICY_PATH.with_suffix('.schema.json').read_text(encoding='utf-8'))
    Draft202012Validator.check_schema(schema)
    errors = list(Draft202012Validator(schema).iter_errors(policy))
    if errors: raise ValueError(str(errors[0]))
    validate_policy(policy)
    Draft202012Validator.check_schema(json.loads((ROOT / 'contracts/baselines/trace-link-seasonal-baseline-v1.schema.json').read_text(encoding='utf-8')))
    return {'baselinePolicy': policy['policyVersion']}


def verify_java_parity(path):
    """Consume Java's raw request, never its result as fitter input."""
    export = json.loads(Path(path).read_text(encoding='utf-8'))
    policy = json.loads(POLICY_PATH.read_text(encoding='utf-8'))
    actual, expected = fit(export['request'], policy), export['artifact']
    python_timezone, java_timezone = actual['timezoneRulesVersion'], expected['timezoneRulesVersion']
    # Compare every fitted cell/configuration; report runtime provenance separately.
    expected = {**expected, 'timezoneRulesVersion': python_timezone}
    maximum = 0
    def compare(left, right):
        nonlocal maximum
        if type(left) in (int, float) and type(right) in (int, float):
            number(left); number(right)
            maximum = max(maximum, abs(left - right))
            if abs(left - right) > 1e-9: raise ValueError('Java/Python numeric baseline mismatch')
        elif isinstance(left, dict) and isinstance(right, dict):
            if set(left) != set(right): raise ValueError('Java/Python baseline fields mismatch')
            for name in left: compare(left[name], right[name])
        elif isinstance(left, list) and isinstance(right, list):
            if len(left) != len(right): raise ValueError('Java/Python baseline row count mismatch')
            for a, b in zip(left, right): compare(a, b)
        elif type(left) is not type(right) or left != right:
            raise ValueError('Java/Python baseline metadata mismatch')
    compare(actual, expected)
    return {'cells': len(actual['cells']), 'maximumDifference': maximum,
            'pythonTimezoneRulesVersion': python_timezone, 'javaTimezoneRulesVersion': java_timezone,
            'timezoneRuleVersionsMatch': python_timezone == java_timezone}


def build(reference_root, policy):
    root = Path(reference_root).resolve() / 'data'
    manifest_raw = (root / 'manifest.json').read_bytes()
    manifest = json.loads(manifest_raw)
    if (manifest['dataset_id'] != policy['datasetId'] or manifest['from'] != '2026-09-10T09:00:00.000Z'
            or manifest['to_exclusive'] != '2026-10-10T09:00:00.000Z' or manifest['source_resolution_seconds'] != 300
            or manifest['synthetic'] is not True): raise ValueError('Incompatible reference manifest')
    hashes = {}
    for item in manifest['files']:
        if Path(item['name']).name != item['name']: raise ValueError('Manifest path traversal')
        raw = (root / item['name']).read_bytes()
        if sha256(raw).hexdigest() != item['sha256'] or len(raw) != item['bytes']: raise ValueError('Reference checksum mismatch')
        with (root / item['name']).open(newline='', encoding='utf-8') as handle:
            if sum(1 for _ in csv.DictReader(handle)) != item['rows']: raise ValueError('Reference row count mismatch')
        hashes[item['name']] = item['sha256']
    topology = dict(topologyVersion=policy['topologyVersion'], namespace='trace-link-reference-v1',
                    validFrom=manifest['from'], validTo=manifest['to_exclusive'], synthetic=True,
                    sourceInventory={n: hashes[n] for n in ('cities.csv', 'links.csv', 'countries.csv')})
    exclusions = []
    with (root / 'scenario_events.csv').open(newline='', encoding='utf-8') as handle:
        for row in csv.DictReader(handle):
            exclusions.append({'from': row['start_ts'], 'to': row['end_ts'], 'reason': 'DECLARED_INJECTION'})
    samples, source_keys = [], {}
    start, end = instant('2026-09-10T09:00:00Z'), instant('2026-09-30T09:00:00Z')
    def add(row, domain, scope, metric, value, eligible=True):
        when = instant(row['ts'])
        if not start <= when < end: return
        definition = definitions()[metric]
        key = dict(datasetId=policy['datasetId'], domain=domain, metricId=metric, scope=scope,
                   unit=definition['unit'], metricPolicyVersion=definition['policyVersion'], topologyVersion=policy['topologyVersion'])
        source_keys[domain + ':' + scope['id'] + ':' + metric] = key
        samples.append(dict(measurementId=domain + ':' + scope['id'] + ':' + metric + ':' + row['ts'], key=key,
                            **{'from': row['ts'], 'to': (when + timedelta(minutes=5)).isoformat().replace('+00:00', 'Z')},
                            value=value, coverage=1, eligible=eligible and value is not None))
    def scope(kind, identity, cities, direction=None, partner=None, procedure=None):
        return dict(namespace='trace-link-reference-v1', type=kind, id=identity, cityIds=cities,
                    directionId=direction, partnerId=partner, procedure=procedure)
    with (root / 'link_kpi_5m.csv').open(newline='', encoding='utf-8') as handle:
        for row in csv.DictReader(handle):
            s = scope('LINK_DIRECTION', row['link_id'] + ':a-to-b', [v.upper() for v in row['link_id'].split('-')], 'a-to-b')
            traffic, cap = float(row['traffic_gbps']), float(row['effective_capacity_gbps'])
            for metric, value in (('transport.traffic', traffic), ('transport.link.utilization', 100 * traffic / cap if cap > 0 else None), ('transport.rtt.snapshot', float(row['rtt_ms']))): add(row, 'TRANSPORT', s, metric, value)
    with (root / 'roaming_kpi_5m.csv').open(newline='', encoding='utf-8') as handle:
        for row in csv.DictReader(handle):
            for procedure, prefix, metric in [('REGISTRATION', 'reg', 'registration'), ('DATA', 'data', 'data'), ('VOICE', 'voice', 'voice')]:
                s = scope('PARTNER_DIRECTION', row['partner_id'] + ':' + row['direction'] + ':' + procedure, [], row['direction'], row['partner_id'], procedure)
                n, d = int(row[prefix + '_success']), int(row[prefix + '_attempts'])
                if not 0 <= n <= d: raise ValueError('Invalid roaming counters')
                add(row, 'ROAMING', s, 'roaming.' + metric + '.success', 100 * n / d if d > 0 else None, d >= policy['minimumRateAttempts'])
    with (root / 'city_kpi_5m.csv').open(newline='', encoding='utf-8') as handle:
        for row in csv.DictReader(handle):
            add(row, 'MOBILE_DATA', scope('CITY', row['city_id'].upper(), [row['city_id'].upper()]), 'mobileData.throughput', float(row['user_throughput_mbps']))
    request = dict(trainingFrom='2026-09-10T09:00:00Z', trainingTo='2026-09-30T09:00:00Z', evaluationFrom='2026-09-30T09:00:00Z',
                   sourceManifestSha256=sha256(manifest_raw).hexdigest(), topologySnapshotSha256=sha256(canonical(topology).encode()).hexdigest(), exclusions=exclusions, samples=samples)
    artifact = fit(request, policy)
    validate_artifact(artifact, policy)
    observed_slots = Counter()
    at = end
    while at < instant('2026-10-10T09:00:00Z'):
        slot, weekday, _ = local_slot(at.isoformat().replace('+00:00', 'Z'), policy)
        observed_slots[(slot, weekday)] += 1
        at += timedelta(minutes=5)
    learned = {(canonical(c['key']), c['slot'], c['weekdayClass']) for c in artifact['cells']}
    fallback_counts, fallback_by_metric = Counter(), {}
    for key in source_keys.values():
        identity = canonical(key)
        metric_counts = fallback_by_metric.setdefault(key['metricId'], Counter())
        for (slot, weekday), count in observed_slots.items():
            status = 'DIRECT' if (identity, slot, weekday) in learned else 'STATIC' if key['metricId'] in policy['staticCenters'] else 'BASELINE_UNAVAILABLE'
            fallback_counts[status] += count
            metric_counts[status] += count
    report = dict(schemaVersion='trace-link-history-fit-report-v1', referenceOnly=True, sourceFreshness='UNKNOWN',
                  historicalEligibility='STRUCTURAL_ONLY_NOT_RUNTIME_FRESHNESS', exclusionPolicy='GLOBAL_INTERVAL_OVER_EXCLUSION_NO_GUESSED_TAILS',
                  maintenanceExclusions=[], omittedMetrics=['transport.packetLoss', 'transport.rtt.mean', 'mobileData.activation.success', 'mobileData.dns.success'],
                  trainingFrom=request['trainingFrom'], trainingTo=request['trainingTo'], validationTo='2026-10-05T09:00:00Z', heldOutTo='2026-10-10T09:00:00Z',
                  sourceFiles=hashes, topologySnapshot=topology,
                  counts={field: artifact[field] for field in ('expectedCount', 'sampleCount', 'acceptedCount', 'excludedCount', 'missingCount', 'invalidCount')},
                  cellCount=len(artifact['cells']), artifactSchemaValidated=True,
                  coverageMinimum=min((c['coverage'] for c in artifact['cells']), default=None),
                  fallbackPolicy=policy['fallback'], peerMappings=len(policy['peerFallbacks']),
                  fallbackLookupInterval={'from':request['evaluationFrom'], 'to':'2026-10-10T09:00:00Z'},
                  fallbackLookupCounts=dict(fallback_counts), fallbackLookupCountsByMetric={k:dict(v) for k,v in fallback_by_metric.items()},
                  staticCenters=policy['staticCenters'])
    report['timezoneRulesVersion'] = tzdata.IANA_VERSION
    report['timezoneDataPackageVersion'] = tzdata.__version__
    report['timezoneDataSource'] = 'PINNED_TZDATA_PACKAGE_BYTES_NOT_HOST_ZONEINFO'
    report['estimatorConfiguration'] = json.loads(canonical(policy))
    return artifact, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference-root', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    policy = json.loads(POLICY_PATH.read_text(encoding='utf-8'))
    artifact, report = build(args.reference_root, policy)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    raw = (canonical(artifact) + '\n').encode()
    published = gzip.compress(raw, mtime=0) if args.output.suffix == '.gz' else raw
    args.output.write_bytes(published)
    report['artifactSha256'] = sha256(raw).hexdigest()
    report['publishedSha256'] = sha256(published).hexdigest()
    report['artifactBytes'] = len(raw)
    report['publishedBytes'] = len(published)
    report['compression'] = 'gzip-mtime0' if args.output.suffix == '.gz' else 'none'
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    print(json.dumps({'cells': len(artifact['cells']), 'sha256': report['artifactSha256']}))


if __name__ == '__main__': main()
