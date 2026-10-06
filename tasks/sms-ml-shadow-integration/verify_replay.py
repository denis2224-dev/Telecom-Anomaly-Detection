"""Additional immutable acceptance evidence for control-only traffic and provenance."""
import argparse
import csv
from collections import Counter
from hashlib import sha256
import json
from pathlib import Path


def verify(directory, output):
    directory=Path(directory)
    report=json.loads((directory/'report.json').read_bytes())
    dataset=json.loads((directory/'dataset.json').read_bytes())
    if report['acceptanceStatus']!='PASSED': raise ValueError('Delivery replay has not passed')
    features=[json.loads(line) for line in (directory/'processor-features.jsonl').read_bytes().splitlines()]
    controls={row['windowId'] for row in features[:dataset['counts']['healthy']]}
    decisions=[json.loads(line) for line in (directory/'rule-on-detections.jsonl').read_bytes().splitlines()]
    control_starts={(row['scopeId'],row['windowStart']) for row in features if row['windowId'] in controls}
    healthy_detections=sum((row['scopeId'],row['windowStart']) in control_starts for row in decisions)
    if healthy_detections: raise AssertionError('Healthy controls created rule incidents')
    with (directory/'labels.csv').open(newline='',encoding='utf-8-sig') as stream: labels=list(csv.DictReader(stream))
    cells=Counter(f"{row['faultFamily']}/{row['severity']}/{row['operatingProfile']}" for row in labels if row['label']=='FAULT')
    expected={f'{family}/{severity}/{profile}' for family in report['families'] for severity in (1,2,3) for profile in report['healthyProfiles']}
    if set(cells)!=expected or any(count<60 for count in cells.values()): raise AssertionError('Incomplete fault-family/severity/profile coverage')
    healthy=Counter(row['operatingProfile'] for row in labels if (row['scopeId'],row['windowStart']) in control_starts)
    if any(healthy[profile]<2016 for profile in report['healthyProfiles']): raise AssertionError('Incomplete healthy controls')
    result=dict(schemaVersion=1,status='PASSED',healthyControlWindows=len(controls),healthyControlDetections=healthy_detections,
                faultCoverage=dict(sorted(cells.items())),healthyControlProfiles=dict(healthy),
                pipelineReportSha256=sha256((directory/'report.json').read_bytes()).hexdigest(),
                provenanceFiles={path.name:sha256(path.read_bytes()).hexdigest() for path in directory.iterdir()
                                 if path.name in ('dataset.json','processor.log','incident.log','concurrent-publisher.log')},
                inferenceBudgetMs=250,minimumFaultWindowsPerFamily=720,minimumHealthyControlsPerProfile=2016,
                realNetworkValidation='PENDING_DATA')
    with Path(output).open('x',encoding='utf-8') as stream: stream.write(json.dumps(result,indent=2)+'\n')
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    print(json.dumps(verify(args.run,args.output),indent=2))
