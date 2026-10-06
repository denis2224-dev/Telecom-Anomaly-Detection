"""Fresh schema-valid observations and separate ground truth; never opens old holdouts."""
import csv
from datetime import datetime, timezone, timedelta
from hashlib import sha256
import json
from pathlib import Path
import sys
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'services/ml-service/training'),str(ROOT/'services/ml-service')]
import generate_history
from scenario_history import scenario_window, NORMALS, FAULTS


def generate(directory,seed=3000000):
    directory=Path(directory)
    directory.mkdir(parents=True,exist_ok=False)
    start=datetime(2027,5,3,tzinfo=timezone.utc)
    captured=[]
    builder=generate_history.build_features
    def capture(raw,nodes,context):
        captured[:] = [raw,*nodes]
        return builder(raw,nodes,context)
    counts={'faults':0,'healthy':0,'recovery':0}
    with (directory/'observations.jsonl').open('x',encoding='utf-8') as raw_stream, \
         (directory/'features.jsonl').open('x',encoding='utf-8') as feature_stream, \
         (directory/'labels.csv').open('x',encoding='utf-8',newline='') as label_stream, \
         patch.object(generate_history,'build_features',capture):
        labels=csv.writer(label_stream)
        labels.writerow(['scopeId','windowStart','label','faultFamily','operatingProfile','severity'])
        def emit(when,scenario,profile,severity,run_seed,run_id,recovery=False):
            window=scenario_window('SMS',when,run_seed,scenario,severity,run_id,operating_profile=profile)
            raw_stream.write(json.dumps(dict(observations=captured,expectedFeature=window),separators=(',',':'),allow_nan=False)+'\n')
            feature_stream.write(json.dumps(window,separators=(',',':'),allow_nan=False)+'\n')
            fault=scenario in FAULTS['SMS']
            labels.writerow([window['scopeId'],window['windowStart'],'FAULT' if fault else 'NORMAL',scenario if fault else '',profile,severity])
            counts['faults' if fault else 'recovery' if recovery else 'healthy']+=1
        # 2016 distinct windows/profile across a full operating week, every five minutes.
        for index,profile in enumerate(NORMALS):
            for offset in range(2016):
                emit(start+timedelta(days=index*7,minutes=offset*5),profile,profile,0,seed+index,'shadow-healthy-'+profile)
        when=start+timedelta(days=28)
        episode=0
        for family in FAULTS['SMS']:
            for severity in (1,2,3):
                for profile in NORMALS:
                    for repetition in range(2):
                        episode+=1
                        for minute in range(30):
                            emit(when+timedelta(minutes=minute),family,profile,severity,seed+100+episode,'shadow-fault-'+str(episode))
                        when+=timedelta(minutes=30)
                        for minute in range(5):
                            emit(when+timedelta(minutes=minute),'nominal',profile,0,seed+1000+episode,'shadow-recovery-'+str(episode),True)
                        when+=timedelta(minutes=5)
    manifest=dict(datasetId=f'sms-shadow-replay-20270503-seed{seed}',syntheticOnly=True,usedForModelSelection=False,
                  seedOffset=seed,start=start.isoformat(),endExclusive=when.isoformat(),counts=counts,windows=sum(counts.values()),
                  files={path.name:sha256(path.read_bytes()).hexdigest() for path in directory.iterdir()})
    (directory/'dataset.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    return manifest


if __name__=='__main__':
    print(json.dumps(generate(Path(sys.argv[1])),indent=2))
