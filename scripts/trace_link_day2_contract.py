"""Strict offline context/result boundaries; no runtime activation."""
from datetime import timedelta
import math
try:
    from .trace_link_contract import read, require, shape, instant, validate_evidence
except ImportError:
    from trace_link_contract import read, require, shape, instant, validate_evidence


def validate_scope(scope, domain):
    cities = {'CHI', 'BAL', 'CAH', 'UNG', 'ORH', 'SOR', 'COM', 'EDI', 'HIN', 'CAU'}
    require(scope['namespace'] == 'trace-link-reference-v1' and all(c in cities for c in scope['cityIds']), 'Unknown scope')
    if domain == 'TRANSPORT':
        parts = scope['id'].split(':')
        require(scope['type'] == 'LINK_DIRECTION' and len(parts) == 2
                and parts[1] == scope['directionId'] and parts[1] in ('a-to-b', 'b-to-a'), 'Invalid directional link')
        ends = parts[0].split('-')
        require(len(ends) == 2 and ends[0] != ends[1] and scope['cityIds'] == [e.upper() for e in ends]
                and all(e == e.lower() for e in ends) and scope['partnerId'] is None
                and scope['procedure'] is None, 'Invalid link endpoints')
    elif domain == 'ROAMING':
        require(scope['type'] == 'PARTNER_DIRECTION' and scope['directionId'] in ('in', 'out')
                and scope['partnerId'] is not None and scope['procedure'] in ('REGISTRATION', 'DATA', 'VOICE'), 'Invalid roaming scope')
    else:
        require(scope['type'] == 'CITY' and scope['id'] in cities and scope['cityIds'] == [scope['id']]
                and scope['directionId'] is None and scope['partnerId'] is None, 'Invalid city scope')


def validate_context(value, baseline_policy=None):
    if baseline_policy is None:
        baseline_policy = read('contracts/baselines/trace-link-baseline-policy-v1.json')
    shape('contracts/baselines/trace-link-baseline-policy-v1.schema.json', baseline_policy)
    shape('contracts/detections/trace-link-evaluation-v1.schema.json', value)
    start, end = instant(value['window']['from']), instant(value['window']['to'])
    require(end - start == timedelta(seconds=300) and start.timestamp() % 300 == 0
            and value['window']['sourceResolutionSeconds'] == 300, 'Expected aligned five-minute window')
    require(instant(value['evaluatedAt']) >= end, 'Evaluation precedes finalized window')
    require(value['scope']['namespace'] == value['namespace'], 'Scope namespace mismatch')
    validate_scope(value['scope'], value['domain'])
    metrics = {m['metricId']: m for m in read('contracts/kpi/trace-link-kpi-definitions-v1.json')['metrics']}
    seen = set()
    for evidence in value['evidence']:
        validate_evidence(evidence)
        if metrics[evidence['metricId']]['domain'] == 'ROAMING' and evidence['metricId'].endswith('.success'):
            require(evidence['scope']['procedure'] == evidence['metricId'].split('.')[1].upper(), 'Metric procedure mismatch')
        if evidence['primitives'] and evidence['primitives']['type'] == 'SNAPSHOT':
            require(start <= instant(evidence['primitives']['snapshotAt']) < end, 'Snapshot outside its window')
        if evidence['metricId'] == 'mobileData.activation.success' and evidence['provenance']['origin'] != 'HAND_CALCULATED':
            require(evidence['primitives'] is None, 'Reference EPS eligibility is unproven')
        if evidence['unit'] == 'PERCENT' and evidence['baseline'] is not None:
            require(0 <= evidence['baseline'] <= 100, 'Percentage evidence baseline outside range')
        require(evidence['metricId'] not in seen, 'Duplicate metric evidence')
        seen.add(evidence['metricId'])
        require(metrics[evidence['metricId']]['domain'] == value['domain'], 'Metric domain mismatch')
        require(evidence['scope'] == value['scope'] and evidence['window'] == value['window']
                and evidence['topologyVersion'] == value['topologyVersion']
                and evidence['observedRevision'] == value['revision']
                and evidence['provenance']['datasetId'] == value['datasetId']
                and evidence['freshness']['asOf'] == value['evaluatedAt'], 'Evidence context mismatch')
    for metric_id, baseline in value['baselines'].items():
        require(metric_id in metrics and metrics[metric_id]['domain'] == value['domain'], 'Baseline metric mismatch')
        require(baseline['baselineVersion'] == baseline_policy['baselineVersion']
                and baseline['policyVersion'] == baseline_policy['policyVersion'], 'Baseline policy/version mismatch')
        require(instant(baseline['trainingFrom']) < instant(baseline['trainingTo']) <= start, 'Baseline training leakage')
        unavailable = baseline['status'] == 'BASELINE_UNAVAILABLE'
        require((baseline['center'] is None) == unavailable, 'Baseline status/center mismatch')
        if unavailable:
            require(baseline['sourceScope'] is None and baseline['coverage'] == 0, 'Unavailable lookup has source/coverage')
        else:
            require(type(baseline['mad']) in (int, float), 'Available lookup requires MAD')
            require(baseline['sourceScope'] is not None, 'Available lookup requires source scope')
            validate_scope(baseline['sourceScope'], value['domain'])
            if baseline['status'] == 'STATIC':
                require(metric_id in baseline_policy['staticCenters'] and baseline['center'] == baseline_policy['staticCenters'][metric_id]
                        and baseline['mad'] == 0 and baseline['coverage'] == 0 and baseline['sourceScope'] == value['scope']
                        and baseline['reason'] == 'STATIC_DEMONSTRATION_POLICY', 'Incompatible static fallback')
            else:
                require(baseline['coverage'] >= baseline_policy['minimumCoverage'], 'Learned baseline coverage too low')
        if metrics[metric_id]['unit'] == 'PERCENT' and baseline['center'] is not None:
            require(0 <= baseline['center'] <= 100, 'Percentage baseline outside range')
        require(baseline['mad'] is None if unavailable else True, 'Unavailable baseline has spread')
        if unavailable or baseline['status'] == 'STATIC':
            require(bool(baseline['reason']), 'Baseline fallback requires reason')
        else:
            require(baseline['reason'] is None, 'Baseline reason mismatch')
        if baseline['status'] == 'DIRECT':
            require(baseline['sourceScope'] == value['scope'], 'Direct baseline scope mismatch')
        elif baseline['status'] == 'PEER':
            peer = baseline['sourceScope']
            require(peer is not None and all(peer[k] == value['scope'][k]
                    for k in ('namespace', 'type', 'directionId', 'partnerId', 'procedure')), 'Incompatible peer baseline')
            require(peer['id'] != value['scope']['id'], 'Peer cannot be the same scope')
            requested = dict(datasetId=value['datasetId'], domain=value['domain'], metricId=metric_id, scope=value['scope'],
                             unit=metrics[metric_id]['unit'], metricPolicyVersion=metrics[metric_id]['policyVersion'], topologyVersion=value['topologyVersion'])
            require(any(p['key'] == requested and p['peerKey'] == {**requested, 'scope': peer} for p in baseline_policy['peerFallbacks']),
                    'Undeclared peer baseline')
        for evidence in value['evidence']:
            if evidence['metricId'] == metric_id and evidence['baseline'] is not None:
                require(not unavailable and evidence['baseline'] == baseline['center']
                        and evidence['baselineVersion'] == baseline['baselineVersion'], 'Evidence/lookup baseline mismatch')


def validate_result(value):
    shape('contracts/detections/trace-link-rule-result-v1.schema.json', value)
    deviation = value.get('baselineDeviation')
    if deviation is not None:
        require(type(deviation) in (int, float) and (type(deviation) is int or math.isfinite(deviation)),
                'Baseline deviation must be a finite number')
    require((value['severity'] is not None) == (value['verdict'] == 'BREACH'), 'Verdict/severity mismatch')
    require(value['scope']['namespace'] == value['namespace'], 'Result namespace mismatch')
    validate_scope(value['scope'], value['domain'])
    require(value['anomalyKind'].startswith(value['domain'] + '_'), 'Anomaly kind/domain mismatch')
    if value['domain'] == 'ROAMING':
        require(value['anomalyKind'] == 'ROAMING_' + value['scope']['procedure'], 'Result procedure mismatch')
    if 'serviceScope' in value:
        kind = value['anomalyKind']
        require(value['domain'] == 'TRANSPORT' and kind.startswith('TRANSPORT_SERVICE_'), 'Invalid mapped service result')
        domain = 'ROAMING' if kind.startswith('TRANSPORT_SERVICE_ROAMING_') else 'MOBILE_DATA' if kind.startswith('TRANSPORT_SERVICE_MOBILE_DATA_') else None
        require(domain is not None, 'Unknown mapped service domain')
        validate_scope(value['serviceScope'], domain)
        if domain == 'ROAMING':
            require(kind == 'TRANSPORT_SERVICE_ROAMING_' + value['serviceScope']['procedure'], 'Mapped service procedure mismatch')
    else:
        require(not value['anomalyKind'].startswith('TRANSPORT_SERVICE_'), 'Missing service scope')
    require(instant(value['window']['to']) - instant(value['window']['from']) == timedelta(seconds=300)
            and instant(value['window']['from']).timestamp() % 300 == 0
            and value['window']['sourceResolutionSeconds'] == 300
            and instant(value['evaluatedAt']) >= instant(value['window']['to']),
            'Result window mismatch')
    require(value['verdict'] != 'BREACH' or bool(value['evidenceIds']), 'Unbacked breach')


def validate_day2_contracts():
    from jsonschema import Draft202012Validator, FormatChecker
    try:
        from .trace_link_contract import ROOT
    except ImportError:
        from trace_link_contract import ROOT
    pairs = [('policies/trace-link-domain-rules-v1.schema.json', 'policies/trace-link-domain-rules-v1.json'),
             ('baselines/trace-link-baseline-policy-v1.schema.json', 'baselines/trace-link-baseline-policy-v1.json')]
    for schema, data in pairs:
        validator = Draft202012Validator(read('contracts/' + schema), format_checker=FormatChecker())
        validator.check_schema(validator.schema)
        validator.validate(read('contracts/' + data))
    suite = read('contracts/fixtures/trace-link/detection-cases.json')
    ids = set()
    for case in suite['cases']:
        require(case['id'] not in ids, 'Duplicate Day2 case')
        ids.add(case['id'])
        validate_context(case['context'])
    require(bool(ids), 'Missing Day2 detection fixtures')
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012
    registry = Registry().with_resources((p.resolve().as_uri(), Resource.from_contents(
        __import__('json').loads(p.read_text(encoding='utf-8')), default_specification=DRAFT202012))
        for p in (ROOT / 'contracts').rglob('*.schema.json'))
    cause_schema = (ROOT / 'contracts/explanations/trace-link-cause-input-v1.schema.json').resolve().as_uri()
    validator = Draft202012Validator({'$ref': cause_schema}, registry=registry, format_checker=FormatChecker())
    causes = read('contracts/fixtures/trace-link/cause-control-cases.json')['cases']
    cause_ids = set()
    for case in causes:
        require(case['id'] not in cause_ids, 'Duplicate cause fixture')
        cause_ids.add(case['id'])
        validator.validate(case['input'])
        validate_result(case['input']['symptom'])
    print(f'PASS: {len(causes)} strict raw-candidate cause fixtures (negative evidence retained for rejection)')
    print(f'PASS: Trace Link Day 2 offline policies and {len(ids)} validated contexts')
