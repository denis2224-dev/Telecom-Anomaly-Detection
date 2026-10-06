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
    def test_experimental_settings_are_recorded_and_used_without_changing_defaults(self):
        from app.inference.scoring import load
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / 'data'
            tiny_history(data)
            models = Path(directory) / 'models'
            manifest = train(data, models, model_version='isoforest-tuned-test',
                             n_estimators=20, max_samples=3, threshold_rank=.97)
            self.assertEqual(manifest['nEstimators'], 20)
            self.assertEqual(manifest['maxSamples'], 3)
            self.assertEqual(manifest['thresholdRank'], .97)
            self.assertEqual(load('SMS', models)[2].max_samples_, 3)
            for options in ({'n_estimators': 0}, {'max_samples': 0}, {'threshold_rank': 1.1}):
                with self.assertRaises(ValueError):
                    train(data, models, model_version='isoforest-tuned-test', **options)

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

    def test_comparison_matches_single_window_scoring_and_rejects_leakage(self):
        from compare_models import compare
        from app.inference.scoring import load, score
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / 'data'
            tiny_history(data)
            models = Path(directory) / 'models'
            train(data, models, model_version='isoforest-test-1')
            split_path = data.parent / 'split_manifest.json'
            result = compare(data, models, models, split_path)
            for service in ('VOLTE', 'SMS'):
                expected_tp = expected_fp = 0
                loaded = load(service, models)
                split = json.loads(split_path.read_text())
                for run in split['runs']:
                    if run['service'] != service or run['split'] != 'test':
                        continue
                    for line in (data / run['path']).read_text().splitlines():
                        row = json.loads(line)
                        window = dict(row, featureVersion=2, baselineVersion='baseline-v2',
                                      quality='COMPLETE', mlEligible=True)
                        detected = score(window, loaded)['anomaly']
                        expected_tp += detected and row['label'] == 'FAULT'
                        expected_fp += detected and row['label'] == 'NORMAL'
                for name in ('reference', 'candidate'):
                    metrics = result[name]['services'][service]
                    self.assertEqual(metrics['truePositives'], expected_tp)
                    self.assertEqual(metrics['falsePositives'], expected_fp)
            # Keep dataset hashes valid while introducing overlapping split times.
            split['runs'][2]['start'] = split['runs'][0]['start']
            split_path.write_text(json.dumps(split), encoding='utf-8')
            manifest_path = models / 'manifest.json'
            model = json.loads(manifest_path.read_text())
            model['datasetManifestSha256'] = sha256(split_path.read_bytes()).hexdigest()
            manifest_path.write_text(json.dumps(model), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'overlap'):
                compare(data, models, models, split_path)

    def test_comparison_rejects_corrupted_test_rows(self):
        from compare_models import compare
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / 'data'
            tiny_history(data)
            models = Path(directory) / 'models'
            train(data, models, model_version='isoforest-test-1')
            with (data / 'SMS-test-2.jsonl').open('ab') as stream:
                stream.write(b'{}\n')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                compare(data, models, models, data.parent / 'split_manifest.json')


if __name__ == '__main__':
    unittest.main()
