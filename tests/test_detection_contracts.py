"""Canonical detection contracts must remain compatible with the existing API."""

import copy
import unittest

from jsonschema import Draft202012Validator
import test_shared_contract as shared
from test_event_contract import FORMAT_CHECKER, ROOT, read_json


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
