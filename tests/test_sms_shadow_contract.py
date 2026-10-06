from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).resolve().parents[1]


class ShadowContractTests(unittest.TestCase):
    def setUp(self):
        schema = json.loads((ROOT / 'contracts/ml-shadow/sms-shadow-evidence-v1.schema.json').read_bytes())
        Draft202012Validator.check_schema(schema)
        self.validate = Draft202012Validator(schema, format_checker=FormatChecker()).validate
        self.event = dict(schemaVersion=1, evidenceId='a'*64, windowId='b'*64, service='SMS',
                          scopeId='SMS-MD-ROUTE-A', windowStart='2027-05-03T00:00:00Z', windowEnd='2027-05-03T00:01:00Z',
                          featureVersion=2, baselineVersion='baseline-v2', topologyVersion='2',
                          requestedAt='2027-05-03T00:01:10Z', completedAt='2027-05-03T00:01:10.100Z',
                          requestedModelVersion='sms-supervised-v1-2', modelVersion='sms-supervised-v1-2',
                          modelSha256='f3baf6be91d56c0a8054a9cd81e028774e3af9464d8124a81aa89180030c9f19',
                          mlStatus='OK', classifierScore=.55, detection=True, threshold=.55)

    def test_ok_and_failures_are_distinct_and_labels_are_excluded(self):
        self.validate(self.event)
        for status in ('DISABLED', 'UNAVAILABLE', 'TIMEOUT', 'INSUFFICIENT_DATA', 'MALFORMED_RESPONSE'):
            failed = dict(self.event, mlStatus=status, classifierScore=None, detection=None, modelVersion=None)
            self.validate(failed)
            with self.assertRaises(ValidationError):
                self.validate(dict(failed, detection=False))
        for field in ('anomalyRank', 'label', 'groundTruth'):
            with self.assertRaises(ValidationError):
                self.validate(dict(self.event, **{field: 1}))
        with self.assertRaises(ValidationError):
            self.validate(dict(self.event, threshold=.5))
