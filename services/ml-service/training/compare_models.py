"""Compare frozen and candidate models on the same later, checksum-verified tests."""

import argparse
from datetime import datetime
from hashlib import sha256
import json
import math
from pathlib import Path
import sys

import numpy

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE.parent))
from app.inference.scoring import load, score
from evaluate import summary


def interval(run):
    start = datetime.fromisoformat(run['start'])
    end = datetime.fromisoformat(run['endExclusive'])
    if start.tzinfo is None or end.tzinfo is None or start >= end:
        raise ValueError('Invalid dataset time interval')
    return start, end


def compare(data, candidate_models, reference_models=HERE.parent / 'models',
            reference_training_manifest=HERE / 'split_manifest.json'):
    data = Path(data)
    test_path = data.parent / 'split_manifest.json'
    tests = json.loads(test_path.read_bytes())
    report = dict(testManifestSha256=sha256(test_path.read_bytes()).hexdigest(),
                  testDatasetVersion=tests['datasetVersion'], syntheticOnly=True,
                  status='EXPERIMENTAL_NOT_PROMOTED')
    for name, models, training_path in (
            ('reference', reference_models, reference_training_manifest),
            ('candidate', candidate_models, test_path)):
        training_bytes = Path(training_path).read_bytes()
        training = json.loads(training_bytes)
        service_results = {}
        for service in ('VOLTE', 'SMS'):
            loaded = load(service, models)
            _, manifest, model, strengths = loaded
            if (sha256(training_bytes).hexdigest() != manifest['datasetManifestSha256']
                    or training['datasetVersion'] != manifest['datasetVersion']):
                raise ValueError(f'{name} training manifest checksum/version mismatch')
            if (tests['featureVersion'] != manifest['featureVersion']
                    or tests['baselineVersion'] != manifest['baselineVersion']):
                raise ValueError('Incompatible test features or baseline')
            fitted = [r for r in training['runs'] if r['service'] == service
                      and r['split'] in ('train', 'calibration')]
            if {r['split'] for r in fitted} != {'train', 'calibration'}:
                raise ValueError('Missing training or calibration split')
            fitted_intervals = sorted(interval(r) for r in fitted)
            if any(left[1] > right[0] for left, right in zip(fitted_intervals, fitted_intervals[1:])):
                raise ValueError('Training/calibration overlap')
            test_runs = [r for r in tests['runs'] if r['service'] == service and r['split'] == 'test']
            test_intervals = sorted(interval(r) for r in test_runs)
            if (not test_intervals or test_intervals[0][0] < fitted_intervals[-1][1]
                    or any(left[1] > right[0] for left, right in zip(test_intervals, test_intervals[1:]))):
                raise ValueError('Test overlap with training/calibration or another test run')
            labels, values = [], []
            identities = set()
            for run in test_runs:
                path = data / run['path']
                raw = path.read_bytes()
                if sha256(raw).hexdigest() != run['sha256']:
                    raise ValueError(f'Test checksum mismatch: {path}')
                rows = [json.loads(line) for line in raw.splitlines()]
                if len(rows) != run['rows']:
                    raise ValueError('Test row count mismatch')
                start, end = interval(run)
                for row in rows:
                    vector = row['featureValues']
                    when = datetime.fromisoformat(row['windowStart'])
                    if (row['runId'] != run['runId'] or row['service'] != service
                            or row.get('label') not in ('NORMAL', 'FAULT')
                            or row['featureNames'] != manifest['services'][service]['featureNames']
                            or not isinstance(vector, list) or len(vector) != 6
                            or not all(type(v) in (int, float) and math.isfinite(v) for v in vector)
                            or when.tzinfo is None or not start <= when < end or when in identities):
                        raise ValueError('Invalid or duplicate test row')
                    identities.add(when)
                    labels.append(row['label'])
                    values.append(vector)
            if set(labels) != {'NORMAL', 'FAULT'}:
                raise ValueError('Comparison requires both normal and fault test rows')
            # Batch scoring uses the production rank formula. Check scalar parity too.
            anomaly_strengths = -model.score_samples(numpy.asarray(values, dtype=float))
            ranks = numpy.searchsorted(strengths, anomaly_strengths, side='right') / len(strengths)
            predictions = (ranks >= manifest['thresholdRank']).tolist()
            for index in (0, len(values) - 1):
                window = dict(service=service, featureVersion=tests['featureVersion'],
                              baselineVersion=tests['baselineVersion'], quality='COMPLETE',
                              mlEligible=True, featureNames=manifest['services'][service]['featureNames'],
                              featureValues=values[index])
                scalar = score(window, loaded)
                if scalar['anomalyRank'] != ranks[index] or scalar['anomaly'] != predictions[index]:
                    raise ValueError('Batch/scalar scoring mismatch')
            service_results[service] = dict(summary(labels, predictions, []),
                                           trainingRows=manifest['services'][service]['trainingRows'],
                                           calibrationRows=len(strengths),
                                           modelSha256=manifest['services'][service]['modelSha256'],
                                           faultRanks=[float(r) for label, r in zip(labels, ranks) if label == 'FAULT'])
        report[name] = dict(modelVersion=manifest['modelVersion'], datasetVersion=manifest['datasetVersion'],
                            thresholdRank=manifest['thresholdRank'], services=service_results)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--candidate-models', type=Path, required=True)
    parser.add_argument('--reference-models', type=Path, default=HERE.parent / 'models')
    parser.add_argument('--reference-training-manifest', type=Path, default=HERE / 'split_manifest.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'tmp/ml-expanded-history/comparison.json')
    args = parser.parse_args()
    result = compare(args.data, args.candidate_models, args.reference_models, args.reference_training_manifest)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    for name in ('reference', 'candidate'):
        for service, metrics in result[name]['services'].items():
            print(f"{name} {service}: recall={metrics['recall']:.3f}, "
                  f"false positives/1000={metrics['falsePositivesPer1000Normal']:.3f}")
