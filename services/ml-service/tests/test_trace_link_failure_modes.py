from copy import deepcopy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
from threading import BoundedSemaphore
import tempfile
import unittest
from unittest.mock import patch
from uuid import uuid4

from fastapi.testclient import TestClient

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'services/ml-service'))
from app.features.trace_link_features import build_features
from app.inference.trace_link_scoring import load, score, DEFAULT


def request(domain='TRANSPORT'):
    rows=json.loads((ROOT/'contracts/fixtures/trace-link/day3-persisted-windows.json').read_text())['windows']
    context=next(r['input']['context'] for r in rows if r['domain']==domain and r['step']==0)
    return {'requestId':str(uuid4()),'requestedAt':datetime.now(timezone.utc).isoformat().replace('+00:00','Z'),
            'requestedModelVersion':'trace-link-shadow-v1','features':build_features(context)}


class TraceLinkFailureModesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.loaded=load()

    def test_offline_candidate_scores_all_domains_without_probability_claim(self):
        for domain in ('TRANSPORT','ROAMING','MOBILE_DATA'):
            req=request(domain);result=score(req,self.loaded)
            self.assertEqual(result['requestId'],req['requestId'])
            self.assertEqual(result['scope'],req['features']['scope'])
            self.assertTrue(0<=result['anomalyScore']<=1)
            self.assertEqual(result['featureContributions'],[])
            self.assertNotIn('probability',result)

    def test_api_rejects_adversarial_provenance_without_score(self):
        from app.inference.api import app
        from unittest.mock import patch
        fixtures=json.loads((ROOT/'contracts/fixtures/trace-link/ml-adversarial-cases-v1.json').read_text())['cases']
        with patch.dict(os.environ,ML_TRACE_LINK_SHADOW_ENABLED='true',ML_TRACE_LINK_CANDIDATE_PATH=str(DEFAULT)),TestClient(app) as client:
            for fixture in fixtures:
                # Deliberately bypass the caller's builder, as an untrusted HTTP caller can.
                req=request(fixture['context']['domain'])
                req['features']=deepcopy(fixture['untrustedFeatures'])
                with self.subTest(case=fixture['id']):
                    response=client.post('/internal/inference/trace-link',json=req)
                    self.assertEqual(response.status_code,422,response.text)
                    self.assertEqual(response.json()['reason'],'INVALID_REQUEST')
                    self.assertNotIn('anomalyScore',response.json())

    def test_feature_tampering_nan_labels_and_wrong_versions_are_rejected(self):
        for kind in ('vector','nan','label','version','scope'):
            req=request()
            if kind=='vector': req['features']['featureValues'][0]=123456
            if kind=='nan': req['features']['featureValues'][0]=float('nan')
            if kind=='label': req['features']['expectedLabel']='FAULT'
            if kind=='version': req['requestedModelVersion']='other'
            if kind=='scope': req['features']['scope']['id']='wrong'
            with self.assertRaises(ValueError,msg=kind):score(req,self.loaded)

    def test_nonfinite_model_output_and_untrained_scope_are_rejected(self):
        import numpy as np
        model=self.loaded[1]['TRANSPORT'][1]['model']
        for value in (float('nan'),float('inf'),float('-inf')):
            with patch.object(model,'score_samples',return_value=np.asarray([value])):
                with self.assertRaisesRegex(ValueError,'NONFINITE_SCORE'):score(request(),self.loaded)
        req=request('ROAMING');features=req['features']
        features['scope'].update(id='ro-partner:in',directionId='in')
        for evidence in features['evidence']:evidence['scope']=deepcopy(features['scope'])
        for baseline in features['baselines'].values():baseline['sourceScope']=deepcopy(features['scope'])
        with self.assertRaisesRegex(ValueError,'UNSUPPORTED_CANDIDATE_SCOPE'):score(req,self.loaded)

    def test_manifest_checksum_checked_before_deserialization(self):
        with tempfile.TemporaryDirectory() as folder:
            import shutil
            shutil.copytree(DEFAULT,Path(folder)/'candidate')
            target=Path(folder)/'candidate'
            (target/'TRANSPORT.joblib').write_bytes(b'corrupt')
            with patch('app.inference.trace_link_scoring.joblib.load') as deserialize:
                with self.assertRaises(ValueError):load(target)
                deserialize.assert_not_called()
        with self.assertRaises(FileNotFoundError):load('missing-trace-link-candidate')

    def test_manifest_byte_mismatch_fails_before_deserialization_and_capability_falls_back(self):
        import shutil
        from app.inference.api import app
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)/'candidate';shutil.copytree(DEFAULT,target)
            manifest=target/'manifest.json';manifest.write_bytes(manifest.read_bytes()+b' ')
            with patch('app.inference.trace_link_scoring.joblib.load') as deserialize:
                with self.assertRaisesRegex(ValueError,'MODEL_MANIFEST_PIN_MISMATCH'):load(target)
                deserialize.assert_not_called()
            with patch.dict(os.environ,ML_TRACE_LINK_SHADOW_ENABLED='true',ML_TRACE_LINK_CANDIDATE_PATH=str(target)),TestClient(app) as client:
                self.assertEqual(client.get('/health/ready').status_code,200)
                self.assertEqual(client.get('/health/trace-link').json()['status'],'FALLBACK')
                body=client.post('/internal/inference/trace-link',json=request()).json()
                self.assertEqual(body['reason'],'MODEL_UNAVAILABLE')
                self.assertNotIn('anomalyScore',body)

    def test_api_disabled_missing_model_saturation_bad_json_and_good_domains(self):
        from app.inference.api import app
        with patch.dict(os.environ,ML_TRACE_LINK_SHADOW_ENABLED='false'),TestClient(app) as client:
            response=client.post('/internal/inference/trace-link',json=request())
            self.assertEqual(response.status_code,503)
            self.assertEqual(response.json()['status'],'DISABLED')
        with patch.dict(os.environ,ML_TRACE_LINK_SHADOW_ENABLED='true',ML_TRACE_LINK_CANDIDATE_PATH='missing'),TestClient(app) as client:
            self.assertEqual(client.get('/health/ready').status_code,200)
            self.assertEqual(client.get('/health/trace-link').json()['status'],'FALLBACK')
            self.assertEqual(client.post('/internal/inference/trace-link',json=request()).json()['reason'],'MODEL_UNAVAILABLE')
        with patch.dict(os.environ,ML_TRACE_LINK_SHADOW_ENABLED='true',ML_TRACE_LINK_CANDIDATE_PATH=str(DEFAULT)),TestClient(app) as client:
            for domain in ('TRANSPORT','ROAMING','MOBILE_DATA'):
                response=client.post('/internal/inference/trace-link',json=request(domain))
                self.assertEqual(response.status_code,200,response.text)
            for body in ('[]','null','{bad'):
                response=client.post('/internal/inference/trace-link',content=body,headers={'content-type':'application/json'})
                self.assertEqual(response.status_code,422)
                self.assertNotIn('anomalyScore',response.json())
            slots=app.state.trace_link_slots
            for _ in range(8):self.assertTrue(slots.acquire(blocking=False))
            try:self.assertEqual(client.post('/internal/inference/trace-link',json=request()).json()['reason'],'SATURATED')
            finally:
                for _ in range(8):slots.release()
            with patch('app.inference.api.trace_link_score',side_effect=RuntimeError('model fault')):
                self.assertEqual(client.post('/internal/inference/trace-link',json=request()).json()['reason'],'INFERENCE_ERROR')
            self.assertEqual(client.post('/internal/inference/trace-link',json=request()).status_code,200)
