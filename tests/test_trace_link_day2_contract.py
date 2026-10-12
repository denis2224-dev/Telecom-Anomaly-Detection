"""Offline boundary rejects guessed populations, truth and context mismatches."""
import copy
import json
from pathlib import Path
import unittest

from scripts.trace_link_day2_contract import validate_context, validate_result

ROOT = Path(__file__).resolve().parents[1]


def context():
    evidence = json.loads((ROOT / 'contracts/fixtures/trace-link/evidence-cases.json').read_text())['cases'][0]['value']
    evidence['metricId'] = 'mobileData.activation.success'
    evidence['scope']['procedure'] = None
    return dict(schemaVersion='trace-link-evaluation-v1', datasetId=evidence['provenance']['datasetId'],
                namespace=evidence['scope']['namespace'], domain='MOBILE_DATA', scope=evidence['scope'],
                window=evidence['window'], revision=0, topologyVersion=evidence['topologyVersion'],
                evaluatedAt=evidence['freshness']['asOf'], evidence=[evidence], baselines={},
                backlog=dict(required=False, drained=None))


class Day2ContractTest(unittest.TestCase):
    def test_valid_context_and_independent_copy(self):
        value = context()
        validate_context(value)
        self.assertEqual(98, value['evidence'][0]['actual'])

    def test_unknown_truth_fields_and_context_mismatch(self):
        for field, value in [('expected', 'BREACH'), ('scenarioId', 'secret')]:
            item = context()
            item[field] = value
            with self.assertRaises(ValueError):
                validate_context(item)
        for field, value in [('datasetId', 'other'), ('revision', 1), ('topologyVersion', 'other'),
                             ('evaluatedAt', '2026-10-10T08:40:02Z')]:
            item = context()
            item[field] = value
            with self.assertRaises(ValueError):
                validate_context(item)

    def test_forged_actual_and_reference_target_population(self):
        for mutation in ('actual', 'origin'):
            item = context()
            if mutation == 'actual':
                item['evidence'][0]['actual'] = 99
            else:
                item['evidence'][0]['provenance'].update(origin='REFERENCE_CSV', sourceChecksum='a' * 64)
            with self.assertRaises(ValueError):
                validate_context(item)

    def test_future_or_incompatible_baseline(self):
        value = context()
        value['evidence'][0].update(baseline=None, baselineVersion=None, deviation=None)
        baseline = dict(status='DIRECT', center=99, mad=0, sourceScope=value['scope'],
                        baselineVersion='trace-link-seasonal-v1', policyVersion='trace-link-baseline-policy-v1',
                        trainingFrom='2026-09-10T09:00:00Z', trainingTo='2026-10-10T09:00:00Z',
                        coverage=1, reason=None)
        value['baselines']['mobileData.activation.success'] = baseline
        with self.assertRaises(ValueError):
            validate_context(value)
        baseline['trainingTo'] = '2026-09-30T09:00:00Z'
        validate_context(value)
        baseline['status'] = 'BASELINE_UNAVAILABLE'
        with self.assertRaises(ValueError):
            validate_context(value)
        baseline.update(status='STATIC', reason='STATIC_DEMONSTRATION_POLICY', center=1000)
        with self.assertRaises(ValueError):
            validate_context(value)

    def test_strict_window_and_safe_integer_revision(self):
        for revision in (-1, 0.5, 9007199254740992):
            item = context()
            item['revision'] = revision
            with self.assertRaises(ValueError):
                validate_context(item)
        item = context()
        item['window'] = copy.deepcopy(item['window'])
        item['window']['to'] = '2026-10-10T08:45:00Z'
        with self.assertRaises(ValueError):
            validate_context(item)

    def test_lookup_cannot_bypass_registry_eligibility(self):
        value = context()
        baseline = dict(status='DIRECT', center=99, mad=0, sourceScope=value['scope'],
                        baselineVersion='trace-link-seasonal-v1', policyVersion='trace-link-baseline-policy-v1',
                        trainingFrom='2026-09-10T09:00:00Z', trainingTo='2026-09-30T09:00:00Z', coverage=1, reason=None)
        value['baselines']['mobileData.activation.success'] = baseline
        for field, replacement in [('mad', None), ('coverage', 0), ('policyVersion', 'wrong-policy'),
                                   ('baselineVersion', 'wrong-baseline')]:
            broken = copy.deepcopy(value)
            broken['baselines']['mobileData.activation.success'][field] = replacement
            with self.subTest(field=field), self.assertRaises(ValueError): validate_context(broken)

    def test_configured_static_zero_and_mismatched_evidence(self):
        value = context()
        # Raw observations may omit baseline; scoring uses the validated lookup.
        value['evidence'][0].update(baseline=None, baselineVersion=None, deviation=None)
        value['baselines']['mobileData.activation.success'] = dict(status='STATIC', center=0, mad=0,
            sourceScope=value['scope'], baselineVersion='trace-link-seasonal-v1', policyVersion='trace-link-baseline-policy-v1',
            trainingFrom='2026-09-10T09:00:00Z', trainingTo='2026-09-30T09:00:00Z', coverage=0, reason='STATIC_DEMONSTRATION_POLICY')
        policy = json.loads((ROOT / 'contracts/baselines/trace-link-baseline-policy-v1.json').read_text())
        policy['staticCenters']['mobileData.activation.success'] = 0
        validate_context(value, policy)
        with self.assertRaises(ValueError): validate_context(value)
        value['evidence'][0].update(baseline=99, baselineVersion='trace-link-seasonal-v1', deviation=-1)
        with self.assertRaises(ValueError): validate_context(value, policy)

    def test_result_rejects_unknown_and_unbacked_severity(self):
        value = context()
        result = {key: value[key] for key in ('datasetId', 'namespace', 'domain', 'scope', 'window',
                                             'revision', 'topologyVersion', 'evaluatedAt')}
        result.update(schemaVersion='trace-link-rule-result-v1', policyVersion='trace-link-domain-rules-v1',
                      baselineVersion='trace-link-seasonal-v1', anomalyKind='MOBILE_DATA_ACTIVATION',
                      verdict='UNKNOWN', reason='NO_MEASUREMENT', severity=None, overloadCandidate=False,
                      evidenceIds=[], anomalyRank=None)
        validate_result(result)
        for kind in ('TRANSPORT_LATENCY', 'MOBILE_DATA_UNIMPLEMENTED'):
            changed = copy.deepcopy(result)
            changed['anomalyKind'] = kind
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                validate_result(changed)
        result['severity'] = 'CRITICAL'
        with self.assertRaises(ValueError):
            validate_result(result)

    def test_optional_signed_baseline_deviation_requires_a_finite_number(self):
        result = json.loads((ROOT / 'contracts/fixtures/trace-link/cause-control-cases.json').read_text())['cases'][0]['input']['symptom']
        for deviation in (-1, 0, 1.25, None):
            result['baselineDeviation'] = deviation
            with self.subTest(deviation=deviation):
                validate_result(result)
        for deviation in (float('nan'), float('inf'), -float('inf'), True, '0'):
            result['baselineDeviation'] = deviation
            with self.subTest(deviation=deviation), self.assertRaises(ValueError):
                validate_result(result)
        del result['baselineDeviation']
        validate_result(result)


if __name__ == '__main__':
    unittest.main()
