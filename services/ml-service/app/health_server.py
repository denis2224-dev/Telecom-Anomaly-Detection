#!/usr/bin/env python3
"""Small ML readiness endpoint; scoring is intentionally not implemented here."""

from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import subprocess
import threading


class Handler(BaseHTTPRequestHandler):
    manifest: Path
    feature_version: str | None
    semaphore: threading.BoundedSemaphore

    def do_GET(self) -> None:
        if not self.semaphore.acquire(blocking=False):
            self.send_error(503, "ML concurrency limit reached")
            return
        try:
            self._do_get()
        finally:
            self.semaphore.release()

    def _do_get(self) -> None:
        if self.path not in {"/health/readiness", "/metrics"}:
            self.send_error(404)
            return
        result = subprocess.run(
            [
                "python",
                "/app/check-manifest.py",
                "--manifest",
                str(self.manifest),
                *(["--feature-version", self.feature_version] if self.feature_version else []),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        ready = result.returncode == 0
        if self.path == "/metrics":
            body = f"telecom_ml_model_ready {int(ready)}\n".encode()
            content_type = "text/plain; version=0.0.4"
        else:
            body = json.dumps({"status": "UP" if ready else "DOWN"}).encode()
            content_type = "application/json"
        self.send_response(200 if ready else 503)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args: object) -> None:
        return


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--feature-version", default=os.environ.get("FEATURE_VERSION"))
    args = parser.parse_args()
    Handler.manifest = args.manifest
    Handler.feature_version = args.feature_version
    Handler.semaphore = threading.BoundedSemaphore(
        max(1, int(os.environ.get("ML_CONCURRENCY", "1")))
    )
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
