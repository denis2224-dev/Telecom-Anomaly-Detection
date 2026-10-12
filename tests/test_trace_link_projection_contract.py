import copy
import json
import unittest
from jsonschema import Draft202012Validator, ValidationError
from scripts.trace_link_projection_contract import SCHEMA, native_example, validate_message, validate_projection_contracts


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

    def test_contradictory_source_metadata_and_effective_quality_are_rejected(self):
        for changes in [
            {'sourceQuality': 'MISSING', 'sourceCoverage': 0},
            {'sourceCoverage': 0},
            {'sourceQuality': 'VALID', 'sourceCoverage': 0},
            {'sourceQuality': 'VALID', 'sourceCoverage': 0.5, 'quality': {'state': 'VALID', 'reason': None}},
            {'sourceQuality': 'VALID', 'sourceCoverage': 1, 'quality': {'state': 'MISSING', 'reason': 'SOURCE_MISSING'}},
            {'sourceQuality': 'STALE', 'sourceCoverage': 0, 'quality': {'state': 'VALID', 'reason': None}},
            {'sourceQuality': 'VALID', 'sourceCoverage': 1, 'primitives': {'numerator': 0, 'denominator': 0},
             'value': None, 'nullReason': 'ZERO_DENOMINATOR', 'quality': {'state': 'VALID', 'reason': None}},
        ]:
            with self.subTest(changes=changes):
                example = native_example()
                example.update(changes)
                with self.assertRaises(ValidationError):
                    validate_message(example)

    def test_measured_zero_stale_and_unknown_source_metadata_remain_distinct(self):
        examples = [native_example() for _ in range(5)]
        examples[1].update(sourceQuality='VALID', sourceCoverage=None,
                           quality={'state': 'INSUFFICIENT_EVIDENCE', 'reason': 'SOURCE_COVERAGE_UNAVAILABLE'})
        examples[2].update(sourceQuality='VALID', sourceCoverage=0.5,
                           quality={'state': 'PARTIAL', 'reason': 'SOURCE_COVERAGE_PARTIAL'})
        examples[3].update(sourceQuality='STALE', sourceCoverage=0,
                           quality={'state': 'STALE', 'reason': 'SOURCE_STALE'})
        examples[4].update(sourceQuality='VALID', sourceCoverage=1, primitives={'numerator': 0, 'denominator': 10},
                           value=0, quality={'state': 'VALID', 'reason': None})
        for example in examples:
            validate_message(example)

    def test_semantic_identity_arithmetic_and_city_conflicts_fail_closed(self):
        for change in [lambda p: p.update(projectionId='0' * 64),
                       lambda p: p['provenance'].update(sourceNaturalId='0' * 64),
                       lambda p: p['scope'].update(contractCityId='BAL'),
                       lambda p: p['window'].update(to='2026-09-10T09:10:00Z'),
                       lambda p: p.update(value=99),
                       lambda p: p['primitives'].update(numerator=9000)]:
            example = native_example()
            change(example)
            with self.assertRaises(ValueError):
                validate_message(example)
