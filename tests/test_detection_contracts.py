"""Canonical detection contracts must remain compatible with the existing API."""

import copy
import hashlib
import json
import unittest

from jsonschema import Draft202012Validator
import test_shared_contract as shared
from test_utils import FORMAT_CHECKER, ROOT, read_json


class DetectionContractTests(unittest.TestCase):
    def test_policy_values_and_schema(self):
        policy = read_json(ROOT / 'contracts/policies/service-rules-v2.json')
        schema = read_json(ROOT / 'contracts/policies/service-rules-v2.schema.json')
        validator = Draft202012Validator(schema)
        validator.validate(policy)
        self.assertEqual(policy['voice'], {
            'minAttempts': 100, 'dropPpStrictlyGreaterThan': 1.0,
            'recoveryDropPpAtMost': 0.5, 'highExtraFailuresAtLeast': 50,
            'criticalExtraFailuresAtLeast': 200,
        })
        self.assertEqual(policy['openAfterBreachedWindows'], 2)
        self.assertEqual(policy['recoverAfterHealthyWindows'], 3)
        bad = copy.deepcopy(policy)
        bad['voice']['minAttempts'] = 0
        self.assertFalse(validator.is_valid(bad))

    def test_current_detection_validates_without_changing_api(self):
        schema = read_json(ROOT / 'contracts/detections/service-detection-v2.schema.json')
        detection = read_json(ROOT / 'contracts/fixtures/incidents/incident-open.json')['latestDetection']
        Draft202012Validator(schema, format_checker=FORMAT_CHECKER).validate(detection)
        shared.SharedContractTests.setUpClass()
        shared.SharedContractTests.detection_validator.validate(detection)

    def test_illustrative_detection_matches_feature_evidence_and_public_api(self):
        detection = read_json(ROOT / 'contracts/fixtures/detections/voice-open-illustrative-v2.json')
        feature = read_json(ROOT / 'contracts/fixtures/features/voice-worked-v2.json')
        schema = read_json(ROOT / 'contracts/detections/service-detection-v2.schema.json')
        validator = Draft202012Validator(schema, format_checker=FORMAT_CHECKER)
        validator.validate(detection)
        shared.SharedContractTests.setUpClass()
        shared.SharedContractTests.detection_validator.validate(detection)
        self.assertEqual(detection['kpis'], feature['kpis'])
        self.assertEqual(detection['evidence'][0]['sourceEventIds'], feature['sourceEventIds'])
        self.assertEqual(detection['impact']['extraFailedAttempts'], 53)
        self.assertEqual(detection['mlStatus'], 'UNAVAILABLE')
        canonical = lambda values: json.dumps(values, ensure_ascii=True, separators=(',', ':')).encode()
        self.assertEqual(detection['correlationKey'], hashlib.sha256(canonical([
            detection['service'], detection['scopeId'], detection['anomalyType'],
            detection['rulesetVersion']])).hexdigest())
        self.assertEqual(detection['episodeId'], hashlib.sha256(canonical([
            detection['correlationKey'], detection['firstObservedAt']])).hexdigest())
        self.assertEqual(detection['detectionId'], hashlib.sha256(canonical([
            detection['episodeId'], detection['windowStart'], detection['phase'],
            detection['rulesetVersion']])).hexdigest())
        self.assertFalse(validator.is_valid(dict(detection, anomalyRank=0)))
        self.assertFalse(validator.is_valid(dict(detection, mlStatus='OK')))
        wrong = copy.deepcopy(detection)
        wrong['impact']['uniqueSubscribers'] = 53
        self.assertFalse(validator.is_valid(wrong))

    def test_feature_schema_rejects_wrong_order_and_fake_ineligible_vector(self):
        schema = read_json(ROOT / 'contracts/features/service-feature-window-v2.schema.json')
        names = read_json(ROOT / 'contracts/features/feature-order-v2.json')['models']['VOLTE']
        payload = dict(schemaVersion=2, featureVersion=2, windowId='test-window',
                       scopeId='VOLTE-MD-CENTRAL', service='VOLTE',
                       windowStart='2026-09-15T08:00:00Z', windowEnd='2026-09-15T08:01:00Z',
                       quality='COMPLETE', baselineVersion='baseline-v2', topologyVersion='2-baseline',
                       kpis=[], featureNames=names, featureValues=[0, 0, 0, 0, 0, 35],
                       mlEligible=True, sourceEventIds=[])
        validator = Draft202012Validator(schema, format_checker=FORMAT_CHECKER)
        validator.validate(payload)
        shared.SharedContractTests.setUpClass()
        shared.SharedContractTests.api_validator(shared.SharedContractTests.schemas['ServiceKpiWindow']).validate(payload)
        self.assertFalse(validator.is_valid(dict(payload, featureNames=list(reversed(names)))))
        self.assertFalse(validator.is_valid(dict(payload, quality='INCOMPLETE')))
        self.assertFalse(validator.is_valid(dict(payload, mlEligible=False)))
        validator.validate(dict(payload, mlEligible=False, featureNames=[], featureValues=[]))

    def test_volte_first_slice_scenario_matches_contracts_and_parity(self):
        scenario = read_json(ROOT / 'tests/e2e/scenarios/volte-first-slice.json')
        self.assertEqual(scenario['scopeId'], 'VOLTE-MD-CENTRAL')
        self.assertEqual(scenario['service'], 'VOLTE')
        self.assertEqual(scenario['topologyVersion'], '2-baseline')
        self.assertEqual(scenario['baselineVersion'], 'baseline-v2')
        self.assertEqual(scenario['rulesetVersion'], 'service-rules-v2')
        self.assertEqual(scenario['expectedIncidentCount'], 1)
        self.assertEqual(scenario['expectedTotalWindows'], 8)

        # Validate that referenced fixtures exist and conform to observations schema
        obs_schema = read_json(ROOT / 'contracts/observations/telecom-observation-v2.schema.json')
        obs_validator = Draft202012Validator(obs_schema, format_checker=FORMAT_CHECKER)
        for w in scenario['windows']:
            fixtures = w.get('fixtures')
            if fixtures:
                for key in ('service', 'node'):
                    if key in fixtures:
                        path = ROOT / 'contracts/fixtures/observations' / fixtures[key]
                        self.assertTrue(path.exists(), f"Fixture missing: {path}")
                        obs_validator.validate(read_json(path))

            m = w.get('measurements')
            if m:
                # Enforce: attempts = technicalSuccesses + technicalFailures + userOutcomes
                self.assertEqual(m['attempts'], m['technicalSuccesses'] + m['technicalFailures'] + m['userOutcomes'])
                # Enforce: eligibleAttempts = attempts - userOutcomes
                eligible = m['attempts'] - m['userOutcomes']
                self.assertEqual(w['kpis']['eligibleAttempts'], eligible)
                # Enforce: CSSR = 100 * technicalSuccesses / eligibleAttempts
                expected_cssr = 100.0 * m['technicalSuccesses'] / eligible
                self.assertAlmostEqual(w['kpis']['cssrPct'], expected_cssr, places=3)

        # Enforce two-consecutive breach rule for episode opening
        self.assertIsNone(scenario['windows'][0]['episodePhase'])
        self.assertIsNone(scenario['windows'][1]['episodePhase'])
        self.assertTrue(scenario['windows'][1].get('candidateStart'))
        self.assertEqual(scenario['windows'][2]['episodePhase'], 'OPEN')
        self.assertEqual(scenario['windows'][2]['sequence'], 1)
        self.assertEqual(scenario['windows'][2]['severity'], 'HIGH')
        self.assertEqual(scenario['windows'][2]['mlStatus'], 'UNAVAILABLE')
        self.assertEqual(scenario['windows'][3]['episodePhase'], 'UPDATE')
        self.assertEqual(scenario['windows'][4]['episodePhase'], 'UNKNOWN')
        self.assertEqual(scenario['windows'][7]['episodePhase'], 'RECOVERY')
        self.assertEqual(scenario['windows'][7]['technicalState'], 'RECOVERED')

        # Validate deterministic identity hashing
        canonical = lambda values: json.dumps(values, ensure_ascii=True, separators=(',', ':')).encode()
        correlation_key = hashlib.sha256(canonical([
            scenario['service'], scenario['scopeId'], 'VOLTE_SETUP_DEGRADATION', scenario['rulesetVersion']
        ])).hexdigest()
        first_observed_at = '2026-09-15T07:59:00Z'
        episode_id = hashlib.sha256(canonical([correlation_key, first_observed_at])).hexdigest()
        open_detection_id = hashlib.sha256(canonical([
            episode_id, '2026-09-15T08:00:00Z', 'OPEN', scenario['rulesetVersion']
        ])).hexdigest()

        # Matches illustrative fixture
        illustrative = read_json(ROOT / 'contracts/fixtures/detections/voice-open-illustrative-v2.json')
        self.assertEqual(correlation_key, illustrative['correlationKey'])
        self.assertEqual(episode_id, illustrative['episodeId'])
        self.assertEqual(open_detection_id, illustrative['detectionId'])

        # Replay expectations
        replay = scenario['replay']
        self.assertEqual(replay['expectedIncidentCount'], 1)
        self.assertEqual(replay['expectedNewIncidents'], 0)
        self.assertEqual(replay['expectedIngestionResult'], 'DUPLICATE')
        self.assertEqual(replay['expectedLatestSequence'], 6)
