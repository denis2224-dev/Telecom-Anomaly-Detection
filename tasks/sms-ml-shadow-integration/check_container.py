"""Verify disabled serving and enabled invalid-package readiness in the built image."""
import json
import subprocess
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier


def check():
    image='telecom-sms-shadow-validation'
    disabled=subprocess.check_output(['docker','run','-d','--rm','-p','127.0.0.1::8090',image],text=True).strip()
    try:
        port=subprocess.check_output(['docker','port',disabled,'8090/tcp'],text=True).strip().split(':')[-1]
        base='http://127.0.0.1:'+port
        deadline=time.monotonic()+45
        while True:
            try:
                with urllib.request.urlopen(base+'/health/ready',timeout=2) as response: assert response.status==200
                break
            except Exception:
                if time.monotonic()>deadline: raise
                time.sleep(.5)
        request=urllib.request.Request(base+'/internal/inference/sms-classifier',data=b'{}',headers={'Content-Type':'application/json'})
        try: urllib.request.urlopen(request,timeout=2);raise AssertionError('Disabled classifier returned success')
        except urllib.error.HTTPError as response:
            body=json.load(response)
            assert response.code==503 and body['mlStatus']=='DISABLED'
            assert body['classifierScore'] is None and body['detection'] is None
    finally: subprocess.run(['docker','stop',disabled],check=True,stdout=subprocess.DEVNULL)
    invalid=subprocess.check_output(['docker','run','-d','-e','ML_SMS_SHADOW_ENABLED=true','-e','ML_SMS_CANDIDATE_PATH=/missing',image],text=True).strip()
    try:
        code=int(subprocess.check_output(['docker','wait',invalid],text=True).strip())
        assert code!=0,'Enabled invalid package must fail startup/readiness'
        logs=subprocess.check_output(['docker','logs',invalid],text=True,stderr=subprocess.STDOUT)
        assert 'Application startup failed' in logs
    finally: subprocess.run(['docker','rm',invalid],check=True,stdout=subprocess.DEVNULL)
    enabled=subprocess.check_output(['docker','run','-d','--rm','-p','127.0.0.1::8090',
        '-e','ML_SMS_SHADOW_ENABLED=true','-e','OMP_NUM_THREADS=1','-e','OPENBLAS_NUM_THREADS=1',image],text=True).strip()
    try:
        port=subprocess.check_output(['docker','port',enabled,'8090/tcp'],text=True).strip().split(':')[-1]
        base='http://127.0.0.1:'+port
        deadline=time.monotonic()+45
        while True:
            try:
                with urllib.request.urlopen(base+'/health/ready',timeout=2) as response: assert response.status==200
                break
            except Exception:
                if time.monotonic()>deadline: raise
                time.sleep(.5)
        root=Path(__file__).resolve().parents[2]
        feature_order=json.loads((root/'contracts/features/feature-order-v2.json').read_text())
        names=feature_order['models']['SMS']
        window=dict(service='SMS',featureVersion=2,baselineVersion='baseline-v2',quality='COMPLETE',
                    mlEligible=True,featureNames=names,featureValues=[1.,2000.,50.,2.,0.,1000.])
        def prediction(barrier=None):
            if barrier: barrier.wait(timeout=10)
            request=urllib.request.Request(base+'/internal/inference/sms-classifier',data=json.dumps(window).encode(),headers={'Content-Type':'application/json'})
            with urllib.request.urlopen(request,timeout=5) as response:
                result=json.load(response)
                assert response.status==200 and result['mlStatus']=='OK'
                return result
        expected=prediction()
        for _ in range(5):
            barrier=Barrier(8)
            with ThreadPoolExecutor(8) as pool:
                results=list(pool.map(prediction,[barrier]*8))
            assert all(result==expected for result in results)
    finally: subprocess.run(['docker','stop',enabled],check=True,stdout=subprocess.DEVNULL)
    print(json.dumps(dict(disabledReadiness='UP',disabledStatus=503,disabledPredictionsNull=True,
        invalidEnabledPackageFailsStartup=True,enabledReadiness='UP',concurrentRequests=8,
        enabledPredictions=40,concurrentScoringParity=True)))


if __name__=='__main__': check()
