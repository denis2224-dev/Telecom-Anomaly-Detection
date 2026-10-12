#!/usr/bin/env python3
"""Run a bounded HTTP capacity profile and emit machine-readable measurements."""

from __future__ import annotations

import argparse
import json
import statistics
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass
class Sample:
    status: int | None
    latency_ms: float | None


def request(url: str, timeout: float) -> Sample:
    started = time.perf_counter()
    try:
        with urlopen(Request(url, headers={"Accept": "application/json"}), timeout=timeout) as response:
            response.read()
            return Sample(response.status, (time.perf_counter() - started) * 1000)
    except HTTPError as exc:
        return Sample(exc.code, (time.perf_counter() - started) * 1000)
    except (TimeoutError, URLError, OSError):
        return Sample(None, None)


def phase(name: str, url: str, seconds: float, rate: float, timeout: float) -> dict:
    end = time.monotonic() + seconds
    samples: list[Sample] = []
    interval = 1 / rate
    next_request = time.monotonic()
    while time.monotonic() < end:
        samples.append(request(url, timeout))
        next_request += interval
        time.sleep(max(0, next_request - time.monotonic()))
    latencies = sorted(s.latency_ms for s in samples if s.latency_ms is not None)
    return {
        "name": name,
        "requested_per_second": rate,
        "requests": len(samples),
        "errors": sum(s.status is None or s.status >= 500 for s in samples),
        "status_counts": {
            str(code): sum(s.status == code for s in samples)
            for code in sorted({s.status for s in samples if s.status is not None})
        },
        "latency_ms": {
            "p50": statistics.median(latencies) if latencies else None,
            "p95": latencies[max(0, int(len(latencies) * 0.95) - 1)] if latencies else None,
            "max": max(latencies) if latencies else None,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True, help="Read-only health or API URL")
    parser.add_argument("--warmup-seconds", type=float, default=120)
    parser.add_argument("--steady-seconds", type=float, default=900)
    parser.add_argument("--burst-seconds", type=float, default=60)
    parser.add_argument("--recovery-seconds", type=float, default=300)
    parser.add_argument("--steady-rate", type=float, default=2)
    parser.add_argument("--burst-rate", type=float, default=10)
    parser.add_argument("--timeout", type=float, default=5)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if min(args.warmup_seconds, args.steady_seconds, args.burst_seconds, args.recovery_seconds) <= 0:
        parser.error("phase durations must be positive")
    started = datetime.now(timezone.utc)
    phases = [
        phase("warmup", args.url, args.warmup_seconds, args.steady_rate, args.timeout),
        phase("steady", args.url, args.steady_seconds, args.steady_rate, args.timeout),
        phase("burst", args.url, args.burst_seconds, args.burst_rate, args.timeout),
        phase("recovery", args.url, args.recovery_seconds, args.steady_rate, args.timeout),
    ]
    report = {
        "started_at": started.isoformat(),
        "finished_at": datetime.now(timezone.utc).isoformat(),
        "target": args.url,
        "phases": phases,
        "interpretation": {
            "application_rates": "Obtain accepted/persisted/finalized/published rates from Prometheus for matching UTC intervals.",
            "capacity_claim": "Not established by this transport-only run; name hardware, configuration, and backlog recovery evidence before claiming capacity.",
        },
    }
    with open(args.output, "w", encoding="utf-8") as output:
        json.dump(report, output, indent=2)
        output.write("\n")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
