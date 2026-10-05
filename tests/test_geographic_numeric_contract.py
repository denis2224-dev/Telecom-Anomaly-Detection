"""Day 1 golden meaning; reference checks, not live geographic feature parity."""

import copy
import json
from decimal import Decimal
from pathlib import Path
import unittest

from scripts.geographic_numeric_contract import aggregate_voice, aggregate_sms_p95, validate_day1_numeric_contracts

ROOT = Path(__file__).resolve().parents[1]


class GeographicNumericContractTests(unittest.TestCase):
    def setUp(self):
        self.suite = json.loads((ROOT / 'contracts/fixtures/geography/numeric-cases-v1.json').read_text())

    def test_shared_voice_golden_values_and_null_reasons(self):
        for case in self.suite['voiceCases']:
            with self.subTest(case=case['id']):
                result = aggregate_voice(case['partitions'])
                for name, expected in case['expected'].items():
                    if name in {'observedPct', 'baselinePct', 'deltaPp', 'detectorDropPp', 'extraFailedAttempts'} and expected is not None:
                        self.assertLessEqual(abs(result[name] - Decimal(str(expected))), Decimal('1e-9'))
                    else:
                        self.assertEqual(result[name], expected)

    def test_shared_percentile_cases(self):
        for case in self.suite['smsCases']:
            with self.subTest(case=case['id']):
                self.assertEqual(aggregate_sms_p95(case['partitions']), case['expected'])

    def test_rejects_wrong_service_unit_time_version_and_overlapping_footprints(self):
        for field, value in [('service', 'SMS'), ('unit', 'RATIO'), ('windowStart', '2026-10-05T08:01:00Z'),
                             ('windowEnd', '2026-10-05T08:02:00Z'), ('topologyVersion', 'other'),
                             ('catalogueVersion', 'other'), ('footprintNodeIds', ['CELL-MD-CHI-01'])]:
            partitions = copy.deepcopy(self.suite['voiceCases'][0]['partitions'])
            partitions[1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                aggregate_voice(partitions)

    def test_invalid_and_nonfinite_counts_are_rejected_without_mutating_inputs(self):
        for field, value in [('attempts', True), ('technicalSuccesses', -1), ('technicalFailures', 0.5),
                             ('userOutcomes', 101), ('baselinePct', 'NaN'), ('baselinePct', 101)]:
            partitions = copy.deepcopy(self.suite['voiceCases'][0]['partitions'])
            partitions[0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                aggregate_voice(partitions)
        original = copy.deepcopy(self.suite)
        aggregate_voice(self.suite['voiceCases'][0]['partitions'])
        self.assertEqual(original, self.suite)

    def test_sms_rejects_invalid_samples_and_incompatible_partitions(self):
        for samples in [[-1], [float('inf')], [True]]:
            partitions = copy.deepcopy(self.suite['smsCases'][0]['partitions'])
            partitions[0]['samples'] = samples
            with self.assertRaises(ValueError):
                aggregate_sms_p95(partitions)
        self.assertRaises(ValueError, aggregate_voice, [])
        self.assertRaises(ValueError, aggregate_sms_p95, [])

    def test_full_day1_contract_entrypoint(self):
        validate_day1_numeric_contracts()
