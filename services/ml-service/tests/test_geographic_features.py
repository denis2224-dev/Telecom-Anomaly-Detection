"""City role authority and independently checked arithmetic/provenance."""
import copy
from datetime import datetime
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT), str(ROOT / 'services/ml-service')]
from app.features import service_features as features

def read(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8'))

class GeographicFeatureTests(unittest.TestCase):
    def setUp(self):
        self.topology = read('contracts/topology/geographic-scopes-v2.json')
        self.catalogue = read('contracts/geography/demo-geography-v1.json')
        self.receipts = read('contracts/fixtures/geography/complete-city-observations-v1.json')

    def build(self, scope, receipts=None, values=None):
        receipts = copy.deepcopy(self.receipts if receipts is None else receipts)
        raw = next(r for r in receipts if r['scopeId'] == scope and r['kind'] == 'SERVICE')
        nodes = [r for r in receipts if r['scopeId'] == scope and r['kind'] == 'NODE']
        at = datetime.fromisoformat(raw['windowStart'])
        baseline = dict(baselineVersion='baseline-v2', status='PEER', scopeId=scope,
                        sourceScopeId='VOLTE-MD-CENTRAL' if raw['service']=='VOLTE' else 'SMS-MD-ROUTE-A',
                        service=raw['service'], hourOfWeek=at.weekday()*24+at.hour,
                        values=values if values is not None else ({'cssrPct':99.3,'rrcSrPct':99.5,'bearerSrPct':99.0}
                            if raw['service']=='VOLTE' else {'p95DeliveryMs':2000,'deliverySrPct':99.0}))
        context = features.FeatureContext(self.topology, self.catalogue)
        return features.build_features(raw, nodes, baseline, context=context)

    def test_all_twenty_city_scopes_have_complete_vectors_and_exact_contributors(self):
        for binding in self.catalogue['scopes']:
            if binding['legacy']: continue
            scope = binding['scopeId']
            with self.subTest(scope=scope):
                result=self.build(scope)
                self.assertTrue(result['mlEligible'])
                self.assertEqual(result['topologyVersion'],self.topology['topologyVersion'])
                self.assertEqual(len(result['featureValues']),6)
                expected=sorted(r['eventId'] for r in self.receipts if r['scopeId']==scope)
                self.assertEqual(result['sourceEventIds'],expected)

    def test_explicit_city_context_does_not_weaken_unauthorized_reporter_checks(self):
        receipts=copy.deepcopy(self.receipts)
        next(r for r in receipts if r['scopeId']=='VOLTE-MD-CHI' and r['kind']=='NODE')['sourceId']='IMS-MD-BAL-01'
        with self.assertRaisesRegex(ValueError,'Non-authoritative'):
            self.build('VOLTE-MD-CHI',receipts)

    def test_stale_node_is_not_a_contributor_or_zero_feature(self):
        receipts=copy.deepcopy(self.receipts)
        node=next(r for r in receipts if r['scopeId']=='SMS-MD-CHI' and r['kind']=='NODE')
        node.update(windowStart='2026-09-15T07:59:00Z',windowEnd='2026-09-15T08:00:00Z')
        result=self.build('SMS-MD-CHI',receipts)
        self.assertFalse(result['mlEligible'])
        self.assertNotIn(node['eventId'],result['sourceEventIds'])
        self.assertIsNone(next(k for k in result['kpis'] if k['name']=='queueDepth')['observed'])

    def test_city_context_is_immutable_and_rejects_unreviewed_topology(self):
        before=copy.deepcopy((self.topology,self.catalogue,self.receipts))
        self.build('VOLTE-MD-BAL')
        self.assertEqual(before,(self.topology,self.catalogue,self.receipts))
        self.topology['topologyVersion']='unknown'
        with self.assertRaises(ValueError):
            features.FeatureContext(self.topology,self.catalogue)

    def test_inferred_absence_keeps_queue_evidence_without_a_service_event(self):
        sys.path.insert(0,str(ROOT/'scripts'))
        from geographic_feature_parity import reference_cases
        result=reference_cases('SMS')['sms-md-chi-queue-only']
        self.assertEqual(result['quality'],'MISSING')
        self.assertFalse(result['mlEligible'])
        self.assertEqual(len(result['sourceEventIds']),1)
        kpis={k['name']:k for k in result['kpis']}
        self.assertEqual(kpis['queueDepth']['observed'],150)
        self.assertEqual(kpis['oldestPendingAgeSec']['observed'],120)
        self.assertIsNone(kpis['deliveredMessages']['observed'])
        self.assertIsNone(kpis['p95DeliveryMs']['observed'])

    def test_fresh_city_vectors_score_with_pinned_models_and_reject_changed_baselines(self):
        sys.path.insert(0,str(ROOT/'scripts'))
        from geographic_feature_parity import reference_cases
        from app.inference.scoring import load,score
        normal_scopes=set()
        for service in ('VOLTE','SMS'):
            loaded=load(service)
            for name,window in reference_cases(service).items():
                if not window['mlEligible']: continue
                with self.subTest(case=name):
                    result=score(window,loaded)
                    self.assertEqual(result['mlStatus'],'OK')
                    self.assertEqual(result['modelVersion'],'isoforest-v2-synthetic-1')
                    if name.endswith('-normal'): normal_scopes.add(window['scopeId'])
                    incompatible=copy.deepcopy(window)
                    incompatible['baselineVersion']='baseline-v2-incompatible'
                    with self.assertRaisesRegex(ValueError,'incompatible'):
                        score(incompatible,loaded)
        self.assertEqual(len(normal_scopes),20)

if __name__=='__main__': unittest.main()
