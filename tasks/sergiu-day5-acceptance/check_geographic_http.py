"""Score fresh persisted geographic windows against the local packaged model."""
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
export_path = ROOT / 'services/processor/target/day5-geographic-parity-java.json'
export = json.loads(export_path.read_text(encoding='utf-8'))
base_url = os.environ.get('ML_SERVICE_URL', '').rstrip('/')
if not base_url:
    raise SystemExit('ML_SERVICE_URL must explicitly identify the private packaged-model service')
endpoint = base_url + '/internal/inference'
with urlopen(base_url + '/health/ready', timeout=2) as response:
    readiness = json.load(response)
assert readiness == {'status': 'UP'}, readiness
cases = []
for name, case in export['cases'].items():
    if name.rsplit('/', 1)[1] not in ('normal', 'fault', 'precision'):
        continue
    window = case['feature']
    request = Request(endpoint, json.dumps(window).encode(), {'Content-Type': 'application/json'})
    with urlopen(request, timeout=2) as response:
        result = json.load(response)
    assert result['mlStatus'] == 'OK' and result['modelVersion'] == 'isoforest-v2-synthetic-1', (name, result)
    incompatible = dict(window, baselineVersion='baseline-v2-changed')
    try:
        with urlopen(Request(endpoint, json.dumps(incompatible).encode(), {'Content-Type': 'application/json'}), timeout=2):
            raise AssertionError('Incompatible baseline accepted: ' + name)
    except HTTPError as error:
        assert error.code == 422, (name, error.code)
    cases.append(dict(caseId=name, windowId=window['windowId'], sourceEventIds=window['sourceEventIds'],
                      result=result, incompatibleBaselineStatus=422))
assert len(cases) == 60
output = dict(recordedAtUtc=datetime.now(timezone.utc).isoformat(), endpoint=endpoint, readiness=readiness,
              javaExportSha256=hashlib.sha256(export_path.read_bytes()).hexdigest(),
              compatiblePassed=len(cases), incompatibleRejected=len(cases), cases=cases)
output_path = ROOT / 'output/day5-reconciliation/geographic-ml.json'
output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text(json.dumps(output, indent=2) + '\n', encoding='utf-8')
print('PASS: 60 fresh geographic HTTP scores and 60 incompatible-baseline rejection controls')
