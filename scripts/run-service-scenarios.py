#!/usr/bin/env python3
"""Exercise the authenticated, public simulator command API.

The session cookie is deliberately supplied by the real browser login in
``tests/e2e/specs/service-scenarios.spec.ts``; this runner never talks to
Keycloak's admin API or the private generator endpoint.
"""

from __future__ import annotations

import argparse
import json
import os
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, build_opener


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.getenv("SERVICE_BASE_URL", "http://telecom.test:8080"))
    parser.add_argument("--cookie", default=os.getenv("SERVICE_SCENARIOS_SESSION_COOKIE"))
    parser.add_argument("--scope-id", default=os.getenv("SERVICE_SCENARIOS_SCOPE", "VOLTE-MD-CENTRAL"))
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--output", type=Path, default=Path("target/service-scenarios.json"))
    args = parser.parse_args()
    if not args.cookie:
        parser.error("Provide the JSESSIONID from the real supervisor browser login.")

    opener = build_opener()
    opener.addheaders = [("Cookie", f"JSESSIONID={args.cookie}")]

    def request(method: str, path: str, body: dict | None = None, csrf: str | None = None):
        headers = {"Accept": "application/json"}
        if csrf:
            headers["X-CSRF-TOKEN"] = csrf
        payload = None if body is None else json.dumps(body).encode()
        if payload:
            headers["Content-Type"] = "application/json"
        response = opener.open(Request(args.base_url.rstrip("/") + path, data=payload, headers=headers, method=method))
        return json.load(response)

    me = request("GET", "/api/auth/me")
    if "SUPERVISOR" not in me.get("roles", []):
        raise RuntimeError("Authenticated session is not a supervisor session")
    csrf = request("GET", "/api/auth/csrf")["token"]
    request_id = str(uuid.uuid4())
    body = {"requestId": request_id, "seed": 29092026, "scopeId": args.scope_id}
    first = request("POST", "/api/simulator/scenarios/VOLTE_IMS_OVERLOAD", body, csrf)
    retry = request("POST", "/api/simulator/scenarios/VOLTE_IMS_OVERLOAD", body, csrf)
    if retry["runId"] != first["runId"] or retry["scheduledStartAt"] != first["scheduledStartAt"]:
        raise RuntimeError("Exact retry did not return the durable command")

    deadline = time.monotonic() + args.timeout
    run = first
    while run["status"] not in {"COMPLETED", "FAILED", "STOPPED"}:
        if time.monotonic() >= deadline:
            raise TimeoutError("Scenario did not reach a terminal status before the deadline")
        run = request("GET", f"/api/simulator/runs/{run['runId']}")
        time.sleep(min(1.0, max(0.0, deadline - time.monotonic())))

    if run["status"] != "COMPLETED":
        raise RuntimeError(f"Scenario ended unexpectedly: {run['status']}")
    phase = None
    while phase != "RECOVERY":
        if time.monotonic() >= deadline:
            raise TimeoutError("Expected RECOVERY episode phase was not observed before the deadline")
        incidents = request("GET", "/api/incidents?" + urlencode({"scopeId": args.scope_id, "size": 100}))
        phase = next(
            (item.get("latestDetection", {}).get("phase") for item in incidents.get("items", [])
             if item.get("latestDetection")),
            None,
        )
        if phase != "RECOVERY":
            time.sleep(min(1.0, max(0.0, deadline - time.monotonic())))

    result = {"requestId": request_id, "runId": first["runId"], "initial": first,
              "retry": retry, "final": run, "episodePhase": phase}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
