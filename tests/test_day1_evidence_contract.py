"""Freeze explanation/auxiliary shapes and authority, without activating correlation."""

import copy
import json
from pathlib import Path
import sys
import unittest
from jsonschema import ValidationError

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from day1_evidence_contract import validate_cause_projection, validate_day1_evidence_contracts, validator


class Day1EvidenceContractTests(unittest.TestCase):
    def setUp(self):
        self.suite = json.loads((ROOT / 'contracts/fixtures/geography/cause-projection-cases-v1.json').read_text())

    def test_shared_cause_and_optional_auxiliary_candidates_validate(self):
        validate_day1_evidence_contracts()

    def test_no_probability_ground_truth_or_confirmed_power_vocabulary(self):
        sample = self.suite['cases'][0]['value']
        for field, value in [('causeConfidence', 0.99), ('probableCauseCode', 'POWER_OFF'),
                             ('groundTruth', 'POWER_OFF'), ('scenarioType', 'power-fault'), ('runId', 'oracle')]:
            candidate = copy.deepcopy(sample)
            candidate[field] = value
            with self.subTest(field=field), self.assertRaises(ValidationError):
                validate_cause_projection(candidate)

    def test_wrong_city_time_version_role_and_reporter_are_not_supporting_evidence(self):
        sample = self.suite['cases'][1]['value']
        for field, value in [('scopeId', 'VOLTE-MD-BAL'), ('windowStart', '2026-10-05T08:01:00Z'),
                             ('topologyVersion', 'wrong'), ('catalogueVersion', 'wrong'),
                             ('nodeId', 'IMS-MD-BAL-01'), ('sourceId', 'IMS-MD-BAL-01'), ('role', 'SMS_SMSC')]:
            candidate = copy.deepcopy(sample)
            candidate['supportingEvidence'][1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_cause_projection(candidate)

    def test_hypothesis_requires_support_and_undetermined_stays_low_confidence(self):
        candidate = copy.deepcopy(self.suite['cases'][1]['value'])
        candidate['supportingEvidence'] = []
        self.assertRaises(ValidationError, validate_cause_projection, candidate)
        candidate = copy.deepcopy(self.suite['cases'][0]['value'])
        candidate['causeConfidence'] = 'MEDIUM'
        self.assertRaises(ValidationError, validate_cause_projection, candidate)

    def test_auxiliary_is_separate_bounded_and_does_not_trust_sender_freshness(self):
        sample = json.loads((ROOT / 'contracts/fixtures/geography/auxiliary-evidence-cases-v1.json').read_text())['cases'][0]
        validate = validator('contracts/auxiliary/auxiliary-network-evidence-v1.schema.json').validate
        for patch in [{'fresh': True}, {'detail': 'x' * 513}, {'groundTruth': 'POWER_OFF'},
                      {'latencyMs': 2}, {'type': 'PING_RESULT', 'status': 'ALARM'}]:
            with self.subTest(patch=patch), self.assertRaises(ValidationError):
                validate(dict(sample, **patch))
