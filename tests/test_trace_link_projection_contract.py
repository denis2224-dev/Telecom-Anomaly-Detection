import copy
import json
import unittest
from jsonschema import Draft202012Validator, ValidationError
from scripts.trace_link_projection_contract import SCHEMA, native_example, validate_projection_contracts


class TraceLinkProjectionContractTest(unittest.TestCase):
    def test_checked_in_native_projection(self):
        validate_projection_contracts()

    def test_aliases_and_unsupported_resolutions_are_rejected(self):
        validator = Draft202012Validator(json.loads(SCHEMA.read_text(encoding='utf-8')))
        for field, value in [('metricId', 'voice.success_rate'), ('domain', 'volte'), ('policyVersion', 'trace-link-md-policy-v1')]:
            example = native_example()
            example[field] = value
            with self.assertRaises(ValidationError):
                validator.validate(example)
        example = native_example()
        example['window']['sourceResolutionSeconds'] = 60
        with self.assertRaises(ValidationError):
            validator.validate(example)

    def test_unknown_quality_cannot_become_valid_from_slot_coverage(self):
        validator = Draft202012Validator(json.loads(SCHEMA.read_text(encoding='utf-8')))
        example = native_example()
        example['quality'] = {'state': 'VALID', 'reason': None}
        with self.assertRaises(ValidationError):
            validator.validate(example)
        example['sourceQuality'] = 'VALID'
        with self.assertRaises(ValidationError):
            validator.validate(example)

    def test_zero_volume_cannot_claim_one_hundred_percent(self):
        validator = Draft202012Validator(json.loads(SCHEMA.read_text(encoding='utf-8')))
        example = copy.deepcopy(native_example())
        example['primitives'] = {'numerator': 0, 'denominator': 0}
        example['value'] = 100
        with self.assertRaises(ValidationError):
            validator.validate(example)
