"""Freeze Sergiu's inputs or validate two supplied connected rehearsal receipts.

This checks evidence integrity/completeness, not its authorship or human approval.
It never manufactures connected receipts from offline study output.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DOMAINS = ('TRANSPORT', 'ROAMING', 'MOBILE_DATA', 'VOLTE', 'SMS')
GATES = ('positive', 'controls', 'concurrent', 'lifecycle', 'ml_failure',
         'restart_replay', 'display_trace', 'analyst_separation', 'full_verification')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def contained(root, relative):
    path = (root / relative).resolve()
    if Path(relative).is_absolute() or not path.is_relative_to(root.resolve()):
        raise ValueError('Path escapes root: ' + relative)
    return path


def check_files(root, files):
    if not isinstance(files, dict) or not files:
        raise ValueError('Missing file inventory')
    for name, expected in files.items():
        if not isinstance(name, str) or not isinstance(expected, str):
            raise ValueError('File inventory requires string paths and hashes')
        path = contained(root, name)
        if not path.is_file() or digest(path) != expected:
            raise ValueError('Missing or changed artifact: ' + name)


def timestamp(value):
    if not isinstance(value, str):
        raise ValueError('Timestamp must be a timezone-qualified string')
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None:
        raise ValueError('Timestamp requires timezone')
    return result


def require_object(value, label):
    if not isinstance(value, dict):
        raise ValueError(label + ' must be an object')
    return value


def validate_outcomes(value):
    outcomes = require_object(value, 'Deterministic outcomes')
    fields = {'counts', 'episodeIds', 'detectionIds', 'controls', 'recovery', 'replay'}
    if set(outcomes) != fields:
        raise ValueError('Deterministic outcomes require exactly: ' + ', '.join(sorted(fields)))
    counts = require_object(outcomes['counts'], 'Outcome counts')
    if set(counts) != {'episodes', 'detections', 'duplicates'} or any(
            type(count) is not int or count < 0 for count in counts.values()):
        raise ValueError('Outcome counts must be nonnegative integers (not booleans)')
    for field, count in (('episodeIds', 'episodes'), ('detectionIds', 'detections')):
        ids = outcomes[field]
        if (not isinstance(ids, list) or any(not isinstance(item, str) or not item.strip() for item in ids)
                or len(set(ids)) != len(ids) or len(ids) != counts[count]):
            raise ValueError('Outcome ' + field + ' must contain unique strings matching its count')
    for field in ('controls', 'recovery', 'replay'):
        decisions = require_object(outcomes[field], 'Outcome ' + field)
        if not decisions or any(not isinstance(key, str) or not key.strip()
                                or not isinstance(decision, str) or not decision.strip()
                                for key, decision in decisions.items()):
            raise ValueError('Outcome ' + field + ' requires named scenario decisions as strings')


def verify_rehearsals(root, manifest, release, runs):
    json.dumps(runs, allow_nan=False)
    require_object(manifest, 'Detection manifest')
    check_files(root, manifest['files'])
    if not release.is_file() or len(runs) != 2:
        raise ValueError('Common release manifest and exactly two runs required')
    release_hash = digest(release)
    shared = require_object(json.loads(release.read_bytes()), 'Common release manifest')
    if shared.get('detectionInputs') != manifest['files']:
        raise ValueError('Common release must pin the complete detectionInputs map')
    scenario = require_object(shared.get('detectionRehearsal'), 'Release rehearsal')
    if not scenario.get('expectedOutcomes') or not scenario.get('startingStateSha256'):
        raise ValueError('Common release must pin expected outcomes and starting state')
    validate_outcomes(scenario['expectedOutcomes'])
    for run in runs:
        require_object(run, 'Run receipt')
        if run.get('mode') != 'CONNECTED' or run.get('releaseSha256') != release_hash:
            raise ValueError('Connected run does not match common release manifest')
        if not run.get('runId') or not run.get('startingStateSha256'):
            raise ValueError('Run identity and initial-state hash required')
        if run['startingStateSha256'] != scenario['startingStateSha256']:
            raise ValueError('Run does not use the approved starting state')
        if timestamp(run['finishedAt']) <= timestamp(run['startedAt']):
            raise ValueError('Invalid execution interval')
        latency = run['latencyMs']
        if type(latency) not in (int, float) or not math.isfinite(latency) or latency < 0:
            raise ValueError('Invalid wall-clock latency')
        gates = require_object(run.get('gates'), 'Run gates')
        if any(not isinstance(gates.get(gate), dict) or gates[gate].get('status') != 'PASS'
               for gate in GATES):
            raise ValueError('Unmet required gate')
        commands = run.get('commands')
        if not isinstance(commands, list) or not commands:
            raise ValueError('Missing or failed command')
        if any(not isinstance(c, dict) or not isinstance(c.get('argv'), list)
               or not c['argv'] or not c['argv'][0] or not all(isinstance(arg, str) for arg in c['argv'])
               or type(c.get('exitCode')) is not int or c['exitCode'] != 0 for c in commands):
            raise ValueError('Missing or failed command')
        check_files(root, run.get('files'))
        if any(gates[gate].get('evidenceFile') not in run['files'] for gate in GATES):
            raise ValueError('Gate evidence is not pinned')
        traces = run.get('traces', [])
        if not isinstance(traces, list) or any(not isinstance(t, dict)
                or not isinstance(t.get('displayId'), str) for t in traces):
            raise ValueError('Run traces must be a list of objects with string displayId')
        if not set(DOMAINS).issubset({t.get('displayId') for t in traces}):
            raise ValueError('Missing domain trace')
        for trace in traces:
            for key in ('datasetId', 'windowId', 'primitive', 'unit', 'baselineVersion', 'policyVersion'):
                if not trace.get(key):
                    raise ValueError('Incomplete trace: ' + key)
            if trace.get('evidenceFile') not in run['files']:
                raise ValueError('Trace evidence is not pinned')
            if not trace.get('detectionId') or not trace.get('incidentEvidenceId'):
                if trace.get('detectionId') or trace.get('incidentEvidenceId') or not trace.get('absenceReason'):
                    raise ValueError('Absent detection requires explicit reason and no incident evidence')
        outcomes = require_object(run.get('outcomes'), 'Run outcomes')
        validate_outcomes(outcomes.get('expected'))
        validate_outcomes(outcomes.get('actual'))
        if outcomes['expected'] != outcomes['actual']:
            raise ValueError('Missing or unexpected deterministic outcomes')
        if outcomes['expected'] != scenario['expectedOutcomes']:
            raise ValueError('Run changed the approved expected outcomes')
    first, second = runs
    if first['runId'] == second['runId']:
        raise ValueError('Runs must be independent')
    if timestamp(second['startedAt']) < timestamp(first['finishedAt']):
        raise ValueError('Rehearsals must execute sequentially after restore')
    for key in ('startingStateSha256', 'outcomes'):
        if first[key] != second[key]:
            raise ValueError('Rehearsal mismatch: ' + key)
    return dict(technicalStatus='PASS', humanAcceptance='PENDING', sharedReleaseApproval=False,
                releaseSha256=release_hash, runIds=[r['runId'] for r in runs])


def freeze(root):
    tracked = subprocess.check_output(['git', 'ls-files'], cwd=root, text=True).splitlines()
    prefixes = ('contracts/', 'services/ml-service/candidate-models/', 'services/ml-service/models/',
                'services/ml-service/app/', 'services/ml-service/training/',
                'services/processor/src/main/', 'services/streaming-support/src/main/')
    files = [p for p in tracked if p.startswith(prefixes) and '/training/data/' not in p]
    files += [p for p in tracked if p.endswith(('pom.xml', 'requirements-dev.txt', 'requirements-ml.txt'))]
    files += ['docs/evidence/assets/trace-link-day5-20261015/study/protocol.json']
    files += ['scripts/verify-sergiu-day6.py', 'tests/test_sergiu_day6.py']
    return dict(schemaVersion=1, scope='DETECTION_INPUTS_ONLY',
                sourceRevision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
                generatedAt=datetime.now(timezone.utc).isoformat(),
                platform=platform.platform(), python=sys.version,
                libraries={d.metadata['Name']: d.version for d in importlib.metadata.distributions()
                           if d.metadata['Name']},
                files={p: digest(root/p) for p in sorted(set(files))},
                commonReleaseStatus='PENDING', humanAcceptance='PENDING', sharedReleaseApproval=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--freeze', action='store_true')
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--release', type=Path)
    parser.add_argument('--run', type=Path, action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    # Refuse to overwrite evidence, even on a failed verification.
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8', newline='\n') as destination:
        try:
            if args.freeze:
                report = freeze(ROOT)
            else:
                if not args.manifest or not args.release:
                    raise ValueError('--manifest and --release required')
                report = verify_rehearsals(ROOT, json.loads(args.manifest.read_bytes()), args.release,
                                          [json.loads(p.read_bytes()) for p in args.run])
            code = 0
        except (ValueError, OSError, KeyError, TypeError) as error:
            report = dict(technicalStatus='INCOMPLETE', reason=str(error),
                          humanAcceptance='PENDING', sharedReleaseApproval=False)
            code = 1
        json.dump(report, destination, indent=2, allow_nan=False)
        destination.write('\n')
    print(report.get('technicalStatus', report.get('scope')))
    return code


if __name__ == '__main__': sys.exit(main())
