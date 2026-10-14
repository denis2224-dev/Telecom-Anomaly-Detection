"""Independent five-minute primitive vectors. No labels, imputation or model I/O."""
from copy import deepcopy
from datetime import datetime
from functools import lru_cache
import json
import math
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[4]
VERSION = 'trace-link-features-v1'


@lru_cache(maxsize=None)
def contract(path):
    return json.loads((ROOT / 'contracts' / path).read_text(encoding='utf-8'))


def validate(path, value):
    try:
        validator(path).validate(value)
        finite(value)
    except Exception as error:
        raise ValueError('INVALID_INPUT: ' + str(error).splitlines()[0]) from error


@lru_cache(maxsize=None)
def validator(path):
    return Draft202012Validator(contract(path), format_checker=FormatChecker())


def finite(value):
    if isinstance(value, dict):
        for child in value.values(): finite(child)
    elif isinstance(value, list):
        for child in value: finite(child)
    elif isinstance(value, float) and not math.isfinite(value):
        raise ValueError('NONFINITE_INPUT')


def instant(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def actual(primitives):
    kind = primitives.get('type')
    if kind == 'RATIO':
        n, d = primitives['numerator'], primitives['denominator']
        if n > d: raise ValueError('INVALID_POPULATION')
        return 100 * n / d if d else None
    if kind in ('SUM_COUNT', 'TIME_WEIGHTED'):
        n, d = (primitives['sum'], primitives['count']) if kind == 'SUM_COUNT' else (primitives['weightedSum'], primitives['validSeconds'])
        return n / d if d else None
    if kind == 'SNAPSHOT': return primitives['value']
    return None


def build_features(context):
    validate('detections/trace-link-evaluation-v1.schema.json', context)
    order = contract('features/trace-link-feature-order-v1.json')['domains'][context['domain']]
    start, end = instant(context['window']['from']), instant(context['window']['to'])
    if ((end - start).total_seconds() != 300 or start.timestamp() % 300 or instant(context['evaluatedAt']) < end
            or context['window']['sourceResolutionSeconds'] != 300):
        raise ValueError('INVALID_WINDOW')
    if context['scope']['type'] != {'TRANSPORT':'LINK_DIRECTION','ROAMING':'PARTNER_DIRECTION','MOBILE_DATA':'CITY'}[context['domain']]:
        raise ValueError('INVALID_SCOPE')
    evidence = {}
    for item in context['evidence']:
        metric = item['metricId']
        if metric in evidence: raise ValueError('DUPLICATE_METRIC')
        if (item['scope'] != context['scope'] or item['window'] != context['window']
                or item['topologyVersion'] != context['topologyVersion'] or item['observedRevision'] != context['revision']
                or item['provenance']['datasetId'] != context['datasetId'] or item['freshness']['asOf'] != context['evaluatedAt']):
            raise ValueError('EVIDENCE_CONTEXT_MISMATCH')
        evidence[metric] = item
    values, available = [], set()
    for spec in order['metrics']:
        metric = spec['metricId']
        item = evidence.get(metric)
        fields = dict.fromkeys(contract('features/trace-link-feature-order-v1.json')['components'])
        if item:
            if item['unit'] != spec['unit']: raise ValueError('UNIT_MISMATCH')
            fresh = item['freshness']
            fields.update(coverage=item['coverage'], sourceAgeSeconds=fresh['sourceAgeSeconds'])
            valid = item['quality']['state'] == 'VALID' and fresh['state'] == 'FRESH' and item['coverage'] >= .9
            if valid:
                if (fresh['observedAt'] is None or instant(fresh['observedAt']) > instant(context['evaluatedAt'])
                        or fresh['sourceAgeSeconds'] > fresh['maxAgeSeconds']
                        or abs((instant(context['evaluatedAt'])-instant(fresh['observedAt'])).total_seconds()-fresh['sourceAgeSeconds'])>1e-9):
                    raise ValueError('INVALID_FRESHNESS')
                p = item['primitives']
                if set(p)-{'type'} != set(spec['primitiveFields']):
                    raise ValueError('PRIMITIVE_POPULATION_MISMATCH')
                if p['type']=='SUM_COUNT' and p['count']!=math.floor(p['count']):
                    raise ValueError('INVALID_POPULATION')
                if p['type']=='TIME_WEIGHTED' and p['validSeconds']>300:
                    raise ValueError('INVALID_VALID_TIME')
                value = actual(p)
                if value is not None:
                    available.add(metric)
                    fields['actual'] = value
                    for component in ('numerator', 'denominator', 'sum', 'count'):
                        fields[component] = p.get(component)
                    baseline = context['baselines'].get(metric)
                    if baseline and baseline['status'] != 'BASELINE_UNAVAILABLE':
                        if (baseline['baselineVersion'] != 'trace-link-seasonal-v1'
                                or baseline['policyVersion'] != 'trace-link-baseline-policy-v1'
                                or instant(baseline['trainingTo']) > start
                                or instant(baseline['trainingFrom']) >= instant(baseline['trainingTo'])
                                or (baseline['status'] in ('DIRECT','STATIC') and baseline['sourceScope'] != context['scope'])
                                or baseline['center'] is None):
                            raise ValueError('INCOMPATIBLE_BASELINE')
                        center = baseline['center']
                        fields['residual'] = (value - center) / max(abs(center), 1)
        for component in contract('features/trace-link-feature-order-v1.json')['components']:
            value = fields[component]
            values.extend((value, int(value is not None)))
    eligible = bool(available.intersection(order['requiredAny']))
    result = {k: deepcopy(context[k]) for k in ('datasetId', 'domain', 'scope', 'window', 'revision', 'topologyVersion', 'evaluatedAt', 'evidence', 'baselines')}
    result.update(featureSchemaVersion=VERSION, featureNames=order['featureNames'], featureValues=values,
                  eligible=eligible, reasonCodes=[] if eligible else ['NO_VALID_FRESH_REQUIRED_METRIC'])
    validate('features/trace-link-feature-window-v1.schema.json', result)
    return result
