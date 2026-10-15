"""Evaluate frozen shadow models and actual Java lifecycle exports without tuning test data."""
import argparse
from collections import Counter, defaultdict
from copy import deepcopy
from datetime import datetime
import gzip
from hashlib import sha256
import json
from pathlib import Path
import sys
from uuid import UUID

from trace_link_candidate import ROOT, canonical, source_context, local_key, SPLITS
from trace_link_study import digest, write_json, VERSION

sys.path.insert(0, str(ROOT / 'services/ml-service'))
from app.features.trace_link_features import build_features
from app.inference.trace_link_scoring import load, score, DEFAULT


def metrics(labels, predictions):
    if len(labels) != len(predictions): raise ValueError('Prediction count mismatch')
    tp = sum(a and b for a, b in zip(labels, predictions))
    fp = sum(not a and b for a, b in zip(labels, predictions))
    fn = sum(a and not b for a, b in zip(labels, predictions))
    tn = sum(not a and not b for a, b in zip(labels, predictions))
    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    return dict(n=len(labels), tp=tp, fp=fp, fn=fn, tn=tn, precision=precision, recall=recall,
                f1=2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else None)


def select_threshold(labels, ranks, candidates):
    if not labels or len(labels) != len(ranks): raise ValueError('Validation predictions required')
    def quality(threshold):
        m = metrics(labels, [r >= threshold for r in ranks])
        return (m['f1'] if m['f1'] is not None else -1, -m['fp'], threshold)
    return max(candidates, key=quality)


def reject_truth(value):
    forbidden = {'label', 'truth', 'scenario', 'scenarioId', 'expectedLabel', 'expectedCause', 'futureOutcome', 'outcome'}
    if isinstance(value, dict):
        if forbidden.intersection(value): raise ValueError('Truth leakage into detector inputs')
        for child in value.values(): reject_truth(child)
    elif isinstance(value, list):
        for child in value: reject_truth(child)


def compatible(a, b):
    return all(a[k] == b[k] for k in ('group', 'domain', 'scope', 'family'))


def match_episodes(truth, detected, tolerance):
    candidates = []
    for ti, t in enumerate(truth):
        for di, d in enumerate(detected):
            if not compatible(t, d): continue
            overlap = min(t['end'], d['end']) - max(t['start'], d['start'])
            if overlap <= -tolerance: continue
            candidates.append((-max(overlap, 0), d['detected'], d['id'], ti, di))
    used_t, used_d, pairs = set(), set(), []
    for _, _, _, ti, di in sorted(candidates):
        if ti in used_t or di in used_d: continue
        used_t.add(ti); used_d.add(di); pairs.append((truth[ti], detected[di]))
    tp, fp, fn = len(pairs), len(detected) - len(pairs), len(truth) - len(pairs)
    return dict(tp=tp, fp=fp, fn=fn, truthEpisodes=len(truth), detectedEpisodes=len(detected),
        precision=tp / len(detected) if detected else None, recall=tp / len(truth) if truth else None,
        duplicates=sum(di not in used_d and any(compatible(t, d) and min(t['end'], d['end']) - max(t['start'], d['start']) > -tolerance for t in truth)
                       for di, d in enumerate(detected)),
        detectionDelaySeconds=[d['detected'] - t['start'] for t, d in pairs],
        finalizedDetectionDelaySeconds=[d['detected'] - t['firstFinalized'] for t, d in pairs],
        recoveryDelaySeconds=[d['recovered'] - t['recovered'] for t, d in pairs if d.get('recovered') is not None],
        recoveryUnobserved=sum(d.get('recovered') is None for _, d in pairs))


def read_study(dataset, protocol):
    manifest = json.loads((dataset / 'manifest.json').read_bytes())
    if manifest['version'] != VERSION: raise ValueError('Unsupported study version')
    for name, expected in manifest['files'].items():
        if not (dataset/name).resolve().is_relative_to(dataset.resolve()): raise ValueError('Dataset path escapes study')
        if digest(dataset / name) != expected: raise ValueError('Dataset checksum mismatch: ' + name)
    if digest(protocol) != manifest['files']['protocol.json']: raise ValueError('Protocol differs from frozen study')
    for name, expected in manifest['sourcePins'].items():
        if not (ROOT/name).resolve().is_relative_to(ROOT.resolve()): raise ValueError('Source pin escapes repository')
        if digest(ROOT / name) != expected: raise ValueError('Source pin mismatch: ' + name)
    rows = json.loads(gzip.decompress((dataset / 'inputs.json.gz').read_bytes()))
    truth = json.loads((dataset / 'truth.json').read_bytes())
    p = json.loads(protocol.read_bytes())
    groups = {g['group']:g for g in truth}
    if len(groups) != len(truth) or len(rows) != manifest['windows'] or len(truth) != manifest['groups']:
        raise ValueError('Duplicate groups or count mismatch')
    seen, intervals, group_slots = set(), defaultdict(list), defaultdict(set)
    for row in rows:
        reject_truth(row['input'])
        c = row['input']['context']; group = groups[row['group']]
        if row['split'] != group['split'] or row['split'] not in ('validation', 'test'): raise ValueError('Split mismatch')
        if c['domain'] != group['domain'] or c['scope'] != group['scope']: raise ValueError('Truth scope mismatch')
        wid = c['window']['id']
        if wid in seen: raise ValueError('Duplicate window identity')
        seen.add(wid)
        if row['slot'] in group_slots[row['group']]: raise ValueError('Duplicate group slot')
        group_slots[row['group']].add(row['slot'])
        start, end = (datetime.fromisoformat(c['window'][k]).timestamp() for k in ('from', 'to'))
        if end - start != 300: raise ValueError('Invalid window length')
        if start != group['start'] - 900 + 300 * row['slot']: raise ValueError('Discontinuous group')
        intervals[row['split']].append((start, end))
        for baseline in c['baselines'].values():
            if datetime.fromisoformat(baseline['trainingTo']).timestamp() > start: raise ValueError('Baseline leakage')
    if any(slots != set(range(12)) for slots in group_slots.values()) or len(group_slots) != len(groups):
        raise ValueError('Incomplete group')
    if max(end for _, end in intervals['validation']) > min(start for start, _ in intervals['test']):
        raise ValueError('Validation/test overlap')
    if set(p['validationSeeds']) & set(p['testSeeds']): raise ValueError('Seed overlap')
    for g in truth:
        if g['seed'] not in p[g['split'] + 'Seeds']: raise ValueError('Undeclared seed')
    return manifest, rows, groups, p


def candidate_context(context, candidate, absent=False):
    """Resolve only unchanged candidate cells, never learn from evaluation rows."""
    c = deepcopy(context); c['baselines'] = {}
    domain = c['domain']; weekday, slot = local_key(c)
    cells = json.loads((candidate / (domain + '-baselines.json')).read_bytes())
    for e in c['evidence']:
        cell = next((b for b in cells if b['metricId'] == e['metricId'] and b['weekdayClass'] == weekday and b['slot'] == slot), None)
        if cell is None or absent: continue
        c['baselines'][e['metricId']] = dict(status='DIRECT', center=cell['center'], mad=cell['mad'],
            sourceScope=deepcopy(c['scope']), baselineVersion='trace-link-seasonal-v1',
            policyVersion='trace-link-baseline-policy-v1', trainingFrom='2026-09-10T00:00:00Z',
            trainingTo='2026-09-24T00:00:00Z', coverage=1, reason=None)
    return c


def rank(context, loaded, candidate, absent=False):
    features = build_features(candidate_context(context, candidate, absent))
    if not features['eligible']: return None, 'INELIGIBLE'
    if features['scope'] != loaded[1][features['domain']][0]['scope']: return None, 'UNSUPPORTED_CANDIDATE_SCOPE'
    request_id = str(UUID(sha256(features['window']['id'].encode()).hexdigest()[:32]))
    result = score(dict(requestId=request_id, requestedAt=context['evaluatedAt'],
                        requestedModelVersion='trace-link-shadow-v1', features=features), loaded)
    return result['anomalyScore'], 'OK'


def frozen_holdout(loaded, candidate, thresholds):
    manifest = json.loads((candidate / 'split-manifest.json').read_bytes())
    results = {}
    for domain in loaded[1]:
        features, ranks = [], []
        seed, first, last = SPLITS['heldOut']
        for day in range(first, last):
            for slot in range(12):
                c = candidate_context(source_context(domain, day, slot, seed), candidate)
                f = build_features(c); features.append(f)
                ranks.append(rank(c, loaded, candidate)[0])
        if sha256(canonical(features)).hexdigest() != manifest['domains'][domain]['hashes']['heldOut']:
            raise ValueError('Frozen holdout input hash mismatch: ' + domain)
        results[domain] = dict(metrics=metrics([False] * len(ranks), [r >= thresholds[domain] for r in ranks]),
                               ranks=ranks, faultRecall=None, reason='normal-only frozen holdout')
    return results


def java_episodes(export_rows, groups, mode):
    episodes = {}
    for row in export_rows:
        if row['mode'] != mode or groups[row['group']]['split'] != 'test': continue
        c = row['context']; g = groups[row['group']]
        for item in row['evaluations']:
            t = item['transition']; result = item['result']
            if t is None: continue
            key = (row['group'], t['episodeId'])
            if t['phase'] == 'OPEN':
                episodes[key] = dict(group=row['group'], domain=c['domain'], scope=c['scope'], family=result['anomalyKind'],
                    id=t['episodeId'], start=int(datetime.fromisoformat(c['window']['from']).timestamp()),
                    end=int(datetime.fromisoformat(c['window']['to']).timestamp()),
                    detected=int(datetime.fromisoformat(result['evaluatedAt']).timestamp()), recovered=None)
            if key in episodes:
                episodes[key]['end'] = int(datetime.fromisoformat(c['window']['to']).timestamp())
                if t['phase'] == 'RECOVERY': episodes[key]['recovered'] = int(datetime.fromisoformat(result['evaluatedAt']).timestamp())
    return list(episodes.values())


def evaluate(dataset, candidate, processor_export, protocol, output):
    manifest, rows, groups, p = read_study(dataset, protocol)
    exported = json.loads(processor_export.read_bytes())
    if exported['inputsSha256'] != manifest['files']['inputs.json.gz']: raise ValueError('Processor export source mismatch')
    expected = {(r['group'], r['slot'], mode) for r in rows for mode in ('deterministic', 'no_topology', 'no_baseline')}
    actual = [(r['group'], r['slot'], r['mode']) for r in exported['rows']]
    if len(actual) != len(set(actual)) or set(actual) != expected: raise ValueError('Incomplete/duplicate processor export')
    original = {(r['group'], r['slot']):r['input']['context'] for r in rows}
    for r in exported['rows']:
        expected_context = deepcopy(original[(r['group'], r['slot'])])
        if r['mode'] == 'no_baseline': expected_context['baselines'] = {}
        if r['context'] != expected_context: raise ValueError('Processor context differs from pinned input')
    loaded = load(candidate)
    # Validation is scored first. Persist the choice before any test scoring.
    validation = {d:[] for d in loaded[1]}
    for row in rows:
        if row['split'] != 'validation': continue
        value, reason = rank(row['input']['context'], loaded, candidate)
        if value is not None:
            g = groups[row['group']]
            validation[g['domain']].append((g['fault'] and 3 <= row['slot'] <= 6, value))
    thresholds = {d:select_threshold([a for a, _ in values], [b for _, b in values], p['thresholds']) for d, values in validation.items()}
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / 'thresholds.json', dict(thresholds=thresholds, protocolSha256=digest(protocol),
        validationCounts={d:len(v) for d, v in validation.items()}, testUsed=False))
    scores, fallbacks = {}, Counter()
    for row in rows:
        if row['split'] != 'test': continue
        for mode in ('ml_shadow', 'no_baseline'):
            value, reason = rank(row['input']['context'], loaded, candidate, mode == 'no_baseline')
            scores[(row['group'], row['slot'], mode)] = value
            fallbacks[(mode, reason)] += 1
    result = dict(version=VERSION, synthetic=True, thresholds=thresholds,
                  sampleCounts=dict(windows=manifest['windows'], groups=manifest['groups'],
                      validationSeeds=p['validationSeeds'], testSeeds=p['testSeeds'],
                      validationEligible={d:len(v) for d,v in validation.items()}),
                  studyManifestSha256=digest(dataset/'manifest.json'),
                  processorExportSha256=digest(processor_export), protocolSha256=digest(protocol), modes={},
                  frozenNormalHoldout=frozen_holdout(loaded, candidate, thresholds),
                  fallbackCounts={f'{mode}:{reason}':count for (mode, reason), count in sorted(fallbacks.items())},
                  limitations=manifest['limitations'] + ['ML-only episode and cause metrics unsupported: no ML lifecycle or attribution policy',
                    'cause-control benchmark is a frozen component oracle, not independent held-out causal truth',
                    'no model promotion or production capacity claim; empirical ranks are not probabilities'])
    truth = [g for g in groups.values() if g['split'] == 'test' and g['fault']]
    for mode in p['ablations']:
        labels, predicted, eligible_labels, eligible_predicted = [], [], [], []
        categories, confusion, states = defaultdict(lambda: [[], []]), defaultdict(lambda: [[], []]), Counter()
        agreement = Counter()
        selected = [r for r in exported['rows'] if r['mode'] == ('deterministic' if mode in ('ml_shadow', 'combined') else mode) and groups[r['group']]['split'] == 'test']
        for row in selected:
            g = groups[row['group']]; slot = row['slot']; domain = g['domain']
            label = g['fault'] and 3 <= slot <= 6
            ml = scores[(row['group'], slot, 'no_baseline' if mode == 'no_baseline' else 'ml_shadow')]
            deterministic = any(i['result']['verdict'] == 'BREACH' for i in row['evaluations'])
            pred = (ml is not None and ml >= thresholds[domain]) if mode == 'ml_shadow' else deterministic
            labels.append(label); predicted.append(pred)
            if mode != 'ml_shadow' or ml is not None:
                eligible_labels.append(label); eligible_predicted.append(pred)
            categories[g['category']][0].append(label); categories[g['category']][1].append(pred)
            key = domain + ':' + canonical(g['scope']).decode()
            confusion[key][0].append(label); confusion[key][1].append(pred)
            states.update(i['result']['verdict'] for i in row['evaluations'])
            if mode == 'combined': agreement['unavailable' if ml is None else 'agree' if (ml >= thresholds[domain]) == deterministic else 'disagree'] += 1
        result['modes'][mode] = dict(windows=metrics(labels, predicted), eligibleWindows=metrics(eligible_labels, eligible_predicted),
            byControl={k:metrics(*v) for k, v in sorted(categories.items())},
            confusionByDomainScopeDirection={k:metrics(*v) for k, v in sorted(confusion.items())},
            verdictCounts=dict(sorted(states.items())), mlAgreement=dict(agreement),
            episodes=None if mode == 'ml_shadow' else match_episodes(truth, java_episodes(exported['rows'], groups, 'deterministic' if mode == 'combined' else mode), p['toleranceSeconds']))
        if mode == 'no_baseline':
            shadow_labels, shadow_predictions = [], []
            for row in selected:
                g = groups[row['group']]
                value = scores[(row['group'], row['slot'], 'no_baseline')]
                if value is None: continue
                shadow_labels.append(g['fault'] and 3 <= row['slot'] <= 6)
                shadow_predictions.append(value >= thresholds[g['domain']])
            result['modes'][mode]['shadowEligibleWindows'] = metrics(shadow_labels, shadow_predictions)
    causes = exported['causeControls']
    result['causeSupport'] = dict(n=len(causes), correct=sum(c['status'] == c['expectedStatus'] for c in causes),
        accuracy=sum(c['status'] == c['expectedStatus'] for c in causes)/len(causes) if causes else None,
        statuses=dict(Counter(c['status'] for c in causes)), noTopologyStatuses=dict(Counter(c['noTopologyStatus'] for c in causes)),
        studyStatuses=dict(Counter(i['cause']['status'] for r in exported['rows'] if r['mode']=='deterministic' and groups[r['group']]['split']=='test' for i in r['evaluations'])))
    write_json(output / 'results.json', result)
    write_json(output / 'predictions.json', [dict(group=g, slot=s, mode=m, rank=v) for (g,s,m),v in sorted(scores.items())])
    lines = ['# Trace Link Day 5 evaluation', '', 'Synthetic offline study; thresholds frozen from validation only.', '',
             '| Mode | N | Precision | Recall | F1 | Episode TP/FP/FN |', '|---|---:|---:|---:|---:|---|']
    for mode, r in result['modes'].items():
        m = r['windows']; e = r['episodes']
        lines.append(f"| {mode} | {m['n']} | {m['precision']} | {m['recall']} | {m['f1']} | " + (f"{e['tp']}/{e['fp']}/{e['fn']}" if e else 'unsupported') + ' |')
    lines += ['', '## Limitations', *['- '+s for s in result['limitations']], '',
              'Second-person runbook reproduction: PENDING. Failure-drill receipts are separate from classification results.']
    (output / 'report.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('dataset', 'processor-export', 'protocol', 'output'): parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--candidate', type=Path, default=DEFAULT)
    args = parser.parse_args()
    evaluate(args.dataset, args.candidate, args.processor_export, args.protocol, args.output)
    print('Evaluation complete; no candidate, policy or production configuration changed')
