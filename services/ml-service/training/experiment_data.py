"""Verified split loading and metrics shared by selection and final evaluation."""

from datetime import datetime
from hashlib import sha256
import json
import math
from pathlib import Path

import numpy

from train import ORDER
from evaluate import summary
from compare_models import interval


def validate_manifest(manifest):
    if manifest['featureVersion'] != ORDER['featureVersion'] or manifest['baselineVersion'] != 'baseline-v2':
        raise ValueError('Incompatible experiment feature or baseline version')
    runs = manifest['runs']
    if (len({r['runId'] for r in runs}) != len(runs)
            or len({r['path'] for r in runs}) != len(runs)):
        raise ValueError('Duplicate experiment run identity or file')
    split_order = {'train': 0, 'calibration': 1, 'validation': 2, 'test': 3}
    for service in ORDER['models']:
        selected = [r for r in runs if r['service'] == service]
        if {r['split'] for r in selected} != set(split_order):
            raise ValueError('Require separate train, calibration, validation and test splits')
        selected.sort(key=lambda r: interval(r)[0])
        for run in selected:
            interval(run)
            if type(run['rows']) is not int or run['rows'] < 1:
                raise ValueError('Invalid experiment row count')
        for left, right in zip(selected, selected[1:]):
            if (interval(left)[1] > interval(right)[0]
                    or split_order[left['split']] > split_order[right['split']]):
                raise ValueError('Experiment split overlap or invalid chronological order')


def read_split(data, service, split, manifest):
    validate_manifest(manifest)
    data = Path(data).resolve()
    rows, identities = [], set()
    for run in manifest['runs']:
        if run['service'] != service or run['split'] != split:
            continue
        path = (data / run['path']).resolve()
        if not path.is_relative_to(data):
            raise ValueError('Dataset path must stay inside data directory')
        raw = path.read_bytes()
        if sha256(raw).hexdigest() != run['sha256']:
            raise ValueError(f'Dataset checksum mismatch: {path}')
        chunk = [json.loads(line) for line in raw.splitlines()]
        if len(chunk) != run['rows']:
            raise ValueError('Dataset row count mismatch')
        start, end = interval(run)
        for row in chunk:
            when = datetime.fromisoformat(row['windowStart'])
            vector = row['featureValues']
            labelled = split in ('validation', 'test')
            if (row['service'] != service or row['runId'] != run['runId']
                    or row['featureNames'] != ORDER['models'][service]
                    or when.tzinfo is None or not start <= when < end or when in identities
                    or not isinstance(vector, list) or len(vector) != 6
                    or not all(type(v) in (int, float) and math.isfinite(v) for v in vector)
                    or ('label' in row) != labelled
                    or (labelled and row['label'] not in ('NORMAL', 'FAULT'))
                    or (labelled and row['label'] != run['label'])
                    or row['severity'] != run['severity']
                    or (run['label'] == 'FAULT' and row['scenario'] != run['scenario'])):
                raise ValueError('Invalid, mislabelled or duplicate experiment row')
            identities.add(when)
        rows.extend(chunk)
    if not rows:
        raise ValueError('Empty experiment split')
    if split in ('validation', 'test') and {row['label'] for row in rows} != {'NORMAL', 'FAULT'}:
        raise ValueError('Require normal and fault examples')
    return rows


def ranks_for(rows, loaded):
    values = numpy.asarray([row['featureValues'] for row in rows], dtype=float)
    strengths = -loaded[2].score_samples(values)
    return numpy.searchsorted(loaded[3], strengths, side='right') / len(loaded[3])


def metrics(rows, ranks, threshold):
    predictions = (numpy.asarray(ranks) >= threshold).tolist()
    result = summary([row['label'] for row in rows], predictions, [])
    families, severities, healthy = {}, {}, {}
    for row, prediction in zip(rows, predictions):
        if row['label'] == 'FAULT':
            for group, key in ((families, row['scenario']), (severities, str(row['severity']))):
                item = group.setdefault(key, dict(windows=0, detected=0))
                item['windows'] += 1
                item['detected'] += prediction
        else:
            item = healthy.setdefault(row['scenario'], dict(windows=0, falsePositives=0))
            item['windows'] += 1
            item['falsePositives'] += prediction
    for item in list(families.values()) + list(severities.values()):
        item['recall'] = item['detected'] / item['windows']
    for item in healthy.values():
        item['falsePositiveRate'] = item['falsePositives'] / item['windows']
    result.update(byFamily=families, bySeverity=severities, byHealthyProfile=healthy,
                  macroRecall=sum(v['recall'] for v in families.values()) / len(families),
                  falsePositiveRate=result['falsePositives'] / result['testNormalWindows'])
    return result
