"""Compare SMS feature windows with the unchanged reference implementation."""

import argparse
from datetime import datetime
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / "services/ml-service")]
from app.features.service_features import build_features


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def compare(expected, actual, path=""):
    """Exact integers/structure, absolute float tolerance; return maximum float error."""
    if isinstance(expected, dict):
        assert isinstance(actual, dict) and expected.keys() == actual.keys(), path
        return max((compare(v, actual[k], path + "/" + k) for k, v in expected.items()), default=0)
    if isinstance(expected, list):
        assert isinstance(actual, list) and len(expected) == len(actual), path
        return max((compare(v, a, path + "/" + str(i)) for i, (v, a) in enumerate(zip(expected, actual))), default=0)
    if type(expected) is float:
        assert type(actual) in (int, float), path
        error = abs(expected - actual)
        assert error <= 1e-9, (path, expected, actual)
        return error
    assert expected == actual and (type(expected) is not bool or type(actual) is bool), (path, expected, actual)
    return 0


def reference_cases():
    suite = read("contracts/fixtures/features/sms-parity-v2.json")
    shared = read("contracts/fixtures/features/parity-v2.json")
    assert suite["featureVersion"] == shared["featureVersion"] == 2
    # Verify shared SMS cases remain consistent
    shared_sms = {c["id"]: c for c in shared["cases"] if c["id"].startswith("sms-")}
    suite_cases = {c["id"]: c for c in suite["cases"]}
    for case_id, shared_case in shared_sms.items():
        assert case_id in suite_cases, f"Shared case {case_id} missing from sms-parity-v2"
        assert suite_cases[case_id] == shared_case, f"Shared case {case_id} has changed expectations"
    catalog = read("contracts/baselines/demo-baseline-v2.json")
    results = {}
    for case in suite["cases"]:
        raw = read("contracts/fixtures/observations/" + case["observation"] + ".json")
        raw.update(case.get("envelopePatch", {}))
        if "metrics" in raw:
            raw["metrics"].update(case.get("metricsPatch", {}))
        nodes = [read("contracts/fixtures/observations/" + n + ".json") for n in case["nodes"]]
        start = datetime.fromisoformat(raw["windowStart"])
        hour = start.weekday() * 24 + start.hour
        matches = [b for b in catalog["baselines"] if b["scopeId"] == raw["scopeId"] and hour in b["hours"]]
        assert len(matches) == 1, "Parity fixtures require explicit direct baseline coverage"
        baseline = dict(
            baselineVersion=catalog["baselineVersion"],
            status="DIRECT",
            scopeId=raw["scopeId"],
            sourceScopeId=raw["scopeId"],
            service=raw["service"],
            hourOfWeek=hour,
            values=matches[0]["values"],
        )
        result = build_features(raw, nodes, baseline)
        expected = case["expected"]
        compare(expected["mlEligible"], result["mlEligible"])
        compare(expected["featureValues"], result["featureValues"])
        observed = {k["name"]: k["observed"] for k in result["kpis"]}
        for name, value in expected["kpis"].items():
            compare(value, observed[name], name)
        results[case["id"]] = result
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-output", default=None, help="Optional Java output for Day 09 parity comparison")
    args = parser.parse_args()
    expected = reference_cases()
    if args.java_output:
        actual = read(args.java_output)
        assert expected.keys() == actual.keys()
        for name, reference in expected.items():
            error = compare(reference, actual[name], name)
            print(f"PASS {name}: integers exact; maximum absolute float difference={error:.17g}")
        print(f"PASS: {len(expected)} persisted Java/Python payloads, all fields compared")
    else:
        for name in expected:
            print(f"PASS {name}: reference evaluation matches expected parity semantics")
        print(f"PASS: {len(expected)} SMS parity cases verified against canonical Python reference")


if __name__ == "__main__":
    main()
