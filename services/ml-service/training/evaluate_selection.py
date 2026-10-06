"""Evaluate a frozen validation selection once on untouched final test runs."""

import argparse
from hashlib import sha256
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from app.inference.scoring import load, score
from experiment_data import validate_manifest, read_split, ranks_for, metrics
from compare_models import interval


def evaluate_selection(data, selection_root, output, references=None):
    data, selection_root, output = Path(data), Path(selection_root).resolve(), Path(output)
    if output.exists():
        raise FileExistsError('Final report already exists; use a new experiment for further tuning')
    manifest_bytes = (data.parent / 'split_manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    validate_manifest(manifest)
    selection_bytes = (selection_root / 'selection.json').read_bytes()
    selection = json.loads(selection_bytes)
    if (selection['selectionSplit'] != 'validation'
            or selection['datasetManifestSha256'] != sha256(manifest_bytes).hexdigest()):
        raise ValueError('Frozen selection does not match the experiment dataset')
    # Verify every selected package before opening any final test row.
    selected = {}
    for service in ('VOLTE', 'SMS'):
        choice = selection['services'][service]
        models = (selection_root / choice['models']).resolve()
        if not models.is_relative_to(selection_root):
            raise ValueError('Frozen selection model path escapes its directory')
        if sha256((models / 'manifest.json').read_bytes()).hexdigest() != choice['manifestSha256']:
            raise ValueError('Model manifest changed after selection')
        loaded = load(service, models)
        packaged = loaded[1]
        if (packaged['datasetManifestSha256'] != sha256(manifest_bytes).hexdigest()
                or packaged['modelVersion'] != choice['modelVersion']
                or packaged['thresholdRank'] != choice['thresholdRank']):
            raise ValueError('Model settings changed after selection')
        selected[service] = loaded
    if references is None:
        references = {'original': dict(models=HERE.parent / 'models', manifest=HERE / 'split_manifest.json')}
    loaded_references = {}
    for name, reference in references.items():
        if name in ('selected', 'services', 'selectionSplit'):
            raise ValueError('Reserved reference name')
        training_bytes = Path(reference['manifest']).read_bytes()
        training = json.loads(training_bytes)
        loaded_references[name] = {}
        for service in ('VOLTE', 'SMS'):
            loaded = load(service, reference['models'])
            if loaded[1]['datasetManifestSha256'] != sha256(training_bytes).hexdigest():
                raise ValueError('Reference training manifest checksum mismatch')
            fitted = [interval(r) for r in training['runs'] if r['service'] == service
                      and r['split'] in ('train', 'calibration')]
            first_test = min(interval(r)[0] for r in manifest['runs'] if r['service'] == service and r['split'] == 'test')
            if not fitted or max(end for _, end in fitted) > first_test:
                raise ValueError('Reference training/calibration overlap with final tests')
            loaded_references[name][service] = loaded
    report = dict(selectionSplit='validation', evaluationSplit='test', status='EXPERIMENTAL_NOT_PROMOTED',
                  syntheticOnly=manifest.get('syntheticOnly', False), datasetVersion=manifest['datasetVersion'],
                  datasetManifestSha256=sha256(manifest_bytes).hexdigest(), selectionSha256=sha256(selection_bytes).hexdigest(),
                  falsePositiveBudget=selection['falsePositiveBudget'])
    groups = dict(loaded_references, selected=selected)
    for name in groups:
        report[name] = dict(services={})
    for service in ('VOLTE', 'SMS'):
        rows = read_split(data, service, 'test', manifest)
        for name, services in groups.items():
            loaded = services[service]
            package = loaded[1]
            if package['featureVersion'] != manifest['featureVersion'] or package['baselineVersion'] != manifest['baselineVersion']:
                raise ValueError('Incompatible final test feature/baseline version')
            ranks = ranks_for(rows, loaded)
            threshold = package['thresholdRank']
            # Check a representative from every healthy profile and fault severity.
            representatives = {}
            for index, row in enumerate(rows):
                representatives.setdefault((row['label'], row['scenario'], row['severity']), index)
            for index in representatives.values():
                window = dict(rows[index], featureVersion=manifest['featureVersion'],
                              baselineVersion=manifest['baselineVersion'], quality='COMPLETE', mlEligible=True)
                scalar = score(window, loaded)
                if scalar['anomalyRank'] != ranks[index] or scalar['anomaly'] != bool(ranks[index] >= threshold):
                    raise ValueError('Final batch/scalar scoring mismatch')
            report[name]['services'][service] = dict(modelVersion=package['modelVersion'], thresholdRank=threshold,
                                                     modelSha256=package['services'][service]['modelSha256'],
                                                     scalarBatchParity='PASS', metrics=metrics(rows, ranks, threshold))
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--selection', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--reference-models', type=Path, default=HERE.parent / 'models')
    parser.add_argument('--reference-training-manifest', type=Path, default=HERE / 'split_manifest.json')
    parser.add_argument('--expanded-models', type=Path)
    parser.add_argument('--expanded-training-manifest', type=Path)
    args = parser.parse_args()
    references = {'original': dict(models=args.reference_models, manifest=args.reference_training_manifest)}
    if bool(args.expanded_models) != bool(args.expanded_training_manifest):
        parser.error('Provide both expanded model and training-manifest paths')
    if args.expanded_models:
        references['expanded'] = dict(models=args.expanded_models, manifest=args.expanded_training_manifest)
    report = evaluate_selection(args.data, args.selection, args.output, references)
    for name in references.keys() | {'selected'}:
        for service, entry in report[name]['services'].items():
            measured = entry['metrics']
            print(f"{name} {service}: final recall={measured['recall']:.3f}, "
                  f"false positives/1000={measured['falsePositivesPer1000Normal']:.3f}")
