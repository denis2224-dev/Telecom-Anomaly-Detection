"""Scenario coverage and split independence for the new ML experiment."""

from datetime import timedelta
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service/training'))


class ScenarioHistoryTests(unittest.TestCase):
    def test_each_fault_family_is_valid_deterministic_and_distinct_from_normal(self):
        from scenario_history import START, FAULTS, NORMALS, scenario_window
        for service in ('VOLTE', 'SMS'):
            for normal in NORMALS:
                w = scenario_window(service, START, 50, normal, 0)
                self.assertTrue(w['mlEligible'])
                self.assertEqual(w, scenario_window(service, START, 50, normal, 0))
            baseline = scenario_window(service, START, 50, 'nominal', 0)
            for family in FAULTS[service]:
                vectors = []
                for level in (1, 2, 3):
                    w = scenario_window(service, START, 50, family, level)
                    self.assertTrue(w['mlEligible'], (service, family, level))
                    self.assertEqual(w, scenario_window(service, START, 50, family, level))
                    self.assertNotEqual(w['featureValues'], baseline['featureValues'])
                    vectors.append(w['featureValues'])
                self.assertEqual(len({tuple(v) for v in vectors}), 3)
        with self.assertRaises(ValueError):
            scenario_window('SMS', START, 50, 'unknown', 1)

    def test_train_calibration_validation_and_test_are_disjoint_and_test_is_fresh(self):
        from scenario_history import generate_scenarios, FAULTS
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'experiment'
            manifest = generate_scenarios(root, normal_weeks=1, cadence_minutes=60,
                                          fault_minutes=2, repetitions=1)
            for service in ('VOLTE', 'SMS'):
                runs = [r for r in manifest['runs'] if r['service'] == service]
                self.assertEqual({r['split'] for r in runs}, {'train', 'calibration', 'validation', 'test'})
                self.assertEqual(len({r['seed'] for r in runs}), len(runs))
                for left, right in zip(runs, runs[1:]):
                    self.assertLessEqual(left['endExclusive'], right['start'])
                for split in ('validation', 'test'):
                    faults = [r for r in runs if r['split'] == split and r['label'] == 'FAULT']
                    self.assertEqual({r['scenario'] for r in faults}, set(FAULTS[service]))
                    self.assertEqual({r['severity'] for r in faults}, {1, 2, 3})
                for run in runs:
                    for line in (root / 'data' / run['path']).read_text().splitlines():
                        row = json.loads(line)
                        self.assertEqual('label' in row, run['split'] in ('validation', 'test'))
                        self.assertEqual(len(row['featureValues']), 6)
            with self.assertRaises(FileExistsError):
                generate_scenarios(root)


if __name__ == '__main__':
    unittest.main()
