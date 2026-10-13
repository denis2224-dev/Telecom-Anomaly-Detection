import argparse
import concurrent.futures
import datetime
import http.cookiejar
import json
import math
import pathlib
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--origin', required=True)
parser.add_argument('--cookies', required=True)
parser.add_argument('--admin-cookies', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--steady-rps', type=float, default=10)
parser.add_argument('--burst-rps', type=float, default=30)
parser.add_argument('--workers', type=int, default=24)
parser.add_argument('--burst-seconds', type=int, default=60)
parser.add_argument('--recovery-seconds', type=int, default=180)
args = parser.parse_args()
if not args.origin.startswith(('http://', 'https://')) or args.workers < 1 or min(args.steady_rps, args.burst_rps) <= 0:
    parser.error('Invalid origin, workers or request rate')
local = threading.local()
samples = []
telemetry = []
workload = [
    '/api/notifications?size=20',
    '/api/notifications?size=20',
    '/api/v2/inventory/cities',
    '/api/v2/metrics/series?datasetId=trace-link-md-30d-v1&domain=transport&metric=link.utilization&scopeType=LINK_DIRECTION&scopeId=chi-hin:a-to-b&from=2026-10-09T09:00:00Z&to=2026-10-10T09:00:00Z&resolution=5m',
    '/api/v2/metrics/series?datasetId=trace-link-md-30d-v1&domain=roaming&metric=registration.success_rate&scopeType=PARTNER_DIRECTION&scopeId=RO-DEMO-01:out&from=2026-10-09T09:00:00Z&to=2026-10-10T09:00:00Z&resolution=15m',
    '/api/v2/metrics/series?datasetId=trace-link-md-30d-v1&domain=mobile-data&metric=data.session_success_rate&scopeType=CITY&scopeId=hin&from=2026-10-09T09:00:00Z&to=2026-10-10T09:00:00Z&resolution=1h'
]

def opener(cookie_file):
    jar = http.cookiejar.MozillaCookieJar(cookie_file)
    jar.load(ignore_discard=True, ignore_expires=False)
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

def request(path, phase, offered_at):
    if not hasattr(local, 'client'):
        local.client = opener(args.cookies)
    started = time.perf_counter()
    status = 0
    valid = False
    error = None
    try:
        with local.client.open(args.origin.rstrip('/') + path, timeout=10) as response:
            status = response.status
            data = response.read()
            valid = status == 200 and 'application/json' in response.headers.get('Content-Type', '')
            if valid:
                payload = json.loads(data)
                if path.startswith('/api/notifications'):
                    valid = isinstance(payload.get('items'), list) and 'unreadCount' in payload
                elif path.startswith('/api/v2/metrics/series'):
                    valid = isinstance(payload.get('points'), list) and payload.get('synthetic') is True
                else:
                    valid = isinstance(payload.get('cities'), list)
            if not valid:
                error = 'invalid_response'
    except urllib.error.HTTPError as failure:
        status = failure.code
        error = 'http_error'
    except Exception as failure:
        error = type(failure).__name__
    return {'phase': phase, 'route': path.split('?')[0], 'status': status,
            'valid': valid, 'error': error, 'latencyMs': (time.perf_counter() - started) * 1000,
            'queueDelayMs': (started - offered_at) * 1000}

def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)]

admin = opener(args.admin_cookies)
started_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
phases = [('warmup', 120, args.steady_rps), ('steady', 900, args.steady_rps),
          ('burst', args.burst_seconds, args.burst_rps), ('recovery', args.recovery_seconds, args.steady_rps)]
phase_stats = []
with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
    for phase, seconds, rate in phases:
        start = time.perf_counter()
        end = start + seconds
        next_offer = start
        next_metrics = start
        offered = rejected = 0
        pending = set()
        while time.perf_counter() < end:
            now = time.perf_counter()
            if now >= next_metrics:
                try:
                    with admin.open(args.origin.rstrip('/') + '/api/ops/capacity', timeout=3) as response:
                        telemetry.append({'phase': phase, 'sample': json.load(response)})
                except Exception as failure:
                    telemetry.append({'phase': phase, 'error': type(failure).__name__})
                next_metrics += 5
            completed = {future for future in pending if future.done()}
            samples.extend(future.result() for future in completed)
            pending -= completed
            while next_offer <= time.perf_counter() and next_offer < end:
                offered += 1
                if len(pending) >= args.workers * 2:
                    rejected += 1
                else:
                    pending.add(pool.submit(request, workload[(offered - 1) % len(workload)], phase, next_offer))
                next_offer += 1 / rate
            time.sleep(0.001)
        samples.extend(future.result() for future in pending)
        phase_stats.append({'phase': phase, 'plannedSeconds': seconds, 'elapsedSeconds': time.perf_counter() - start,
                            'offered': offered, 'clientRejected': rejected})
summary = []
for phase, _, _ in phases:
    for route in sorted({sample['route'] for sample in samples}):
        group = [sample for sample in samples if sample['phase'] == phase and sample['route'] == route]
        successful = [sample['latencyMs'] for sample in group if sample['valid']]
        summary.append({'phase': phase, 'route': route, 'completed': len(group), 'successful': len(successful),
                        'errors': len(group) - len(successful), 'p50Ms': percentile(successful, .50),
                        'p95Ms': percentile(successful, .95), 'p99Ms': percentile(successful, .99),
                        'maxClientQueueMs': max((sample['queueDelayMs'] for sample in group), default=None)})
output = pathlib.Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({'startedAt': started_at, 'phaseStats': phase_stats, 'summary': summary,
    'telemetry': telemetry, 'samples': samples}, indent=2), encoding='utf-8')
print(str(output.resolve()))
