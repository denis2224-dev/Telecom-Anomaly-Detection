"""Expanded synthetic history must remain deterministic and separated by time."""

from datetime import datetime
from hashlib import sha256
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / 'services/ml-service/training')]
from generate_history import generate


class ExpandedHistoryTests(unittest.TestCase):
    def test_more_weeks_have_fresh_rows_and_disjoint_normal_and_fault_splits(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / 'data'
            manifest = generate(data, cadence_minutes=60, training_weeks=6,
                                calibration_weeks=2, test_weeks=1,
                                fault_runs=3, fault_minutes=4)
            self.assertNotEqual(manifest['datasetVersion'], 'synthetic-v2-1')
            for service in ('VOLTE', 'SMS'):
                runs = [r for r in manifest['runs'] if r['service'] == service]
                self.assertEqual(sum(r['rows'] for r in runs if r['split'] == 'train'), 6 * 168)
                self.assertEqual(sum(r['rows'] for r in runs if r['split'] == 'calibration'), 2 * 168)
                self.assertEqual(sum(r['rows'] for r in runs if r['split'] == 'test'), 168 + 12)
                self.assertEqual(len({r['seed'] for r in runs}), len(runs))
                identities = set()
                for left, right in zip(runs, runs[1:]):
                    self.assertLessEqual(left['endExclusive'], right['start'])
                for run in runs:
                    raw = (data / run['path']).read_bytes()
                    self.assertEqual(sha256(raw).hexdigest(), run['sha256'])
                    rows = [json.loads(line) for line in raw.splitlines()]
                    self.assertEqual(len(rows), run['rows'])
                    for row in rows:
                        self.assertNotIn(row['windowStart'], identities)
                        identities.add(row['windowStart'])
                        self.assertEqual(len(row['featureValues']), 6)
                        self.assertEqual('label' in row, run['split'] == 'test')
                        self.assertLess(datetime.fromisoformat(row['windowStart']),
                                        datetime.fromisoformat(run['endExclusive']))
            before = (data.parent / 'split_manifest.json').read_bytes()
            generate(data, cadence_minutes=60, training_weeks=6,
                     calibration_weeks=2, test_weeks=1, fault_runs=3, fault_minutes=4)
            self.assertEqual(before, (data.parent / 'split_manifest.json').read_bytes())

    def test_invalid_sizes_do_not_write_a_dataset(self):
        with tempfile.TemporaryDirectory() as directory:
            for options in ({'training_weeks': 0}, {'calibration_weeks': -1},
                            {'test_weeks': 0}, {'fault_runs': 0}, {'fault_minutes': 0}):
                with self.assertRaises(ValueError):
                    generate(Path(directory) / 'data', **options)
            self.assertFalse((Path(directory) / 'data').exists())

    def test_expansion_requires_a_separate_data_location(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / 'data'
            with patch('generate_history.DATA', data):
                with self.assertRaisesRegex(ValueError, 'output'):
                    generate(output=data, training_weeks=12)
            self.assertFalse(data.exists())


if __name__ == '__main__':
    unittest.main()
