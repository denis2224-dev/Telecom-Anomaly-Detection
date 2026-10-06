"""Consume fresh final SMS tests once, after verifying the frozen validation choice."""

import argparse
from hashlib import sha256
import json
from pathlib import Path

import numpy as np
from threadpoolctl import threadpool_limits

from sms_supervised_data import read_split, arrays, validate_manifest
from sms_classifier import load_classifier, classifier_scores, score
from sms_supervised_selection import GATES, choose, passes_gates
from experiment_data import metrics, ranks_for
from compare_models import interval
from app.inference.scoring import load as load_isolation, score as score_isolation

HERE = Path(__file__).resolve().parent


def frozen_classifier(selection_root, manifest_bytes):
    selection_bytes = (selection_root / 'selection.json').read_bytes()
    selection = json.loads(selection_bytes)
    if (selection.get('packageKind') != 'sms-supervised-selection-v1' or selection['gates'] != GATES
            or selection['datasetManifestSha256'] != sha256(manifest_bytes).hexdigest()
            or not selection['validationPassed'] or not passes_gates(selection['validationMetrics'])):
        raise ValueError('Frozen selection does not match dataset or acceptance gates')
    search_bytes = (selection_root / 'validation-search.json').read_bytes()
    if sha256(search_bytes).hexdigest() != selection['validationSearchSha256']:
        raise ValueError('Validation search checksum changed after selection')
    search = json.loads(search_bytes)
    if search['datasetManifestSha256'] != selection['datasetManifestSha256'] or search['gates'] != GATES:
        raise ValueError('Validation search dataset or gates changed')
    choice = choose(search['candidates'])
    if any(choice[k] != selection[v] for k, v in (('settings', 'settings'), ('threshold', 'threshold'),
                                                 ('metrics', 'validationMetrics'))):
        raise ValueError('Validation choice changed after selection')
    models = (selection_root / selection['modelDirectory']).resolve()
    if not models.is_relative_to(selection_root):
        raise ValueError('Selected model path escapes selection directory')
    if sha256((models / 'manifest.json').read_bytes()).hexdigest() != selection['modelManifestSha256']:
        raise ValueError('Model manifest changed after selection')
    loaded = load_classifier(models)
    package = loaded[0]
    for field in ('modelVersion', 'datasetManifestSha256', 'datasetVersion', 'modelSha256', 'settings', 'threshold'):
        if package[field] != selection[field]:
            raise ValueError('Packaged classifier settings changed after selection')
    return selection, selection_bytes, loaded


def isolation_references(references, manifest):
    loaded = {}
    first_test = min(interval(r)[0] for r in manifest['runs'] if r['split'] == 'test')
    for name, reference in references.items():
        if name == 'selected':
            raise ValueError('Reserved reference name')
        training_bytes = Path(reference['manifest']).read_bytes()
        training = json.loads(training_bytes)
        model = load_isolation('SMS', reference['models'])
        package = model[1]
        if (package['datasetManifestSha256'] != sha256(training_bytes).hexdigest()
                or package['datasetVersion'] != training['datasetVersion']
                or package['featureVersion'] != manifest['featureVersion']
                or package['baselineVersion'] != manifest['baselineVersion']):
            raise ValueError('Reference training checksum, version or feature mismatch')
        fitted = [r for r in training['runs'] if r['service'] == 'SMS' and r['split'] in ('train', 'calibration')]
        if ({r['split'] for r in fitted} != {'train', 'calibration'}
                or max(interval(r)[1] for r in fitted) > first_test):
            raise ValueError('Reference training overlaps final test or is incomplete')
        loaded[name] = model
    return loaded


def parity(rows, scores, threshold, scorer, loaded):
    representatives = {}
    for index, row in enumerate(rows):
        representatives.setdefault((row['scenario'], row['severity'], row['operatingProfile']), index)
    for index in representatives.values():
        window = dict(rows[index], featureVersion=2, baselineVersion='baseline-v2', quality='COMPLETE', mlEligible=True)
        scalar = scorer(window, loaded)
        value = scalar.get('classifierScore', scalar.get('anomalyRank'))
        decision = scalar.get('detection', scalar.get('anomaly'))
        if not np.isclose(value, scores[index], rtol=0, atol=1e-12) or decision != bool(scores[index] >= threshold):
            raise ValueError('Final scalar/batch scoring mismatch')
    return len(representatives)


def evaluate(data, selection_root, output, references=None):
    data, selection_root, output = Path(data), Path(selection_root).resolve(), Path(output).resolve()
    if output.exists():
        raise FileExistsError('Final report already exists; use a new experiment for further development')
    manifest_bytes = (data.parent / 'split_manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    validate_manifest(manifest)
    selection, selection_bytes, loaded = frozen_classifier(selection_root, manifest_bytes)
    if references is None:
        references = {'original': dict(models=HERE.parent / 'models', manifest=HERE / 'split_manifest.json')}
    reference_models = isolation_references(references, manifest)
    # An exclusive claim also prevents rerunning the same final test into another
    # report path. A failed attempt consumes the holdout; do not tune on it later.
    claim = dict(datasetManifestSha256=sha256(manifest_bytes).hexdigest(),
                 selectionSha256=sha256(selection_bytes).hexdigest(), output=str(output))
    claim_path = selection_root / 'final-test-consumption.json'
    if claim_path.exists():
        raise FileExistsError('Final test already consumed for this selection')
    with claim_path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(claim, indent=2) + '\n')
    rows = read_split(data, 'test', manifest)
    with threadpool_limits(limits=4):
        scores = classifier_scores(loaded[1], arrays(rows, loaded[0]['featureView'])[0])
        measured = metrics(rows, scores, selection['threshold'])
        checks = parity(rows, scores, selection['threshold'], score, loaded)
        selected = dict(modelVersion=selection['modelVersion'], modelSha256=selection['modelSha256'],
                        settings=selection['settings'], threshold=selection['threshold'], metrics=measured,
                        scalarBatchParity='PASS', parityRepresentatives=checks)
        comparisons = {}
        for name, model in reference_models.items():
            ranks = ranks_for(rows, model)
            threshold = model[1]['thresholdRank']
            checks = parity(rows, ranks, threshold, score_isolation, model)
            comparisons[name] = dict(modelVersion=model[1]['modelVersion'], thresholdRank=threshold,
                                     modelSha256=model[1]['services']['SMS']['modelSha256'],
                                     datasetManifestSha256=model[1]['datasetManifestSha256'],
                                     metrics=metrics(rows, ranks, threshold), scalarBatchParity='PASS',
                                     parityRepresentatives=checks)
    report = dict(datasetVersion=manifest['datasetVersion'], **claim, gates=GATES, selectionSplit='validation',
                  evaluationSplit='test', syntheticOnly=True, status='EXPERIMENTAL_NOT_PROMOTED',
                  passed=passes_gates(measured), selected=selected, references=comparisons,
                  finalRuns=[r for r in manifest['runs'] if r['split'] == 'test'])
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(report, indent=2, allow_nan=False) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--selection', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expanded-models', type=Path)
    parser.add_argument('--expanded-training-manifest', type=Path)
    parser.add_argument('--isolation-models', type=Path)
    parser.add_argument('--isolation-training-manifest', type=Path)
    args = parser.parse_args()
    references = {'original': dict(models=HERE.parent / 'models', manifest=HERE / 'split_manifest.json')}
    for name, models, training in (('expanded', args.expanded_models, args.expanded_training_manifest),
                                   ('validation-isolation-forest', args.isolation_models, args.isolation_training_manifest)):
        if bool(models) != bool(training):
            parser.error(f'Provide both {name} model and training-manifest paths')
        if models:
            references[name] = dict(models=models, manifest=training)
    report = evaluate(args.data, args.selection, args.output, references)
    for name, entry in dict(report['references'], selected=report['selected']).items():
        result = entry['metrics']
        print(f"{name}: final recall={result['recall']:.2%}; false positives={result['falsePositiveRate']:.2%}")
    print(f"All SMS acceptance gates: {'PASS' if report['passed'] else 'FAIL'}")
    if not report['passed']:
        raise SystemExit(1)
