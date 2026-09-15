"""Verify packaged JAR previews and real HTTP probes with Kafka unavailable (stdlib only)."""

import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

from observation_contract import ROOT, ObservationBatch


def request_health(port, group):
    url = f"http://127.0.0.1:{port}/actuator/health/{group}"
    try:
        with urlopen(url, timeout=2) as response:
            return response.status, json.load(response)["status"]
    except HTTPError as response:
        return response.code, json.load(response)["status"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java", help="Java 21 executable")
    args = parser.parse_args()
    output = ROOT / "target/streaming-smoke"
    output.mkdir(parents=True, exist_ok=True)
    jars = {name: ROOT / f"services/{name}/target/{name}-0.3.0-SNAPSHOT.jar"
            for name in ("event-generator", "processor")}
    for jar in jars.values():
        if not jar.is_file():
            raise FileNotFoundError(f"Build the executable JAR first: {jar}")
    preview = [args.java, "-jar", str(jars["event-generator"]),
               "--spring.main.web-application-type=none", "--spring.main.banner-mode=off",
               "--logging.level.root=OFF", "--debug=false", "--trace=false",
               "--generator.preview=true", "--generator.count=10",
               "--generator.seed=15092026", "--generator.logical-time=2026-09-15T08:03:42Z",
               "--spring.kafka.bootstrap-servers="]
    runs = [subprocess.run(preview, check=True, capture_output=True, text=True, timeout=45).stdout
            for _ in range(2)]
    assert runs[0] == runs[1], "Packaged preview bytes differ"
    events = json.loads(runs[0])
    assert len(events) == 10
    batch = ObservationBatch()
    assert all(batch.accept(event) == "ACCEPTED" for event in events)
    assert all(batch.accept(event) == "DUPLICATE" for event in events)
    (output / "preview.json").write_text(runs[0], encoding="utf-8")
    print("PASS: packaged preview generates ten identical payloads twice; Python schema/semantics/retries pass")

    # Reserve neither a shared broker nor a fixed Kafka port; use an unused local endpoint.
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        kafka_port = sock.getsockname()[1]
    for name, port in (("event-generator", 8081), ("processor", 8083)):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", port))  # Fail rather than probe someone else's process.
        command = [args.java, "-jar", str(jars[name]), f"--server.port={port}",
                   f"--spring.kafka.bootstrap-servers=127.0.0.1:{kafka_port}",
                   "--generator.preview=false", "--logging.level.root=WARN", "--debug=false", "--trace=false"]
        with (output / f"{name}.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            try:
                deadline = time.monotonic() + 40
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f"{name} exited; inspect {log.name}")
                    try:
                        if request_health(port, "liveness") == (200, "UP"):
                            break
                    except (URLError, TimeoutError):
                        pass
                    if time.monotonic() >= deadline:
                        raise TimeoutError(f"{name} did not become live")
                    time.sleep(0.2)
                assert request_health(port, "readiness") == (503, "DOWN")
                assert request_health(port, "liveness") == (200, "UP")
                print(f"PASS: {name}:{port}, Kafka unavailable at startup: liveness=200/UP, readiness=503/DOWN")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
