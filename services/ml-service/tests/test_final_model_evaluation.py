"""Final test evaluation requires a frozen selection and never overwrites reports."""

import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service/training'))


class FinalEvaluationTests(unittest.TestCase):
    def test_final_evaluation_freezes_selection_checks_parity_and_rejects_mutations(self):
        from scenario_history import generate_scenarios
        from tune_models import tune
        from evaluate_selection import evaluate_selection
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = generate_scenarios(root / 'dataset', normal_weeks=1, cadence_minutes=60,
                                          fault_minutes=1, repetitions=1)
            data = root / 'dataset/data'
            tune(data, root / 'selection', grid=((20, 32),), thresholds=(.99, 1.), false_positive_budget=.05)
            references = {'untuned': dict(models=root / 'selection/search/n20-s32',
                                          manifest=root / 'dataset/split_manifest.json')}
            output = root / 'final.json'
            result = evaluate_selection(data, root / 'selection', output, references)
            self.assertEqual(result['selectionSplit'], 'validation')
            for name in ('untuned', 'selected'):
                for service, entry in result[name]['services'].items():
                    self.assertEqual(entry['metrics']['testNormalWindows'], 168)
                    self.assertEqual(entry['metrics']['testFaultWindows'], 12)
                    self.assertEqual(len(entry['metrics']['byFamily']), 4)
                    self.assertEqual(entry['scalarBatchParity'], 'PASS')
            original = output.read_bytes()
            with self.assertRaises(FileExistsError):
                evaluate_selection(data, root / 'selection', output, references)
            self.assertEqual(output.read_bytes(), original)
            package = root / 'selection/SMS/manifest.json'
            before = package.read_bytes()
            changed = json.loads(before)
            changed['thresholdRank'] = .5
            package.write_text(json.dumps(changed), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'selection'):
                evaluate_selection(data, root / 'selection', root / 'tampered.json', references)
            package.write_bytes(before)
            run = next(r for r in manifest['runs'] if r['split'] == 'test')
            (data / run['path']).write_bytes(b'{}\n')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                evaluate_selection(data, root / 'selection', root / 'corrupt.json', references)


if __name__ == '__main__':
    unittest.main()
