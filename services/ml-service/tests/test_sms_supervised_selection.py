"""Selection requires every family/profile gate and cannot read final test rows."""

from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service/training'))


def good_metrics():
    from scenario_history import FAULTS, NORMALS
    return dict(recall=.8, macroRecall=.8, precision=.99, falsePositiveRate=.005,
                byFamily={f: dict(windows=100, detected=80, recall=.8) for f in FAULTS['SMS']},
                byHealthyProfile={p: dict(windows=1000, falsePositives=5, falsePositiveRate=.005) for p in NORMALS})


class SmsSupervisedSelectionTests(unittest.TestCase):
    def test_overall_success_cannot_hide_a_failed_family_or_healthy_profile(self):
        from sms_supervised_selection import passes_gates, choose
        passing = good_metrics()
        self.assertTrue(passes_gates(passing))
        cases = []
        for field, value in (('recall', .69), ('falsePositiveRate', .011)):
            item = deepcopy(passing)
            item[field] = value
            cases.append(item)
        item = deepcopy(passing)
        item['byFamily']['backlog']['recall'] = .69
        cases.append(item)
        item = deepcopy(passing)
        item['byHealthyProfile']['healthy-jitter']['falsePositiveRate'] = .011
        cases.append(item)
        item = deepcopy(passing)
        del item['byFamily']['delivery-failure']
        cases.append(item)
        for failed in cases:
            self.assertFalse(passes_gates(failed))
            with self.assertRaisesRegex(ValueError, 'gates'):
                choose([dict(metrics=failed, configurationIndex=0, thresholdIndex=0)])
        boundary = deepcopy(passing)
        boundary['byFamily']['backlog']['recall'] = .7
        boundary['byHealthyProfile']['healthy-jitter']['falsePositiveRate'] = .01
        self.assertTrue(passes_gates(boundary))
        weaker = deepcopy(passing)
        weaker['byFamily']['backlog']['recall'] = .71
        self.assertEqual(choose([dict(metrics=weaker, configurationIndex=0, thresholdIndex=0),
                                 dict(metrics=passing, configurationIndex=1, thresholdIndex=0)])['configurationIndex'], 1)

    def test_grid_and_thresholds_include_ablation_and_quantile_tie_boundaries(self):
        from sms_supervised_selection import GRID, thresholds
        self.assertEqual(len(GRID), 48)
        self.assertEqual(sum(g['estimator'] == 'hist-gradient-boosting' for g in GRID), 32)
        self.assertEqual(sum(g['estimator'] == 'random-forest' for g in GRID), 16)
        self.assertEqual({g['view'] for g in GRID}, {'six', 'without-absolute-delay'})
        found = thresholds(np.asarray([.2] * 100))
        self.assertIn(float(np.nextafter(.2, np.inf)), found)
        self.assertIn(.99, found)
        self.assertEqual(len([t for t in found if .05 <= t <= .95]), 20)

    def test_selection_succeeds_without_any_test_files_and_freezes_artifacts(self):
        from sms_supervised_data import generate, read_split
        from sms_supervised_selection import tune
        from sms_classifier import load_classifier
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = generate(root / 'dataset', normal_weeks=1, cadence_minutes=15,
                                fault_minutes=5, repetitions=1)
            data = root / 'dataset/data'
            for run in manifest['runs']:
                if run['split'] == 'test':
                    (data / run['path']).unlink()
            seen = []

            def guarded_read(data, split, manifest):
                seen.append(split)
                self.assertNotEqual(split, 'test')
                return read_split(data, split, manifest)

            grid = (dict(estimator='hist-gradient-boosting', learning_rate=.1, max_iter=100,
                         max_leaf_nodes=7, l2_regularization=0., view='six'),)
            with patch('sms_supervised_selection.read_split', side_effect=guarded_read):
                selection = tune(data, root / 'selection', grid=grid, model_version='sms-test-selection')
            self.assertEqual(set(seen), {'train', 'calibration', 'validation'})
            loaded = load_classifier(root / 'selection/model')
            self.assertEqual(loaded[0]['threshold'], selection['threshold'])
            self.assertTrue(selection['validationPassed'])
            self.assertEqual(json.loads((root / 'selection/selection.json').read_bytes()), selection)
            with self.assertRaises(FileExistsError):
                tune(data, root / 'selection', grid=grid)


if __name__ == '__main__':
    unittest.main()
