import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument('--name', required=True)
p.add_argument('--cwd', required=True)
p.add_argument('command', nargs=argparse.REMAINDER)
a = p.parse_args()
command = a.command[1:] if a.command[:1] == ['--'] else a.command
root = Path(__file__).resolve().parent
log = root / (a.name + '.log')
start = datetime.datetime.now(datetime.timezone.utc).isoformat()
t = time.monotonic()
with log.open('wb') as stream:
    result = subprocess.run(command, cwd=a.cwd, stdout=stream, stderr=subprocess.STDOUT)
record = dict(name=a.name, cwd=a.cwd, command=command, startedAt=start,
              finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
              elapsedSeconds=round(time.monotonic()-t, 3), exitCode=result.returncode,
              status='PASS' if result.returncode == 0 else 'FAIL',
              log=log.name, logSha256=hashlib.sha256(log.read_bytes()).hexdigest())
(root/(a.name+'.json')).write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
print(json.dumps(record))
print(log.read_text(encoding='utf-8', errors='replace')[-3500:])
raise SystemExit(result.returncode)
