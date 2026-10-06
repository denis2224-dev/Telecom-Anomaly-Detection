"""Fresh, varied synthetic raw observations for validation-based ML selection."""

import argparse
from datetime import datetime, timezone, timedelta
from hashlib import sha256
import json
from pathlib import Path
import random

from generate_history import make_window, WEEK, MINUTE, BASELINES

START = datetime(2026, 5, 4, tzinfo=timezone.utc)
NORMALS = ('nominal', 'high-load', 'low-volume', 'healthy-jitter')
FAULTS = {'VOLTE': ('ims-capacity', 'transport-loss', 'access-failure', 'service-failure'),
          'SMS': ('delivery-delay', 'backlog', 'delivery-failure', 'mixed-delay-backlog')}


def scenario_window(service, start, seed, scenario, severity, run_id='scenario'):
    if (service not in FAULTS or scenario not in NORMALS + FAULTS[service]
            or (scenario in NORMALS and severity != 0)
            or (scenario in FAULTS[service] and severity not in (1, 2, 3))):
        raise ValueError('Unknown scenario or invalid severity')

    def transform(raw, nodes, rng):
        m = raw['metrics']
        factor = 1.8 if scenario == 'high-load' else .35 if scenario == 'low-volume' else 1
        if service == 'VOLTE':
            old_eligible = m['attempts'] - m['userOutcomes']
            eligible = max(50, round(old_eligible * factor))
            failures = round(eligible * m['technicalFailures'] / old_eligible)
            if scenario in ('ims-capacity', 'transport-loss', 'service-failure'):
                failures = round(eligible * (.02, .05, .10)[severity - 1])
            user = round(eligible * .02)
            sip_fraction = (.5, .75, .9)[severity - 1] if scenario in ('ims-capacity', 'service-failure') else .3
            m.update(attempts=eligible + user, userOutcomes=user,
                     technicalSuccesses=eligible - failures, technicalFailures=failures,
                     sip503Count=round(failures * sip_fraction))
            for attempts, successes in (('rrcAttempts', 'rrcSuccesses'), ('bearerAttempts', 'bearerSuccesses')):
                success_rate = m[successes] / m[attempts]
                m[attempts] = round(m[attempts] * factor)
                if scenario == 'access-failure':
                    success_rate = (.97, .90, .75)[severity - 1]
                m[successes] = round(m[attempts] * success_rate)
            if scenario == 'high-load':
                nodes[0]['metrics']['cpuPct'] = round(rng.uniform(50, 68), 2)
            elif scenario == 'healthy-jitter':
                nodes[0]['metrics']['cpuPct'] = round(rng.uniform(38, 55), 2)
                nodes[1]['metrics']['packetLossRatio'] = round(rng.uniform(.002, .004), 6)
            elif scenario == 'ims-capacity':
                nodes[0]['metrics']['cpuPct'] = round((72, 85, 96)[severity - 1] + rng.uniform(-2, 2), 2)
            elif scenario == 'transport-loss':
                nodes[1]['metrics']['packetLossRatio'] = round((.006, .015, .05)[severity - 1] * rng.uniform(.9, 1.1), 6)
        else:
            delivered = max(5, round(m['deliveredMessages'] * factor))
            success_rate = m['deliverySuccesses'] / m['deliveryAttempts']
            if scenario == 'delivery-failure':
                success_rate = (.96, .85, .60)[severity - 1]
            m.update(deliveredMessages=delivered, deliverySuccesses=delivered,
                     deliveryAttempts=round(delivered / success_rate))
            delay = 1700 * rng.uniform(1.15, 1.7) if scenario == 'healthy-jitter' else 1700
            if scenario in ('delivery-delay', 'mixed-delay-backlog'):
                delay = (4000, 9000, 30000)[severity - 1]
            m['deliveryDelayMs'] = [max(1, round(delay * rng.uniform(.8, 1.2))) for _ in range(delivered)]
            gauge = nodes[0]['metrics']
            if scenario == 'high-load':
                gauge.update(queueDepth=rng.randrange(10, 36), oldestPendingAgeSeconds=rng.randrange(5, 26))
            elif scenario in ('backlog', 'mixed-delay-backlog'):
                gauge.update(queueDepth=round((45, 150, 450)[severity - 1] * rng.uniform(.9, 1.1)),
                             oldestPendingAgeSeconds=round((35, 120, 600)[severity - 1] * rng.uniform(.9, 1.1)))

    return make_window(service, start, f'{seed}:{scenario}:{severity}', run_id=run_id,
                       raw_transform=transform)


def generate_scenarios(root, normal_weeks=8, cadence_minutes=5, fault_minutes=30, repetitions=2,
                       start=START, seed_offset=0):
    root = Path(root)
    if (not isinstance(start, datetime) or start.tzinfo is None
            or type(seed_offset) is not int or seed_offset < 0):
        raise ValueError('Use an aware UTC start and nonnegative integer seed offset')
    origin = start.astimezone(timezone.utc)
    if origin.weekday() != 0 or origin.time() != START.time():
        raise ValueError('Experiment start must be Monday at midnight UTC')
    if any(type(v) is not int or v < 1 for v in (normal_weeks, cadence_minutes, fault_minutes, repetitions)):
        raise ValueError('Dataset sizes must be positive integers')
    if 60 % cadence_minutes or 12 * repetitions * fault_minutes > 7 * 24 * 60:
        raise ValueError('Cadence must divide 60 and fault runs must fit within a week')
    root.mkdir(parents=True, exist_ok=False)
    data = root / 'data'
    data.mkdir()
    runs = []
    for service in ('VOLTE', 'SMS'):
        specs = [('train', origin + week * WEEK, WEEK, None, 0) for week in range(normal_weeks)]
        calibration = origin + normal_weeks * WEEK
        specs.append(('calibration', calibration, WEEK, None, 0))
        for split, when in (('validation', calibration + WEEK), ('test', calibration + 3 * WEEK)):
            specs.append((split, when, WEEK, None, 0))
            fault_start = when + WEEK
            for family in FAULTS[service]:
                for severity in (1, 2, 3):
                    for _ in range(repetitions):
                        specs.append((split, fault_start, fault_minutes * MINUTE, family, severity))
                        fault_start += fault_minutes * MINUTE
        for index, (split, start, span, family, severity) in enumerate(specs):
            run_id = f'{service.lower()}-{split}-{index}'
            seed = (800000 if service == 'VOLTE' else 900000) + seed_offset + index
            path = data / f'{run_id}.jsonl'
            digest, count = sha256(), 0
            step = 1 if family else cadence_minutes
            with path.open('wb') as stream:
                for offset in range(0, int(span / MINUTE), step):
                    when = start + offset * MINUTE
                    scenario = family or random.Random(f'{seed}:{when}').choices(NORMALS, (.55, .2, .15, .1))[0]
                    window = scenario_window(service, when, seed, scenario, severity, run_id)
                    if not window['mlEligible']:
                        raise ValueError(f'Ineligible generated window: {run_id} {when}')
                    row = dict(service=service, runId=run_id, windowStart=window['windowStart'],
                               featureNames=window['featureNames'], featureValues=window['featureValues'],
                               scenario=scenario, severity=severity)
                    if split in ('validation', 'test'):
                        row['label'] = 'FAULT' if family else 'NORMAL'
                    line = (json.dumps(row, separators=(',', ':'), allow_nan=False) + '\n').encode()
                    stream.write(line)
                    digest.update(line)
                    count += 1
            runs.append(dict(runId=run_id, service=service, split=split, seed=seed,
                             start=start.isoformat(), endExclusive=(start + span).isoformat(),
                             cadenceMinutes=step, rows=count, eligibleRows=count, path=path.name,
                             sha256=digest.hexdigest(), scenario=family or 'healthy-mixture', severity=severity,
                             label='FAULT' if family else 'NORMAL'))
        print(f'Generated {service} scenario history', flush=True)
    version = f'synthetic-validation-v1-t{normal_weeks}-m{cadence_minutes}-f{repetitions}x{fault_minutes}'
    if origin != START or seed_offset:
        version += f'-start{origin:%Y%m%d}-seed{seed_offset}'
    manifest = dict(datasetVersion=version,
                    featureVersion=2, baselineVersion=BASELINES['baselineVersion'], syntheticOnly=True, runs=runs)
    (root / 'split_manifest.json').write_bytes((json.dumps(manifest, indent=2) + '\n').encode())
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New experiment directory, containing data/')
    parser.add_argument('--training-weeks', type=int, default=8)
    parser.add_argument('--start-date', default=START.date().isoformat(), help='Monday YYYY-MM-DD in UTC')
    parser.add_argument('--seed-offset', type=int, default=0, help='Change for a fresh experiment')
    args = parser.parse_args()
    origin = datetime.strptime(args.start_date, '%Y-%m-%d').replace(tzinfo=timezone.utc)
    manifest = generate_scenarios(args.output, normal_weeks=args.training_weeks,
                                  start=origin, seed_offset=args.seed_offset)
    print(f"Generated {sum(r['rows'] for r in manifest['runs'])} eligible rows")
