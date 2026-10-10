"""Trace Link Day 1 contract controls, independent of detector activation."""
import copy
import importlib
import json
from pathlib import Path
import unittest

from scripts.trace_link_contract import validate_dictionary, validate_evidence, validate_trace_link_contracts

ROOT = Path(__file__).resolve().parents[1]


class TraceLinkContractTests(unittest.TestCase):
    def setUp(self):
        self.dictionary = json.loads((ROOT / 'contracts/kpi/trace-link-kpi-definitions-v1.json').read_text(encoding='utf-8'))
        self.cases = json.loads((ROOT / 'contracts/fixtures/trace-link/evidence-cases.json').read_text(encoding='utf-8'))['cases']
        self.value = copy.deepcopy(self.cases[0]['value'])

    def test_checked_in_contracts_and_both_import_styles(self):
        validate_trace_link_contracts()
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        try:
            self.assertTrue(callable(importlib.import_module('trace_link_contract').validate_trace_link_contracts))
        finally:
            sys.path.pop(0)

    def test_dictionary_covers_domains_and_honest_source_limits(self):
        validate_dictionary(self.dictionary)
        metrics = {m['metricId']: m for m in self.dictionary['metrics']}
        self.assertEqual({m['domain'] for m in metrics.values()}, {'VOLTE', 'TRANSPORT', 'ROAMING', 'SMS', 'MOBILE_DATA', 'QUALITY'})
        for name in ('mobileData.dns.success', 'roaming.sms.finalizedSuccess', 'sms.delay.mean', 'transport.rtt.mean', 'transport.link.state'):
            self.assertEqual(metrics[name]['reference']['availability'], 'REQUIRES_ADDITIONAL_PRIMITIVES')
        self.assertEqual(len(self.dictionary['cityIdMap']), 10)
        self.assertEqual(metrics['transport.link.utilization']['aggregation'], 'TIME_WEIGHTED_MEAN')
        self.assertEqual(metrics['transport.link.utilization.peak']['aggregation'], 'MAXIMUM')

    def test_period_snapshot_rejects_exact_exclusive_end(self):
        e = copy.deepcopy(next(c['value'] for c in self.cases if c['value']['metricId'] == 'sms.queue'))
        e['primitives']['snapshotAt'] = e['window']['to']
        e['freshness'].update(observedAt=e['window']['to'], sourceAgeSeconds=1, state='FRESH')
        e['quality'] = {'state': 'VALID', 'reason': None}
        with self.assertRaises(ValueError):
            validate_evidence(e)

    def test_named_peak_keeps_maximum_instead_of_mean(self):
        e = copy.deepcopy(self.value)
        e.update(metricId='transport.link.utilization.peak', primitives={'type': 'MAXIMUM', 'max': 85},
                 actual=85, baseline=None, baselineVersion=None, deviation=None)
        e['scope'] = {'type': 'LINK_DIRECTION', 'id': 'chi-hin:a-to-b', 'cityIds': ['CHI', 'HIN'],
                      'directionId': 'a-to-b', 'partnerId': None, 'procedure': None}
        validate_evidence(e)
        for patch in [{'actual': 84}, {'primitives': {'type': 'TIME_WEIGHTED', 'weightedSum': 25500, 'validSeconds': 300}},
                      {'actual': 101, 'primitives': {'type': 'MAXIMUM', 'max': 101}}]:
            candidate = copy.deepcopy(e)
            candidate.update(patch)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_evidence(candidate)

    def test_dictionary_duplicate_ids_wrong_map_and_false_availability_rejected(self):
        patches = [lambda d: d['metrics'].append(copy.deepcopy(d['metrics'][0])),
                   lambda d: d['cityIdMap'][0].update(runtimeCityId='RIB'),
                   lambda d: d['metrics'][0]['reference'].update(availability='SUPPORTED', reason='incompatible')]
        for patch in patches:
            d = copy.deepcopy(self.dictionary)
            patch(d)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_dictionary(d)

    def test_unknown_truth_nested_and_nonfinite_rejected(self):
        for patch in [lambda e: e.update(groundTruth='fault'), lambda e: e['scope'].update(scenarioId='fault'),
                      lambda e: e.update(actual=float('nan')), lambda e: e['primitives'].update(numerator=float('inf'))]:
            e = copy.deepcopy(self.value)
            patch(e)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_evidence(e)

    def test_window_utc_order_and_generation_rejected(self):
        for field, value in [('from', '2026-10-10T08:40:00Z'), ('from', '2026-10-10T11:35:00+03:00'),
                             ('to', '2026-10-10T08:35:00Z'), ('generatedAt', '2026-10-10T08:00:00Z')]:
            e = copy.deepcopy(self.value)
            if field == 'generatedAt':
                e[field] = value
            else:
                e['window'][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                validate_evidence(e)

    def test_population_zero_denominator_and_primitive_consistency(self):
        for patch in [lambda e: e['primitives'].update(denominator=0),
                      lambda e: e['primitives'].update(numerator=1001),
                      lambda e: e.update(actual=99), lambda e: e.update(unit='SECONDS')]:
            e = copy.deepcopy(self.value)
            patch(e)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_evidence(e)

    def test_histogram_edges_count_and_metric_policy_compatibility(self):
        original = next(c['value'] for c in self.cases if c['value']['metricId'] == 'sms.delay.p95')
        for patch in [lambda e: e['primitives']['histogram']['edges'].reverse(),
                      lambda e: e['primitives']['histogram']['counts'].pop(),
                      lambda e: e['primitives']['histogram']['counts'].__setitem__(0, -1),
                      lambda e: e['primitives']['histogram'].update(unit='MILLISECONDS'),
                      lambda e: e['primitives']['histogram']['edges'].__setitem__(0, .5)]:
            e = copy.deepcopy(original)
            patch(e)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_evidence(e)

    def test_provenance_quality_and_dependency_consistency(self):
        for patch in [lambda e: e['provenance'].update(synthetic=False),
                      lambda e: e['provenance'].update(datasetId='another'),
                      lambda e: e['quality'].update(state='VALID', reason='ZERO_DENOMINATOR'),
                      lambda e: e.update(dependencyRelationId='invented'),
                      lambda e: e.update(policyVersion='service-rules-v2')]:
            e = copy.deepcopy(self.value)
            patch(e)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_evidence(e)

    def test_reference_cannot_invent_missing_primitives_or_omit_checksum(self):
        missing = copy.deepcopy(next(c['value'] for c in self.cases if c['value']['metricId'] == 'sms.delay.mean'))
        missing.update(primitives={'type': 'SUM_COUNT', 'sum': 1000, 'count': 1}, actual=1000,
                       quality={'state': 'VALID', 'reason': None})
        # An independent hand-calculated target case is legitimate; the CSV cannot supply this sum.
        validate_evidence(missing)
        missing['provenance'].update(origin='REFERENCE_CSV', sourceChecksum='a' * 64)
        with self.assertRaises(ValueError):
            validate_evidence(missing)
        e = copy.deepcopy(self.value)
        e['provenance']['origin'] = 'REFERENCE_CSV'
        with self.assertRaises(ValueError):
            validate_evidence(e)

    def test_empty_histogram_is_unavailable_not_healthy_zero(self):
        e = copy.deepcopy(next(c['value'] for c in self.cases if c['value']['metricId'] == 'sms.delay.p95'))
        e['primitives']['histogram']['counts'] = [0] * 9
        e.update(actual=None, quality={'state': 'INSUFFICIENT_EVIDENCE', 'reason': 'EMPTY_HISTOGRAM'})
        validate_evidence(e)
        e.update(actual=0, quality={'state': 'VALID', 'reason': None})
        with self.assertRaises(ValueError):
            validate_evidence(e)

    def test_dependency_requires_overlap_quality_and_same_topology(self):
        e = copy.deepcopy(self.value)
        e.update(dependencyRelationId='fixture-relation', dependency={
            'relationId': 'fixture-relation', 'scopeId': 'chi-hin:a-to-b',
            'topologyVersion': e['topologyVersion'], 'from': e['window']['from'],
            'to': e['window']['to'], 'quality': 'VALID', 'fresh': True})
        validate_evidence(e)
        for patch in [{'topologyVersion': 'wrong'}, {'from': e['window']['to'], 'to': '2026-10-10T08:45:00Z'},
                      {'quality': 'STALE'}, {'fresh': False}]:
            candidate = copy.deepcopy(e)
            candidate['dependency'].update(patch)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_evidence(candidate)

    def test_baseline_coverage_and_source_age_must_be_honest(self):
        for patch in [lambda e: e.update(baselineVersion=None), lambda e: e.update(deviation=1),
                      lambda e: e.update(coverage=.5), lambda e: e['freshness'].update(sourceAgeSeconds=0),
                      lambda e: e['freshness'].update(state='STALE')]:
            e = copy.deepcopy(self.value)
            patch(e)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_evidence(e)

    def test_count_primitives_require_safe_nonnegative_integers(self):
        for n, d in [(.5, 1), (1, 1.5), (9007199254740992, 9007199254740992)]:
            e = copy.deepcopy(self.value)
            e.update(primitives={'type': 'RATIO', 'numerator': n, 'denominator': d}, actual=100*n/d,
                     baseline=None, baselineVersion=None, deviation=None)
            with self.subTest(n=n, d=d), self.assertRaises(ValueError):
                validate_evidence(e)
        e = copy.deepcopy(next(c['value'] for c in self.cases if c['value']['metricId'] == 'sms.queue'))
        for count in (1.5, 9007199254740992):
            e.update(actual=count)
            e['primitives']['value'] = count
            with self.subTest(count=count), self.assertRaises(ValueError):
                validate_evidence(e)
        e = copy.deepcopy(next(c['value'] for c in self.cases if c['value']['metricId'] == 'sms.delay.p95'))
        e['primitives']['histogram']['counts'][4] = 9007199254740992
        with self.assertRaises(ValueError):
            validate_evidence(e)

    def test_city_and_link_scopes_reject_irrelevant_or_inconsistent_dimensions(self):
        for patch in [{'directionId': 'out'}, {'partnerId': 'RO-DEMO-01'}]:
            e = copy.deepcopy(self.value)
            e['scope'].update(patch)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_evidence(e)
        original = next(c['value'] for c in self.cases if c['value']['metricId'] == 'transport.link.utilization.peak')
        for patch in [{'id': 'chi-hin:b-to-a'}, {'cityIds': ['CHI', 'BAL']},
                      {'partnerId': 'RO-DEMO-01'}, {'procedure': 'ORIGINATING_SETUP'}]:
            e = copy.deepcopy(original)
            e['scope'].update(patch)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_evidence(e)

    def test_sms_measurements_use_the_correct_population_scope(self):
        for original in (c['value'] for c in self.cases if c['value']['metricId'] in ('sms.delay.mean', 'sms.delay.p95', 'sms.queue')):
            e = copy.deepcopy(original)
            e['scope']['procedure'] = 'ORIGINATING_SETUP'
            with self.subTest(metric=e['metricId']), self.assertRaises(ValueError):
                validate_evidence(e)

    def test_dictionary_rejects_inconsistent_units_and_primitive_shape(self):
        for patch in [{'unit': 'SECONDS'}, {'primitiveFields': ['missingRawField']}]:
            d = copy.deepcopy(self.dictionary)
            d['metrics'][0].update(patch)
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                validate_dictionary(d)


if __name__ == '__main__':
    unittest.main()
