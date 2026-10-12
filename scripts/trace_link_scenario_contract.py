"""Independent arithmetic for isolated 60-second synthetic measurements and evaluator fixtures."""
import argparse
import hashlib
import json
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'tests/evaluation/trace-link'
GENERATOR = 'trace-link-minute-generator-v1'
DATASET = 'trace-link-live-synthetic-v1'
ID_FIELDS = ('datasetId', 'sourceId', 'scopeNamespace', 'domain', 'scopeType', 'scopeId',
             'directionId', 'direction', 'partnerId', 'countryId', 'procedure', 'accessTechnology',
             'metricId', 'windowStart', 'sourceResolutionSeconds', 'schemaVersion',
             'catalogueVersion', 'topologyVersion', 'policyVersion')
FORBIDDEN = {'scenarioType', 'expectedFaultLabel', 'injectedProbableCause', 'futureIncidentOutcome',
             'scenarioTruthId', 'groundTruthTimeline', 'evaluationPassFail', 'expectedTransition',
             'runId', 'features', 'projectionMessage'}
METRICS = {'TRANSPORT': ['transport.traffic', 'transport.effectiveCapacity', 'transport.link.utilization'],
           'ROAMING': ['roaming.registration.success'],
           'MOBILE_DATA': ['mobileData.dns.success', 'mobileData.activation.success']}


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'), parse_float=Decimal)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def no_truth(value):
    if isinstance(value, dict):
        require(not FORBIDDEN.intersection(value), 'Evaluator truth entered detector payload')
        for child in value.values():
            no_truth(child)
    elif isinstance(value, list):
        for child in value:
            no_truth(child)


def identity(e):
    return hashlib.sha256('|'.join(str(e.get(f, '-')) for f in ID_FIELDS).encode()).hexdigest()


def value(e):
    p = e['primitives']
    if e['quality'] == 'MISSING':
        return None
    if e['aggregationType'] == 'RATIO_OF_SUMS':
        return Decimal(100) * p['numerator'] / p['denominator'] if p['denominator'] else None
    if e['aggregationType'] == 'TIME_WEIGHTED_MEAN':
        return Decimal(p['weightedSum']) / p['validSeconds'] if p['validSeconds'] else None
    return p['value']


def validate_scenarios(path=None):
    suite = read(path or FIXTURES / 'domain-measurements-v1.json')
    schema = read(ROOT / 'contracts/observations/trace-link-domain-window-v1.schema.json')
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    dictionary = {m['metricId']: m for m in read(ROOT / 'contracts/kpi/trace-link-kpi-definitions-v1.json')['metrics']}
    require(len(suite) == 9, 'Expected three profiles per domain')
    count = 0
    for case in suite:
        c, windows = case['command'], case['windows']
        s, params = c['scope'], c['parameters']
        require(len(windows) == 8, 'Expected eight windows')
        scope_key = '|'.join(str(s[f]) if s[f] is not None else '-'
                             for f in ('domain', 'id', 'direction', 'country', 'procedure', 'topologyVersion'))
        seen = set()
        for ordinal, window in enumerate(windows):
            time = (datetime.fromisoformat(c['start']) + timedelta(minutes=ordinal)).isoformat().replace('+00:00', 'Z')
            fault = 2 <= ordinal < 5 and c['type'] != 'NORMAL_CONTROL'
            missing = ordinal in params['missingMinutes'] or fault and c['type'] == 'TELEMETRY_GAP'
            require([e['metricId'] for e in window] == METRICS[s['domain']], 'Wrong independent metric set')
            h = hashlib.sha256(f"{GENERATOR}|{c['version']}|{c['seed']}|{scope_key}|{time}".encode()).hexdigest()
            for e in window:
                validator.validate(e)
                no_truth(e)
                require(e['datasetId'] == DATASET and e['sourceId'] == f"{GENERATOR}-seed-{c['seed']}"
                        and e['sourceResolutionSeconds'] == 60 and e['windowStart'] == time, 'Source/time isolation mismatch')
                require(e['eventId'] == identity(e) and e['eventId'] not in seen, 'Unstable/shared identity')
                seen.add(e['eventId'])
                m = dictionary[e['metricId']]
                require(all(e[k] == m[k] for k in ('domain', 'population', 'unit', 'policyVersion'))
                        and e['aggregationType'] == m['aggregation'], 'Dictionary mismatch')
                p = e['primitives']
                if missing:
                    require(e['quality'] == 'MISSING' and e['coverage'] == 0 and all(v is None for v in p.values())
                            and value(e) is None, 'Missing evidence manufactured recovery')
                elif s['domain'] == 'TRANSPORT':
                    bp = params['degradedUtilizationBp'] if fault and c['type'] == 'TRANSPORT_LINK_OVERLOAD' else params['normalUtilizationBp']
                    bp = max(0, bp - int(h[:4], 16) % 51)
                    traffic = Decimal(params['capacityGbps']) * bp / 10000
                    t = e['transport']
                    require(t['trafficGbps'] == traffic and t['effectiveCapacityGbps'] == params['capacityGbps'], 'Traffic/capacity arithmetic')
                    expected = traffic if e['metricId'] == 'transport.traffic' else Decimal(bp) / 100 if e['metricId'] == 'transport.link.utilization' else Decimal(params['capacityGbps'])
                    require(value(e) == expected and e['quality'] == 'VALID', 'Valid-time arithmetic')
                else:
                    variation = int(hashlib.sha256(f"{h}|{e['metricId']}".encode()).hexdigest()[:4], 16) % 51
                    attempts = params['attempts'] + variation if params['attempts'] else 0
                    impaired = fault and (c['type'] == 'ROAMING_REGISTRATION_FAILURE'
                                         or c['type'] == 'MOBILE_DATA_DNS_FAILURE' and e['metricId'] == 'mobileData.dns.success')
                    bp = params['degradedSuccessBp'] if impaired else params['healthySuccessBp']
                    require(p == {'numerator': attempts * bp // 10000, 'denominator': attempts}, 'Independent population arithmetic')
                    require(e['quality'] == ('VALID' if attempts else 'INSUFFICIENT_EVIDENCE'), 'Zero population quality')
                count += 1
    manifest = read(ROOT / 'contracts/datasets/trace-link-live-synthetic-v1.json')
    require(manifest['datasetId'] == DATASET and manifest['sourceResolutionSeconds'] == 60
            and manifest['kafka'] == 'DISABLED' and manifest['consumer'] == 'PENDING_REVIEW', 'Activation/namespace boundary')
    return count


def validate_transitions():
    schedule = read(FIXTURES / 'transition-schedule-v1.json')
    observations = [json.loads(line) for line in (FIXTURES / 'transition-observations-v1.jsonl').read_text().splitlines()]
    truth = read(FIXTURES / 'transition-expectations-v1.json')
    require(schedule['acceptance'] == 'PENDING' and len(observations) == len(truth) == len(schedule['steps']) == 12,
            'Transition acceptance must remain pending')
    for step, e, expected in zip(schedule['steps'], observations, truth):
        no_truth(e)
        require(expected == {'observationId': e['eventId'], 'expectedTransition': step['expectation'], 'acceptance': 'PENDING'}, 'Truth association mismatch')
    require(observations[6] == observations[8], 'Duplicate replay must preserve exact content')
    require(observations[5]['quality'] == 'MISSING' and value(observations[5]) is None, 'Gap must stay unknown')
    require(value(observations[4]) < value(observations[3]) < value(observations[6]), 'Expected measured worsening/recovery')
    return len(truth)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-output', type=Path)
    args = parser.parse_args()
    print(f'PASS: {validate_scenarios(args.java_output)} independent minute measurements; {validate_transitions()} evaluator expectations PENDING')
