"""Exercise a real local ASGI server or an explicitly supplied packaged service URL."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'services/ml-service'))
from app.features.trace_link_features import build_features
from app.inference.trace_link_scoring import load,score


def get(url,body=None):
    req=Request(url,data=None if body is None else json.dumps(body,allow_nan=False).encode(),
                headers={'Content-Type':'application/json'})
    try:
        with urlopen(req,timeout=5) as result:return result.status,json.loads(result.read())
    except HTTPError as error:return error.code,json.loads(error.read())


@contextmanager
def server():
    with socket.socket() as candidate:candidate.bind(('127.0.0.1',0));port=candidate.getsockname()[1]
    env=dict(os.environ,PYTHONPATH=str(ROOT/'services/ml-service'),ML_TRACE_LINK_SHADOW_ENABLED='true')
    env.pop('ML_TRACE_LINK_CANDIDATE_PATH',None)
    log=ROOT/'tmp/day4-http-server.log';log.parent.mkdir(exist_ok=True)
    with log.open('w') as output:
        process=subprocess.Popen([sys.executable,'-m','uvicorn','app.inference.api:app','--host','127.0.0.1','--port',str(port)],
          env=env,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        try:
            base=f'http://127.0.0.1:{port}'
            for _ in range(100):
                if process.poll() is not None:raise RuntimeError('ASGI server failed; inspect '+str(log))
                try:
                    if get(base+'/health/trace-link')[1]['status']=='OK':break
                except OSError:pass
                time.sleep(.1)
            else:raise RuntimeError('ASGI startup timed out')
            yield base
        finally:
            process.terminate()
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)


def verify(base):
    loaded=load();rows=json.loads((ROOT/'contracts/fixtures/trace-link/day3-persisted-windows.json').read_text())['windows']
    results=[]
    for domain in ('TRANSPORT','ROAMING','MOBILE_DATA'):
        context=next(r['input']['context'] for r in rows if r['domain']==domain and r['step']==0)
        request={'requestId':str(uuid4()),'requestedAt':datetime.now(timezone.utc).isoformat().replace('+00:00','Z'),
                 'requestedModelVersion':'trace-link-shadow-v1','features':build_features(context)}
        expected=score(request,loaded);started=time.perf_counter();status,actual=get(base+'/internal/inference/trace-link',request)
        if status!=200:raise ValueError(f'{domain}: HTTP {status} {actual}')
        for field in expected.keys()-{'latencyMs','generatedAt'}:
            if expected[field]!=actual[field]:raise ValueError('Offline/HTTP mismatch: '+field)
        results.append({'domain':domain,'modelSha256':actual['modelSha256'],'anomalyScore':actual['anomalyScore'],
                        'roundTripMs':(time.perf_counter()-started)*1000,'serverLatencyMs':actual['latencyMs']})
    if get(base+'/internal/inference/trace-link',{'unexpected':True})[0]!=422:raise ValueError('Malformed request accepted')
    rejected=[]
    fixtures=json.loads((ROOT/'contracts/fixtures/trace-link/ml-adversarial-cases-v1.json').read_text())['cases']
    for fixture in fixtures:
        request={'requestId':str(uuid4()),'requestedAt':datetime.now(timezone.utc).isoformat().replace('+00:00','Z'),
                 'requestedModelVersion':'trace-link-shadow-v1','features':fixture['untrustedFeatures']}
        status,body=get(base+'/internal/inference/trace-link',request)
        if status!=422 or body.get('reason')!='INVALID_REQUEST' or 'anomalyScore' in body:
            raise ValueError(f"Adversarial context accepted: {fixture['id']}: HTTP {status} {body}")
        rejected.append(fixture['id'])
    print(json.dumps({'status':'PASS','transport':'real HTTP','results':results,'rejectedAdversarialCases':rejected},indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--url');args=parser.parse_args()
    if args.url:verify(args.url.rstrip('/'))
    else:
        with server() as base:verify(base)
