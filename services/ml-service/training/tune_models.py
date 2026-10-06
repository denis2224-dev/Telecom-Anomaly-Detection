"""Select service-specific experimental settings using validation data only."""

import argparse
from hashlib import sha256
import json
from pathlib import Path
import shutil
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.inference.scoring import load
from experiment_data import read_split, validate_manifest, ranks_for, metrics
from train import train

GRID = ((200, 256), (200, 1024), (200, 4096), (400, 256), (400, 1024), (400, 4096))
THRESHOLDS = (.95, .975, .98, .985, .99, .9925, .995, .9975, 1.)


def choose(candidates, false_positive_budget):
    eligible = [c for c in candidates if c['falsePositiveRate'] <= false_positive_budget]
    if not eligible:
        raise ValueError('No model/threshold meets the validation false-positive budget')
    return max(eligible, key=lambda c: (c['macroRecall'], c['precision'] or 0,
                                       -c['falsePositiveRate'], c['threshold']))


def tune(data, root, grid=GRID, thresholds=THRESHOLDS, false_positive_budget=.01,
         model_version='isoforest-v2-validation-1'):
    data, root = Path(data), Path(root)
    if not 0 <= false_positive_budget < 1 or not thresholds or any(not 0 < t <= 1 for t in thresholds):
        raise ValueError('Invalid validation budget or thresholds')
    manifest_bytes = (data.parent / 'split_manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    validate_manifest(manifest)
    root.mkdir(parents=True, exist_ok=False)
    validation = {}
    for service in ('VOLTE', 'SMS'):
        for split in ('train', 'calibration'):
            read_split(data, service, split, manifest)
        validation[service] = read_split(data, service, 'validation', manifest)
    candidates = {'VOLTE': [], 'SMS': []}
    for estimators, samples in grid:
        config = f'n{estimators}-s{samples}'
        models = root / 'search' / config
        train(data, models, model_version=f'{model_version}-{config}',
              n_estimators=estimators, max_samples=samples)
        for service in ('VOLTE', 'SMS'):
            ranks = ranks_for(validation[service], load(service, models))
            for threshold in thresholds:
                measured = metrics(validation[service], ranks, threshold)
                candidates[service].append(dict(measured, threshold=threshold,
                                                nEstimators=estimators, maxSamples=samples, config=config))
        print(f'Evaluated {config} on validation only', flush=True)
    selection = dict(modelVersion=model_version, datasetVersion=manifest['datasetVersion'],
                     datasetManifestSha256=sha256(manifest_bytes).hexdigest(), syntheticOnly=manifest.get('syntheticOnly', False),
                     falsePositiveBudget=false_positive_budget, selectionSplit='validation',
                     status='EXPERIMENTAL_NOT_PROMOTED', services={})
    for service in ('VOLTE', 'SMS'):
        chosen = choose(candidates[service], false_positive_budget)
        source = root / 'search' / chosen['config']
        target = root / service
        target.mkdir()
        packaged = json.loads((source / 'manifest.json').read_bytes())
        entry = packaged['services'][service]
        for field in ('modelFile', 'calibrationFile'):
            shutil.copyfile(source / entry[field], target / entry[field])
        packaged['modelVersion'] = f'{model_version}-{service}'
        packaged['thresholdRank'] = chosen['threshold']
        packaged['services'] = {service: entry}
        package_bytes = (json.dumps(packaged, indent=2) + '\n').encode()
        (target / 'manifest.json').write_bytes(package_bytes)
        load(service, target)  # Verify the completed package before freezing selection.
        selection['services'][service] = dict(models=service, modelVersion=packaged['modelVersion'],
                                             manifestSha256=sha256(package_bytes).hexdigest(),
                                             thresholdRank=chosen['threshold'], validation=chosen)
    (root / 'validation-search.json').write_bytes((json.dumps(candidates, indent=2) + '\n').encode())
    (root / 'selection.json').write_bytes((json.dumps(selection, indent=2) + '\n').encode())
    return selection


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New selection/model directory')
    parser.add_argument('--false-positive-budget', type=float, default=.01)
    parser.add_argument('--model-version', default='isoforest-v2-validation-1')
    args = parser.parse_args()
    selected = tune(args.data, args.output, false_positive_budget=args.false_positive_budget,
                    model_version=args.model_version)
    for service, item in selected['services'].items():
        result = item['validation']
        print(f"Selected {service}: {result['config']}, threshold={item['thresholdRank']}, "
              f"validation recall={result['recall']:.3f}, false positives/1000={result['falsePositivesPer1000Normal']:.3f}")
