"""Conversion preserves measured counters and refuses unverifiable receipt claims."""
import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/trace_link_day3_capacity_input.py'
spec = importlib.util.spec_from_file_location('capacity_input', SCRIPT)
converter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(converter)


def replay():
    return {'startedAt': '2026-10-13T10:00:00Z', 'endedAt': '2026-10-13T10:00:02Z',
            'inputWindows': 2, 'outputDetections': 1, 'actualKafkaMessages': 1, 'maximumWorkerThreads': 4,
            'observedConcurrency': 2, 'initialPending': 0, 'peakPending': 2, 'finalPending': 0,
            'queueCapacityBound': 2, 'duplicateEpisodes': 0, 'detectionNanos': 1000000000,
            'maxHeapBytes': 9000000, 'windows': [{'windowId': 'w1', 'inputSha256': 'a' * 64},
                                              {'windowId': 'w2', 'inputSha256': 'b' * 64}],
            'detections': [{'lifecycle': {'detectionId': 'd1', 'episodeId': 'e1', 'sequence': 1}}]}


def profile():
    return {'warmupSeconds': 120, 'steadySeconds': 900, 'burstSeconds': 60, 'burstMultiplier': 2,
            'drainTimeoutSeconds': 300, 'steadyRatePerSecond': 1, 'rateUnit': 'persisted_windows',
            'configuredConcurrency': 4, 'queueCapacityBound': 1000}


class CapacityInputTest(unittest.TestCase):
    def convert(self, raw=None, receipts=None, mode='accelerated-replay'):
        return converter.convert(raw or replay(), {'description': 'actual host', 'cpuCount': 8, 'memoryBytes': 1000000},
                                 profile(), 'c' * 40, mode, 'd' * 64, receipts)

    def receipt(self):
        return {'source': 'producer-file', 'producerFileSha256': 'd' * 64,
                'receivedAt': [{'detectionId': 'd1', 'episodeId': 'e1', 'sequence': 1, 'receivedAt': '2026-10-13T10:00:03Z'}],
                'counts': {'receivedUnique': 1}, 'startedAt': '2026-10-13T10:00:02Z', 'endedAt': '2026-10-13T10:00:04Z'}

    def test_missing_receipts_are_unmeasured_and_host_ram_is_not_jvm_heap(self):
        data = self.convert()
        report = converter.benchmark.summarize(data)
        self.assertEqual(data['counts']['received'], 0)
        self.assertFalse(data['counts']['receivingMeasured'])
        self.assertEqual(data['hardware']['memoryBytes'], 1000000)
        self.assertEqual(data['sourceEvidence']['jvmMaxHeapBytes'], 9000000)
        self.assertEqual(report['gates']['correctness']['status'], 'PASS')
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertIn('INCIDENT_RECEIPT_UNMEASURED', report['gates']['realTimeCapacity']['reasons'])

    def test_receipts_require_exact_source_hash_and_committed_identity(self):
        self.assertEqual(self.convert(receipts=self.receipt())['counts']['received'], 1)
        for field, value in [('source', 'protocol-oracle'), ('producerFileSha256', 'e' * 64)]:
            receipt = self.receipt(); receipt[field] = value
            with self.assertRaises(ValueError): self.convert(receipts=receipt)
        for field, value in [('detectionId', 'missing'), ('episodeId', 'other'), ('sequence', 2)]:
            receipt = self.receipt(); receipt['receivedAt'][0][field] = value
            with self.assertRaises(ValueError): self.convert(receipts=receipt)
        receipt = self.receipt(); receipt['receivedAt'].append(receipt['receivedAt'][0].copy())
        with self.assertRaises(ValueError): self.convert(receipts=receipt)

    def test_missing_actual_counters_and_nonactual_host_memory_are_rejected(self):
        for field in ('observedConcurrency', 'peakPending', 'startedAt', 'windows'):
            raw = replay(); raw.pop(field)
            with self.assertRaises(ValueError): self.convert(raw=raw)
        with self.assertRaises(ValueError):
            converter.convert(replay(), {'description': 'JVM', 'cpuCount': 4, 'maxHeapBytes': 20}, profile(), 'c' * 40,
                              'accelerated-replay', 'd' * 64)

    def test_missing_some_receipts_fails_real_count_reconciliation(self):
        receipt = self.receipt(); receipt['receivedAt'] = []; receipt['counts']['receivedUnique'] = 0
        data = self.convert(receipts=receipt)
        self.assertIn('RECEIVING_NOT_DRAINED', converter.benchmark.summarize(data)['gates']['correctness']['reasons'])

    def test_separate_receiver_replay_retains_counts_without_original_pipeline_latency(self):
        raw = replay(); raw['deliveryTraces'] = [{'detectionId': 'd1', 'outboxCreatedAt': '2026-10-13T10:00:01Z', 'kafkaAcknowledgedAt': '2026-10-13T10:00:02Z'}]
        report = converter.benchmark.summarize(self.convert(raw=raw, receipts=self.receipt()))
        self.assertEqual(report['durableBoundaryMs']['outboxDelivery']['samples'], 0)
        self.assertEqual(report['counts']['received'], 1)
        self.assertIn('SEPARATE_RECEIVER_REPLAY_NOT_SIMULTANEOUS', report['gates']['realTimeCapacity']['reasons'])
        self.assertFalse(report['sourceEvidence']['receiverRun']['simultaneousWithProducer'])
        receipt = self.receipt(); receipt['receivedAt'][0]['receivedAt'] = '2026-10-13T10:00:00Z'
        with self.assertRaises(ValueError): self.convert(raw=raw, receipts=receipt)

    def test_simultaneous_receiver_uses_actual_matching_delivery_identity(self):
        raw = replay(); raw['deliveryTraces'] = [{'detectionId': 'd1', 'outboxCreatedAt': '2026-10-13T10:00:01Z', 'kafkaAcknowledgedAt': '2026-10-13T10:00:02Z'}]
        receipt = self.receipt(); receipt['startedAt'] = raw['startedAt']
        report = converter.benchmark.summarize(self.convert(raw=raw, receipts=receipt))
        self.assertEqual(report['durableBoundaryMs']['outboxDelivery']['p50'], 2000)

    def test_realtime_keeps_background_native_inputs_separate_from_phased_rate(self):
        raw = replay()
        raw.update(runId='measured-native', nativeWindows=[{'windowId': 'n1', 'input': {'observed': 'actual'}}],
                   capacityWindows=raw['windows'], evaluated=3, committedDetections=1, published=1,
                   configuredConcurrency=4, ratePerSecond=1, rateUnit='persisted_windows', resources=[], queueCapacityBound=1000,
                   globalDrainSeconds=.1,
                   phases=[{'phase': 'warmup', 'startedAt': raw['startedAt'], 'endedAt': raw['endedAt'], 'offered': 2, 'accepted': 2}])
        data = self.convert(raw=raw, mode='real-time')
        self.assertEqual(data['counts']['accepted'], 3)
        self.assertEqual(data['backgroundInputCounts'], {'offered': 1, 'accepted': 1})
        self.assertEqual(converter.benchmark.summarize(data)['gates']['correctness']['status'], 'PASS')
        raw['ratePerSecond'] = 4
        with self.assertRaises(ValueError): self.convert(raw=raw, mode='real-time')

    def test_native_lifecycle_delays_use_matching_database_commit_boundaries(self):
        context = {'datasetId': 'native', 'namespace': 'ns', 'domain': 'TRANSPORT', 'scope': {'id': 'scope'}}
        raw = {'runId': 'native', 'nativeWindows': [], 'traces': [], 'detections': []}
        windows = [('candidate', '10:00', '10:05'), ('opening', '10:05', '10:10'),
                   ('escalation', '10:10', '10:15'), ('recovery', '10:25', '10:30')]
        for identity, start, end in windows:
            window = {'id': identity, 'from': f'2026-10-13T{start}:00Z', 'to': f'2026-10-13T{end}:00Z'}
            raw['nativeWindows'].append({'windowId': identity, 'input': {'context': {**context, 'window': window}}})
            raw['traces'].append({'windowId': identity, 'evaluationCommittedAt': f'2026-10-13T{end}:01Z'})
        for sequence, (identity, phase, severity) in enumerate([('opening', 'OPEN', 'HIGH'), ('escalation', 'UPDATE', 'CRITICAL'), ('recovery', 'RECOVERY', 'CRITICAL')], 1):
            window = next(w['input']['context']['window'] for w in raw['nativeWindows'] if w['windowId'] == identity)
            raw['detections'].append({'context': context, 'lifecycle': {'episodeId': 'ep', 'detectionId': f'd{sequence}', 'sequence': sequence,
                                     'window': window, 'phase': phase, 'severity': severity}})
        traces = converter.native_lifecycle_traces(raw)
        self.assertEqual(len(traces), 3)
        self.assertEqual(traces[0]['firstBadWindowAt'], '2026-10-13T10:00:00Z')
        self.assertEqual(traces[0]['firstBadWindowEnd'], '2026-10-13T10:05:00Z')
        self.assertEqual(traces[0]['openedAt'], '2026-10-13T10:10:01Z')
        self.assertEqual(traces[1]['escalationWindowAt'], '2026-10-13T10:10:00Z')
        self.assertEqual(traces[2]['finalHealthyWindowAt'], '2026-10-13T10:30:00Z')

    def test_exact_delivery_traces_replace_aggregate_window_spans(self):
        raw = replay()
        raw['traces'] = [{'windowId': 'w1', 'finalizedAt': '2026-10-13T10:00:00Z',
                          'detectorClaimedAt': '2026-10-13T10:00:00.5Z',
                          'outboxCreatedAt': '2026-10-13T10:00:00Z', 'kafkaAcknowledgedAt': '2026-10-13T10:00:02Z'}]
        raw['deliveryTraces'] = [{'detectionId': 'd1', 'outboxCreatedAt': '2026-10-13T10:00:01Z',
                                  'kafkaAcknowledgedAt': '2026-10-13T10:00:02Z'}]
        data = self.convert(raw=raw)
        report = converter.benchmark.summarize(data)
        self.assertEqual(report['durableBoundaryMs']['outboxToKafka']['samples'], 1)
        self.assertEqual(report['durableBoundaryMs']['outboxToKafka']['p50'], 1000)
        self.assertEqual(report['durableBoundaryMs']['detectorQueueWait']['samples'], 1)
        delivery = [t for t in data['traces'] if 'outboxCreatedAt' in t]
        self.assertEqual([t['detectionId'] for t in delivery], ['d1'])
        window_trace = next(t for t in data['traces'] if 'windowId' in t)
        self.assertNotIn('outboxCreatedAt', window_trace)

    def test_realtime_global_drain_and_source_tree_hash_are_preserved(self):
        raw = replay()
        raw.update(runId='native', nativeWindows=[], capacityWindows=raw['windows'], evaluated=2,
                   committedDetections=1, published=1, configuredConcurrency=4, ratePerSecond=1,
                   rateUnit='persisted_windows', resources=[], queueCapacityBound=1000, phases=[],
                   drainSeconds=.5, capacityDrainSeconds=.1,
                   sourceFiles=[{'path': 'Harness.java', 'sha256': 'a' * 64}])
        raw['sourceTreeSha256'] = converter.digest(raw['sourceFiles'])
        data = self.convert(raw=raw, mode='real-time')
        self.assertEqual(data['queues']['drainSeconds'], .5)
        self.assertEqual(data['sourceEvidence']['sourceFiles'], raw['sourceFiles'])
        self.assertEqual(data['sourceEvidence']['sourceTreeSha256'], raw['sourceTreeSha256'])
        self.assertNotIn('NATIVE_FINAL_DRAIN_UNMEASURED', data['measurementGaps'])
        raw['sourceTreeSha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'Source file manifest hash mismatch'): self.convert(raw=raw, mode='real-time')
        raw['sourceTreeSha256'] = converter.digest(raw['sourceFiles']); raw['rateUnit'] = 'source_observations'
        with self.assertRaisesRegex(ValueError, 'rate units differ'): self.convert(raw=raw, mode='real-time')

    def test_cli_pipeline_hashes_actual_artifact_bytes_and_reports_incomplete_replay(self):
        import hashlib
        import json
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            raw, hardware, frozen = path / 'raw.json', path / 'hardware.json', path / 'profile.json'
            output, report = path / 'input.json', path / 'report.json'
            raw.write_text(json.dumps(replay()))
            hardware.write_text(json.dumps({'description': 'test-only host fixture', 'cpuCount': 8, 'memoryBytes': 1000000}))
            frozen.write_text(json.dumps(profile()))
            args = ['--raw', str(raw), '--hardware', str(hardware), '--profile', str(frozen), '--source-commit', 'c' * 40,
                    '--mode', 'accelerated-replay', '--output', str(output)]
            self.assertEqual(converter.main(args), 0)
            self.assertEqual(converter.benchmark.main(['--input', str(output), '--output', str(report)]), 0)
            recorded = json.loads(report.read_text())
            self.assertEqual(recorded['status'], 'INCOMPLETE')
            self.assertEqual(recorded['sourceEvidence']['producerSha256'], hashlib.sha256(raw.read_bytes()).hexdigest())
            hardware.write_text('{}')
            self.assertEqual(converter.main(args), 2)


if __name__ == '__main__': unittest.main()
