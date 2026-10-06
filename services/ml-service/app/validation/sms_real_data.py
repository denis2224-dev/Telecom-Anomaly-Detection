"""Evaluate the frozen SMS classifier on canonical windows and a separate label ledger."""
import argparse
import csv
from datetime import datetime, timedelta
from hashlib import sha256
import json
import math
from pathlib import Path
import time

from jsonschema import Draft202012Validator, FormatChecker
import numpy as np
from app.inference.scoring import ROOT
from app.inference.sms_classifier import FEATURES, classifier_scores, load_classifier

FAMILIES=('delivery-delay','backlog','delivery-failure','mixed-delay-backlog')
PROFILES=('nominal','high-load','low-volume','healthy-jitter')
PACKAGE=ROOT/'services/ml-service/candidate-models/sms-supervised-v1-2'
MODEL_HASH='f3baf6be91d56c0a8054a9cd81e028774e3af9464d8124a81aa89180030c9f19'
OUTPUT=Draft202012Validator(json.loads((ROOT/'contracts/features/service-feature-window-v2.schema.json').read_bytes()),format_checker=FormatChecker())
BASELINE=json.loads((ROOT/'contracts/baselines/demo-baseline-v2.json').read_bytes())
BASELINE_VALUES=next(item['values'] for item in BASELINE['baselines'] if item['service']=='SMS')


def utc(value):
    if not isinstance(value,str) or not value.endswith('Z'):
        raise ValueError('Require UTC windowStart ending in Z')
    try: when=datetime.fromisoformat(value)
    except ValueError as error: raise ValueError('Invalid UTC timestamp') from error
    if when.second or when.microsecond: raise ValueError('Require an aligned UTC minute')
    return when


def validate_window(window):
    try:
        json.dumps(window,allow_nan=False)
        OUTPUT.validate(window)
    except Exception as error: raise ValueError('Incompatible or nonfinite canonical feature window') from error
    if (window['service']!='SMS' or window['baselineVersion']!='baseline-v2' or window['quality']!='COMPLETE'
            or window['mlEligible'] is not True or window['featureNames']!=FEATURES
            or any(field in window for field in ('label','groundTruth','scenario','operatingProfile'))):
        raise ValueError('Require complete SMS features with the frozen baseline and no labels')
    start=utc(window['windowStart'])
    if datetime.fromisoformat(window['windowEnd'])!=start+timedelta(minutes=1):
        raise ValueError('Incompatible feature window interval')
    expected=sha256(json.dumps([window['scopeId'],window['windowStart'],2],separators=(',',':')).encode()).hexdigest()
    if window['windowId']!=expected: raise ValueError('Noncanonical feature window identity')
    kpis={item['name']:item for item in window['kpis']}
    if len(kpis)!=len(window['kpis']): raise ValueError('Ambiguous duplicate KPIs')
    if any(kpis.get(name,{}).get('baseline')!=value for name,value in BASELINE_VALUES.items()):
        raise ValueError('Incompatible KPI baseline values')
    try:
        values=window['featureValues']
        observed=[kpis[name]['observed'] for name in ('p95DeliveryMs','queueDepth','oldestPendingAgeSec','deliverySrPct','deliveredMessages')]
        expected_values=[observed[0]/BASELINE_VALUES['p95DeliveryMs'],observed[0],observed[1],observed[2],
                         observed[3]-BASELINE_VALUES['deliverySrPct'],observed[4]]
        if any(type(v) not in (int,float) or not math.isfinite(v) for v in values+observed):
            raise ValueError('Nonfinite KPI or feature')
        if not all(math.isclose(a,b,rel_tol=1e-12,abs_tol=1e-12) for a,b in zip(values,expected_values)):
            raise ValueError('Features disagree with canonical KPIs/baseline')
    except (KeyError,TypeError) as error: raise ValueError('Missing canonical SMS KPIs') from error
    return window['scopeId'],start


def read_inputs(features,labels):
    windows={}
    for line in Path(features).read_bytes().splitlines():
        window=json.loads(line)
        key=validate_window(window)
        if key in windows: raise ValueError('Duplicate feature scope/window: ambiguous join')
        windows[key]=window
    if not windows: raise ValueError('Empty canonical features')
    ledger={}
    with Path(labels).open(newline='',encoding='utf-8-sig') as stream:
        reader=csv.DictReader(stream)
        if reader.fieldnames!=['scopeId','windowStart','label','faultFamily','operatingProfile','severity']:
            raise ValueError('Use the exact label CSV template columns')
        for row in reader:
            if None in row or any(value is None for value in row.values()):
                raise ValueError('Invalid label CSV row width')
            key=row['scopeId'],utc(row['windowStart'])
            if key in ledger: raise ValueError('Duplicate label scope/window: ambiguous join')
            if (row['label'] not in ('FAULT','NORMAL') or row['operatingProfile'] not in PROFILES
                    or (row['label']=='FAULT' and (row['faultFamily'] not in FAMILIES or row['severity'] not in ('1','2','3')))
                    or (row['label']=='NORMAL' and (row['faultFamily'] or row['severity']!='0'))):
                raise ValueError('Invalid label, fault family, severity or operating profile')
            ledger[key]=row
    if not ledger: raise ValueError('Empty label ledger')
    return windows,ledger


def counts(rows):
    result=dict(tp=0,fn=0,fp=0,tn=0)
    for row in rows:
        fault=row['label']=='FAULT'; detected=row['detection']
        result['tp' if fault and detected else 'fn' if fault else 'fp' if detected else 'tn']+=1
    faults=result['tp']+result['fn']; healthy=result['fp']+result['tn']
    result.update(faultWindows=faults,healthyWindows=healthy,
                  recall=result['tp']/faults if faults else None,
                  falsePositiveRate=result['fp']/healthy if healthy else None,
                  precision=result['tp']/(result['tp']+result['fp']) if result['tp']+result['fp'] else None)
    return result


def metrics(rows):
    return dict(overall=counts(rows),
                families={family:counts([r for r in rows if r['faultFamily']==family]) for family in FAMILIES},
                healthyProfiles={profile:counts([r for r in rows if r['label']=='NORMAL' and r['operatingProfile']==profile]) for profile in PROFILES},
                severities={str(level):counts([r for r in rows if r['label']=='FAULT' and str(r['severity'])==str(level)]) for level in (1,2,3)})


def acceptance(result,missing,min_faults=100,min_healthy=1000):
    if (any(missing.values()) or any(v['faultWindows']<min_faults for v in result['families'].values())
            or any(v['healthyWindows']<min_healthy for v in result['healthyProfiles'].values())):
        return 'INSUFFICIENT_COVERAGE'
    if (result['overall']['recall']<.70 or result['overall']['falsePositiveRate']>.01
            or any(v['recall']<.70 for v in result['families'].values())
            or any(v['falsePositiveRate']>.01 for v in result['healthyProfiles'].values())):
        return 'FAILED_METRICS'
    return 'PASSED'


def evaluate(features,labels,package=PACKAGE,dataset_id='supplied-data'):
    manifest,model=load_classifier(package)
    if manifest['modelSha256']!=MODEL_HASH or manifest['modelVersion']!='sms-supervised-v1-2' or manifest['threshold']!=.55:
        raise ValueError('Require the frozen classifier and cutoff; no model selection or retraining')
    windows,ledger=read_inputs(features,labels)
    keys=sorted(windows.keys() & ledger.keys())
    if not keys: raise ValueError('No joined labelled feature windows')
    values=np.asarray([windows[key]['featureValues'] for key in keys],dtype=float)[:,manifest['projection']]
    started=time.perf_counter()
    scores=classifier_scores(model,values)
    elapsed=(time.perf_counter()-started)*1000
    rows=[dict(ledger[key],detection=bool(value>=.55)) for key,value in zip(keys,scores)]
    result=metrics(rows)
    missing=dict(unlabelledFeatures=len(windows.keys()-ledger.keys()),labelsWithoutFeatures=len(ledger.keys()-windows.keys()))
    return dict(schemaVersion=1,evaluationKind='SUPPLIED_LABELLED_DATA',datasetId=dataset_id,
                modelVersion=manifest['modelVersion'],threshold=.55,scoreSemantics=manifest['scoreSemantics'],
                **result,missingCoverage=missing,acceptanceStatus=acceptance(result,missing),
                coverageRequirements=dict(faultsPerFamily=100,healthyPerProfile=1000),
                inference=dict(batchMs=elapsed,windows=len(keys),msPerWindow=elapsed/len(keys)),
                provenance=dict(modelSha256=manifest['modelSha256'],packageManifestSha256=sha256((Path(package)/'manifest.json').read_bytes()).hexdigest(),
                                featuresSha256=sha256(Path(features).read_bytes()).hexdigest(),labelsSha256=sha256(Path(labels).read_bytes()).hexdigest(),
                                baselineSha256=sha256((ROOT/'contracts/baselines/demo-baseline-v2.json').read_bytes().replace(b'\r\n',b'\n')).hexdigest()))


def write_report(report,output):
    output=Path(output)
    markdown=output.with_suffix('.md')
    if output.exists() or markdown.exists(): raise FileExistsError('Evaluation reports are immutable; use a fresh output path')
    text=['# SMS classifier evaluation', '', f"Dataset: {report.get('datasetId','synthetic replay')}. Acceptance: **{report['acceptanceStatus']}**.",
          '', 'Scores are uncalibrated. Frozen cutoff: 0.55. No retraining or threshold selection.', '',
          '| Cohort | TP | FN | FP | TN | Recall | False positives |','| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for name,group in [('Overall',report['overall']),*report['families'].items(),*report['healthyProfiles'].items()]:
        recall='n/a' if group['recall'] is None else f"{group['recall']:.2%}"
        fpr='n/a' if group['falsePositiveRate'] is None else f"{group['falsePositiveRate']:.2%}"
        text.append(f"| {name} | {group['tp']} | {group['fn']} | {group['fp']} | {group['tn']} | {recall} | {fpr} |")
    text+=['','Missing coverage: '+json.dumps(report.get('missingCoverage',{})), '', 'Provenance:','', '```json',json.dumps(report['provenance'],indent=2),'```','',
           'Actual real-network validation remains PENDING_DATA until labelled network data is supplied. Synthetic replay metrics do not establish real-network accuracy.']
    with output.open('x',encoding='utf-8') as stream: stream.write(json.dumps(report,indent=2,allow_nan=False)+'\n')
    with markdown.open('x',encoding='utf-8') as stream: stream.write('\n'.join(text)+'\n')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--features',type=Path,required=True)
    parser.add_argument('--labels',type=Path,required=True)
    parser.add_argument('--dataset-id',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    report=evaluate(args.features,args.labels,dataset_id=args.dataset_id)
    write_report(report,args.output)
    print(report['acceptanceStatus'])
