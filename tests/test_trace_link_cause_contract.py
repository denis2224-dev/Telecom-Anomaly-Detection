"""Cause envelopes retain diagnostics without admitting rejected attached support."""
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator, ValidationError

ROOT = Path(__file__).resolve().parents[1]


class TraceLinkCauseContractTest(unittest.TestCase):
    def test_output_identity_and_reason_codes_have_closed_vocabularies(self):
        schema = json.loads((ROOT / 'contracts/explanations/trace-link-cause-result-v1.schema.json').read_text())
        properties = schema['properties']
        checks = [
            ('domain', 'TRANSPORT', 'UNKNOWN_DOMAIN'),
            ('namespace', 'trace-link-reference-v1', 'runtime-unapproved'),
            ('revision', 9007199254740991, 9007199254740992),
            ('reasonCodes', ['NO_SYMPTOM'], ['UNREVIEWED_REASON']),
            ('reasonCodes', ['DEPENDENCY_PROVENANCE_MISMATCH'], ['INVENTED_PROVENANCE_REASON']),
            ('rejectedEvidence', [{'candidateId': 'c', 'evidenceId': 'e', 'reason': 'STALE_EVIDENCE'}],
             [{'candidateId': 'c', 'evidenceId': 'e', 'reason': 'UNREVIEWED_REASON'}]),
        ]
        for field, valid, invalid in checks:
            with self.subTest(field=field):
                validator = Draft202012Validator(properties[field])
                validator.validate(valid)
                with self.assertRaises(ValidationError):
                    validator.validate(invalid)

    def test_schemas_are_valid_and_raw_candidate_shapes_are_closed(self):
        path = ROOT / 'contracts/explanations/trace-link-cause-input-v1.schema.json'
        schema = json.loads(path.read_text())
        Draft202012Validator.check_schema(schema)
        candidate = schema['properties']['candidates']['items']
        validator = Draft202012Validator(candidate)
        example = json.loads((ROOT / 'contracts/fixtures/trace-link/cause-control-cases.json').read_text())['cases'][0]['input']['candidates'][0]
        validator.validate(example)
        with self.assertRaises(ValidationError):
            validator.validate({**example, 'expectedLabel': 'fault'})
        for key in ('sourceId', 'pathId', 'candidateId'):
            invalid = dict(example)
            invalid.pop(key)
            with self.assertRaises(ValidationError):
                validator.validate(invalid)

    def test_output_cannot_claim_supported_confidence_without_dependency(self):
        schema = json.loads((ROOT / 'contracts/explanations/trace-link-cause-result-v1.schema.json').read_text())
        Draft202012Validator.check_schema(schema)
        branches = schema['allOf'][0]
        unresolved = Draft202012Validator(branches['then'])
        unresolved.validate({'probableCauseCode': 'UNDETERMINED', 'causeConfidence': None,
                             'dependency': None, 'supportingEvidenceIds': []})
        with self.assertRaises(ValidationError):
            unresolved.validate({'causeConfidence': 'MEDIUM'})
        with self.assertRaises(ValidationError):
            unresolved.validate({'supportingEvidenceIds': ['rejected-evidence']})
        supported = Draft202012Validator(branches['else'])
        with self.assertRaises(ValidationError):
            supported.validate({'dependency': None})
        with self.assertRaises(ValidationError):
            supported.validate({'supportingEvidenceIds': []})


if __name__ == '__main__':
    unittest.main()
