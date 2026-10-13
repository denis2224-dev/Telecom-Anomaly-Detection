"""Summarize recorded Day 3 measurements; never generate timing or capacity evidence.

CLI: python training/benchmark.py --input measured.json --output report.json
Input is trace-link-capacity-input-v1. Counts offered/accepted/duplicate/invalid/late
use inputUnit, finalized/evaluated count windows, published/received count distinct
detection envelopes. committedDetections counts distinct committed outbox rows;
duplicateEpisodes counts detected duplicate episode identities. resources.scope
names what was actually sampled. Empty stage
arrays mean unmeasured. Historical event-time waits are separate from compute.
Exit 0: passing correctness, including explicitly incomplete replay capacity;
exit 1: failed evidence gates; exit 2: invalid measurement input.
"""
import argparse
import copy
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import sys

STAGES = ('rule', 'join', 'mlHttp', 'collectionWait', 'finalizationWait',
          'detectorQueueWait', 'outboxDelivery', 'firstBadToOpen', 'escalation',
          'finalHealthyToRecovery')
BOUNDARIES = {
    'collectionWait': ('sourceObservedAt', 'receiptCommittedAt'),
    'finalizationWait': ('windowEnd', 'featureFinalizedAt'),
    'detectorQueueWait': ('featureFinalizedAt', 'detectorClaimedAt'),
    'evaluationCommit': ('detectorClaimedAt', 'evaluationCommittedAt'),
    'outboxToKafka': ('outboxCreatedAt', 'kafkaAcknowledgedAt'),
    'outboxDelivery': ('outboxCreatedAt', 'incidentReceivedAt'),
    'firstBadToOpen': ('firstBadWindowAt', 'openedAt'),
    'firstFinalizedBadToOpen': ('firstBadWindowEnd', 'openedAt'),
    'escalation': ('escalationWindowAt', 'escalatedAt'),
    'finalHealthyToRecovery': ('finalHealthyWindowAt', 'recoveredAt'),
}
COUNT_FIELDS = ('offered', 'accepted', 'duplicate', 'invalid', 'late', 'finalized', 'evaluated',
                'committedDetections', 'published', 'received', 'duplicateEpisodes')
PROFILE_FIELDS = ('warmupSeconds', 'steadySeconds', 'burstSeconds', 'burstMultiplier',
                  'drainTimeoutSeconds', 'steadyRatePerSecond', 'rateUnit')
UNITS = ('source_observations', 'persisted_windows')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def numeric(value, name, integer=False, positive=False):
    require(isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) and value >= 0, f'{name} must be a finite nonnegative number')
    require(not integer or isinstance(value, int), f'{name} must be an integer')
    require(not positive or value > 0, f'{name} must be positive')
    return value


def timestamp(value, name):
    require(isinstance(value, str) and value.endswith('Z'), f'{name} requires UTC Z timestamp')
    try:
        parsed = datetime.fromisoformat(value[:-1] + '+00:00')
        require(parsed.tzinfo is not None, f'{name} requires complete UTC timestamp')
        return parsed
    except ValueError as error:
        raise ValueError(f'Invalid timestamp: {name}') from error


def obj(value, names, name):
    require(isinstance(value, dict), f'{name} must be an object')
    require(set(names).issubset(value), f'{name} missing fields: {sorted(set(names) - set(value))}')
    return value


def distribution(values):
    ordered = sorted(values)
    def percentile(fraction):
        return ordered[math.ceil(len(ordered) * fraction) - 1] if ordered else None
    return {'samples': len(ordered), 'p50': percentile(.5), 'p95': percentile(.95), 'p99': percentile(.99),
            'min': ordered[0] if ordered else None, 'max': ordered[-1] if ordered else None}


def summarize(data):
    obj(data, ('schemaVersion', 'mode', 'run', 'hardware', 'profile', 'concurrency', 'windows',
               'counts', 'samplesMs', 'queues', 'inference', 'resources'), 'input')
    require(data['schemaVersion'] == 'trace-link-capacity-input-v1', 'Unsupported input schema')
    require(data['mode'] in ('real-time', 'accelerated-replay'), 'Unknown measurement mode')
    run = obj(data['run'], ('runId', 'datasetId', 'inputWindowsSha256', 'sourceCommit', 'startedAt', 'endedAt'), 'run')
    for name in ('runId', 'datasetId', 'sourceCommit'):
        require(isinstance(run[name], str) and bool(run[name].strip()), f'run.{name} required')
    require(bool(re.fullmatch('[a-f0-9]{64}', run['inputWindowsSha256'])), 'Invalid input window manifest hash')
    elapsed = (timestamp(run['endedAt'], 'endedAt') - timestamp(run['startedAt'], 'startedAt')).total_seconds()
    require(elapsed > 0, 'Run must have a positive measured duration')
    hardware = obj(data['hardware'], ('description', 'cpuCount', 'memoryBytes'), 'hardware')
    require(isinstance(hardware['description'], str) and bool(hardware['description'].strip()), 'Hardware description required')
    numeric(hardware['cpuCount'], 'cpuCount', integer=True, positive=True)
    numeric(hardware['memoryBytes'], 'memoryBytes', integer=True, positive=True)
    profile = obj(data['profile'], PROFILE_FIELDS, 'profile')
    require(profile['rateUnit'] in UNITS, 'Unknown rateUnit')
    for name in PROFILE_FIELDS[:-1]:
        numeric(profile[name], name, positive=name in ('steadyRatePerSecond', 'steadySeconds'))
    require(profile['burstMultiplier'] >= 1, 'burstMultiplier must be at least 1')
    concurrency = obj(data['concurrency'], ('configured', 'observedMax'), 'concurrency')
    numeric(concurrency['configured'], 'configured concurrency', integer=True, positive=True)
    numeric(concurrency['observedMax'], 'observed concurrency', integer=True)
    require(isinstance(data['windows'], list) and bool(data['windows']), 'Measured windows required')
    window_ids = set()
    window_hashes = {}
    for window in data['windows']:
        obj(window, ('windowId', 'inputSha256'), 'window')
        require(isinstance(window['windowId'], str) and window['windowId'] and window['windowId'] not in window_ids, 'Window IDs must be unique')
        require(isinstance(window['inputSha256'], str) and bool(re.fullmatch('[a-f0-9]{64}', window['inputSha256'])), 'Invalid window input hash')
        window_ids.add(window['windowId'])
        window_hashes[window['windowId']] = window['inputSha256']
    manifest_hash = hashlib.sha256(json.dumps(data['windows'], sort_keys=True, separators=(',', ':'),
                                             ensure_ascii=False).encode()).hexdigest()
    require(run['inputWindowsSha256'] == manifest_hash, 'Input window manifest hash mismatch')
    counts = obj(data['counts'], (*COUNT_FIELDS, 'inputUnit'), 'counts')
    receiving_measured = counts.get('receivingMeasured', True)
    require(isinstance(receiving_measured, bool), 'receivingMeasured must be boolean')
    require(receiving_measured or counts['received'] == 0, 'Unmeasured receipt cannot have a received count')
    background = obj(data.get('backgroundInputCounts', {'offered': 0, 'accepted': 0}), ('offered', 'accepted'), 'backgroundInputCounts')
    for name in ('offered', 'accepted'): numeric(background[name], 'background ' + name, integer=True)
    require(background['accepted'] <= background['offered'], 'Background accepted exceeds offered')
    require(counts['inputUnit'] == profile['rateUnit'], 'Input/rate units differ')
    for name in COUNT_FIELDS:
        numeric(counts[name], name, integer=True)
    samples = obj(data['samplesMs'], STAGES, 'samplesMs')
    for name in STAGES:
        require(isinstance(samples[name], list), f'{name} samples must be an array')
        for sample in samples[name]:
            numeric(sample, f'{name} sample')
    queues = obj(data['queues'], ('initialDepth', 'peakDepth', 'finalDepth', 'capacityBound', 'drainSeconds'), 'queues')
    for name in ('initialDepth', 'peakDepth', 'finalDepth', 'capacityBound'):
        numeric(queues[name], name, integer=True, positive=name == 'capacityBound')
    require(queues['peakDepth'] >= max(queues['initialDepth'], queues['finalDepth']), 'Queue peak is below an observed depth')
    if queues['drainSeconds'] is not None:
        numeric(queues['drainSeconds'], 'drainSeconds')
    inference = obj(data['inference'], ('eligibleRequests', 'ok', 'timeout', 'rejected', 'unavailable', 'fallback'), 'inference')
    for name in ('eligibleRequests', 'ok', 'timeout', 'rejected', 'unavailable', 'fallback'):
        numeric(inference[name], name, integer=True)
    resources = obj(data['resources'], ('scope', 'samples'), 'resources')
    require(isinstance(resources['scope'], str) and bool(resources['scope'].strip()), 'Resource measurement scope required')
    require(isinstance(resources['samples'], list), 'Resource samples must be an array')
    for sample in resources['samples']:
        obj(sample, ('at',), 'resource sample')
        timestamp(sample['at'], 'resource at')
        for name, value in sample.items():
            if name != 'at': numeric(value, f'resource {name}')

    derived = {name: [] for name in BOUNDARIES}
    traces = data.get('traces', [])
    require(isinstance(traces, list), 'traces must be an array')
    time_names = {name for pair in BOUNDARIES.values() for name in pair}
    for trace in traces:
        require(isinstance(trace, dict) and ('windowId' in trace or 'detectionId' in trace), 'Trace identity required')
        if 'windowId' in trace:
            require(trace['windowId'] in window_ids, 'Trace window absent from input manifest')
            if 'inputSha256' in trace:
                require(trace['inputSha256'] == window_hashes[trace['windowId']], 'Trace input hash differs from manifest')
        parsed = {name: timestamp(trace[name], name) for name in time_names if name in trace}
        for name, (start, end) in BOUNDARIES.items():
            if start in parsed and end in parsed:
                duration = (parsed[end] - parsed[start]).total_seconds() * 1000
                numeric(duration, f'trace {name}')
                derived[name].append(duration)

    phases = data.get('phaseMeasurements', [])
    require(isinstance(phases, list), 'phaseMeasurements must be an array')
    measured_phases = {}
    previous_end = timestamp(run['startedAt'], 'startedAt')
    for phase in phases:
        obj(phase, ('phase', 'startedAt', 'endedAt', 'offered', 'accepted'), 'phase measurement')
        name = phase['phase']
        require(name in ('warmup', 'steady', 'burst', 'drain') and name not in measured_phases, 'Unknown or duplicate phase')
        start, end = timestamp(phase['startedAt'], 'phase startedAt'), timestamp(phase['endedAt'], 'phase endedAt')
        require(previous_end <= start < end <= timestamp(run['endedAt'], 'endedAt'), 'Phase intervals overlap or escape run')
        for field in ('offered', 'accepted'): numeric(phase[field], f'phase {field}', integer=True)
        require(phase['accepted'] <= phase['offered'], 'Phase accepted count exceeds offered')
        seconds = (end - start).total_seconds()
        measured_phases[name] = {**phase, 'elapsedSeconds': seconds, 'acceptedRatePerSecond': phase['accepted'] / seconds}
        previous_end = end

    failures = []
    if counts['offered'] != sum(counts[name] for name in ('accepted', 'duplicate', 'invalid', 'late')):
        failures.append('INPUT_COUNT_MISMATCH')
    if counts['evaluated'] != counts['finalized']: failures.append('EVALUATION_NOT_DRAINED')
    if counts['finalized'] != len(window_ids): failures.append('WINDOW_MANIFEST_COUNT_MISMATCH')
    if counts['committedDetections'] != counts['published']: failures.append('COMMITTED_OUTPUT_NOT_PUBLISHED')
    if receiving_measured and counts['received'] != counts['published']: failures.append('RECEIVING_NOT_DRAINED')
    if counts['duplicateEpisodes'] > 0: failures.append('DUPLICATE_EPISODES')
    if phases and any(counts[name] != sum(phase[name] for phase in phases) + background[name] for name in ('offered', 'accepted')):
        failures.append('PHASE_COUNT_MISMATCH')
    if concurrency['observedMax'] > concurrency['configured']: failures.append('CONCURRENCY_BOUND_EXCEEDED')
    if queues['peakDepth'] > queues['capacityBound']: failures.append('QUEUE_BOUND_EXCEEDED')
    if queues['finalDepth'] > queues['initialDepth']: failures.append('QUEUE_NOT_DRAINED')
    if queues['drainSeconds'] is None or queues['drainSeconds'] > profile['drainTimeoutSeconds']:
        failures.append('DRAIN_TIMEOUT_OR_UNMEASURED')
    if inference['eligibleRequests'] != sum(inference[name] for name in ('ok', 'timeout', 'rejected', 'unavailable')):
        failures.append('INFERENCE_COUNT_MISMATCH')
    if inference['fallback'] != sum(inference[name] for name in ('timeout', 'rejected', 'unavailable')):
        failures.append('FALLBACK_COUNT_MISMATCH')
    correctness = {'status': 'FAIL' if failures else 'PASS', 'reasons': failures}
    gaps = data.get('measurementGaps', [])
    require(isinstance(gaps, list) and all(isinstance(reason, str) and reason for reason in gaps), 'Invalid measurementGaps')
    incomplete = gaps.copy()
    if not receiving_measured: incomplete.append('INCIDENT_RECEIPT_UNMEASURED')
    if data['mode'] == 'accelerated-replay': incomplete.append('ACCELERATED_REPLAY_NOT_REAL_TIME_CAPACITY')
    if elapsed < sum(profile[name] for name in ('warmupSeconds', 'steadySeconds', 'burstSeconds')):
        incomplete.append('FULL_PROFILE_NOT_MEASURED')
    if not resources['samples']: incomplete.append('RESOURCE_USE_UNMEASURED')
    if data['mode'] == 'real-time':
        for stage in ('rule', 'join'):
            if not samples[stage]: incomplete.append(stage.upper() + '_STAGE_UNMEASURED')
        if counts['inputUnit'] == 'persisted_windows':
            incomplete.append('SOURCE_COLLECTION_AND_FINALIZATION_CAPACITY_UNMEASURED')
        if not all(name in measured_phases for name in ('warmup', 'steady', 'burst')):
            incomplete.append('PROFILE_PHASES_UNMEASURED')
        else:
            for name in ('warmup', 'steady', 'burst'):
                if measured_phases[name]['elapsedSeconds'] < profile[name + 'Seconds']:
                    incomplete.append(name.upper() + '_DURATION_NOT_MEASURED')
            for name, multiplier in (('steady', 1), ('burst', profile['burstMultiplier'])):
                phase = measured_phases[name]
                target = profile['steadyRatePerSecond'] * multiplier * profile[name + 'Seconds']
                if phase['offered'] < math.floor(target):
                    incomplete.append(name.upper() + '_OFFERED_RATE_NOT_MEASURED')
    capacity = {'status': 'FAIL' if failures else 'INCOMPLETE' if incomplete else 'PASS', 'reasons': failures or incomplete}
    limitations = ['New-domain ML is not eligible on Day 3; mlHttp samples apply only to eligible existing models.',
                   'Reported whole-run rates include warmup, burst and drain; they are not steady-state maxima.']
    if profile['rateUnit'] == 'persisted_windows':
        limitations.append('Input starts at finalized persisted windows; source collection and live finalization capacity are unmeasured.')
    if data['mode'] == 'accelerated-replay':
        limitations.append('Historical event-time waiting is simulated and must not be reported as algorithm latency.')
    return {
        'schemaVersion': 'trace-link-capacity-report-v1', 'status': capacity['status'], 'mode': data['mode'],
        'eventTimeBasis': 'SIMULATED_HISTORICAL_EVENT_TIME' if data['mode'] == 'accelerated-replay' else 'LIVE_UTC',
        'run': copy.deepcopy(run), 'hardware': copy.deepcopy(hardware), 'profile': copy.deepcopy(profile),
        'concurrency': copy.deepcopy(concurrency), 'counts': copy.deepcopy(counts), 'queues': copy.deepcopy(queues),
        'inference': copy.deepcopy(inference), 'resources': copy.deepcopy(resources), 'newDomainMl': 'NOT_ELIGIBLE_DAY3',
        'windows': copy.deepcopy(data['windows']), 'traces': copy.deepcopy(traces), 'phaseMeasurements': measured_phases,
        'backgroundInputCounts': copy.deepcopy(background),
        'sourceEvidence': copy.deepcopy(data.get('sourceEvidence', {})),
        'elapsedSeconds': elapsed, 'wholeRunRatesPerSecond': {'acceptedInputs': counts['accepted'] / elapsed,
            'evaluatedWindows': counts['evaluated'] / elapsed, 'receivedDetections': counts['received'] / elapsed},
        'latencyMs': {name: distribution(samples[name]) for name in STAGES},
        'durableBoundaryMs': {name: distribution(values) for name, values in derived.items()},
        'gates': {'correctness': correctness, 'realTimeCapacity': capacity}, 'limitations': limitations,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        raw = args.input.read_bytes()
        report = summarize(json.loads(raw.decode('utf-8-sig')))
        report['rawEvidence'] = {'path': str(args.input.resolve()), 'sha256': hashlib.sha256(raw).hexdigest()}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + '\n', encoding='utf-8')
        return 1 if report['status'] == 'FAIL' else 0
    except (ValueError, TypeError, KeyError, OSError) as error:
        print(f'Invalid capacity evidence: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
