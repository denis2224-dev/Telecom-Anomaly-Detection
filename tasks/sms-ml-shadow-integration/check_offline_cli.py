"""Check the actual offline command against the enabled classifier HTTP endpoint."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT=Path(__file__).resolve().parents[2]


def check(directory,base):
    directory=Path(directory)
    with (directory/'features.jsonl').open(encoding='utf-8') as stream: window=json.loads(next(stream))
    path=directory/'offline-cli-window.json'
    with path.open('x',encoding='utf-8') as stream: stream.write(json.dumps(window))
    offline=json.loads(subprocess.check_output([sys.executable,str(ROOT/'services/ml-service/training/sms_classifier.py'),
        '--models',str(ROOT/'services/ml-service/candidate-models/sms-supervised-v1-2'),'--window',str(path.resolve())],cwd=ROOT,text=True))
    request=urllib.request.Request(base+'/internal/inference/sms-classifier',data=json.dumps(window).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=2) as response: served=json.load(response)
    if served['mlStatus']!='OK' or any(served[key]!=value for key,value in offline.items()):
        raise AssertionError('Offline CLI and HTTP classifier differ')
    result=dict(status='PASSED',offlineCliHttpParity=True,modelVersion=offline['modelVersion'],
                modelSha256=served['modelSha256'],threshold=served['threshold'])
    with (directory/'offline-cli-check.json').open('x',encoding='utf-8') as stream:stream.write(json.dumps(result,indent=2)+'\n')
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',type=Path,required=True)
    parser.add_argument('--base-url',required=True)
    args=parser.parse_args()
    print(json.dumps(check(args.run,args.base_url),indent=2))
