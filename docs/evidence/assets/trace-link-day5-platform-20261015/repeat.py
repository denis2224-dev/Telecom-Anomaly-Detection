from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
import gzip
import json
import subprocess
import sys

root = Path.cwd()
output = root/'tmp/day5-platform-correction'
archive = root/'docs/evidence/assets/trace-link-day5-20261015'
export = output/'archived-processor.json'
export.write_bytes(gzip.decompress((archive/'processor-export.json.gz').read_bytes()))
records = []
for label in ('first', 'second'):
    command = [sys.executable, 'services/ml-service/training/trace_link_evaluation.py',
        '--dataset', str(archive/'study'), '--protocol', str(archive/'study/protocol.json'),
        '--processor-export', str(export), '--output', str(output/label)]
    start = datetime.now(timezone.utc).isoformat()
    with (output/(label+'-evaluation.log')).open('w', encoding='utf-8') as log:
        code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT).returncode
    records.append(dict(command=command, startedAt=start, exitCode=code))
    if code:
        (output/'repeat.json').write_text(json.dumps(dict(commands=records, status='FAIL'), indent=2))
        sys.exit(code)
comparisons = {}
for name in ('results.json', 'predictions.json', 'thresholds.json'):
    paths = [output/'first'/name, output/'second'/name, archive/'results'/name]
    hashes = [sha256(path.read_bytes()).hexdigest() for path in paths]
    comparisons[name] = dict(first=hashes[0], second=hashes[1], archived=hashes[2], equal=len(set(hashes))==1)
passed = all(item['equal'] for item in comparisons.values())
(output/'repeat.json').write_text(json.dumps(dict(commands=records,
    exportKind='ARCHIVED_JAVA_EXPORT_NOT_FRESH_REPRODUCTION', comparisons=comparisons,
    status='PASS' if passed else 'FAIL'), indent=2)+'\n', encoding='utf-8')
sys.exit(0 if passed else 1)
