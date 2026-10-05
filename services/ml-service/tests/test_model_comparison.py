"""Candidate models must be versioned and compared on untouched shared tests."""

from datetime import timedelta
from hashlib import sha256
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / 'services/ml-service/training')]
from generate_history import START, make_window
from train import train


def tiny_history(data):
    data.mkdir()
    runs = []
    for service in ('VOLTE', 'SMS'):
        for day, (split, fault) in enumerate((('train', False), ('calibration', False),
                                              ('test', False), ('test', True))):
            start = START + timedelta(days=day)
            run_id = f'{service}-{split}-{day}'
            rows = []
            for index in range(3):
                window = make_window(service, start + timedelta(minutes=index), 900 + day, fault)
                row = dict(service=service, runId=run_id, windowStart=window['windowStart'],
                           featureNames=window['featureNames'], featureValues=window['featureValues'])
                if split == 'test':
                    row['label'] = 'FAULT' if fault else 'NORMAL'
                rows.append(row)
            path = data / f'{run_id}.jsonl'
            raw = ''.join(json.dumps(row) + '\n' for row in rows).encode()
            path.write_bytes(raw)
            runs.append(dict(runId=run_id, service=service, split=split, rows=3,
                             start=start.isoformat(), endExclusive=(start + timedelta(minutes=3)).isoformat(),
                             path=path.name, sha256=sha256(raw).hexdigest()))
    manifest = dict(datasetVersion='synthetic-test-candidate', featureVersion=2,
                    baselineVersion='baseline-v2', runs=runs)
    (data.parent / 'split_manifest.json').write_text(json.dumps(manifest), encoding='utf-8')


class CandidateTrainingTests(unittest.TestCase):
    def test_expanded_data_requires_candidate_location_and_version(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / 'data'
            tiny_history(data)
            with self.assertRaisesRegex(ValueError, 'candidate'):
                train(data)
            with self.assertRaisesRegex(ValueError, 'version'):
                train(data, Path(directory) / 'models')
            result = train(data, Path(directory) / 'models', model_version='isoforest-test-1')
            self.assertEqual(result['modelVersion'], 'isoforest-test-1')
            self.assertEqual(result['services']['SMS']['trainingRows'], 3)


if __name__ == '__main__':
    unittest.main()
