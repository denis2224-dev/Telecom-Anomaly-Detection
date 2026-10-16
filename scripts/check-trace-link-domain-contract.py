import hashlib
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

root = Path(__file__).resolve().parents[1]
schema = json.loads((root / 'contracts/detections/trace-domain-detection-v1.schema.json').read_text())
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema, format_checker=FormatChecker())
examples = json.loads((root / 'contracts/fixtures/trace-link/domain-detection-examples-v1.json').read_text())
def digest(values):
    return hashlib.sha256(json.dumps(values, separators=(',', ':')).encode()).hexdigest()
for item in examples:
    validator.validate(item)
    episode = digest([item[key] for key in ['datasetId', 'domain', 'metric', 'metricVersion', 'scopeType', 'scopeId', 'policyVersion', 'topologyVersion', 'firstObservedAt']])
    detection = digest([episode, item['windowStart'], item['phase'], item['metricVersion']])
    if episode != item['episodeId'] or detection != item['detectionId']:
        raise ValueError('Identity mismatch')
if {item['domain'] for item in examples} != {'transport', 'roaming', 'mobile-data'}:
    raise ValueError('Missing domain example')
print('PASS: strict domain examples and identities')
