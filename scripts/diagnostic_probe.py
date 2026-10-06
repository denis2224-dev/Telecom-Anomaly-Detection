"""Bounded diagnostic probes over an infrastructure-owned target inventory.

This module reports protocol-specific reachability evidence. It deliberately
does not infer telecom health, incidents, or root causes from probe results.
"""

from __future__ import annotations

import enum
import http.client
import json
import queue
import socket
import subprocess
import threading
import time
import urllib.parse
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, Mapping


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INVENTORY = ROOT / "contracts/probes/inventory.json"


class Protocol(str, enum.Enum):
    TCP = "TCP"
    HTTP = "HTTP"
    ICMP = "ICMP"


class ProbeResultType(str, enum.Enum):
    SUCCESS = "SUCCESS"
    TIMEOUT = "TIMEOUT"
    REJECTED = "REJECTED"
    TARGET_UNAVAILABLE = "TARGET_UNAVAILABLE"
    WORKER_UNAVAILABLE = "WORKER_UNAVAILABLE"
    ERROR = "ERROR"


class WorkerStatus(str, enum.Enum):
    HEALTHY = "HEALTHY"
    DEGRADED = "DEGRADED"
    STOPPED = "STOPPED"


class Freshness(str, enum.Enum):
    CURRENT = "CURRENT"
    STALE = "STALE"
    UNKNOWN = "UNKNOWN"


class ErrorCategory(str, enum.Enum):
    NONE = "NONE"
    TARGET_NOT_ALLOWED = "TARGET_NOT_ALLOWED"
    INVALID_TARGET = "INVALID_TARGET"
    TIMEOUT = "TIMEOUT"
    CONNECTION_REFUSED = "CONNECTION_REFUSED"
    DNS_FAILURE = "DNS_FAILURE"
    HTTP_ERROR = "HTTP_ERROR"
    ICMP_UNREACHABLE = "ICMP_UNREACHABLE"
    WORKER_STOPPED = "WORKER_STOPPED"
    QUEUE_FULL = "QUEUE_FULL"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class ProbeTarget:
    target_id: str
    target_name: str
    target_type: str
    protocol: Protocol
    address: str
    port: int | None = None
    path: str | None = None

    @classmethod
    def from_json(cls, value: Mapping[str, object]) -> "ProbeTarget":
        protocol = Protocol(str(value["protocol"]))
        port = value.get("port")
        if protocol is Protocol.TCP and (port is None or not 1 <= int(port) <= 65535):
            raise ValueError("TCP inventory targets require a valid port")
        if protocol is not Protocol.TCP and port is not None:
            raise ValueError("only TCP inventory targets may define a port")
        return cls(
            target_id=str(value["targetId"]),
            target_name=str(value["targetName"]),
            target_type=str(value["targetType"]),
            protocol=protocol,
            address=str(value["address"]),
            port=int(port) if port is not None else None,
            path=str(value["path"]) if value.get("path") is not None else None,
        )


@dataclass(frozen=True)
class ProbeResult:
    probe_id: str
    target_id: str
    target_type: str
    protocol: Protocol
    observed_at: datetime
    result: ProbeResultType
    latency_ms: int | None
    error_category: ErrorCategory
    worker_status: WorkerStatus
    freshness: Freshness
    vantage_point: str
    target: str

    def to_dict(self) -> dict[str, object]:
        return {
            "probeId": self.probe_id,
            "targetId": self.target_id,
            "targetType": self.target_type,
            "protocol": self.protocol.value,
            "observedAt": self.observed_at.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
            "result": self.result.value,
            "latencyMs": self.latency_ms,
            "errorCategory": self.error_category.value,
            "workerStatus": self.worker_status.value,
            "freshness": self.freshness.value,
            "vantagePoint": self.vantage_point,
            "target": self.target,
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), sort_keys=True)

    def with_freshness(self, now: datetime, max_age: timedelta) -> "ProbeResult":
        observed = self.observed_at.astimezone(timezone.utc)
        current = now.astimezone(timezone.utc)
        freshness = (
            Freshness.UNKNOWN
            if current < observed
            else Freshness.CURRENT
            if current - observed <= max_age
            else Freshness.STALE
        )
        return ProbeResult(**{**asdict(self), "freshness": freshness})


@dataclass(frozen=True)
class _Work:
    target: ProbeTarget
    protocol: Protocol
    callback: Callable[[ProbeTarget, Protocol], ProbeResult]


class BoundedProbeWorker:
    """Fixed workers and a bounded queue for controlled reachability checks."""

    def __init__(
        self,
        inventory: Mapping[str, ProbeTarget],
        *,
        vantage_point: str,
        timeout: float = 1.0,
        retries: int = 0,
        max_workers: int = 2,
        queue_size: int = 8,
        freshness: timedelta = timedelta(minutes=2),
    ) -> None:
        if timeout <= 0 or retries < 0 or max_workers <= 0 or queue_size <= 0:
            raise ValueError("timeout, workers and queue must be positive; retries cannot be negative")
        self._inventory = dict(inventory)
        self._vantage_point = vantage_point
        self._timeout = timeout
        self._retries = retries
        self._freshness = freshness
        self._tasks: queue.Queue[tuple[_Work, queue.Queue[ProbeResult]] | None] = queue.Queue(maxsize=queue_size)
        self._stop = threading.Event()
        self._threads = [
            threading.Thread(target=self._run, name=f"diagnostic-probe-{i}", daemon=True)
            for i in range(max_workers)
        ]
        for thread in self._threads:
            thread.start()

    @classmethod
    def from_inventory(
        cls, path: Path = DEFAULT_INVENTORY, **kwargs: object
    ) -> "BoundedProbeWorker":
        entries = json.loads(path.read_text(encoding="utf-8"))
        inventory = {entry["targetId"]: ProbeTarget.from_json(entry) for entry in entries}
        if len(inventory) != len(entries):
            raise ValueError("probe inventory contains duplicate targetId values")
        return cls(inventory, **kwargs)

    @property
    def worker_status(self) -> WorkerStatus:
        return WorkerStatus.STOPPED if self._stop.is_set() else WorkerStatus.HEALTHY

    def stop(self) -> None:
        if self._stop.is_set():
            return
        self._stop.set()
        for _ in self._threads:
            try:
                self._tasks.put_nowait(None)
            except queue.Full:
                break
        for thread in self._threads:
            thread.join(timeout=self._timeout + 0.5)

    def probe(self, target_id: str, protocol: Protocol | str | None = None) -> ProbeResult:
        observed = datetime.now(timezone.utc)
        target = self._inventory.get(target_id)
        if target is None:
            return self._result(
                target_id, None, protocol, observed, ProbeResultType.REJECTED,
                ErrorCategory.TARGET_NOT_ALLOWED,
            )
        requested = Protocol(protocol) if protocol is not None else target.protocol
        if requested is not target.protocol:
            return self._result(
                target_id, target, requested, observed, ProbeResultType.REJECTED,
                ErrorCategory.INVALID_TARGET,
            )
        if self._stop.is_set():
            return self._result(
                target_id, target, requested, observed, ProbeResultType.WORKER_UNAVAILABLE,
                ErrorCategory.WORKER_STOPPED,
            )
        result_queue: queue.Queue[ProbeResult] = queue.Queue(maxsize=1)
        work = _Work(target, requested, self._execute)
        try:
            self._tasks.put_nowait((work, result_queue))
        except queue.Full:
            return self._result(
                target_id, target, requested, observed, ProbeResultType.ERROR,
                ErrorCategory.QUEUE_FULL, worker_status=WorkerStatus.DEGRADED,
            )
        try:
            return result_queue.get(timeout=self._timeout * (self._retries + 1) + 0.5)
        except queue.Empty:
            return self._result(
                target_id, target, requested, observed, ProbeResultType.TIMEOUT,
                ErrorCategory.TIMEOUT,
            )

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                item = self._tasks.get(timeout=0.05)
            except queue.Empty:
                continue
            if item is None:
                self._tasks.task_done()
                return
            work, result_queue = item
            try:
                result_queue.put(work.callback(work.target, work.protocol))
            finally:
                self._tasks.task_done()

    def _execute(self, target: ProbeTarget, protocol: Protocol) -> ProbeResult:
        observed = datetime.now(timezone.utc)
        for attempt in range(self._retries + 1):
            try:
                started = time.monotonic()
                if protocol is Protocol.TCP:
                    with socket.create_connection((target.address, target.port), timeout=self._timeout):
                        pass
                elif protocol is Protocol.HTTP:
                    self._http(target)
                else:
                    self._icmp(target)
                latency = max(0, round((time.monotonic() - started) * 1000))
                return self._result(
                    target.target_id, target, protocol, observed, ProbeResultType.SUCCESS,
                    ErrorCategory.NONE, latency_ms=latency,
                )
            except (TimeoutError, subprocess.TimeoutExpired):
                category = ErrorCategory.TIMEOUT
                result = ProbeResultType.TIMEOUT
            except ConnectionRefusedError:
                category = ErrorCategory.CONNECTION_REFUSED
                result = ProbeResultType.TARGET_UNAVAILABLE
            except socket.gaierror:
                category = ErrorCategory.DNS_FAILURE
                result = ProbeResultType.TARGET_UNAVAILABLE
            except OSError:
                category = ErrorCategory.ICMP_UNREACHABLE if protocol is Protocol.ICMP else ErrorCategory.UNKNOWN
                result = ProbeResultType.TARGET_UNAVAILABLE
            except ValueError:
                category = ErrorCategory.INVALID_TARGET
                result = ProbeResultType.ERROR
            except Exception:
                category = ErrorCategory.HTTP_ERROR if protocol is Protocol.HTTP else ErrorCategory.UNKNOWN
                result = ProbeResultType.ERROR
            if attempt < self._retries:
                continue
            return self._result(target.target_id, target, protocol, observed, result, category)
        raise AssertionError("probe attempts exhausted without a result")

    def _http(self, target: ProbeTarget) -> None:
        parsed = urllib.parse.urlsplit(target.address)
        if parsed.scheme not in {"http", "https"} or parsed.hostname is None:
            raise ValueError("invalid HTTP inventory target")
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        connection_cls = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
        connection = connection_cls(parsed.hostname, port, timeout=self._timeout)
        try:
            request_path = parsed.path or "/"
            if parsed.query:
                request_path += "?" + parsed.query
            connection.request("GET", request_path)
            response = connection.getresponse()
            response.read(0)
            if response.status >= 500:
                raise RuntimeError(f"HTTP status {response.status}")
        finally:
            connection.close()

    def _icmp(self, target: ProbeTarget) -> None:
        command = ["ping", "-c", "1", "-W", str(max(1, round(self._timeout * 1000))), target.address]
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       timeout=self._timeout + 0.2)

    def _result(
        self, target_id: str, target: ProbeTarget | None, protocol: Protocol | str | None,
        observed: datetime, result: ProbeResultType, error: ErrorCategory,
        *, latency_ms: int | None = None, worker_status: WorkerStatus | None = None,
    ) -> ProbeResult:
        selected = Protocol(protocol) if protocol is not None else (target.protocol if target else Protocol.TCP)
        value = ProbeResult(
            probe_id=str(uuid.uuid4()),
            target_id=target_id,
            target_type=target.target_type if target else "UNKNOWN",
            protocol=selected,
            observed_at=observed,
            result=result,
            latency_ms=latency_ms,
            error_category=error,
            worker_status=worker_status or self.worker_status,
            freshness=Freshness.CURRENT,
            vantage_point=self._vantage_point,
            target=target.target_name if target else "UNKNOWN",
        )
        return value.with_freshness(datetime.now(timezone.utc), self._freshness)


def load_inventory(path: Path = DEFAULT_INVENTORY) -> dict[str, ProbeTarget]:
    entries = json.loads(path.read_text(encoding="utf-8"))
    inventory = {entry["targetId"]: ProbeTarget.from_json(entry) for entry in entries}
    if len(inventory) != len(entries):
        raise ValueError("probe inventory contains duplicate targetId values")
    return inventory


if __name__ == "__main__":
    worker = BoundedProbeWorker.from_inventory()
    try:
        print(worker.probe("local-readiness").to_json())
    finally:
        worker.stop()
