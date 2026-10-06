"""Frozen classifier selections are evaluated once, with checked scalar/batch parity."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service/training'))


class SmsSupervisedEvaluationTests(unittest.TestCase):
    def test_package_and_search_mutations_fail_before_any_final_test_read(self):
        from sms_supervised_data import generate
        from sms_supervised_selection import tune, passes_gates
        from sms_supervised_evaluation import evaluate
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generate(root / 'dataset', normal_weeks=1, cadence_minutes=15, fault_minutes=5, repetitions=1)
            data, selected = root / 'dataset/data', root / 'selection'
            grid = (dict(estimator='hist-gradient-boosting', learning_rate=.1, max_iter=100,
                         max_leaf_nodes=7, l2_regularization=0., view='six'),)
            tune(data, selected, grid=grid, model_version='sms-evaluation-test')
            for path in (selected / 'model/manifest.json', selected / 'validation-search.json'):
                before = path.read_bytes()
                path.write_bytes(before + b' ')
                with patch('sms_supervised_evaluation.read_split') as read:
                    with self.assertRaisesRegex(ValueError, 'changed|checksum'):
                        evaluate(data, selected, root / 'tampered.json', references={})
                    read.assert_not_called()
                path.write_bytes(before)
            result = evaluate(data, selected, root / 'final.json', references={})
            self.assertEqual(result['selected']['scalarBatchParity'], 'PASS')
            self.assertEqual(result['selected']['metrics']['testNormalWindows'], 768)
            self.assertEqual(result['selected']['metrics']['testFaultWindows'], 240)
            self.assertEqual(result['passed'], passes_gates(result['selected']['metrics']))
            self.assertEqual(result, json.loads((root / 'final.json').read_bytes()))
            self.assertTrue((selected / 'final-test-consumption.json').exists())
            with self.assertRaises(FileExistsError):
                evaluate(data, selected, root / 'final.json', references={})
            # Renaming the report does not permit another look at the same final test.
            with self.assertRaisesRegex(FileExistsError, 'consumed'):
                evaluate(data, selected, root / 'second.json', references={})


if __name__ == '__main__':
    unittest.main()
