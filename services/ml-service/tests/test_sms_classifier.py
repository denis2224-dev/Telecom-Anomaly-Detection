"""Offline classifiers have explicit scores and preserve the public feature input."""

from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np
from sklearn.ensemble import RandomForestClassifier

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service/training'))


class ReversedClasses:
    classes_ = np.asarray([1, 0])

    def predict_proba(self, values):
        return np.tile([.8, .2], (len(values), 1))


class SmsClassifierTests(unittest.TestCase):
    def test_fault_class_lookup_does_not_assume_column_one(self):
        from sms_classifier import classifier_scores
        self.assertEqual(classifier_scores(ReversedClasses(), [[1]])[0], .8)
        invalid = ReversedClasses()
        invalid.classes_ = np.asarray([0, 2])
        with self.assertRaisesRegex(ValueError, 'class'):
            classifier_scores(invalid, [[1]])

    def test_projected_package_scalar_batch_parity_and_strict_input_validation(self):
        from sms_classifier import package, load_classifier, score, classifier_scores
        from sms_supervised_data import FEATURES
        x = np.asarray([[0., 1., 2., 3., 4.], [10., 11., 12., 13., 14.]])
        model = RandomForestClassifier(n_estimators=5, random_state=3).fit(x, [0, 1])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'model'
            package(root, model, 'without-absolute-delay', .5, 'sms-test-1', 'a' * 64,
                    'test-dataset', dict(estimator='random-forest'), 2)
            loaded = load_classifier(root)
            rows = [[0., 999., 1., 2., 3., 4.], [10., 888., 11., 12., 13., 14.]]
            batch = classifier_scores(model, x)
            for index, values in enumerate(rows):
                window = dict(service='SMS', featureVersion=2, baselineVersion='baseline-v2',
                              quality='COMPLETE', mlEligible=True, featureNames=FEATURES, featureValues=values)
                result = score(window, loaded)
                self.assertEqual(set(result), {'classifierScore', 'detection', 'modelVersion'})
                self.assertEqual(result['classifierScore'], batch[index])
                self.assertEqual(result['detection'], bool(batch[index] >= .5))
                for field, value in (('featureNames', list(reversed(FEATURES))), ('featureVersion', 3),
                                     ('service', 'VOLTE'), ('mlEligible', False),
                                     ('featureValues', [float('nan')] * 6), ('featureValues', [True] * 6)):
                    broken = deepcopy(window)
                    broken[field] = value
                    with self.assertRaises(ValueError):
                        score(broken, loaded)
            manifest_path = root / 'manifest.json'
            original = manifest_path.read_bytes()
            manifest = json.loads(original)
            manifest['projection'] = [1, 0, 2, 3, 4]
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'projection'):
                load_classifier(root)
            manifest_path.write_bytes(original)
            (root / 'SMS.joblib').write_bytes(b'changed artifact')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                load_classifier(root)


if __name__ == '__main__':
    unittest.main()
