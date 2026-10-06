"""Select on validation only, enforce a false-alarm budget, and freeze selection."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service/training'))


class ValidationSelectionTests(unittest.TestCase):
    def test_selection_prefers_balanced_fault_recall_under_false_alarm_budget(self):
        from tune_models import choose
        # Strong aggregate recall cannot compensate for exceeding the alarm budget.
        candidates = [dict(threshold=.98, macroRecall=1., precision=.9, falsePositiveRate=.02),
                      dict(threshold=.99, macroRecall=.8, precision=.9, falsePositiveRate=.005),
                      dict(threshold=.995, macroRecall=.6, precision=1., falsePositiveRate=0.)]
        self.assertEqual(choose(candidates, .01)['threshold'], .99)
        with self.assertRaisesRegex(ValueError, 'budget'):
            choose(candidates[:1], .01)

    def test_test_rows_cannot_affect_tuning_and_selection_packages_match_scalar_scoring(self):
        from scenario_history import generate_scenarios
        from tune_models import tune
        from experiment_data import read_split
        from app.inference.scoring import load, score
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = generate_scenarios(root / 'dataset', normal_weeks=1, cadence_minutes=60,
                                          fault_minutes=1, repetitions=1)
            data = root / 'dataset/data'
            # Tuning must succeed without opening any final test file.
            for run in manifest['runs']:
                if run['split'] == 'test':
                    (data / run['path']).write_bytes(b'not valid test data')
            seen = []
            def guarded_read(data, service, split, manifest):
                seen.append(split)
                self.assertNotEqual(split, 'test')
                return read_split(data, service, split, manifest)
            with patch('tune_models.read_split', side_effect=guarded_read):
                selected = tune(data, root / 'selection', grid=((20, 32),), thresholds=(.99, 1.),
                                false_positive_budget=.05)
            self.assertEqual(set(seen), {'train', 'calibration', 'validation'})
            self.assertEqual(set(selected['services']), {'VOLTE', 'SMS'})
            for service, choice in selected['services'].items():
                loaded = load(service, root / 'selection' / choice['models'])
                self.assertEqual(loaded[1]['thresholdRank'], choice['thresholdRank'])
                rows = read_split(data, service, 'validation', manifest)
                row = rows[0]
                window = dict(row, quality='COMPLETE', mlEligible=True, featureVersion=2,
                              baselineVersion='baseline-v2')
                self.assertEqual(score(window, loaded)['modelVersion'], choice['modelVersion'])
            with self.assertRaises(FileExistsError):
                tune(data, root / 'selection')

    def test_data_loader_rejects_time_overlap_and_changed_checksum(self):
        from scenario_history import generate_scenarios
        from experiment_data import validate_manifest, read_split
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'dataset'
            manifest = generate_scenarios(root, normal_weeks=1, cadence_minutes=60,
                                          fault_minutes=1, repetitions=1)
            validate_manifest(manifest)
            broken = json.loads(json.dumps(manifest))
            broken['runs'][1]['start'] = broken['runs'][0]['start']
            with self.assertRaisesRegex(ValueError, 'overlap'):
                validate_manifest(broken)
            run = next(r for r in manifest['runs'] if r['split'] == 'validation')
            (root / 'data' / run['path']).write_bytes(b'{}\n')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                read_split(root / 'data', run['service'], 'validation', manifest)


if __name__ == '__main__':
    unittest.main()
