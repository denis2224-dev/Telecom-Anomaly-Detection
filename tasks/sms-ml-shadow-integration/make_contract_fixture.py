"""Create the illustrative healthy event once from canonical fixtures and frozen scoring."""
from hashlib import sha256
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'services/ml-service'))
from app.features.service_features import build_features
from app.inference.sms_classifier import score,load_classifier

if __name__=='__main__':
    raw=json.loads((ROOT/'contracts/fixtures/observations/normal-sms.json').read_bytes())
    node=json.loads((ROOT/'contracts/fixtures/observations/normal-smsc.json').read_bytes())
    baselines=json.loads((ROOT/'contracts/baselines/demo-baseline-v2.json').read_bytes())
    context=dict(baselineVersion='baseline-v2',scopeId=raw['scopeId'],sourceScopeId=raw['scopeId'],service='SMS',status='DIRECT',hourOfWeek=32,
                 values=next(b['values'] for b in baselines['baselines'] if b['service']=='SMS'))
    window=build_features(raw,[node],context)
    loaded=load_classifier(ROOT/'services/ml-service/candidate-models/sms-supervised-v1-2')
    result=score(window,loaded)
    event=dict(schemaVersion=1,evidenceId=sha256(json.dumps([window['windowId'],result['modelVersion']],separators=(',',':')).encode()).hexdigest(),
               **{field:window[field] for field in ('windowId','service','scopeId','windowStart','windowEnd','featureVersion','baselineVersion','topologyVersion')},
               requestedAt='2026-09-15T08:01:10Z',completedAt='2026-09-15T08:01:10.050Z',requestedModelVersion=result['modelVersion'],
               modelSha256=loaded[0]['modelSha256'],mlStatus='OK',threshold=.55,**result)
    output=ROOT/'contracts/fixtures/ml-shadow/sms-healthy-v1.json'
    output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('x',encoding='utf-8') as stream:stream.write(json.dumps(event,indent=2)+'\n')
