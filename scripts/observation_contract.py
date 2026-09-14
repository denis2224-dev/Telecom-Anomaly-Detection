"""Offline v2 schema, per-document semantics and separate cross-event checks."""

from datetime import datetime
import copy
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


SCHEMA = read_json(ROOT / "contracts/observations/telecom-observation-v2.schema.json")
VALIDATOR = Draft202012Validator(SCHEMA, format_checker=FormatChecker())
SCOPES = {scope["scopeId"]: scope for scope in read_json(
    ROOT / "contracts/topology/demo-scopes-v2.json")["scopes"]}


def validate_observation(event):
    """Validate structure first, then time, authority and counter relationships."""
    VALIDATOR.validate(event)
    start, end, emitted = (datetime.fromisoformat(event[k]) for k in
                           ("windowStart", "windowEnd", "emittedAt"))
    if (end - start).total_seconds() != 60 or emitted < end:
        raise ValueError("Expected one completed UTC minute and emittedAt >= windowEnd")
    scope = SCOPES.get(event["scopeId"])
    if scope is None:
        raise ValueError("Unknown scope")
    source, kind = event["sourceId"], event["kind"]
    nodes = {n["sourceId"]: n["nodeId"] for n in scope["nodes"]}
    if kind == "SERVICE" and (source != scope["serviceSourceId"] or
                               event["service"] != scope["service"]):
        raise ValueError("Non-authoritative service source/scope")
    if kind == "NODE" and nodes.get(source) != event["nodeId"]:
        raise ValueError("Non-authoritative node source/scope")
    if kind == "HEARTBEAT" and source not in {scope["serviceSourceId"], *nodes}:
        raise ValueError("Unknown heartbeat source")
    m = event.get("metrics")
    if m is None:
        return
    if kind == "SERVICE" and event["service"] == "VOLTE":
        if m["attempts"] != m["technicalSuccesses"] + m["technicalFailures"] + m["userOutcomes"]:
            raise ValueError("VoLTE counter identity")
        for successes, attempts in [("technicalSuccesses", "attempts"),
                                    ("rrcSuccesses", "rrcAttempts"),
                                    ("bearerSuccesses", "bearerAttempts")]:
            if m[successes] > m[attempts]:
                raise ValueError(f"{successes} exceeds its own attempts")
        if m["sip503Count"] > m["technicalFailures"]:
            raise ValueError("Final SIP 503 is a subset of technical failures")
    if kind == "SERVICE" and event["service"] == "SMS":
        if m["deliverySuccesses"] > m["deliveryAttempts"]:
            raise ValueError("SMS successes exceed finalized attempts")
        if m["deliveredMessages"] != len(m["deliveryDelayMs"]):
            raise ValueError("One sample per distinct delivered message")
        if m["deliveredMessages"] > m["deliverySuccesses"]:
            raise ValueError("Delivered messages exceed successful attempts")
    if kind == "NODE" and m.get("queueDepth") == 0 and m["oldestPendingAgeSeconds"] != 0:
        raise ValueError("Empty queue has zero oldest pending age")


class ObservationBatch:
    """Finite reference batch, not a durable production receipt store."""

    def __init__(self):
        self.by_id = {}
        self.by_interval = {}

    def accept(self, event):
        validate_observation(event)
        key = tuple(event[k] for k in ("sourceId", "scopeId", "kind", "windowStart"))
        prior_id = self.by_id.get(event["eventId"])
        prior_interval = self.by_interval.get(key)
        for prior in (prior_id, prior_interval):
            if prior is not None and prior != event:
                raise ValueError("CONFLICT: changed content for eventId or natural interval")
        if prior_id is not None or prior_interval is not None:
            return "DUPLICATE"
        snapshot = copy.deepcopy(event)
        self.by_id[event["eventId"]] = snapshot
        self.by_interval[key] = snapshot
        return "ACCEPTED"


def patched(event, patch):
    result = copy.deepcopy(event)
    for pointer, value in patch.items():
        parts = pointer.lstrip("/").split("/")
        target = result
        for part in parts[:-1]:
            target = target[part]
        target[parts[-1]] = value
    return result
