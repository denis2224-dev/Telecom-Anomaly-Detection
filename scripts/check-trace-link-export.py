import argparse
import csv
import http.cookiejar
import io
import json
from decimal import Decimal
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPCookieProcessor, build_opener

parser = argparse.ArgumentParser()
parser.add_argument('--origin', required=True)
parser.add_argument('--cookies', required=True)
parser.add_argument('--query', required=True)
args = parser.parse_args()
origin = urlparse(args.origin)
if origin.scheme not in ('http', 'https') or not origin.netloc or origin.path not in ('', '/'):
    raise SystemExit('Use a single application origin')
jar = http.cookiejar.MozillaCookieJar(args.cookies)
jar.load(ignore_discard=True, ignore_expires=False)
opener = build_opener(HTTPCookieProcessor(jar))
query = json.loads(open(args.query, encoding='utf-8').read())
encoded = urlencode(query)

def fetch(path):
    with opener.open(args.origin.rstrip('/') + path, timeout=15) as response:
        if urlparse(response.url).netloc != origin.netloc:
            raise SystemExit('Authentication must complete on the application origin')
        return response.read().decode('utf-8')

series = json.loads(fetch('/api/v2/metrics/series?' + encoded), parse_float=Decimal)
rows = list(csv.DictReader(io.StringIO(fetch('/api/v2/exports/metrics.csv?' + encoded))))
if len(rows) != len(series['points']):
    raise SystemExit('CSV point count differs from JSON')
for actual, expected in zip(rows, series['points']):
    for field in ('from', 'to', 'quality', 'nullReason', 'qualityReason', 'sourceQuality'):
        if actual[field] != (expected.get(field) or ''):
            raise SystemExit('CSV bucket or quality differs from JSON')
    for field in ('value', 'coverage', 'numerator', 'denominator', 'sumValue', 'sampleCount',
                  'expectedSlots', 'receivedSlots', 'sourceCoverage'):
        source = expected.get(field)
        if (source is None and actual[field] != '') or (source is not None and
                (actual[field] == '' or Decimal(actual[field]) != Decimal(str(source)))):
            raise SystemExit('CSV primitive or value differs from JSON')
    for field in ('datasetId', 'domain', 'metric', 'scopeType', 'scopeId', 'resolution', 'unit',
                  'aggregation', 'policyVersion', 'topologyVersion', 'requestedFrom', 'requestedTo',
                  'effectiveFrom', 'effectiveTo'):
        if actual[field] != str(series[field]):
            raise SystemExit('CSV query context differs from JSON')
    for field in ('synthetic', 'partialStart', 'partialEnd'):
        if actual[field] != str(series[field]).lower():
            raise SystemExit('CSV provenance differs from JSON')
    if int(actual['sourceResolutionSeconds']) != series['sourceResolutionSeconds']:
        raise SystemExit('CSV source resolution differs from JSON')
    for field in ('projectionIds', 'provenance'):
        if json.loads(actual[field], parse_float=Decimal) != expected[field]:
            raise SystemExit('CSV row provenance differs from JSON')
print(json.dumps({'status': 'passed', 'points': len(rows), 'datasetId': series['datasetId']}))
