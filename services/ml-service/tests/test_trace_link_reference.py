"""Day 1 arithmetic: independent examples and the hand-reviewed shared suite."""
import copy
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service'))
from app.features.trace_link_reference import calculate

WINDOW = {'from': '2026-10-10T08:00:00Z', 'to': '2026-10-10T08:10:00Z'}


def compare(test, actual, expected):
    if isinstance(expected, dict):
        test.assertEqual(set(actual), set(expected))
        for key in expected:
            compare(test, actual[key], expected[key])
    elif isinstance(expected, list):
        test.assertEqual(len(actual), len(expected))
        for a, e in zip(actual, expected):
            compare(test, a, e)
    elif isinstance(expected, (int, float)) and not isinstance(expected, bool):
        test.assertIsInstance(actual, (int, float))
        test.assertNotIsInstance(actual, bool)
        if isinstance(expected, int):
            test.assertEqual(actual, expected)
        else:
            test.assertAlmostEqual(actual, expected, delta=1e-9)
    else:
        test.assertEqual(actual, expected)


class TraceLinkReferenceTests(unittest.TestCase):
    def test_weighted_ratio_and_null(self):
        result = calculate('ratio', {'partitions': [
            {'numerator': 990, 'denominator': 1000}, {'numerator': 9, 'denominator': 10}]}, WINDOW)
        self.assertAlmostEqual(result['values']['value'], 98.9108910891089, delta=1e-9)
        self.assertEqual(result['primitives'], {'numerator': 999, 'denominator': 1010})
        empty = calculate('ratio', {'partitions': [{'numerator': 0, 'denominator': 0}]}, WINDOW)
        self.assertIsNone(empty['values']['value'])
        self.assertEqual(empty['nullReason'], 'ZERO_DENOMINATOR')

    def test_capacity_segments_use_time_weighting(self):
        result = calculate('utilization', {'segments': [
            {'trafficGbps': 10, 'effectiveCapacityGbps': 20, 'validSeconds': 300, 'quality': 'VALID'},
            {'trafficGbps': 10, 'effectiveCapacityGbps': 10, 'validSeconds': 300, 'quality': 'VALID'}
        ], 'expectedSeconds': 600}, WINDOW)
        self.assertEqual(result['values'], {'meanPct': 75, 'peakPct': 100,
                                          'trafficMeanGbps': 10, 'transferredGbEstimate': 6000})

    def test_histogram_pools_then_interpolates_and_converts(self):
        result = calculate('histogram', {'histograms': [
            {'counts': [5, 0], 'edges': [1, 2], 'unit': 'SECONDS', 'schemaVersion': 'v1'},
            {'counts': [0, 5], 'edges': [1, 2], 'unit': 'SECONDS', 'schemaVersion': 'v1'}
        ], 'outputUnit': 'MILLISECONDS'}, WINDOW)
        self.assertEqual(result['primitives']['counts'], [5, 5])
        self.assertAlmostEqual(result['values']['p95'], 1900, delta=1e-9)

    def test_queue_conservation_and_immutable_input(self):
        raw = {'openingQueue': 10, 'submitted': 20, 'delivered': 15, 'terminalNegative': 5,
               'closingQueue': 10, 'durationSeconds': 300}
        before = copy.deepcopy(raw)
        result = calculate('queue', raw, WINDOW)
        self.assertEqual(result['values']['finalizedSuccessPct'], 75)
        self.assertEqual(result['values']['growth'], 0)
        self.assertEqual(raw, before)
        raw['closingQueue'] = 11
        self.assertEqual(calculate('queue', raw, WINDOW), {'error': 'INVALID_INPUT'})

    def test_invalid_late_bins_and_recursive_truth(self):
        cases = [
            ('ratio', {'partitions': [{'numerator': 1, 'denominator': 0}]}),
            ('ratio', {'partitions': [{'numerator': True, 'denominator': 1}]}),
            ('ratio', {'partitions': [{'numerator': 0, 'denominator': 1, 'truth': {'label': 1}}]}),
            ('histogram', {'histograms': [{'counts': [10, -1], 'edges': [1, 2],
                                         'unit': 'SECONDS', 'schemaVersion': 'v1'}],
                           'outputUnit': 'SECONDS'}),
            ('utilization', {'segments': [{'trafficGbps': float('nan'), 'effectiveCapacityGbps': 20,
                                          'validSeconds': 300, 'quality': 'VALID'}], 'expectedSeconds': 600})]
        for operation, raw in cases:
            with self.subTest(operation=operation, raw=raw):
                self.assertEqual(calculate(operation, raw, WINDOW), {'error': 'INVALID_INPUT'})

    def test_shared_hand_calculated_cases(self):
        suite = json.loads((ROOT / 'contracts/fixtures/trace-link/arithmetic-cases.json').read_text(encoding='utf-8'))
        self.assertEqual(suite['suiteVersion'], 'trace-link-arithmetic-v1')
        for case in suite['cases']:
            with self.subTest(case=case['id']):
                result = calculate(case['operation'], case['input'], case['window'])
                compare(self, result, {'error': case['expectedError']} if 'expectedError' in case else case['expected'])

    def test_boundary_rejects_missing_types_nonfinite_and_truth_before_filtering(self):
        suite = json.loads((ROOT / 'contracts/fixtures/trace-link/arithmetic-cases.json').read_text(encoding='utf-8'))
        for operation in ('ratio', 'histogram', 'utilization', 'queue', 'city_summary', 'link_snapshot', 'roaming_summary'):
            case = next(c for c in suite['cases'] if c['operation'] == operation and 'expected' in c)
            for malformed in (None, [], {}, 'invalid', 12):
                with self.subTest(operation=operation, malformed=malformed):
                    self.assertEqual(calculate(operation, malformed, WINDOW), {'error': 'INVALID_INPUT'})
            raw = copy.deepcopy(case['input'])
            raw['injectedTruth'] = {'label': 'bad'}
            self.assertEqual(calculate(operation, raw, WINDOW), {'error': 'INVALID_INPUT'})
        for end in ('2026-10-10T08:00:00Z', '2026-10-10T08:10:00+00:00',
                    '2026-10-10T24:00:00Z', '2026-10-10T23:59:60Z'):
            self.assertEqual(calculate('ratio', {'partitions': []}, {'from': WINDOW['from'], 'to': end}),
                             {'error': 'INVALID_INPUT'})

    def test_bounded_capacity_precision_and_point_dataset_membership(self):
        suite = json.loads((ROOT / 'contracts/fixtures/trace-link/arithmetic-cases.json').read_text(encoding='utf-8'))
        for operation in ('utilization', 'link_snapshot'):
            case = next(c for c in suite['cases'] if c['operation'] == operation and 'expected' in c)
            raw = copy.deepcopy(case['input'])
            row = raw['segments' if operation == 'utilization' else 'rows'][0]
            row['trafficGbps'] = row['effectiveCapacityGbps'] + 1
            self.assertEqual(calculate(operation, raw, WINDOW), {'error': 'INVALID_INPUT'})
        self.assertEqual(calculate('ratio', {'partitions': []},
                                   {'from': WINDOW['from'], 'to': '2026-10-10T08:10:00.0001Z'}),
                         {'error': 'INVALID_INPUT'})
        self.assertEqual(calculate('ratio', {'partitions': []},
                                   {'from': WINDOW['from'], 'to': '2026-10-10T08:10:00.000Z'})['nullReason'],
                         'ZERO_DENOMINATOR')
        raw = copy.deepcopy(next(c for c in suite['cases'] if c['operation'] == 'link_snapshot' and 'expected' in c)['input'])
        raw['at'] = raw['dataFrom']
        raw['rows'] = [dict(raw['rows'][0], ts='2026-10-10T07:55:00Z')]
        result = calculate('link_snapshot', raw, WINDOW)
        self.assertEqual(result['quality'], 'MISSING')
        self.assertEqual(result['nullReason'], 'NO_OBSERVATIONS')
        self.assertIsNone(result['values']['ageSeconds'])

    def test_link_age_is_explicit_when_stale_or_capacity_missing(self):
        suite = json.loads((ROOT / 'contracts/fixtures/trace-link/arithmetic-cases.json').read_text(encoding='utf-8'))
        raw = copy.deepcopy(next(c for c in suite['cases'] if c['id'] == 'link-period-exclusive-boundary')['input'])
        self.assertEqual(calculate('link_snapshot', raw, WINDOW)['values']['ageSeconds'], 300)
        raw['maxAgeSeconds'] = 299
        stale = calculate('link_snapshot', raw, WINDOW)
        self.assertEqual(stale['quality'], 'STALE')
        self.assertEqual(stale['values']['ageSeconds'], 300)
        raw['maxAgeSeconds'] = 300
        raw['rows'][1]['effectiveCapacityGbps'] = None
        missing = calculate('link_snapshot', raw, WINDOW)
        self.assertEqual(missing['nullReason'], 'MISSING_CAPACITY')
        self.assertEqual(missing['values']['ageSeconds'], 300)
        raw['at'] = raw['dataTo']
        self.assertIsNone(calculate('link_snapshot', raw, WINDOW)['values']['ageSeconds'])

    def test_mixed_latest_times_and_safe_rounding(self):
        suite = json.loads((ROOT / 'contracts/fixtures/trace-link/arithmetic-cases.json').read_text(encoding='utf-8'))
        city = copy.deepcopy(next(c for c in suite['cases'] if c['id'] == 'city-primitives-and-snapshots')['input'])
        city['rows'][1]['cityId'] = 'HIN'
        result = calculate('city_summary', city, WINDOW)
        self.assertEqual(result['nullReason'], 'SNAPSHOT_MISALIGNED')
        self.assertIsNone(result['primitives']['queue'])
        self.assertIsNone(result['primitives']['oldest'])
        self.assertIsNone(result['snapshotAt'])
        self.assertEqual(result['values']['voiceSR'], 100)
        roaming = copy.deepcopy(next(c for c in suite['cases'] if c['id'] == 'roaming-any-stale-control')['input'])
        roaming['maxAgeSeconds'] = 600
        result = calculate('roaming_summary', roaming, WINDOW)
        self.assertEqual(result['nullReason'], 'SNAPSHOT_MISALIGNED')
        self.assertIsNone(result['primitives']['users'])
        self.assertIsNone(result['values']['users'])
        roaming['rows'] = [roaming['rows'][0]]
        roaming['rows'][0]['users'] = 9007199254740991
        roaming['cityShare'] = 1
        result = calculate('roaming_summary', roaming, WINDOW)
        self.assertEqual(result['values']['users'], 9007199254740991)


if __name__ == '__main__':
    unittest.main()
