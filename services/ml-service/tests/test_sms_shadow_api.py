from copy import deepcopy
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from concurrent.futures import ThreadPoolExecutor
from threading import Condition, Event

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service'))
sys.path.insert(0, str(ROOT / 'services/ml-service/training'))


class SmsShadowApiTests(unittest.TestCase):
    def test_eight_prediction_slots_reject_ninth_and_release_after_completion(self):
        from app.inference.api import app
        entered = 0
        condition, release = Condition(), Event()
        def blocked_score(window, loaded):
            nonlocal entered
            with condition:
                entered += 1
                condition.notify_all()
            if not release.wait(5):
                raise RuntimeError('Test requests were not released')
            return dict(classifierScore=.1, detection=False, modelVersion='sms-supervised-v1-2')
        with patch.dict(os.environ, ML_SMS_SHADOW_ENABLED='true'), TestClient(app) as client:
            with patch('app.inference.api.classifier_score', side_effect=blocked_score), ThreadPoolExecutor(8) as pool:
                pending = [pool.submit(client.post, '/internal/inference/sms-classifier', json=self.window()) for _ in range(8)]
                try:
                    with condition:
                        self.assertTrue(condition.wait_for(lambda: entered == 8, timeout=5))
                    response = client.post('/internal/inference/sms-classifier', json=self.window())
                    self.assertEqual(response.status_code, 503)
                    self.assertEqual(response.json()['mlStatus'], 'UNAVAILABLE')
                    self.assertIsNone(response.json()['classifierScore'])
                finally:
                    release.set()
                self.assertTrue(all(request.result().status_code == 200 for request in pending))
            self.assertEqual(client.post('/internal/inference/sms-classifier', json=self.window()).status_code, 200)

    def window(self):
        from sms_supervised_data import FEATURES
        return dict(service='SMS', featureVersion=2, baselineVersion='baseline-v2', quality='COMPLETE',
                    mlEligible=True, featureNames=FEATURES, featureValues=[1., 2000., 50., 2., 0., 1000.])

    def test_enabled_offline_http_parity_and_single_worker(self):
        from app.inference.api import app
        from sms_classifier import score, load_classifier
        package = ROOT / 'services/ml-service/candidate-models/sms-supervised-v1-2'
        loaded = load_classifier(package)
        self.assertEqual(loaded[1].n_jobs, 1)
        with patch.dict(os.environ, ML_SMS_SHADOW_ENABLED='true'), TestClient(app) as client:
            self.assertEqual(client.get('/health/ready').status_code, 200)
            for values in ([1., 2000., 50., 2., 0., 1000.], [20., 40000., 5000., 600., -30., 50.]):
                window = dict(self.window(), featureValues=values)
                response = client.post('/internal/inference/sms-classifier', json=window)
                self.assertEqual(response.status_code, 200)
                result = response.json()
                offline = score(window, loaded)
                for field in offline:
                    self.assertEqual(result[field], offline[field])
                self.assertEqual(result['threshold'], .55)
                self.assertEqual(result['mlStatus'], 'OK')
                self.assertEqual(result['modelSha256'], loaded[0]['modelSha256'])
                self.assertNotIn('anomalyRank', result)
            for patch_value in ({'quality':'INCOMPLETE'}, {'baselineVersion':'wrong'}, {'label':'FAULT'},
                                {'featureValues':[True]*6}, {'featureNames':[]}):
                response = client.post('/internal/inference/sms-classifier', json=dict(self.window(), **patch_value))
                self.assertEqual(response.status_code, 422)
                self.assertIsNone(response.json()['classifierScore'])
                self.assertIsNone(response.json()['detection'])
            for body in ([], 'invalid', None):
                response = client.post('/internal/inference/sms-classifier', json=body)
                self.assertEqual(response.status_code, 422)
                self.assertIsNone(response.json()['classifierScore'])
                self.assertIsNone(response.json()['detection'])

    def test_disabled_invalid_package_readiness_and_unavailable(self):
        from app.inference.api import app
        with patch.dict(os.environ, ML_SMS_SHADOW_ENABLED='false'), TestClient(app) as client:
            response = client.post('/internal/inference/sms-classifier', json=self.window())
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.json()['mlStatus'], 'DISABLED')
            self.assertIsNone(response.json()['detection'])
        with patch.dict(os.environ, ML_SMS_SHADOW_ENABLED='true', ML_SMS_CANDIDATE_PATH='missing-package'):
            with self.assertRaises((ValueError, FileNotFoundError)), TestClient(app):
                pass
        with patch.dict(os.environ, ML_SMS_SHADOW_ENABLED='true'), TestClient(app) as client:
            app.state.sms_classifier = None
            self.assertEqual(client.get('/health/ready').status_code, 503)
            response = client.post('/internal/inference/sms-classifier', json=self.window())
            self.assertEqual(response.status_code, 503)
            self.assertIsNone(response.json()['classifierScore'])

    def test_frozen_identity_is_checked_before_deserializing(self):
        from app.inference.sms_classifier import load_classifier
        package=ROOT/'services/ml-service/candidate-models/sms-supervised-v1-2'
        with patch('app.inference.sms_classifier.joblib.load') as deserialize:
            with self.assertRaisesRegex(ValueError,'frozen identity'):
                load_classifier(package,expected=('wrong-version','a'*64,.55))
            deserialize.assert_not_called()
