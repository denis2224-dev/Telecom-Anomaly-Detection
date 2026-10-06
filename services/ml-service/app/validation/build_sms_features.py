"""Build canonical SMS windows from aggregated SERVICE/NODE observation JSONL."""
import argparse
from collections import defaultdict
from datetime import datetime
import json
from pathlib import Path

from app.features.service_features import build_features, SCOPES
from app.validation.sms_real_data import BASELINE,validate_window


def build(source,output):
    groups=defaultdict(list)
    for line in Path(source).read_bytes().splitlines():
        event=json.loads(line)
        if (event.get('kind') not in ('SERVICE','NODE')
                or (event.get('kind')=='SERVICE' and event.get('service')!='SMS')
                or SCOPES.get(event.get('scopeId'),{}).get('service')!='SMS'):
            raise ValueError('Require canonical aggregated SMS SERVICE/NODE observations')
        groups[event['scopeId'],event['windowStart'],event['windowEnd']].append(event)
    if not groups: raise ValueError('Empty aggregated observations')
    windows=[]
    for (scope,start,end),events in sorted(groups.items()):
        services=[event for event in events if event['kind']=='SERVICE']
        nodes=[event for event in events if event['kind']=='NODE']
        if len(services)!=1 or len({event['sourceId'] for event in nodes})!=len(nodes):
            raise ValueError('Ambiguous SERVICE or NODE observations for one window')
        when=datetime.fromisoformat(start)
        hour=when.weekday()*24+when.hour
        baselines=[item for item in BASELINE['baselines'] if item['scopeId']==scope and item['service']=='SMS' and hour in item['hours']]
        if len(baselines)!=1: raise ValueError('No unique frozen baseline for supplied scope/hour')
        context=dict(baselineVersion=BASELINE['baselineVersion'],status='DIRECT',scopeId=scope,sourceScopeId=scope,
                     service='SMS',hourOfWeek=hour,values=baselines[0]['values'])
        window=build_features(services[0],nodes,context)
        validate_window(window)
        windows.append(window)
    with Path(output).open('x',encoding='utf-8') as stream:
        for window in windows: stream.write(json.dumps(window,separators=(',',':'),allow_nan=False)+'\n')
    return len(windows)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--observations',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    print(f'Built {build(args.observations,args.output)} canonical SMS windows')
