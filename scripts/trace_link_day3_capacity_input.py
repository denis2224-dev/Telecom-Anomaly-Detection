"""Convert actual harness exports into benchmark input, preserving missing stages.

python scripts/trace_link_day3_capacity_input.py --raw RAW.json --hardware HOST.json
  --profile scripts/trace-link-day3-load-profile.json --source-commit SHA
  --mode real-time --receipts RECEIVED.json --output INPUT.json
Then python services/ml-service/training/benchmark.py --input INPUT.json --output REPORT.json.

HOST.json must record actual host description, cpuCount and physical memoryBytes.
maxHeapBytes is retained as JVM metadata and never substituted for host RAM.
Receipts are optional but must be hash-bound to RAW.json and come from producer-file,
not protocol-oracle. Missing receipts never acquire a fabricated received count.
Partial/older harness exports missing required measured counters are rejected.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('trace_link_benchmark', ROOT / 'services/ml-service/training/benchmark.py')
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


def convert(raw, hardware, profile, source_commit, mode, raw_sha256, receipts=None):
    require = benchmark.require
    require(mode in ('real-time', 'accelerated-replay'), 'Unknown conversion mode')
    require(raw.get('mode', mode) == mode, 'Producer/conversion mode mismatch')
    require(len(source_commit) == 40 and all(c in 'abcdef0123456789' for c in source_commit), 'Actual source commit SHA required')
    require(raw.get('sourceCommit', source_commit) in (source_commit, 'UNKNOWN'), 'Producer/source commit mismatch')
    benchmark.obj(hardware, ('description', 'cpuCount', 'memoryBytes'), 'physical host manifest')
    benchmark.obj(raw, ('startedAt', 'endedAt', 'detections', 'initialPending', 'finalPending',
                        'queueCapacityBound', 'observedConcurrency', 'peakPending', 'duplicateEpisodes'), 'raw measurement')
    gaps = []
    if mode == 'real-time':
        benchmark.obj(raw, ('nativeWindows', 'capacityWindows', 'phases', 'ratePerSecond', 'evaluated',
                            'committedDetections', 'published', 'actualKafkaMessages', 'configuredConcurrency', 'resources'), 'real-time raw')
        require(raw['ratePerSecond'] == profile['steadyRatePerSecond'], 'Raw rate differs from frozen profile')
        require(raw['queueCapacityBound'] == profile['queueCapacityBound'], 'Raw queue bound differs from frozen profile')
        require(raw['configuredConcurrency'] == profile['configuredConcurrency'], 'Raw concurrency differs from frozen profile')
        native = [{'windowId': w['windowId'], 'inputSha256': digest(w['input'])} for w in raw['nativeWindows']]
        windows = native + raw['capacityWindows']
        background = {'offered': len(native), 'accepted': len(native)}
        phases = raw['phases']
        evaluated, committed, published = raw['evaluated'], raw['committedDetections'], raw['published']
        configured = raw['configuredConcurrency']
        drain = raw.get('globalDrainSeconds', raw.get('capacityDrainSeconds'))
        if 'globalDrainSeconds' not in raw: gaps.append('NATIVE_FINAL_DRAIN_UNMEASURED')
    else:
        benchmark.obj(raw, ('windows', 'inputWindows', 'outputDetections', 'actualKafkaMessages',
                            'maximumWorkerThreads', 'detectionNanos'), 'replay raw')
        windows = raw['windows']
        require(raw['inputWindows'] == len(windows), 'Replay input manifest count mismatch')
        evaluated, committed, published = raw['inputWindows'], raw['outputDetections'], raw['actualKafkaMessages']
        phases, background = [], {'offered': 0, 'accepted': 0}
        configured = raw['maximumWorkerThreads']
        drain = raw.get('drainSeconds', raw['detectionNanos'] / 1e9)
        if 'drainSeconds' not in raw: gaps.append('REPLAY_DRAIN_EXCLUDES_KAFKA_AND_RECEIVER')
    require(isinstance(windows, list) and windows, 'Actual window manifest required')
    require(len(raw['detections']) == committed, 'Committed detection export mismatch')
    require(raw['actualKafkaMessages'] == published, 'Kafka observation/published mismatch')
    identities = {}
    for envelope in raw['detections']:
        life = envelope['lifecycle']
        require(life['detectionId'] not in identities, 'Duplicate detection export identity')
        identities[life['detectionId']] = life
    received = 0
    traces = [dict(trace) for trace in raw.get('traces', [])]
    for trace in traces:
        if 'finalizedAt' in trace: trace['featureFinalizedAt'] = trace.pop('finalizedAt')
    delivery_traces = {}
    for trace in raw.get('deliveryTraces', []):
        require(trace['detectionId'] in identities and trace['detectionId'] not in delivery_traces, 'Invalid delivery trace identity')
        delivery_traces[trace['detectionId']] = dict(trace)
    if receipts is not None:
        benchmark.obj(receipts, ('source', 'producerFileSha256', 'receivedAt', 'counts', 'startedAt', 'endedAt'), 'receipt artifact')
        require(receipts['source'] == 'producer-file', 'Protocol oracle cannot establish measured receiving')
        require(receipts['producerFileSha256'] == raw_sha256, 'Receipt artifact is not bound to producer export')
        seen = set()
        for row in receipts['receivedAt']:
            identity = row['detectionId']
            require(identity in identities and identity not in seen, 'Unknown or duplicate receiver detection')
            life = identities[identity]
            require(row['episodeId'] == life['episodeId'] and row['sequence'] == life['sequence'], 'Receipt lifecycle identity mismatch')
            benchmark.timestamp(row['receivedAt'], 'incident receivedAt')
            seen.add(identity)
            trace = delivery_traces.pop(identity, {'detectionId': identity})
            trace['incidentReceivedAt'] = row['receivedAt']
            traces.append(trace)
        received = len(seen)
        require(receipts['counts']['receivedUnique'] == received, 'Receipt count does not match immutable evidence rows')
    traces.extend(delivery_traces.values())
    samples = {stage: raw.get('samplesMs', {}).get(stage, []) for stage in benchmark.STAGES}
    for stage in ('rule', 'join'):
        if not samples[stage]: gaps.append(stage.upper() + '_STAGE_UNMEASURED')
    data = {
        'schemaVersion': 'trace-link-capacity-input-v1', 'mode': mode,
        'run': {'runId': raw.get('runId', raw_sha256), 'datasetId': raw.get('runId', 'synthetic-native-fixture-v1'),
                'inputWindowsSha256': digest(windows), 'sourceCommit': source_commit,
                'startedAt': raw['startedAt'], 'endedAt': raw['endedAt']},
        'hardware': hardware, 'profile': {name: profile[name] for name in benchmark.PROFILE_FIELDS},
        'concurrency': {'configured': configured, 'observedMax': raw['observedConcurrency']}, 'windows': windows,
        'counts': {'inputUnit': 'persisted_windows', 'offered': len(windows), 'accepted': len(windows),
                   'duplicate': 0, 'invalid': 0, 'late': 0, 'finalized': len(windows), 'evaluated': evaluated,
                   'committedDetections': committed, 'published': published, 'received': received,
                   'receivingMeasured': receipts is not None, 'duplicateEpisodes': raw['duplicateEpisodes']},
        'samplesMs': samples, 'queues': {'initialDepth': raw['initialPending'], 'peakDepth': raw['peakPending'],
            'finalDepth': raw['finalPending'], 'capacityBound': raw['queueCapacityBound'], 'drainSeconds': drain},
        'inference': {'eligibleRequests': 0, 'ok': 0, 'timeout': 0, 'rejected': 0, 'unavailable': 0, 'fallback': 0},
        'resources': {'scope': 'detector-jvm-heap; physical host RAM supplied separately', 'samples': raw.get('resources', [])},
        'phaseMeasurements': phases, 'backgroundInputCounts': background, 'traces': traces, 'measurementGaps': gaps,
        'sourceEvidence': {'producerSha256': raw_sha256, 'synthetic': raw.get('synthetic'),
                           'boundary': raw.get('boundary', 'POSTGRES_TO_KAFKA_ACK'), 'jvmMaxHeapBytes': raw.get('maxHeapBytes'),
                           'jvmAvailableProcessors': raw.get('cpuCount', raw.get('availableProcessors')),
                           'sourceManifest': raw.get('sourceManifest'), 'drainBoundary': raw.get('drainBoundary')},
    }
    benchmark.summarize(data)
    return data


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('raw', 'hardware', 'profile', 'output'): parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--receipts', type=Path)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--mode', required=True, choices=('real-time', 'accelerated-replay'))
    args = parser.parse_args(argv)
    try:
        raw_bytes = args.raw.read_bytes()
        read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
        data = convert(json.loads(raw_bytes.decode('utf-8-sig')), read(args.hardware), read(args.profile), args.source_commit,
                       args.mode, hashlib.sha256(raw_bytes).hexdigest(), read(args.receipts) if args.receipts else None)
        data['sourceEvidence'].update(hardwareSha256=hashlib.sha256(args.hardware.read_bytes()).hexdigest(),
                                      profileSha256=hashlib.sha256(args.profile.read_bytes()).hexdigest(),
                                      receiptSha256=hashlib.sha256(args.receipts.read_bytes()).hexdigest() if args.receipts else None)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(data, indent=2, allow_nan=False) + '\n', encoding='utf-8')
        return 0
    except (ValueError, TypeError, KeyError, OSError) as error:
        print('Invalid or incomplete raw measurement: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
