"""Supervised labels stay separate from canonical estimator inputs."""

from copy import deepcopy
from hashlib import sha256
from pathlib import Path
import json
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service/training'))


class SmsSupervisedDataTests(unittest.TestCase):
    def test_disjoint_labelled_splits_and_no_metadata_in_inputs(self):
        from sms_supervised_data import generate, read_split, arrays, validate_manifest
        from experiment_data import validate_manifest as validate_normal_only
        from scenario_history import FAULTS, NORMALS
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'dataset'
            manifest = generate(root, normal_weeks=1, cadence_minutes=60,
                                fault_minutes=1, repetitions=1)
            validate_manifest(manifest)
            with self.assertRaises(ValueError):
                validate_normal_only(manifest)
            runs = manifest['runs']
            self.assertEqual(len({r['seed'] for r in runs}), len(runs))
            self.assertEqual(len({r['episodeId'] for r in runs}), len(runs))
            for split in ('train', 'validation', 'test'):
                rows = read_split(root / 'data', split, manifest)
                self.assertEqual({r['label'] for r in rows}, {'NORMAL', 'FAULT'})
                faults = [r for r in rows if r['label'] == 'FAULT']
                self.assertEqual({r['scenario'] for r in faults}, set(FAULTS['SMS']))
                self.assertEqual({r['operatingProfile'] for r in faults}, set(NORMALS))
                self.assertEqual({r['severity'] for r in faults}, {1, 2, 3})
                x, y = arrays(rows, 'six')
                self.assertEqual(x.shape, (len(rows), 6))
                self.assertEqual(y.tolist(), [int(r['label'] == 'FAULT') for r in rows])
                changed = deepcopy(rows)
                for row in changed:
                    row.update(runId='ignored', seed=999, episodeId='ignored', severity=99)
                self.assertTrue((arrays(changed, 'six')[0] == x).all())
                projected, _ = arrays(rows, 'without-absolute-delay')
                self.assertEqual(projected.shape[1], 5)
                self.assertTrue((projected == x[:, [0, 2, 3, 4, 5]]).all())
            self.assertEqual({r['label'] for r in read_split(root / 'data', 'calibration', manifest)}, {'NORMAL'})
            broken = deepcopy(manifest)
            broken['runs'][1]['start'] = broken['runs'][0]['start']
            with self.assertRaisesRegex(ValueError, 'overlap'):
                validate_manifest(broken)
            with self.assertRaises(FileExistsError):
                generate(root)

    def test_reader_rejects_hash_order_label_nonfinite_and_duplicate_rows(self):
        from sms_supervised_data import generate, read_split
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'dataset'
            manifest = generate(root, normal_weeks=1, cadence_minutes=60,
                                fault_minutes=1, repetitions=1)
            run = manifest['runs'][0]
            path = root / 'data' / run['path']
            original = path.read_bytes()
            path.write_bytes(b'{}\n')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                read_split(root / 'data', 'train', manifest)
            original_rows = [json.loads(line) for line in original.splitlines()]
            for mutation in ('order', 'label', 'nonfinite', 'duplicate'):
                rows = deepcopy(original_rows)
                if mutation == 'order':
                    rows[0]['featureNames'].reverse()
                elif mutation == 'label':
                    rows[0]['label'] = 'FAULT'
                elif mutation == 'nonfinite':
                    rows[0]['featureValues'][0] = float('nan')
                else:
                    rows[1] = rows[0]
                raw = ('\n'.join(json.dumps(r) for r in rows) + '\n').encode()
                path.write_bytes(raw)
                run['sha256'] = sha256(raw).hexdigest()
                with self.assertRaisesRegex(ValueError, 'Invalid'):
                    read_split(root / 'data', 'train', manifest)


if __name__ == '__main__':
    unittest.main()
