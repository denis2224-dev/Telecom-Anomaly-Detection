"""Fit on training only; choose experimental SMS settings on validation only."""

import argparse
from hashlib import sha256
from itertools import product
import json
from pathlib import Path

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from threadpoolctl import threadpool_limits

from sms_supervised_data import read_split, arrays, VIEWS, validate_manifest
from sms_classifier import classifier_scores, package
from scenario_history import FAULTS, NORMALS
from experiment_data import metrics

GATES = dict(minimumRecall=.7, minimumFamilyRecall=.7, maximumFalsePositiveRate=.01,
             maximumProfileFalsePositiveRate=.01)
RANDOM_STATE = 6102026
GRID = tuple(
    dict(estimator='hist-gradient-boosting', learning_rate=rate, max_iter=iterations,
         max_leaf_nodes=leaves, l2_regularization=regularization, view=view)
    for rate, iterations, leaves, regularization, view in product((.05, .1), (100, 200), (7, 15), (0., 1.), VIEWS)
) + tuple(
    dict(estimator='random-forest', n_estimators=trees, max_depth=depth, min_samples_leaf=leaf, view=view)
    for trees, depth, leaf, view in product((200, 400), (8, None), (1, 5), VIEWS)
)


def thresholds(healthy_scores):
    fixed = [i / 100 for i in range(5, 100, 5)] + [.99]
    quantiles = [float(np.nextafter(np.quantile(healthy_scores, q), np.inf)) for q in (.99, .995, .999)]
    return sorted(set(fixed + quantiles))


def passes_gates(result):
    families, profiles = result['byFamily'], result['byHealthyProfile']
    return (set(families) == set(FAULTS['SMS']) and set(profiles) == set(NORMALS)
            and result['recall'] is not None and result['recall'] >= GATES['minimumRecall']
            and result['falsePositiveRate'] <= GATES['maximumFalsePositiveRate']
            and all(v['windows'] > 0 and v['recall'] >= GATES['minimumFamilyRecall'] for v in families.values())
            and all(v['windows'] > 0 and v['falsePositiveRate'] <= GATES['maximumProfileFalsePositiveRate']
                    for v in profiles.values()))


def choose(candidates):
    passing = [c for c in candidates if passes_gates(c['metrics'])]
    if not passing:
        raise ValueError('No SMS classifier passes all detection and false-alarm gates')

    def ranking(candidate):
        result = candidate['metrics']
        return (-min(v['recall'] for v in result['byFamily'].values()), -result['macroRecall'],
                -(result['precision'] or 0), max(v['falsePositiveRate'] for v in result['byHealthyProfile'].values()),
                candidate['configurationIndex'], candidate['thresholdIndex'])

    return min(passing, key=ranking)


def estimator(settings):
    parameters = {k: v for k, v in settings.items() if k not in ('estimator', 'view')}
    if settings['estimator'] == 'hist-gradient-boosting':
        return HistGradientBoostingClassifier(**parameters, early_stopping=False, class_weight='balanced',
                                              random_state=RANDOM_STATE)
    if settings['estimator'] == 'random-forest':
        return RandomForestClassifier(**parameters, class_weight='balanced', random_state=RANDOM_STATE, n_jobs=-1)
    raise ValueError('Unknown experimental estimator')


def tune(data, output, grid=GRID, model_version='sms-supervised-v1-1'):
    data, output = Path(data), Path(output)
    output.mkdir(parents=True, exist_ok=False)
    manifest_bytes = (data.parent / 'split_manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    validate_manifest(manifest)
    dataset_hash = sha256(manifest_bytes).hexdigest()
    # These are the only row files opened during selection. Test metadata is checked,
    # but no final test feature or label is used to fit, calibrate or select.
    training = read_split(data, 'train', manifest)
    calibration = read_split(data, 'calibration', manifest)
    validation = read_split(data, 'validation', manifest)
    candidates, models = [], []
    for index, settings in enumerate(grid):
        view = settings['view']
        x, y = arrays(training, view)
        model = estimator(settings)
        # Cap OpenMP threads on Windows: tiny six-feature histograms otherwise spend
        # most of their time coordinating hundreds of threads rather than fitting.
        with threadpool_limits(limits=4):
            model.fit(x, y)
            healthy_scores = classifier_scores(model, arrays(calibration, view)[0])
            validation_scores = classifier_scores(model, arrays(validation, view)[0])
        models.append(model)
        for threshold_index, threshold in enumerate(thresholds(healthy_scores)):
            result = metrics(validation, validation_scores, threshold)
            candidates.append(dict(configurationIndex=index, thresholdIndex=threshold_index, settings=settings,
                                   threshold=threshold, metrics=result, passed=passes_gates(result)))
        print(f"Validation configuration {index + 1}/{len(grid)}: {settings['estimator']} {view}", flush=True)
    search = dict(datasetManifestSha256=dataset_hash, gates=GATES, randomState=RANDOM_STATE,
                  configurationCount=len(grid), candidates=candidates, syntheticOnly=True,
                  ranking='worst-family recall, macro recall, precision, worst-profile FPR, grid order, threshold order')
    search_path = output / 'validation-search.json'
    search_path.write_bytes((json.dumps(search, indent=2, allow_nan=False) + '\n').encode())
    choice = choose(candidates)
    selected_manifest = package(output / 'model', models[choice['configurationIndex']], choice['settings']['view'],
                                choice['threshold'], model_version, dataset_hash, manifest['datasetVersion'],
                                choice['settings'], len(training))
    selection = dict(packageKind='sms-supervised-selection-v1', datasetManifestSha256=dataset_hash,
                     datasetVersion=manifest['datasetVersion'], modelVersion=model_version, modelDirectory='model',
                     modelManifestSha256=sha256((output / 'model/manifest.json').read_bytes()).hexdigest(),
                     modelSha256=selected_manifest['modelSha256'],
                     validationSearchSha256=sha256(search_path.read_bytes()).hexdigest(),
                     threshold=choice['threshold'], settings=choice['settings'], gates=GATES,
                     randomState=RANDOM_STATE, validationMetrics=choice['metrics'], validationPassed=True,
                     selectionUses='training, healthy calibration, validation; no final test rows',
                     syntheticOnly=True, status='EXPERIMENTAL_NOT_PROMOTED')
    (output / 'selection.json').write_bytes((json.dumps(selection, indent=2, allow_nan=False) + '\n').encode())
    return selection


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--model-version', default='sms-supervised-v1-1')
    args = parser.parse_args()
    result = tune(args.data, args.output, model_version=args.model_version)
    print(json.dumps(dict(modelVersion=result['modelVersion'], settings=result['settings'], threshold=result['threshold'],
                         validationMetrics=result['validationMetrics'])), flush=True)
