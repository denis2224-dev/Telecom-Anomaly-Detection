"""Compare Day 18 persisted boundary features with the unchanged Python reference."""

from datetime import datetime
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "scripts"), str(ROOT), str(ROOT / "services/ml-service")]
from app.features.service_features import build_features

spec = importlib.util.spec_from_file_location("voice_parity", ROOT / "scripts/check-voice-parity.py")
parity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parity)


def main():
    names = ("first-minute", "second-minute", "zero-eligible-0", "zero-eligible-10", "no-node", "sms-maximum")
    catalogue = json.loads((ROOT / "contracts/baselines/demo-baseline-v2.json").read_text(encoding="utf-8"))
    for name in names:
        path = ROOT / "services/processor/target" / f"day18-boundary-{name}.json"
        saved = json.loads(path.read_text(encoding="utf-8"))
        service = saved["service"]
        start = datetime.fromisoformat(service["windowStart"])
        hour = start.weekday() * 24 + start.hour
        matches = [row for row in catalogue["baselines"] if row["scopeId"] == service["scopeId"] and hour in row["hours"]]
        assert len(matches) == 1
        baseline = dict(baselineVersion=catalogue["baselineVersion"], status="DIRECT", scopeId=service["scopeId"],
                        sourceScopeId=service["scopeId"], service=service["service"], hourOfWeek=hour, values=matches[0]["values"])
        reference = build_features(service, saved["nodes"], baseline)
        error = parity.compare(reference, saved["feature"], name)
        print(f"PASS {name}: full persisted payload; maximum absolute float difference={error:.17g}")
    print(f"PASS: {len(names)} Day 18 persisted Java/Python boundary payloads")


if __name__ == "__main__":
    main()
