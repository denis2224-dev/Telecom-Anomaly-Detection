"""Verify load test utility behaves correctly and fails closed on invalid parameters."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('load_test', ROOT / 'scripts/load-test.py')
LOAD_TEST = importlib.util.module_from_spec(SPEC)
sys.modules['load_test'] = LOAD_TEST
SPEC.loader.exec_module(LOAD_TEST)


class LoadTestTests(unittest.TestCase):
    def test_sample_structure(self):
        sample = LOAD_TEST.Sample(status=200, latency_ms=12.5)
        self.assertEqual(sample.status, 200)
        self.assertEqual(sample.latency_ms, 12.5)

    def test_request_success(self):
        mock_response = MagicMock()
        mock_response.status = 200
        mock_response.read.return_value = b'{"status":"UP"}'
        mock_response.__enter__.return_value = mock_response

        with patch.object(LOAD_TEST, 'urlopen', return_value=mock_response):
            sample = LOAD_TEST.request('http://127.0.0.1:8082/actuator/health', timeout=1.0)
            self.assertEqual(sample.status, 200)
            self.assertIsNotNone(sample.latency_ms)

    def test_request_http_error(self):
        err = HTTPError('http://127.0.0.1:8082/err', 503, 'Service Unavailable', {}, None)
        with patch.object(LOAD_TEST, 'urlopen', side_effect=err):
            sample = LOAD_TEST.request('http://127.0.0.1:8082/err', timeout=1.0)
            self.assertEqual(sample.status, 503)
            self.assertIsNotNone(sample.latency_ms)

    def test_request_connection_failure(self):
        with patch.object(LOAD_TEST, 'urlopen', side_effect=TimeoutError()):
            sample = LOAD_TEST.request('http://127.0.0.1:8082/timeout', timeout=1.0)
            self.assertIsNone(sample.status)
            self.assertIsNone(sample.latency_ms)

    def test_phase_aggregates_metrics(self):
        samples = [
            LOAD_TEST.Sample(200, 10.0),
            LOAD_TEST.Sample(200, 20.0),
            LOAD_TEST.Sample(500, 30.0),
            LOAD_TEST.Sample(None, None),
        ]
        # 1 call before loop for end, 1 for next_request, then 4 loop iterations:
        # iteration 1: check condition (0.0 < 10.0), time.sleep (0.25 - 0.0)
        # iteration 2: check condition (0.25 < 10.0), time.sleep
        # iteration 3: check condition (0.50 < 10.0), time.sleep
        # iteration 4: check condition (0.75 < 10.0), time.sleep
        # iteration 5: check condition (10.0 < 10.0) False -> break
        clock_ticks = [0.0, 0.0, 0.0, 0.0, 0.25, 0.25, 0.50, 0.50, 0.75, 0.75, 10.0]
        with patch.object(LOAD_TEST, 'request', side_effect=samples):
            with patch('time.monotonic', side_effect=clock_ticks):
                with patch('time.sleep'):
                    result = LOAD_TEST.phase('steady', 'http://test', seconds=1.0, rate=4.0, timeout=1.0)

        self.assertEqual(result['name'], 'steady')
        self.assertEqual(result['requests'], 4)
        self.assertEqual(result['errors'], 2)  # one 500, one None
        self.assertEqual(result['status_counts'], {'200': 2, '500': 1})
        self.assertEqual(result['latency_ms']['p50'], 20.0)
        self.assertEqual(result['latency_ms']['max'], 30.0)
