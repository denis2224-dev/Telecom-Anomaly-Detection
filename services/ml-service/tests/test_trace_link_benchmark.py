"""Capacity reports use recorded measurements and explicitly distinguish replay."""
import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'training' / 'benchmark.py'
spec = importlib.util.spec_from_file_location('trace_link_benchmark', MODULE)
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


def measured():
    return {
        'schemaVersion': 'trace-link-capacity-input-v1', 'mode': 'accelerated-replay',
        'run': {'runId': 'bounded-run', 'datasetId': 'synthetic-native-v1', 'inputWindowsSha256': 'a' * 64,
                'sourceCommit': 'b' * 40, 'startedAt': '2026-10-12T10:00:00Z', 'endedAt': '2026-10-12T10:00:10Z'},
        'hardware': {'description': 'test JVM process', 'cpuCount': 4, 'memoryBytes': 1000000},
        'profile': {'warmupSeconds': 120, 'steadySeconds': 900, 'burstSeconds': 60,
                    'burstMultiplier': 2, 'drainTimeoutSeconds': 300, 'steadyRatePerSecond': 1,
                    'rateUnit': 'persisted_windows'},
        'concurrency': {'configured': 4, 'observedMax': 2},
        'windows': [{'windowId': 'w1', 'inputSha256': 'c' * 64}],
        'counts': {'inputUnit': 'persisted_windows', 'offered': 1, 'accepted': 1, 'duplicate': 0,
                   'invalid': 0, 'late': 0, 'finalized': 1, 'evaluated': 1,
                   'committedDetections': 2, 'published': 2, 'received': 2, 'duplicateEpisodes': 0},
        'samplesMs': {'rule': [1, 2, 3, 4], 'join': [2], 'mlHttp': [], 'collectionWait': [],
                      'finalizationWait': [], 'detectorQueueWait': [], 'outboxDelivery': [],
                      'firstBadToOpen': [], 'escalation': [], 'finalHealthyToRecovery': []},
        'queues': {'initialDepth': 0, 'peakDepth': 1, 'finalDepth': 0, 'capacityBound': 100, 'drainSeconds': 1},
        'inference': {'eligibleRequests': 0, 'ok': 0, 'timeout': 0, 'rejected': 0, 'unavailable': 0, 'fallback': 0},
        'resources': {'scope': 'detector-jvm', 'samples': [{'at': '2026-10-12T10:00:01Z', 'heapUsedBytes': 1000}]},
    }


class TraceLinkBenchmarkTest(unittest.TestCase):
    def test_percentiles_and_empty_samples_are_not_fabricated(self):
        report = benchmark.summarize(measured())
        self.assertEqual(report['latencyMs']['rule'], {'samples': 4, 'p50': 2, 'p95': 4, 'p99': 4, 'min': 1, 'max': 4})
        self.assertIsNone(report['latencyMs']['mlHttp']['p95'])
        self.assertEqual(report['newDomainMl'], 'NOT_ELIGIBLE_DAY3')
        self.assertEqual(report['gates']['correctness']['status'], 'PASS')
        self.assertEqual(report['gates']['realTimeCapacity']['status'], 'INCOMPLETE')

    def test_counts_and_bounded_concurrency_fail_without_changing_measurements(self):
        data = measured()
        data['counts']['received'] = 1
        data['counts']['offered'] = 2
        data['concurrency']['observedMax'] = 5
        report = benchmark.summarize(data)
        self.assertEqual(report['status'], 'FAIL')
        self.assertIn('INPUT_COUNT_MISMATCH', report['gates']['correctness']['reasons'])
        self.assertIn('RECEIVING_NOT_DRAINED', report['gates']['correctness']['reasons'])
        self.assertEqual(report['counts'], data['counts'])

    def test_rejects_missing_nonfinite_negative_and_unknown_units(self):
        for mutate in [lambda d: d.pop('hardware'), lambda d: d['samplesMs']['rule'].append(-1),
                       lambda d: d['samplesMs']['rule'].append(float('nan')),
                       lambda d: d['profile'].update(rateUnit='calls')]:
            data = measured(); mutate(data)
            with self.assertRaises(ValueError):
                benchmark.summarize(data)

    def test_realtime_requires_actual_complete_profile_and_queues(self):
        data = measured(); data['mode'] = 'real-time'
        self.assertEqual(benchmark.summarize(data)['gates']['realTimeCapacity']['status'], 'INCOMPLETE')
        data['run']['endedAt'] = '2026-10-12T10:18:01Z'
        self.assertEqual(benchmark.summarize(data)['gates']['realTimeCapacity']['status'], 'INCOMPLETE')
        data['phaseMeasurements'] = [
            {'phase': 'warmup', 'startedAt': '2026-10-12T10:00:00Z', 'endedAt': '2026-10-12T10:02:00Z', 'offered': 120, 'accepted': 120},
            {'phase': 'steady', 'startedAt': '2026-10-12T10:02:00Z', 'endedAt': '2026-10-12T10:17:00Z', 'offered': 900, 'accepted': 900},
            {'phase': 'burst', 'startedAt': '2026-10-12T10:17:00Z', 'endedAt': '2026-10-12T10:18:00Z', 'offered': 120, 'accepted': 120},
        ]
        self.assertEqual(benchmark.summarize(data)['status'], 'FAIL')
        self.assertIn('PHASE_COUNT_MISMATCH', benchmark.summarize(data)['gates']['correctness']['reasons'])
        data['counts'].update(offered=1140, accepted=1140, finalized=1140, evaluated=1140)
        data['windows'] = [{'windowId': f'w{i}', 'inputSha256': 'c' * 64} for i in range(1140)]
        self.assertEqual(benchmark.summarize(data)['gates']['realTimeCapacity']['status'], 'PASS')
        data['queues']['finalDepth'] = 1
        self.assertEqual(benchmark.summarize(data)['status'], 'FAIL')

    def test_committed_loss_duplicate_episodes_and_manifest_mismatch_fail(self):
        for update, reason in [({'committedDetections': 3}, 'COMMITTED_OUTPUT_NOT_PUBLISHED'),
                               ({'duplicateEpisodes': 1}, 'DUPLICATE_EPISODES'),
                               ({'finalized': 2, 'evaluated': 2}, 'WINDOW_MANIFEST_COUNT_MISMATCH')]:
            data = measured(); data['counts'].update(update)
            self.assertIn(reason, benchmark.summarize(data)['gates']['correctness']['reasons'])

    def test_cli_preserves_raw_evidence_hash_and_reports_invalid_input(self):
        import hashlib
        import json
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            source, output = path / 'input.json', path / 'output.json'
            raw = json.dumps(measured()).encode('utf-8')
            source.write_bytes(raw)
            self.assertEqual(benchmark.main(['--input', str(source), '--output', str(output)]), 0)
            report = json.loads(output.read_text())
            self.assertEqual(report['rawEvidence']['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual(report['status'], 'INCOMPLETE')
            source.write_text('{}')
            self.assertEqual(benchmark.main(['--input', str(source), '--output', str(output)]), 2)

    def test_durable_boundaries_derive_delay_and_historical_wait_is_labelled(self):
        data = measured()
        data['traces'] = [{'windowId': 'w1', 'windowEnd': '2026-10-10T08:40:00Z',
                           'featureFinalizedAt': '2026-10-12T10:00:01Z', 'detectorClaimedAt': '2026-10-12T10:00:02Z',
                           'evaluationCommittedAt': '2026-10-12T10:00:03Z', 'outboxCreatedAt': '2026-10-12T10:00:03Z',
                           'kafkaAcknowledgedAt': '2026-10-12T10:00:04Z', 'incidentReceivedAt': '2026-10-12T10:00:05Z'}]
        report = benchmark.summarize(data)
        self.assertEqual(report['durableBoundaryMs']['detectorQueueWait']['p50'], 1000)
        self.assertEqual(report['durableBoundaryMs']['outboxDelivery']['p50'], 2000)
        self.assertEqual(report['eventTimeBasis'], 'SIMULATED_HISTORICAL_EVENT_TIME')
        data['traces'][0]['detectorClaimedAt'] = '2026-10-12T09:00:00Z'
        with self.assertRaises(ValueError): benchmark.summarize(data)


if __name__ == '__main__':
    unittest.main()
