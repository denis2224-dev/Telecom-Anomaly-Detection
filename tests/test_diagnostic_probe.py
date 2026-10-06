import json
import socket
import threading
import time
import unittest
from datetime import datetime, timezone
from datetime import timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from jsonschema import Draft202012Validator, FormatChecker

from scripts.diagnostic_probe import (
    BoundedProbeWorker,
    ErrorCategory,
    Freshness,
    ProbeResultType,
    Protocol,
    WorkerStatus,
    load_inventory,
)


ROOT = Path(__file__).resolve().parents[1]


class _ReadyHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health/ready":
            self.send_response(200)
            self.end_headers()
            return
        self.send_response(404)
        self.end_headers()

    def log_message(self, *_args):
        return


class DiagnosticProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _ReadyHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=2)

    def worker(self, targets, **kwargs):
        return BoundedProbeWorker(
            targets, vantage_point="test-worker", timeout=0.1, **kwargs
        )

    def http_target(self):
        from scripts.diagnostic_probe import ProbeTarget
        return ProbeTarget(
            "ready", "Local readiness", "HTTP_READINESS", Protocol.HTTP,
            f"http://127.0.0.1:{self.server.server_port}/health/ready",
        )

    def test_allowlisted_http_target_is_successful_with_utc_evidence(self):
        worker = self.worker({"ready": self.http_target()})
        try:
            result = worker.probe("ready")
            self.assertEqual(ProbeResultType.SUCCESS, result.result)
            self.assertEqual(Protocol.HTTP, result.protocol)
            self.assertEqual(WorkerStatus.HEALTHY, result.worker_status)
            self.assertEqual("test-worker", result.vantage_point)
            self.assertEqual("+00:00", result.observed_at.isoformat()[-6:])
            self.assertIsNotNone(result.latency_ms)
        finally:
            worker.stop()

    def test_unknown_target_is_rejected_before_network_access(self):
        worker = self.worker({})
        try:
            with patch("socket.create_connection", side_effect=AssertionError("network called")):
                result = worker.probe("not-in-inventory")
            self.assertEqual(ProbeResultType.REJECTED, result.result)
            self.assertEqual(ErrorCategory.TARGET_NOT_ALLOWED, result.error_category)
        finally:
            worker.stop()

    def test_timeout_is_bounded_and_worker_remains_healthy(self):
        from scripts.diagnostic_probe import ProbeTarget
        target = ProbeTarget("slow", "Slow TCP", "TCP_REACHABILITY", Protocol.TCP, "127.0.0.1", 9)
        worker = self.worker({"slow": target})
        try:
            def timeout(*_args, **_kwargs):
                time.sleep(0.2)
                raise TimeoutError

            with patch("socket.create_connection", side_effect=timeout):
                started = time.monotonic()
                result = worker.probe("slow")
                elapsed = time.monotonic() - started
            self.assertLess(elapsed, 1.0)
            self.assertEqual(ProbeResultType.TIMEOUT, result.result)
            self.assertEqual(WorkerStatus.HEALTHY, result.worker_status)
        finally:
            worker.stop()

    def test_stale_result_is_not_current_health(self):
        worker = self.worker({})
        try:
            result = worker._result(
                "old", None, Protocol.TCP, datetime.now(timezone.utc) - timedelta(minutes=5),
                ProbeResultType.SUCCESS, ErrorCategory.NONE,
            )
            self.assertEqual(Freshness.STALE, result.freshness)
        finally:
            worker.stop()

    def test_stopped_worker_is_distinct_from_target_failure(self):
        worker = self.worker({})
        worker.stop()
        result = worker.probe("not-in-inventory")
        self.assertEqual(ProbeResultType.REJECTED, result.result)
        worker = self.worker({"ready": self.http_target()})
        worker.stop()
        result = worker.probe("ready")
        self.assertEqual(ProbeResultType.WORKER_UNAVAILABLE, result.result)
        self.assertEqual(ErrorCategory.WORKER_STOPPED, result.error_category)
        self.assertEqual(WorkerStatus.STOPPED, result.worker_status)

    def test_protocol_mismatch_is_rejected(self):
        worker = self.worker({"ready": self.http_target()})
        try:
            result = worker.probe("ready", Protocol.TCP)
            self.assertEqual(ProbeResultType.REJECTED, result.result)
            self.assertEqual(ErrorCategory.INVALID_TARGET, result.error_category)
        finally:
            worker.stop()

    def test_tcp_http_and_icmp_results_keep_protocol_identity(self):
        from scripts.diagnostic_probe import ProbeTarget

        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        tcp_target = ProbeTarget(
            "tcp", "Local TCP", "TCP_REACHABILITY", Protocol.TCP,
            "127.0.0.1", listener.getsockname()[1],
        )
        worker = self.worker({"tcp": tcp_target, "ready": self.http_target(), "icmp": ProbeTarget(
            "icmp", "Local ICMP", "ICMP_REACHABILITY", Protocol.ICMP, "127.0.0.1",
        )})
        accept_thread = threading.Thread(target=lambda: listener.accept()[0].close(), daemon=True)
        accept_thread.start()
        try:
            with patch("subprocess.run"):
                tcp = worker.probe("tcp")
                http = worker.probe("ready")
                icmp = worker.probe("icmp")
            self.assertEqual(Protocol.TCP, tcp.protocol)
            self.assertEqual(Protocol.HTTP, http.protocol)
            self.assertEqual(Protocol.ICMP, icmp.protocol)
            self.assertEqual(ProbeResultType.SUCCESS, tcp.result)
            self.assertEqual(ProbeResultType.SUCCESS, http.result)
            self.assertEqual(ProbeResultType.SUCCESS, icmp.result)
        finally:
            listener.close()
            worker.stop()

    def test_inventory_and_result_contracts_validate(self):
        inventory = load_inventory()
        self.assertEqual(3, len(inventory))
        schema = json.loads((ROOT / "contracts/probes/diagnostic-probe-v1.schema.json").read_text())
        Draft202012Validator.check_schema(schema)
        worker = self.worker({"ready": self.http_target()})
        try:
            Draft202012Validator(schema, format_checker=FormatChecker()).validate(
                worker.probe("ready").to_dict()
            )
        finally:
            worker.stop()


if __name__ == "__main__":
    unittest.main()
