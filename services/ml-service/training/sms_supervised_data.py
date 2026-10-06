"""Fresh labelled SMS experiments, independent of normal-only IF training."""

import argparse
from datetime import datetime, timezone, timedelta
from hashlib import sha256
import json
import math
from pathlib import Path
import random

import numpy as np

from generate_history import WEEK, MINUTE
from scenario_history import FAULTS, NORMALS, scenario_window
from train import ORDER
from compare_models import interval

KIND = 'sms-supervised-v1'
START = datetime(2026, 8, 24, tzinfo=timezone.utc)
FEATURES = ORDER['models']['SMS']
VIEWS = {'six': list(range(6)), 'without-absolute-delay': [0, 2, 3, 4, 5]}
SPLITS = ('train', 'calibration', 'validation', 'test')


def generate(root, normal_weeks=8, cadence_minutes=5, fault_minutes=30, repetitions=2,
             start=START, seed_offset=1000000, balanced_healthy=False, healthy_profile_days=2):
    sizes = (normal_weeks, cadence_minutes, fault_minutes, repetitions, healthy_profile_days)
    if (any(type(v) is not int or v < 1 for v in sizes) or 60 % cadence_minutes
            or 48 * repetitions * fault_minutes > int(WEEK / MINUTE)
            or type(seed_offset) is not int or seed_offset < 0 or type(balanced_healthy) is not bool
            or start.tzinfo is None or start.utcoffset() != timedelta(0)
            or start.weekday() != 0 or start.time() != START.time()):
        raise ValueError('Invalid dataset sizes, seed offset or UTC Monday start')
    root = Path(root)
    root.mkdir(parents=True, exist_ok=False)
    data = root / 'data'
    data.mkdir()
    specs = [('train', start + i * WEEK, WEEK, None, 0, None) for i in range(normal_weeks)]

    def faults(split, when):
        for family in FAULTS['SMS']:
            for severity in (1, 2, 3):
                for profile in NORMALS:
                    for _ in range(repetitions):
                        span = fault_minutes * MINUTE
                        specs.append((split, when, span, family, severity, profile))
                        when += span

    faults('train', start + normal_weeks * WEEK)
    calibration = start + (normal_weeks + 1) * WEEK
    specs.append(('calibration', calibration, WEEK, None, 0, None))
    evaluation_span = max(2 * WEEK, timedelta(days=4 * healthy_profile_days) + 48 * repetitions * fault_minutes * MINUTE)
    for split, when in (('validation', calibration + WEEK), ('test', calibration + WEEK + evaluation_span)):
        # Equal profile coverage gives each false-alarm gate a useful denominator.
        for profile in NORMALS:
            specs.append((split, when, timedelta(days=healthy_profile_days), None, 0, profile))
            when += timedelta(days=healthy_profile_days)
        faults(split, when)
    runs = []
    for index, (split, when, span, family, severity, profile) in enumerate(specs):
        run_id = f'sms-supervised-{start:%Y%m%d}-{seed_offset}-{split}-{index}'
        seed = seed_offset + index
        path = data / f'{run_id}.jsonl'
        step = 1 if family else cadence_minutes
        count, digest = 0, sha256()
        with path.open('wb') as stream:
            for offset in range(0, int(span / MINUTE), step):
                timestamp = when + offset * MINUTE
                weights = (1, 1, 1, 1) if balanced_healthy else (.55, .2, .15, .1)
                healthy = profile or random.Random(f'{seed}:{timestamp}').choices(NORMALS, weights)[0]
                scenario = family or healthy
                window = scenario_window('SMS', timestamp, seed, scenario, severity, run_id,
                                         operating_profile=healthy)
                if not window['mlEligible']:
                    raise ValueError('Ineligible generated observation')
                row = dict(service='SMS', runId=run_id, episodeId=run_id, seed=seed,
                           windowStart=window['windowStart'], featureNames=window['featureNames'],
                           featureValues=window['featureValues'], label='FAULT' if family else 'NORMAL',
                           scenario=scenario, severity=severity, operatingProfile=healthy)
                line = (json.dumps(row, separators=(',', ':'), allow_nan=False) + '\n').encode()
                stream.write(line)
                digest.update(line)
                count += 1
        runs.append(dict(service='SMS', runId=run_id, episodeId=run_id, seed=seed, split=split,
                         start=when.isoformat(), endExclusive=(when + span).isoformat(), rows=count,
                         path=path.name, sha256=digest.hexdigest(), cadenceMinutes=step,
                         label='FAULT' if family else 'NORMAL', scenario=family or profile or 'healthy-mixture',
                         severity=severity, operatingProfile=profile or 'healthy-mixture'))
    version = (f'{KIND}-t{normal_weeks}-m{cadence_minutes}-f{repetitions}x{fault_minutes}'
               f'-start{start:%Y%m%d}-seed{seed_offset}')
    if balanced_healthy or healthy_profile_days != 2:
        version += f"-{'balanced' if balanced_healthy else 'mixture'}-p{healthy_profile_days}"
    manifest = dict(datasetKind=KIND, datasetVersion=version,
                    featureVersion=2, baselineVersion='baseline-v2', syntheticOnly=True, runs=runs)
    validate_manifest(manifest)
    (root / 'split_manifest.json').write_bytes((json.dumps(manifest, indent=2) + '\n').encode())
    return manifest


def validate_manifest(manifest):
    if (manifest.get('datasetKind') != KIND or manifest.get('featureVersion') != 2
            or manifest.get('baselineVersion') != 'baseline-v2' or manifest.get('syntheticOnly') is not True):
        raise ValueError('Incompatible supervised dataset')
    runs = manifest['runs']
    if {r['split'] for r in runs} != set(SPLITS):
        raise ValueError('Require train, calibration, validation and test splits')
    for field in ('runId', 'episodeId', 'path', 'seed'):
        if len({r[field] for r in runs}) != len(runs):
            raise ValueError('Duplicate run, episode, seed or file identity')
    ordered = sorted(runs, key=lambda r: interval(r)[0])
    for run in ordered:
        interval(run)
        fault = run['label'] == 'FAULT'
        if (run['service'] != 'SMS' or run['label'] not in ('NORMAL', 'FAULT')
                or type(run['rows']) is not int or run['rows'] < 1
                or type(run['seed']) is not int or run['seed'] < 0
                or run['scenario'] not in FAULTS['SMS'] + NORMALS + ('healthy-mixture',)
                or run['operatingProfile'] not in NORMALS + ('healthy-mixture',)
                or (fault and (run['scenario'] not in FAULTS['SMS'] or run['severity'] not in (1, 2, 3)))
                or (not fault and (run['scenario'] in FAULTS['SMS'] or run['severity'] != 0))
                or (run['split'] == 'calibration' and fault)):
            raise ValueError('Invalid supervised run or calibration label')
    for left, right in zip(ordered, ordered[1:]):
        if (interval(left)[1] > interval(right)[0]
                or SPLITS.index(left['split']) > SPLITS.index(right['split'])):
            raise ValueError('Split overlap or invalid chronological order')
    for split in ('train', 'validation', 'test'):
        group = [r for r in runs if r['split'] == split]
        if ({r['scenario'] for r in group if r['label'] == 'FAULT'} != set(FAULTS['SMS'])
                or not any(r['label'] == 'NORMAL' for r in group)):
            raise ValueError('Require all fault families and normal observations')
        combinations = {(r['scenario'], r['severity'], r['operatingProfile']) for r in group if r['label'] == 'FAULT'}
        required = {(family, level, profile) for family in FAULTS['SMS'] for level in (1, 2, 3) for profile in NORMALS}
        if combinations != required:
            raise ValueError('Incomplete fault family, severity or operating-profile coverage')


def read_split(data, split, manifest):
    validate_manifest(manifest)
    if split not in SPLITS:
        raise ValueError('Unknown split')
    data = Path(data).resolve()
    rows, identities = [], set()
    for run in manifest['runs']:
        if run['split'] != split:
            continue
        path = (data / run['path']).resolve()
        if not path.is_relative_to(data):
            raise ValueError('Dataset path escapes data directory')
        raw = path.read_bytes()
        if sha256(raw).hexdigest() != run['sha256']:
            raise ValueError('Dataset checksum mismatch')
        chunk = [json.loads(line) for line in raw.splitlines()]
        if len(chunk) != run['rows']:
            raise ValueError('Invalid row count')
        start, end = interval(run)
        for row in chunk:
            when = datetime.fromisoformat(row['windowStart'])
            vector = row['featureValues']
            if (row['service'] != 'SMS' or row['featureNames'] != FEATURES
                    or any(row.get(k) != run[k] for k in ('runId', 'episodeId', 'seed', 'label', 'severity'))
                    or when.tzinfo is None or not start <= when < end or when in identities
                    or not isinstance(vector, list) or len(vector) != 6
                    or not all(type(v) in (float, int) and math.isfinite(v) for v in vector)
                    or row['operatingProfile'] not in NORMALS
                    or (run['operatingProfile'] != 'healthy-mixture' and row['operatingProfile'] != run['operatingProfile'])
                    or (run['scenario'] != 'healthy-mixture' and row['scenario'] != run['scenario'])
                    or (row['label'] == 'NORMAL' and row['scenario'] != row['operatingProfile'])):
                raise ValueError('Invalid, mislabelled or duplicate supervised row')
            identities.add(when)
        rows.extend(chunk)
    if not rows:
        raise ValueError('Empty supervised split')
    return rows


def arrays(rows, view):
    """Only canonical numeric features enter X; only fault labels enter y."""
    return (np.asarray([r['featureValues'] for r in rows], dtype=float)[:, VIEWS[view]],
            np.asarray([int(r['label'] == 'FAULT') for r in rows]))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--start-date', default=START.date().isoformat())
    parser.add_argument('--seed-offset', type=int, default=1000000)
    parser.add_argument('--balanced-healthy', action='store_true', help='Equal healthy profile sampling in training/calibration')
    parser.add_argument('--healthy-profile-days', type=int, default=2, help='Days per healthy profile in validation and final tests')
    args = parser.parse_args()
    manifest = generate(args.output, start=datetime.strptime(args.start_date, '%Y-%m-%d').replace(tzinfo=timezone.utc),
                        seed_offset=args.seed_offset, balanced_healthy=args.balanced_healthy,
                        healthy_profile_days=args.healthy_profile_days)
    print(f"Generated {sum(r['rows'] for r in manifest['runs'])} SMS rows", flush=True)
