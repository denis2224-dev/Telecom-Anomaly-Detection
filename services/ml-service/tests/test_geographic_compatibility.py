"""Pin the scorer contract for city peers; this is not new-city feature parity."""

import copy
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'services/ml-service'))
from app.inference.scoring import load, score


class GeographicModelCompatibilityTests(unittest.TestCase):
    def test_all_city_scopes_accept_unchanged_semantics_and_reject_changed_baseline(self):
        catalogue = json.loads((ROOT / 'contracts/geography/demo-geography-v1.json').read_text(encoding='utf-8'))
        explanations = json.loads((ROOT / 'contracts/fixtures/detections/service-explanation-cases.json').read_text())
        fixtures = {service: next(case['windows'][0]['feature'] for case in explanations['cases']
                                 if case['service'] == service and case['windows'][0]['feature']['mlEligible'])
                    for service in ('VOLTE', 'SMS')}
        loaded = {service: load(service) for service in fixtures}
        for binding in catalogue['scopes']:
            if binding['legacy']:
                continue
            service = 'VOLTE' if binding['scopeId'].startswith('VOLTE-') else 'SMS'
            window = copy.deepcopy(fixtures[service])
            window['scopeId'] = binding['scopeId']
            window['topologyVersion'] = catalogue['topologyVersion']
            with self.subTest(scope=binding['scopeId']):
                # Reusing a golden vector proves compatibility only, not geographic model generalization.
                result = score(window, loaded[service])
                self.assertEqual(result['mlStatus'], 'OK')
                self.assertEqual(result['modelVersion'], 'isoforest-v2-synthetic-1')
                self.assertGreaterEqual(result['anomalyRank'], 0)
                self.assertLessEqual(result['anomalyRank'], 1)
                incompatible = copy.deepcopy(window)
                incompatible['baselineVersion'] = 'baseline-v2.geography-changed'
                with self.assertRaisesRegex(ValueError, 'incompatible'):
                    score(incompatible, loaded[service])
                window['featureNames'] = list(reversed(window['featureNames']))
                with self.assertRaises(ValueError):
                    score(window, loaded[service])
