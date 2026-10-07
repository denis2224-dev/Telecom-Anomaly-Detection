"""Pure version-2 features from canonical observations; no training or inference."""

import copy
from datetime import datetime, timedelta
from functools import lru_cache
from hashlib import sha256
import json
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
if str(ROOT / 'scripts') not in sys.path:
    sys.path.insert(0, str(ROOT / 'scripts'))
from scripts.observation_contract import ObservationBatch, SCOPES, read_json
from scripts.geography_contract import authority_scopes, resolve, validate_catalogue
from jsonschema import Draft202012Validator, FormatChecker

ORDER = read_json(ROOT / 'contracts/features/feature-order-v2.json')
TOPOLOGY = read_json(ROOT / 'contracts/topology/demo-scopes-v2.json')
OUTPUT = Draft202012Validator(
    read_json(ROOT / 'contracts/features/service-feature-window-v2.schema.json'),
    format_checker=FormatChecker())


class FeatureContext:
    """A captured authority snapshot; callers opt into city inputs explicitly."""
    def __init__(self, topology=TOPOLOGY, catalogue=None):
        self._topology = copy.deepcopy(topology)
        self._catalogue = copy.deepcopy(catalogue if catalogue is not None else read_json(
            ROOT / 'contracts/geography/demo-geography-v1.json'))
        self._geographic = read_json(ROOT / 'contracts/topology/geographic-scopes-v2.json')
        validate_catalogue(self._catalogue, self._geographic)
        if self._topology not in (TOPOLOGY, self._geographic):
            raise ValueError('Unreviewed feature topology')
        self.scopes = authority_scopes(self._topology)
        self.topology_version = self._topology['topologyVersion']

    @lru_cache(maxsize=64)
    def role_node(self, scope, role):
        target = resolve(self._catalogue, self._geographic, scope, role)
        if target not in self.scopes[scope]['nodes']:
            raise ValueError('Feature role/source authority mismatch')
        return target['nodeId']


DEFAULT_CONTEXT = FeatureContext()


def _ratio(numerator, denominator, scale=1):
    return scale * numerator / denominator if denominator else None


def _delta(observed, baseline):
    return observed - baseline if observed is not None and baseline is not None else None


def _baseline_values(context, raw, scopes):
    start = datetime.fromisoformat(raw['windowStart'])
    if (context['scopeId'] != raw['scopeId'] or context['service'] != raw['service']
            or context['hourOfWeek'] != start.weekday() * 24 + start.hour):
        raise ValueError('Baseline scope/service/UTC hour does not match observation')
    if not isinstance(context['baselineVersion'], str) or not context['baselineVersion'].strip():
        raise ValueError('Missing baseline catalogue version')
    values = context['values']
    if context['status'] == 'BASELINE_MISSING':
        if values or context['sourceScopeId'] is not None:
            raise ValueError('Missing baseline cannot carry substitute values')
        return {}
    if context['status'] not in ('DIRECT', 'PEER'):
        raise ValueError('Unknown baseline status')
    if context['status'] == 'DIRECT' and context['sourceScopeId'] != raw['scopeId']:
        raise ValueError('Direct baseline must belong to this scope')
    if context['status'] == 'PEER':
        peer = scopes.get(context['sourceScopeId'])
        if peer is None or peer['service'] != raw['service'] or context['sourceScopeId'] == raw['scopeId']:
            raise ValueError('Peer baseline must identify another scope of the same service')
    expected = {'cssrPct', 'rrcSrPct', 'bearerSrPct'} if raw['service'] == 'VOLTE' else {'p95DeliveryMs', 'deliverySrPct'}
    if set(values) != expected:
        raise ValueError('Baseline metrics do not match service')
    for name, value in values.items():
        if (type(value) not in (int, float) or not math.isfinite(value) or value < 0
                or (name.endswith('Pct') and value > 100)):
            raise ValueError('Invalid baseline measurement: ' + name)
    return values


def build_features(observation, node_observations, baseline, *, context=DEFAULT_CONTEXT):
    """Return a canonical window from a resolved BaselineRegistry context.

    Valid but unaligned node intervals are ignored, unauthorized input is rejected.
    COMPLETE describes service coverage; missing nodes independently disable ML.
    """
    json.dumps([observation, node_observations, baseline], allow_nan=False)
    batch = ObservationBatch(context.scopes)
    batch.accept(observation)
    if observation['kind'] != 'SERVICE':
        raise ValueError('Expected SERVICE observation')
    return _build(observation, node_observations, baseline, context, batch, {observation['eventId']})


def build_missing_features(scope, start, end, node_observations, baseline, *, context=DEFAULT_CONTEXT):
    """Inferred SERVICE absence: no fabricated observation or service event ID."""
    beginning, ending = datetime.fromisoformat(start), datetime.fromisoformat(end)
    if (beginning.utcoffset() != timedelta(0) or beginning.second or beginning.microsecond
            or ending - beginning != timedelta(minutes=1)):
        raise ValueError('Expected one aligned UTC minute')
    service = context.scopes[scope]['service']
    envelope = dict(scopeId=scope, service=service, windowStart=start, windowEnd=end, quality='MISSING')
    json.dumps([envelope, node_observations, baseline], allow_nan=False)
    return _build(envelope, node_observations, baseline, context, ObservationBatch(context.scopes), set())


def _build(observation, node_observations, baseline, context, batch, source_ids):
    values = _baseline_values(baseline, observation, context.scopes)
    nodes = {}
    for node in node_observations:
        batch.accept(node)
        if node['kind'] != 'NODE':
            raise ValueError('Expected NODE evidence')
        if (node['quality'] == 'COMPLETE'
                and all(node[k] == observation[k] for k in ('scopeId', 'windowStart', 'windowEnd'))):
            nodes[node['nodeId']] = node
    m = observation.get('metrics', {}) if observation['quality'] == 'COMPLETE' else {}
    kpis = []

    def kpi(name, observed, unit, numerator=None, denominator=None):
        kpis.append(dict(name=name, observed=observed, baseline=values.get(name), unit=unit,
                         numerator=numerator, denominator=denominator))
        return observed

    def rate(name, successes, attempts):
        numerator, denominator = m.get(successes), m.get(attempts)
        observed = _ratio(numerator, denominator, 100) if m else None
        return kpi(name, observed, 'PERCENT', numerator, denominator)

    def node_metric(node_id, name):
        node = nodes.get(node_id)
        value = node['metrics'].get(name) if node else None
        if value is not None:
            source_ids.add(node['eventId'])
        return value

    if observation['service'] == 'VOLTE':
        eligible = m['attempts'] - m['userOutcomes'] if m else None
        cssr = kpi('cssrPct', _ratio(m['technicalSuccesses'], eligible, 100) if m else None,
                   'PERCENT', m.get('technicalSuccesses'), eligible)
        kpi('eligibleAttempts', eligible, 'COUNT')
        sip = kpi('sip503Ratio', _ratio(m['sip503Count'], eligible) if m else None,
                  'RATIO', m.get('sip503Count'), eligible)
        kpi('sip503Count', m.get('sip503Count'), 'COUNT')
        rrc = rate('rrcSrPct', 'rrcSuccesses', 'rrcAttempts')
        bearer = rate('bearerSrPct', 'bearerSuccesses', 'bearerAttempts')
        loss = kpi('packetLossRatio', node_metric(context.role_node(observation['scopeId'], 'VOLTE_TRANSPORT'), 'packetLossRatio'), 'RATIO')
        cpu = kpi('imsCpuPct', node_metric(context.role_node(observation['scopeId'], 'VOLTE_IMS'), 'cpuPct'), 'PERCENT')
        vector = [_delta(cssr, values.get('cssrPct')), sip, _delta(rrc, values.get('rrcSrPct')),
                  _delta(bearer, values.get('bearerSrPct')), loss, cpu]
    else:
        samples = m.get('deliveryDelayMs', [])
        # ponytail: sort <=10,000 demo samples; use mergeable histograms for production streams.
        p95 = kpi('p95DeliveryMs', sorted(samples)[math.ceil(.95 * len(samples)) - 1] if samples else None,
                  'MILLISECONDS')
        sr = rate('deliverySrPct', 'deliverySuccesses', 'deliveryAttempts')
        delivered = kpi('deliveredMessages', m.get('deliveredMessages'), 'COUNT')
        smsc = context.role_node(observation['scopeId'], 'SMS_SMSC')
        depth = kpi('queueDepth', node_metric(smsc, 'queueDepth'), 'COUNT')
        age = kpi('oldestPendingAgeSec', node_metric(smsc, 'oldestPendingAgeSeconds'), 'SECONDS')
        ratio = _ratio(p95, values.get('p95DeliveryMs')) if p95 is not None else None
        vector = [ratio, p95, depth, age, _delta(sr, values.get('deliverySrPct')), delivered]
    bounds = OUTPUT.schema['properties']['featureValues']['items']
    eligible = observation['quality'] == 'COMPLETE' and all(
        v is not None and bounds['minimum'] <= v <= bounds['maximum'] for v in vector)
    identity = [observation['scopeId'], observation['windowStart'], ORDER['featureVersion']]
    result = {key: observation[key] for key in ('scopeId', 'service', 'windowStart', 'windowEnd', 'quality')}
    result.update(schemaVersion=2, featureVersion=ORDER['featureVersion'],
                  windowId=sha256(json.dumps(identity, separators=(',', ':')).encode()).hexdigest(),
                  baselineVersion=baseline['baselineVersion'], topologyVersion=context.topology_version,
                  kpis=kpis, featureNames=ORDER['models'][observation['service']][:] if eligible else [],
                  featureValues=vector if eligible else [], mlEligible=eligible,
                  sourceEventIds=sorted(source_ids))
    OUTPUT.validate(result)
    return result
