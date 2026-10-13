import argparse
import hashlib
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

p=argparse.ArgumentParser()
p.add_argument('--name',required=True)
p.add_argument('--root',type=Path,required=True)
a=p.parse_args()
dest=Path(__file__).resolve().parent/'reports'/a.name
dest.mkdir(parents=True,exist_ok=True)
suites=[]
for source in a.root.rglob('TEST-*.xml'):
    if source.parent.name not in ('surefire-reports','failsafe-reports'):
        continue
    relative=source.relative_to(a.root)
    target=dest/relative
    target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(source,target)
    t=ET.parse(source).getroot()
    suites.append(dict(name=t.get('name'),path=relative.as_posix(),
        sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        **{k:int(t.get(k,0)) for k in ('tests','failures','errors','skipped')},
        skips=[dict(test=x.get('name'),message=s.get('message'),text=s.text)
               for x in t.findall('testcase') for s in x.findall('skipped')]))
report=dict(name=a.name,suites=suites,
    totals={k:sum(t[k] for t in suites) for k in ('tests','failures','errors','skipped')})
(dest/'summary.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report['totals']))
