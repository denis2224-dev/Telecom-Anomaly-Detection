import http.cookiejar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]

class CapacityHarnessTest(unittest.TestCase):
    def test_slow_metrics_does_not_block_offered_http_work(self):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                if self.path.startswith('/api/ops/capacity'):
                    time.sleep(.6)
                    body = {'observedAt': 'test'}
                elif self.path.startswith('/api/notifications'):
                    body = {'items': [], 'unreadCount': 0}
                elif self.path.startswith('/api/v2/metrics/series'):
                    body = {'points': [], 'synthetic': True}
                else:
                    body = {'cities': []}
                encoded = json.dumps(body).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)
        with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    cookie = root / 'cookies.txt'
                    http.cookiejar.MozillaCookieJar(str(cookie)).save()
                    output = root / 'result.json'
                    subprocess.run([sys.executable, str(ROOT / 'scripts/trace-link-api-capacity.py'),
                        '--origin', f'http://127.0.0.1:{server.server_port}', '--cookies', str(cookie),
                        '--admin-cookies', str(cookie), '--output', str(output), '--steady-rps', '20',
                        '--burst-rps', '20', '--workers', '2', '--warmup-seconds', '1',
                        '--steady-seconds', '2', '--burst-seconds', '1', '--recovery-seconds', '1'],
                        check=True, capture_output=True, timeout=15)
                    data = json.loads(output.read_text())
                    self.assertEqual(0, sum(p['clientRejected'] for p in data['phaseStats']))
                    self.assertEqual(0, sum(p['errors'] for p in data['summary']))
                    self.assertEqual(100, sum(p['offered'] for p in data['phaseStats']))
            finally:
                server.shutdown()
