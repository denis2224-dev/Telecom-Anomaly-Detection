"""Rebuild the optional synthetic candidate; never inspect held-out scores."""
import argparse
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from functools import lru_cache
import json
from pathlib import Path
import random
import sys
from zoneinfo import ZoneInfo

import joblib
import numpy as np
import sklearn
from sklearn.ensemble import IsolationForest

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'services/ml-service'))
from app.features.trace_link_features import build_features, contract

MODEL='trace-link-shadow-v1'
SPLITS={'baseline':(4001,0,14),'train':(4101,14,22),'calibration':(4201,22,26),'heldOut':(4301,26,30)}
LOCAL=ZoneInfo('Europe/Chisinau')


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()


def local_key(context):
    local=datetime.fromisoformat(context['window']['from'].replace('Z','+00:00')).astimezone(LOCAL)
    return ('weekday' if local.weekday()<5 else 'weekend',local.strftime('%H:%M'))


@lru_cache(maxsize=1)
def templates():
    return json.loads((ROOT/'contracts/fixtures/trace-link/day3-persisted-windows.json').read_text())['windows']


def source_context(domain,day,slot,seed):
    """Explicit synthetic populations, not reconstructed reference percentages."""
    context=deepcopy(next(r['input']['context'] for r in templates() if r['domain']==domain and r['step']==0))
    context['datasetId']='trace-link-day4-native-synthetic-v1'
    rng=random.Random(seed*100000+day*100+slot)
    start=datetime(2026,9,10,8,tzinfo=timezone.utc)+timedelta(days=day,minutes=slot*5)
    stamp=lambda d:d.isoformat().replace('+00:00','Z')
    context['window'].update(id=sha256(f'{domain}:{seed}:{day}:{slot}'.encode()).hexdigest(),
                             **{'from':stamp(start),'to':stamp(start+timedelta(minutes=5))})
    context['evaluatedAt']=stamp(start+timedelta(minutes=5,seconds=1))
    template=deepcopy(context['evidence'][0]);context['evidence']=[];context['baselines']={}
    definitions=contract('features/trace-link-feature-order-v1.json')['domains'][domain]['metrics']
    for spec in definitions:
        metric=spec['metricId']
        if domain=='ROAMING' and 'success' in metric.lower() and not metric.startswith('roaming.data.'):
            continue  # Procedure scope is DATA; no other procedure is injected into it.
        if metric.endswith('utilization'): p={'type':'TIME_WEIGHTED','weightedSum':rng.uniform(30,65)*300,'validSeconds':300}
        elif metric.endswith('packetLoss'):
            p={'type':'RATIO','numerator':rng.randint(0,8),'denominator':10000}
        elif 'success' in metric.lower():
            attempts=rng.randint(900,1200);p={'type':'RATIO','numerator':attempts-rng.randint(0,8),'denominator':attempts}
        elif metric.endswith('rtt.mean'):
            p={'type':'SUM_COUNT','sum':rng.uniform(12,25)*100,'count':100}
        elif metric.endswith('throughput'):
            p={'type':'SUM_COUNT','sum':rng.uniform(20,45)*100,'count':100}
        else: p={'type':'SNAPSHOT','value':rng.uniform(2,8) if metric.endswith('jitter') else rng.uniform(20,40),'snapshotAt':stamp(start+timedelta(minutes=4))}
        from app.features.trace_link_features import actual
        evidence=deepcopy(template)
        evidence.update(metricId=metric,unit=spec['unit'],primitives=p,actual=actual(p),baseline=None,baselineVersion=None,
                        evidenceId=context['window']['id']+':'+metric,measurementId=context['window']['id']+':'+metric,
                        window=deepcopy(context['window']),scope=deepcopy(context['scope']),generatedAt=context['evaluatedAt'])
        observed=p['snapshotAt'] if p['type']=='SNAPSHOT' else context['window']['to']
        age=(datetime.fromisoformat(context['evaluatedAt'])-datetime.fromisoformat(observed)).total_seconds()
        evidence['freshness'].update(observedAt=observed,asOf=context['evaluatedAt'],sourceAgeSeconds=age)
        evidence['provenance']['sourceChecksum']=sha256(canonical(p)).hexdigest()
        evidence['provenance']['datasetId']=context['datasetId']
        context['evidence'].append(evidence)
    return context


def train(output):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    order_path=ROOT/'contracts/features/trace-link-feature-order-v1.json'
    manifest={'modelVersion':MODEL,'featureSchemaVersion':'trace-link-features-v1','synthetic':True,
      'featureContractSha256':sha256(order_path.read_bytes()).hexdigest(),
      'pythonVersion':sys.version.split()[0],'numpyVersion':np.__version__,'sklearnVersion':sklearn.__version__,'joblibVersion':joblib.__version__,
      'randomState':13102026,'nEstimators':200,'nJobs':1,'scoreMeaning':'empirical calibration rank, not probability',
      'baselineVersion':'trace-link-seasonal-v1','baselineTimezone':'Europe/Chisinau','domains':{}}
    split_manifest={'datasetId':'trace-link-day4-native-synthetic-v1','synthetic':True,
       'generator':'trace_link_candidate.py:v1','generatorSha256':sha256(Path(__file__).read_bytes()).hexdigest(),
       'sourceTemplateSha256':sha256((ROOT/'contracts/fixtures/trace-link/day3-persisted-windows.json').read_bytes().replace(b'\r\n',b'\n')).hexdigest(),
       'textSourceHashPolicy':'LF-normalized UTF-8; model/artifact hashes use exact raw bytes',
       'splits':{k:{'seed':v[0],'fromDay':v[1],'toDayExclusive':v[2]} for k,v in SPLITS.items()},'domains':{}}
    for domain in contract('features/trace-link-feature-order-v1.json')['domains']:
        baseline={}
        baseline_rows=[source_context(domain,day,slot,SPLITS['baseline'][0]) for day in range(14) for slot in range(12)]
        # Same fixed scope and local slot; weekday/weekend kept independent.
        for context in baseline_rows:
            weekday,slot=local_key(context)
            for e in context['evidence']: baseline.setdefault((e['metricId'],weekday,slot),[]).append(e['actual'])
        centers={key:float(np.median(values)) for key,values in baseline.items()}
        mads={key:float(np.median(np.abs(np.asarray(values)-centers[key]))) for key,values in baseline.items()}
        baseline_json=[{'metricId':key[0],'weekdayClass':key[1],'slot':key[2],'center':value,'mad':mads[key],'samples':len(baseline[key])} for key,value in sorted(centers.items())]
        arrays={};counts={};split_hashes={}
        for split,(seed,first,last) in SPLITS.items():
            if split=='baseline': continue
            vectors=[];sanitized=[]
            for day in range(first,last):
                for slot in range(12):
                    context=source_context(domain,day,slot,seed)
                    weekday,local_slot=local_key(context)
                    for e in context['evidence']:
                        center=centers[(e['metricId'],weekday,local_slot)]
                        context['baselines'][e['metricId']]={'status':'DIRECT','center':center,'mad':mads[(e['metricId'],weekday,local_slot)],'sourceScope':context['scope'],
                          'baselineVersion':'trace-link-seasonal-v1','policyVersion':'trace-link-baseline-policy-v1',
                          'trainingFrom':'2026-09-10T00:00:00Z','trainingTo':'2026-09-24T00:00:00Z','coverage':1,'reason':None}
                    features=build_features(context)
                    if not features['eligible']: raise ValueError('Synthetic candidate row ineligible')
                    vectors.append([np.nan if v is None else v for v in features['featureValues']]);sanitized.append(features)
            arrays[split]=np.asarray(vectors,dtype=float);counts[split]=len(vectors);split_hashes[split]=sha256(canonical(sanitized)).hexdigest()
        training=arrays['train']
        impute=np.asarray([float(np.median(column[np.isfinite(column)])) if np.isfinite(column).any() else 0 for column in training.T])
        filled=np.where(np.isnan(training),impute,training)
        center=np.median(filled,axis=0);scale=np.median(np.abs(filled-center),axis=0);scale=np.where(scale>0,scale,1)
        model=IsolationForest(n_estimators=200,random_state=13102026,n_jobs=1).fit((filled-center)/scale)
        calibration=sorted((-model.score_samples((np.where(np.isnan(arrays['calibration']),impute,arrays['calibration'])-center)/scale)).tolist())
        package={'model':model,'impute':impute,'center':center,'scale':scale}
        model_file=output/(domain+'.joblib');joblib.dump(package,model_file)
        calibration_file=output/(domain+'-calibration.json');calibration_file.write_bytes(canonical(calibration)+b'\n')
        baseline_file=output/(domain+'-baselines.json');baseline_file.write_bytes(canonical(baseline_json)+b'\n')
        manifest['domains'][domain]={'modelFile':model_file.name,'modelSha256':sha256(model_file.read_bytes()).hexdigest(),
          'calibrationFile':calibration_file.name,'calibrationSha256':sha256(calibration_file.read_bytes()).hexdigest(),
          'baselineFile':baseline_file.name,'baselineSha256':sha256(baseline_file.read_bytes()).hexdigest(),
          'featureNames':contract('features/trace-link-feature-order-v1.json')['domains'][domain]['featureNames'],
          'sampleCounts':counts,'scope':baseline_rows[0]['scope'],'baselineTrainingTo':'2026-09-24T00:00:00Z'}
        # Hash held-out inputs without scoring them. They are reserved for Day 5.
        split_manifest['domains'][domain]={'hashes':split_hashes,'sampleCounts':counts}
    split_file=output/'split-manifest.json';split_file.write_bytes(canonical(split_manifest)+b'\n')
    manifest['splitManifestSha256']=sha256(split_file.read_bytes()).hexdigest()
    manifest['datasetId']=split_manifest['datasetId']
    (output/'manifest.json').write_bytes((json.dumps(manifest,indent=2)+'\n').encode('utf-8'))
    return manifest


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'services/ml-service/candidate-models'/MODEL)
    args=parser.parse_args();result=train(args.output)
    print('Packaged '+result['modelVersion']+'; held-out inputs hashed, never scored')
