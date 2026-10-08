"""Canonical detection contracts must remain compatible with the existing API."""

import copy
import hashlib
import json
import unittest

from jsonschema import Draft202012Validator
import test_shared_contract as shared
from test_utils import FORMAT_CHECKER, ROOT, read_json


class DetectionContractTests(unittest.TestCase):
    def test_explanation_collection_validates_every_nested_payload(self):
        import importlib.util
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        spec = importlib.util.spec_from_file_location('check_contracts', ROOT / 'scripts/check-contracts.py')
        checks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checks)
        suite = read_json(ROOT / 'contracts/fixtures/detections/service-explanation-cases.json')
        checks.validate_explanation_cases(suite)
        expected_ids = {f'{service}-{trajectory}' for service in ('volte', 'sms')
                        for trajectory in ('normal', 'fault', 'gray-zone', 'low-volume', 'missing-source', 'recovered')}
        expected_ids.update(('volte-chi-mapped-recovery', 'sms-bal-mapped-recovery'))
        self.assertEqual({case['id'] for case in suite['cases']}, expected_ids)
        self.assertEqual(len(suite['cases']), 14)
        for defect in ('feature_order', 'fake_subscribers', 'invalid_node', 'duplicate_case',
                       'unknown_topology', 'geographic_reporter', 'wrong_saved_authority'):
            with self.subTest(defect=defect):
                broken = copy.deepcopy(suite)
                if defect == 'feature_order':
                    broken['cases'][0]['windows'][0]['feature']['featureNames'].reverse()
                elif defect == 'fake_subscribers':
                    broken['cases'][1]['detections'][0]['impact']['uniqueSubscribers'] = 0
                elif defect == 'invalid_node':
                    del broken['cases'][0]['windows'][0]['node']['sourceId']
                elif defect == 'unknown_topology':
                    broken['cases'][-1]['windows'][0]['feature']['topologyVersion'] = 'unreviewed'
                elif defect == 'geographic_reporter':
                    broken['cases'][-1]['windows'][0]['node']['sourceId'] = 'UNREVIEWED-REPORTER'
                elif defect == 'wrong_saved_authority':
                    broken['cases'][-1]['windows'][0]['feature']['topologyVersion'] = '2-baseline'
                else:
                    broken['cases'].append(copy.deepcopy(broken['cases'][0]))
                with self.assertRaises((ValueError, __import__('jsonschema').ValidationError)):
                    checks.validate_explanation_cases(broken)

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
