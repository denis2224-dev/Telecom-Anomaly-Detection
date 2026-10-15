"""Generate evaluation-only inputs and separate truth; never fit or score a model."""
import argparse
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import gzip
from hashlib import sha256
import json
from pathlib import Path
import subprocess

from trace_link_candidate import ROOT, source_context, canonical

VERSION = 'trace-link-day5-study-v1'
DOMAINS = {
    'TRANSPORT': {'loss': ('transport.packetLoss', 'TRANSPORT_PACKET_LOSS'),
                  'latency': ('transport.rtt.mean', 'TRANSPORT_LATENCY')},
    'ROAMING': {'data': ('roaming.data.success', 'ROAMING_DATA'),
                'registration': ('roaming.registration.success', 'ROAMING_REGISTRATION'),
                'voice': ('roaming.voice.success', 'ROAMING_VOICE')},
    'MOBILE_DATA': {'activation': ('mobileData.activation.success', 'MOBILE_DATA_ACTIVATION'),
                    'dns': ('mobileData.dns.success', 'MOBILE_DATA_DNS')},
}
CONTROLS = ('normal_daytime', 'healthy_high_load', 'low_volume', 'missing_source',
            'gap_during_impairment', 'gap_during_recovery', 'opposite_direction')
PROTOCOL = dict(version=VERSION, thresholds=[.90, .95, .99], toleranceSeconds=300,
                impairmentSlots=[3, 4, 5, 6], recoverySlot=7, sequenceWindows=12,
                validationSeeds=list(range(5101, 5106)), testSeeds=list(range(5201, 5206)),
                thresholdSelection='validation F1; fewer FP; higher threshold',
                deterministicBaseline='shipped STATIC demonstration fallback; not learned seasonal',
                mlBaseline='unchanged candidate preceding fourteen-day baseline cells',
                ablations=['deterministic', 'ml_shadow', 'combined', 'no_topology', 'no_baseline'])


def digest(path):
    return sha256(Path(path).read_bytes()).hexdigest()


def write_json(path, value):
    Path(path).write_bytes(canonical(value) + b'\n')


def static_baselines(context):
    policy = json.loads((ROOT / 'contracts/baselines/trace-link-baseline-policy-v1.json').read_text())
    context['baselines'] = {}
    for evidence in context['evidence']:
        metric = evidence['metricId']
        if metric not in policy['staticCenters']: continue
        context['baselines'][metric] = dict(status='STATIC', center=policy['staticCenters'][metric], mad=0,
            sourceScope=deepcopy(context['scope']), baselineVersion=policy['baselineVersion'],
            policyVersion=policy['policyVersion'], trainingFrom='2026-09-10T00:00:00Z',
            trainingTo='2026-09-24T00:00:00Z', coverage=0, reason='STATIC_DEMONSTRATION_POLICY')


def change(evidence, value=None, low_volume=False, missing=False):
    from app.features.trace_link_features import actual
    p = evidence['primitives']
    if missing:
        evidence.update(primitives=None, actual=None, coverage=0)
        evidence['quality'] = dict(state='MISSING', reason='SOURCE_GAP')
        evidence['freshness'].update(state='UNKNOWN', observedAt=None, sourceAgeSeconds=None)
    else:
        if p['type'] == 'RATIO':
            if low_volume: p.update(denominator=10, numerator=9)
            elif value is not None: p['numerator'] = round(value / 100 * p['denominator'])
        elif p['type'] == 'SUM_COUNT':
            if low_volume: p.update(count=1, sum=20)
            elif value is not None: p['sum'] = value * p['count']
        elif p['type'] == 'TIME_WEIGHTED' and value is not None: p['weightedSum'] = value * p['validSeconds']
        evidence['actual'] = actual(p)
    evidence['deviation'] = None
    evidence['provenance']['sourceChecksum'] = sha256(canonical(evidence['primitives'])).hexdigest()


def generate(output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / 'protocol.json', PROTOCOL)
    rows, groups = [], []
    for split, seeds, day0 in [('validation', PROTOCOL['validationSeeds'], 22), ('test', PROTOCOL['testSeeds'], 27)]:
        for domain, faults in DOMAINS.items():
            for seed in seeds:
                for category in (*faults, *CONTROLS):
                    group = sha256(f'{VERSION}:{split}:{domain}:{seed}:{category}'.encode()).hexdigest()
                    metric, family = faults.get(category, next(iter(faults.values())))
                    fault = category in faults or category in ('gap_during_impairment', 'gap_during_recovery')
                    scope = None
                    for slot in range(12):
                        context = source_context(domain, day0 + seeds.index(seed), slot, seed)
                        context['datasetId'] = VERSION
                        context['window']['id'] = sha256(f'{group}:{slot}'.encode()).hexdigest()
                        if domain == 'ROAMING' and category in ('registration', 'voice'):
                            context['scope']['procedure'] = category.upper()
                            context['scope']['id'] += ':' + category
                            for e in context['evidence']:
                                if e['metricId'] == 'roaming.data.success': e['metricId'] = metric
                        if category == 'opposite_direction' and domain != 'MOBILE_DATA':
                            context['scope']['directionId'] = 'b-to-a' if domain == 'TRANSPORT' else 'in'
                            context['scope']['id'] = 'chi-hin:b-to-a' if domain == 'TRANSPORT' else 'ro-partner:in'
                        for e in context['evidence']:
                            e.update(scope=deepcopy(context['scope']), window=deepcopy(context['window']),
                                     evidenceId=context['window']['id'] + ':' + e['metricId'],
                                     measurementId=context['window']['id'] + ':' + e['metricId'])
                            e['provenance']['datasetId'] = VERSION
                            value = None
                            if fault and 3 <= slot <= 6 and e['metricId'] == metric:
                                value = 4 if metric.endswith('packetLoss') else 150 if metric.endswith('rtt.mean') else 95
                            if category == 'healthy_high_load' and e['metricId'].endswith('utilization'): value = 95
                            missing = category == 'missing_source' or (category == 'gap_during_impairment' and slot == 5) or (category == 'gap_during_recovery' and slot in (7, 8))
                            change(e, value, category == 'low_volume', missing)
                        static_baselines(context)
                        scope = context['scope']
                        rows.append(dict(group=group, split=split, slot=slot, input=dict(
                            schemaVersion='trace-link-persisted-window-v1', finalized=True, context=context,
                            candidates=[], controls=[], dependencySnapshot=dict(topologyVersion=context['topologyVersion'], relations=[]))))
                    start = datetime(2026, 9, 10, 8, tzinfo=timezone.utc) + timedelta(days=day0 + seeds.index(seed))
                    epoch = int(start.timestamp())
                    groups.append(dict(group=group, split=split, seed=seed, category=category, domain=domain,
                        scope=scope, family=family, fault=fault, start=epoch + 900, end=epoch + 2100,
                        firstFinalized=epoch + 1200, recovered=epoch + 2100, expectedCause='UNDETERMINED'))
    raw = canonical(rows) + b'\n'
    (output / 'inputs.json.gz').write_bytes(gzip.compress(raw, mtime=0))
    write_json(output / 'truth.json', groups)
    pins = {}
    tracked = subprocess.check_output(['git', 'ls-files', 'contracts',
        'services/ml-service/candidate-models/trace-link-shadow-v1', 'services/ml-service/app',
        'services/processor/src/main/java', 'services/streaming-support/src/main/java'], cwd=ROOT, text=True)
    for name in tracked.splitlines(): pins[name] = digest(ROOT / name)
    for name in ('services/ml-service/requirements-ml.txt', 'services/ml-service/training/trace_link_candidate.py',
                 'services/ml-service/training/trace_link_study.py', 'services/ml-service/training/trace_link_evaluation.py',
                 'services/processor/src/test/java/md/utm/telecom/processing/tracelink/TraceLinkStudyExportTest.java'):
        pins[name] = digest(ROOT / name)
    write_json(output / 'manifest.json', dict(version=VERSION, synthetic=True,
        sourceRevision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        files={name:digest(output / name) for name in ('inputs.json.gz', 'truth.json', 'protocol.json')},
        sourcePins=pins, groups=len(groups), windows=len(rows),
        limitations=['paired scenario variants share seed; groups are not independent real-world trials',
                     'normal daytime only; no night-time or continuous thirty-day coverage',
                     'deterministic shipped fallback is STATIC; seasonal effectiveness is not established',
                     'each group runs with independent lifecycle state; this is not a concurrent deployed replay']))
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    print(generate(parser.parse_args().output))
