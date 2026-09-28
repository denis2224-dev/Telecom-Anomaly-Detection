"""Reproducible synthetic history for the two version-2 service models."""

import argparse
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import json
from pathlib import Path
import random
import sys
from uuid import NAMESPACE_URL, uuid5

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT), str(ROOT / "services/ml-service")]
from app.features.service_features import build_features

HERE = Path(__file__).resolve().parent
DATA = HERE / "data"
START = datetime(2026, 1, 5, tzinfo=timezone.utc)  # Monday, four weeks before calibration.
MINUTE = timedelta(minutes=1)
WEEK = timedelta(weeks=1)
FIXTURES = {
    "VOLTE": ("normal-volte", "normal-ims", "normal-transport"),
    "SMS": ("normal-sms", "normal-smsc"),
}
BASELINES = json.loads((ROOT / "contracts/baselines/demo-baseline-v2.json").read_text(encoding="utf-8"))


def observation(name):
    return json.loads((ROOT / "contracts/fixtures/observations" / (name + ".json")).read_text(encoding="utf-8"))


def make_window(service, start, seed, fault=False, run_id="sample"):
    """Return a canonical feature window from independently varied raw observations."""
    rng = random.Random(f"{seed}:{service}:{start.isoformat()}:{fault}")
    raw, *nodes = (observation(name) for name in FIXTURES[service])
    end = start + MINUTE
    for event in (raw, *nodes):
        event.update(windowStart=start.isoformat().replace("+00:00", "Z"),
                     windowEnd=end.isoformat().replace("+00:00", "Z"),
                     emittedAt=end.isoformat().replace("+00:00", "Z"))
        event["eventId"] = str(uuid5(NAMESPACE_URL, f"{run_id}:{event['sourceId']}:{start.isoformat()}"))

    busy = start.weekday() < 5 and 8 <= start.hour < 19
    evening = 19 <= start.hour < 23
    load = 350 if busy else 120 if evening else -180
    load += rng.randrange(-80, 81)
    if service == "VOLTE":
        m = raw["metrics"]
        eligible = 1000 + load
        failures = round(eligible * (0.06 if fault else rng.uniform(.004, .009)))
        user = max(0, round(eligible * .02))
        rrc = round(eligible * 1.2)
        bearer = round(eligible * 1.1)
        m.update(attempts=eligible + user, technicalSuccesses=eligible - failures,
                 technicalFailures=failures, userOutcomes=user,
                 rrcAttempts=rrc, rrcSuccesses=rrc - round(rrc * rng.uniform(.003, .007)),
                 bearerAttempts=bearer, bearerSuccesses=bearer - round(bearer * rng.uniform(.007, .013)),
                 sip503Count=round(failures * (.8 if fault else .3)))
        nodes[0]["metrics"]["cpuPct"] = round((88 if fault else 32 + max(load, 0) / 35) + rng.uniform(-4, 4), 2)
        nodes[1]["metrics"]["packetLossRatio"] = round(rng.uniform(.0003, .002), 6)
        nodes[1]["metrics"]["throughputMbps"] = round(120 + load / 10 + rng.uniform(-5, 5), 2)
    else:
        m = raw["metrics"]
        delivered = max(35, round(100 + load / 10))
        successes = delivered + rng.randrange(0, 3)
        m.update(deliveryAttempts=successes + round(successes * rng.uniform(.005, .025)),
                 deliverySuccesses=successes, deliveredMessages=delivered,
                 deliveryDelayMs=[max(1, round((28000 if fault else 1700) * rng.uniform(.75, 1.25)))
                                  for _ in range(delivered)])
        nodes[0]["metrics"].update(queueDepth=round(200 + rng.uniform(0, 30)) if fault else rng.randrange(0, 20),
                                     oldestPendingAgeSeconds=round(120 + rng.uniform(0, 30)) if fault else 0)
        if not fault and nodes[0]["metrics"]["queueDepth"]:
            nodes[0]["metrics"]["oldestPendingAgeSeconds"] = rng.randrange(1, 20)

    baseline = next(b for b in BASELINES["baselines"] if b["service"] == service)
    context = dict(baselineVersion=BASELINES["baselineVersion"], status="DIRECT",
                   scopeId=raw["scopeId"], sourceScopeId=raw["scopeId"], service=service,
                   hourOfWeek=start.weekday() * 24 + start.hour, values=baseline["values"])
    return build_features(raw, nodes, context)


def generate(output=DATA, cadence_minutes=5):
    if cadence_minutes < 1 or 60 % cadence_minutes:
        raise ValueError("cadence_minutes must divide 60")
    output.mkdir(parents=True, exist_ok=True)
    runs = []
    for service in FIXTURES:
        specs = [(f"train-{week + 1}", "train", START + week * WEEK, WEEK, 100 + week, False)
                 for week in range(4)]
        specs += [("calibration", "calibration", START + 4 * WEEK, WEEK, 200, False),
                  ("test-normal", "test", START + 5 * WEEK, WEEK, 300, False),
                  ("test-fault", "test", START + 6 * WEEK, 8 * MINUTE, 400, True)]
        for name, split, start, span, seed, fault in specs:
            run_id = f"{service.lower()}-{name}"
            path = output / (run_id + ".jsonl")
            digest = sha256()
            count = 0
            step = 1 if fault else cadence_minutes
            with path.open("wb") as stream:
                for offset in range(0, int(span / MINUTE), step):
                    when = start + offset * MINUTE
                    window = make_window(service, when, seed, fault, run_id)
                    if not window["mlEligible"]:
                        raise ValueError(f"Ineligible generated window: {run_id} {when}")
                    row = dict(service=service, runId=run_id, windowStart=window["windowStart"],
                               featureNames=window["featureNames"], featureValues=window["featureValues"])
                    if split == "test":
                        row["label"] = "FAULT" if fault else "NORMAL"
                    line = (json.dumps(row, separators=(",", ":"), allow_nan=False) + "\n").encode()
                    stream.write(line)
                    digest.update(line)
                    count += 1
            runs.append(dict(runId=run_id, service=service, split=split, seed=seed,
                             start=start.isoformat().replace("+00:00", "Z"),
                             endExclusive=(start + span).isoformat().replace("+00:00", "Z"),
                             cadenceMinutes=step, rows=count, eligibleRows=count,
                             path=path.name, sha256=digest.hexdigest()))
    manifest = dict(datasetVersion="synthetic-v2-1", featureVersion=2,
                    baselineVersion=BASELINES["baselineVersion"], runs=runs)
    (output.parent / "split_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DATA)
    parser.add_argument("--cadence-minutes", type=int, default=5)
    args = parser.parse_args()
    result = generate(args.output, args.cadence_minutes)
    print(f"Generated {sum(run['rows'] for run in result['runs'])} eligible rows across {len(result['runs'])} runs")
