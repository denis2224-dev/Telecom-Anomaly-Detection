"""Independent primitive expectations and cross-language parity for Day 4."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service'))
from app.features.trace_link_features import build_features


def contexts():
    rows = json.loads((ROOT / 'contracts/fixtures/trace-link/day3-persisted-windows.json').read_text())['windows']
    return {r['domain']: deepcopy(r['input']['context']) for r in rows if r['step'] == 0}


class TraceLinkFeaturesTests(unittest.TestCase):
    def test_reviewer_adversarial_contexts_are_rejected(self):
        fixtures = json.loads((ROOT / 'contracts/fixtures/trace-link/ml-adversarial-cases-v1.json').read_text())['cases']
        for fixture in fixtures:
            with self.subTest(case=fixture['id']), self.assertRaisesRegex(ValueError, fixture['reason']):
                build_features(fixture['context'])

    def test_procedure_populations_are_valid_in_their_own_scopes_in_both_directions(self):
        for metric, procedure in [('roaming.registration.success','REGISTRATION'),('roaming.data.success','DATA'),
                                  ('roaming.voice.success','VOICE'),('roaming.sms.finalizedSuccess','SMS')]:
            for direction in ('in','out'):
                context=contexts()['ROAMING'];context['baselines']={}
                context['scope'].update(procedure=procedure,directionId=direction,id='ro-partner:'+direction)
                context['evidence'][0].update(metricId=metric,scope=deepcopy(context['scope']))
                self.assertTrue(build_features(context)['eligible'],(metric,direction))

    def test_snapshot_in_window_matches_observed_time_and_preserves_source_age(self):
        context=contexts()['TRANSPORT'];item=deepcopy(context['evidence'][0]);context['evidence'].append(item)
        item.update(metricId='transport.jitter',unit='MILLISECONDS',actual=3,
                    primitives={'type':'SNAPSHOT','value':3,'snapshotAt':'2026-10-10T08:39:00Z'})
        item['freshness'].update(observedAt='2026-10-10T08:39:00Z',sourceAgeSeconds=61)
        features=build_features(context)
        values=dict(zip(features['featureNames'],features['featureValues']))
        self.assertEqual(values['transport.jitter.actual'],3)
        self.assertEqual(values['transport.jitter.sourceAgeSeconds'],61)

    def test_only_exact_policy_declared_peer_mapping_is_accepted(self):
        from app.features.trace_link_features import contract
        context=contexts()['ROAMING'];baseline=context['baselines']['roaming.data.success']
        baseline['status']='PEER';baseline['sourceScope']['id']='peer:out'
        key={k:deepcopy(context[k]) for k in ('datasetId','domain','scope','topologyVersion')}
        key.update(metricId='roaming.data.success',unit='PERCENT',metricPolicyVersion='trace-link-kpi-v1')
        peer={**deepcopy(key),'scope':deepcopy(baseline['sourceScope'])}
        policy=deepcopy(contract('baselines/trace-link-baseline-policy-v1.json'))
        policy['peerFallbacks']=[{'key':key,'peerKey':peer}]
        self.assertTrue(build_features(context,policy)['eligible'])
        for field,value in [('metricId','roaming.voice.success'),('metricPolicyVersion','wrong'),('unit','COUNT')]:
            wrong=deepcopy(policy);wrong['peerFallbacks'][0]['peerKey'][field]=value
            with self.assertRaisesRegex(ValueError,'INCOMPATIBLE_BASELINE'):build_features(context,wrong)

    def test_primitive_ratio_and_zero_baseline_have_hand_calculated_values(self):
        context = contexts()['ROAMING']
        metric = 'roaming.data.success'
        context['evidence'][0]['actual'] = 1  # Display is not the numerical oracle.
        features = build_features(context)
        values = dict(zip(features['featureNames'], features['featureValues']))
        self.assertEqual(values[metric + '.actual'], 97)
        self.assertEqual(values[metric + '.numerator'], 970)
        self.assertEqual(values[metric + '.denominator'], 1000)
        self.assertAlmostEqual(values[metric + '.residual'], -2 / 99)
        context['baselines'][metric]['center'] = 0
        values = dict(zip(features['featureNames'], build_features(context)['featureValues']))
        self.assertEqual(values[metric + '.residual'], 97)

    def test_missing_stale_and_zero_population_do_not_become_healthy(self):
        for mutation in ('missing', 'stale', 'zero'):
            context = contexts()['TRANSPORT']
            if mutation == 'missing':
                context['evidence'] = []
            elif mutation == 'stale':
                context['evidence'][0]['freshness']['state'] = 'STALE'
                context['evidence'][0]['quality'] = {'state': 'STALE', 'reason': 'TEST'}
            else:
                context['evidence'][0]['primitives'].update(numerator=0, denominator=0)
                context['evidence'][0]['actual'] = None
                context['evidence'][0]['quality'] = {'state': 'INSUFFICIENT_EVIDENCE', 'reason': 'ZERO_DENOMINATOR'}
            result = build_features(context)
            self.assertFalse(result['eligible'], mutation)
            self.assertTrue(result['reasonCodes'])

    def test_domains_scope_direction_and_inputs_are_preserved(self):
        for context in contexts().values():
            original = deepcopy(context)
            result = build_features(context)
            self.assertTrue(result['eligible'])
            self.assertEqual(result['scope'], context['scope'])
            self.assertEqual(result['window'], context['window'])
            self.assertEqual(context, original)
            self.assertEqual(len(result['featureNames']), len(result['featureValues']))
        context = contexts()['ROAMING']
        context['scope']['directionId'] = 'in'
        context['scope']['id'] = 'ro-partner:in'
        context['evidence'][0]['scope'] = deepcopy(context['scope'])
        context['baselines']['roaming.data.success']['sourceScope'] = deepcopy(context['scope'])
        self.assertEqual(build_features(context)['scope']['directionId'], 'in')

    def test_future_truth_duplicate_metric_and_nonfinite_are_rejected(self):
        for mutation in ('truth', 'duplicate', 'nan'):
            context = contexts()['MOBILE_DATA']
            if mutation == 'truth': context['scenarioId'] = 'fault'
            if mutation == 'duplicate': context['evidence'].append(deepcopy(context['evidence'][0]))
            if mutation == 'nan': context['evidence'][0]['primitives']['numerator'] = float('nan')
            with self.assertRaises(ValueError, msg=mutation): build_features(context)

    def test_snapshot_cannot_substitute_for_a_rate_or_mean_population(self):
        context = contexts()['TRANSPORT']
        context['evidence'][0]['primitives'] = {'type':'SNAPSHOT','value':3,'snapshotAt':context['window']['to']}
        with self.assertRaises(ValueError): build_features(context)

    def test_fresh_java_exports_match_independent_python(self):
        path = ROOT / 'services/processor/target/trace-link-shadow-java.json'
        if not path.exists():
            self.skipTest('Run TraceLinkFeaturesTest to produce fresh Java parity exports')
        for row in json.loads(path.read_text()):
            expected = build_features(row['context'])
            actual = row['features']
            for key in expected.keys() - {'featureValues'}:
                self.assertEqual(actual[key], expected[key], key)
            for name, a, b in zip(expected['featureNames'], actual['featureValues'], expected['featureValues']):
                if b is None: self.assertIsNone(a, name)
                elif isinstance(b, int): self.assertEqual(a, b, name)
                else: self.assertAlmostEqual(a, b, delta=1e-9, msg=name)
