"""Evaluate the frozen packaged models on untouched labelled test runs."""

import argparse
from hashlib import sha256
import json
from pathlib import Path
import statistics
import sys
from time import perf_counter
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "services/ml-service"))
from app.inference.scoring import load, score

HERE = Path(__file__).resolve().parent
SPLIT = HERE / "split_manifest.json"


def summary(labels, predictions, latencies_ms):
    tp = sum(label == "FAULT" and predicted for label, predicted in zip(labels, predictions))
    fp = sum(label == "NORMAL" and predicted for label, predicted in zip(labels, predictions))
    faults = labels.count("FAULT")
    normals = labels.count("NORMAL")
    ordered = sorted(latencies_ms)
    return dict(testNormalWindows=normals, testFaultWindows=faults, truePositives=tp,
                falsePositives=fp, falseNegatives=faults - tp, trueNegatives=normals - fp,
                precision=tp / (tp + fp) if tp + fp else None,
                recall=tp / faults if faults else None,
                falsePositivesPer1000Normal=1000 * fp / normals if normals else None,
                medianInferenceMs=statistics.median(ordered) if ordered else None,
                p95InferenceMs=ordered[(95 * len(ordered) + 99) // 100 - 1] if ordered else None)


def evaluate(data, api_url=None):
    split_bytes = SPLIT.read_bytes()
    split = json.loads(split_bytes)
    model_manifest = json.loads((HERE.parent / "models/manifest.json").read_text(encoding="utf-8"))
    if sha256(split_bytes).hexdigest() != model_manifest["datasetManifestSha256"]:
        raise ValueError("Packaged model and dataset manifests disagree")
    results = dict(datasetVersion=split["datasetVersion"], modelVersion=model_manifest["modelVersion"],
                   thresholdRank=model_manifest["thresholdRank"], services={})
    for service in ("VOLTE", "SMS"):
        loaded = load(service)
        labels, predictions, latencies, ranks = [], [], [], []
        test_runs = [run for run in split["runs"] if run["service"] == service and run["split"] == "test"]
        for run in test_runs:
            path = data / run["path"]
            if sha256(path.read_bytes()).hexdigest() != run["sha256"]:
                raise ValueError(f"Test run checksum mismatch: {path}")
            for index, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
                row = json.loads(line)
                if row["runId"] != run["runId"] or row["service"] != service or row["label"] not in ("NORMAL", "FAULT"):
                    raise ValueError(f"Invalid labelled test row in {path}")
                window = dict(service=service, featureVersion=split["featureVersion"],
                              baselineVersion=split["baselineVersion"], quality="COMPLETE", mlEligible=True,
                              featureNames=row["featureNames"], featureValues=row["featureValues"])
                start = perf_counter()
                result = score(window, loaded)
                latencies.append((perf_counter() - start) * 1000)
                labels.append(row["label"])
                predictions.append(result["anomaly"])
                ranks.append(result["anomalyRank"])
                if api_url and index == 0:
                    request = Request(api_url.rstrip("/") + "/internal/inference",
                                      data=json.dumps(window).encode("utf-8"),
                                      headers={"Content-Type": "application/json"}, method="POST")
                    with urlopen(request, timeout=2) as response:
                        remote = json.load(response)
                    if remote != result:
                        raise ValueError(f"HTTP/local score mismatch for {service} {run['runId']}")
        entry = model_manifest["services"][service]
        results["services"][service] = dict(summary(labels, predictions, latencies),
                                            featureNames=entry["featureNames"],
                                            modelSha256=entry["modelSha256"],
                                            calibrationSha256=entry["calibrationSha256"],
                                            runs=[dict(runId=r["runId"], seed=r["seed"], rows=r["rows"])
                                                  for r in test_runs],
                                            faultRanks=[rank for label, rank in zip(labels, ranks) if label == "FAULT"],
                                            httpParity="PASS" if api_url else "NOT_RUN")
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=HERE / "data")
    parser.add_argument("--api-url", help="Check representative test windows against live HTTP inference")
    parser.add_argument("--output", type=Path, default=ROOT / "tmp/g2-model-evaluation.json")
    args = parser.parse_args()
    report = evaluate(args.data, args.api_url)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))
