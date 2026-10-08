"""Offline Day 3 acceptance oracles; these do not exercise a live priority API."""

import json
from decimal import Decimal
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Day3QueueDisplayCasesTest(unittest.TestCase):
    def setUp(self):
        self.suite = json.loads((ROOT / 'contracts/fixtures/geography/day3-queue-display-cases.json').read_text())

    def test_order_examples_respect_state_severity_service_and_stable_ties(self):
        severity = {'CRITICAL': 0, 'HIGH': 1, 'MEDIUM': 2}
        for case in self.suite['queueCases']:
            with self.subTest(case=case['id']):
                def key(row):
                    bucket = (2 if row['technicalState'] == 'RECOVERED' else
                              0 if row['technicalState'] == 'ONGOING' and row['freshness'] == 'FRESH' else 1)
                    # Explicit service grouping makes mixed-service comparison transitive.
                    return (bucket, severity[row['severity']] if bucket == 0 else 0,
                            row['service'] if bucket == 0 else '',
                            row['comparableImpact'] is None if bucket == 0 else False,
                            -row['comparableImpact'] if bucket == 0 and row['comparableImpact'] is not None else 0,
                            row['firstObservedAt'], row['incidentId'])
                ordered = sorted(case['items'], key=key)
                self.assertEqual([row['incidentId'] for row in ordered], case['expectedIds'])
                self.assertEqual(len(set(case['expectedIds'])), len(case['items']))
                # Workflow resolution must not reorder technical incidents.
                changed = [dict(row, analystStatus='RESOLVED') for row in case['items']]
                self.assertEqual([row['incidentId'] for row in sorted(changed, key=key)], case['expectedIds'])

    def test_display_math_uses_counters_signed_pp_and_true_percentile(self):
        for case in self.suite['displayCases']:
            with self.subTest(case=case['id']):
                source, expected = case['input'], case['expected']
                self.assertIsNone(expected['uniqueSubscribers'])
                if source['service'] == 'VOLTE':
                    denominator = source['attempts'] - source['userOutcomes']
                    observed = Decimal(source['technicalSuccesses']) * 100 / denominator
                    self.assertEqual(expected['denominator'], denominator)
                    self.assertEqual(Decimal(str(expected['observed'])), observed)
                    baseline = source['baseline']
                    if baseline is None:
                        self.assertIsNone(expected['deltaPp'])
                        self.assertIsNone(expected['extraFailedAttempts'])
                    else:
                        delta = observed - Decimal(str(baseline))
                        self.assertEqual(Decimal(str(expected['deltaPp'])), delta)
                        extra = max(Decimal(0), -delta * denominator / 100)
                        self.assertEqual(Decimal(str(expected['extraFailedAttempts'])), extra)
                else:
                    samples = sorted(source['completedDelaySamplesMs'])
                    self.assertEqual(expected['sampleCount'], len(samples))
                    # ceil(.95*n), with an integer oracle independent of feature code.
                    observed = samples[(95 * len(samples) + 99) // 100 - 1]
                    self.assertEqual(expected['observed'], observed)
                    self.assertEqual(Decimal(str(expected['delayRatio'])), Decimal(observed) / Decimal(str(source['baseline'])))

    def test_unknown_preserves_original_provenance_without_current_measurement(self):
        case = self.suite['unknownCase']
        self.assertIsNone(case['expected']['currentImpact'])
        self.assertIsNone(case['expected']['currentSeverity'])
        for field in ('impact', 'severity'):
            history = case['expected']['historical' + field.title()]
            self.assertEqual(history['sourceWindowEnd'], case['lastEvaluated']['windowEnd'])
            self.assertNotEqual(history['sourceWindowEnd'], case['unknownDecision']['windowEnd'])
            self.assertEqual(history['sourceEventIds'], case['lastEvaluated']['sourceEventIds'])
            self.assertEqual(history['sourceDetectionId'], case['lastEvaluated']['detectionId'])
        self.assertEqual(case['expected']['technicalState'], 'UNKNOWN')
        self.assertEqual(case['expected']['analystStatus'], 'INVESTIGATING')


if __name__ == '__main__':
    unittest.main()
