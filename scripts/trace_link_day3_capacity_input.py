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


def native_lifecycle_traces(raw):
    """Use native five-minute windows and actual database commit times only."""
    windows = {w['input']['context']['window']['id']: w for w in raw.get('nativeWindows', [])
               if isinstance(w.get('input'), dict) and 'context' in w['input']}
    commits = {t['windowId']: t['evaluationCommittedAt'] for t in raw.get('traces', [])
               if 'windowId' in t and 'evaluationCommittedAt' in t}
    previous_severity, result = {}, []
    for envelope in sorted(raw['detections'], key=lambda d: (d['lifecycle']['episodeId'], d['lifecycle']['sequence'])):
        context, life = envelope.get('context', {}), envelope['lifecycle']
        if context.get('datasetId') != raw.get('runId'): continue
        native = windows.get(life['window']['id'])
        if native is None or native['windowId'] not in commits: continue
        committed = commits[native['windowId']]
        trace = {'detectionId': life['detectionId'], 'nativeLifecycle': True}
        if life['phase'] == 'OPEN':
            candidates = [w['input']['context']['window'] for w in windows.values()
                          if all(w['input']['context'].get(f) == context.get(f)
                                 for f in ('datasetId', 'namespace', 'domain', 'scope'))
                          and w['input']['context']['window']['to'] == life['window']['from']]
            if len(candidates) == 1:
                trace.update(firstBadWindowAt=candidates[0]['from'], firstBadWindowEnd=candidates[0]['to'], openedAt=committed)
        elif life['phase'] == 'UPDATE' and previous_severity.get(life['episodeId']) != life['severity']:
            trace.update(escalationWindowAt=life['window']['from'], escalatedAt=committed)
        elif life['phase'] == 'RECOVERY':
            trace.update(finalHealthyWindowAt=life['window']['to'], recoveredAt=committed)
        previous_severity[life['episodeId']] = life['severity']
        if len(trace) > 2: result.append(trace)
    return result


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
        require(raw.get('rateUnit') == profile['rateUnit'], 'Raw rate units differ from frozen profile')
        native = [{'windowId': w['windowId'], 'inputSha256': digest(w['input'])} for w in raw['nativeWindows']]
        windows = native + raw['capacityWindows']
        background = {'offered': len(native), 'accepted': len(native)}
        phases = raw['phases']
        evaluated, committed, published = raw['evaluated'], raw['committedDetections'], raw['published']
        configured = raw['configuredConcurrency']
        drain = raw.get('globalDrainSeconds', raw.get('drainSeconds'))
        if drain is None: gaps.append('NATIVE_FINAL_DRAIN_UNMEASURED')
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
        if raw.get('deliveryTraces') and 'windowId' in trace:
            # Per-window min/max spans may aggregate several outcomes. Exact per-detection
            # measurements below are the only samples for publication latency.
            trace.pop('outboxCreatedAt', None)
            trace.pop('kafkaAcknowledgedAt', None)
            trace.pop('incidentReceivedAt', None)
    delivery_traces = {}
    for trace in raw.get('deliveryTraces', []):
        require(trace['detectionId'] in identities and trace['detectionId'] not in delivery_traces, 'Invalid delivery trace identity')
        delivery_traces[trace['detectionId']] = dict(trace)
    receiver_run = None
    if receipts is not None:
        benchmark.obj(receipts, ('source', 'producerFileSha256', 'receivedAt', 'counts', 'startedAt', 'endedAt'), 'receipt artifact')
        require(receipts['source'] == 'producer-file', 'Protocol oracle cannot establish measured receiving')
        require(receipts['producerFileSha256'] == raw_sha256, 'Receipt artifact is not bound to producer export')
        receiver_start = benchmark.timestamp(receipts['startedAt'], 'receiver run start')
        receiver_end = benchmark.timestamp(receipts['endedAt'], 'receiver run end')
        require(receiver_start <= receiver_end, 'Invalid receiver run interval')
        # A fresh consumer of a producer export verifies receiving, but not the original live pipeline latency.
        simultaneous = (receiver_start <= benchmark.timestamp(raw['startedAt'], 'producer start')
                        and receiver_end >= benchmark.timestamp(raw['endedAt'], 'producer end'))
        if not simultaneous: gaps.append('SEPARATE_RECEIVER_REPLAY_NOT_SIMULTANEOUS')
        seen = set()
        for row in receipts['receivedAt']:
            identity = row['detectionId']
            require(identity in identities and identity not in seen, 'Unknown or duplicate receiver detection')
            life = identities[identity]
            require(row['episodeId'] == life['episodeId'] and row['sequence'] == life['sequence'], 'Receipt lifecycle identity mismatch')
            received_at = benchmark.timestamp(row['receivedAt'], 'incident receivedAt')
            require(receiver_start <= received_at <= receiver_end, 'Receipt timestamp escapes receiver run')
            if identity in delivery_traces and 'outboxCreatedAt' in delivery_traces[identity]:
                require(received_at >= benchmark.timestamp(delivery_traces[identity]['outboxCreatedAt'], 'outbox createdAt'),
                        'Receipt precedes original committed delivery')
            seen.add(identity)
            if simultaneous:
                trace = delivery_traces.pop(identity, {'detectionId': identity})
                trace['incidentReceivedAt'] = row['receivedAt']
                traces.append(trace)
        received = len(seen)
        require(receipts['counts']['receivedUnique'] == received, 'Receipt count does not match immutable evidence rows')
        receiver_run = {'source': receipts['source'], 'producerFileSha256': receipts['producerFileSha256'],
                        'startedAt': receipts['startedAt'], 'endedAt': receipts['endedAt'],
                        'simultaneousWithProducer': simultaneous, 'counts': receipts['counts'], 'receivedAt': receipts['receivedAt']}
    traces.extend(delivery_traces.values())
    if mode == 'real-time':
        detection_traces = {t['detectionId']: t for t in traces if 'detectionId' in t}
        for lifecycle_trace in native_lifecycle_traces(raw):
            identity = lifecycle_trace['detectionId']
            if identity in detection_traces: detection_traces[identity].update(lifecycle_trace)
            else:
                traces.append(lifecycle_trace)
                detection_traces[identity] = lifecycle_trace
    source_files = raw.get('sourceFiles')
    if source_files is not None:
        require(isinstance(source_files, list) and bool(source_files), 'Source file manifest must be nonempty')
        require(digest(source_files) == raw.get('sourceTreeSha256'), 'Source file manifest hash mismatch')
        paths = set()
        for entry in source_files:
            benchmark.obj(entry, ('path', 'sha256'), 'source file')
            require(entry['path'] not in paths and isinstance(entry['path'], str) and entry['path'], 'Source file identity invalid')
            require(isinstance(entry['sha256'], str) and len(entry['sha256']) == 64
                    and all(c in 'abcdef0123456789' for c in entry['sha256']), 'Invalid source file hash')
            paths.add(entry['path'])
    elif mode == 'real-time': gaps.append('SOURCE_FILE_MANIFEST_UNMEASURED')
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
                           'sourceManifest': raw.get('sourceManifest'), 'sourceFiles': source_files,
                           'sourceTreeSha256': raw.get('sourceTreeSha256'), 'receiverRun': receiver_run,
                           'drainBoundary': raw.get('drainBoundary', 'DETECTOR_JOBS_AND_OUTBOX_TO_KAFKA_ACK'),
                           'drainMeasurementField': ('globalDrainSeconds' if 'globalDrainSeconds' in raw else 'drainSeconds'),
                           'capacityOnlyDrainSeconds': raw.get('capacityDrainSeconds'),
                           'nativeLifecycleDelayDefinition': 'First bad window start and end to OPEN database commit are reported separately; escalation window start to escalation commit; final healthy window end to RECOVERY commit. Window-end time is the source finalized interval boundary. Five-minute policy waiting is included in opening/escalation, not compute latency.'},
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
