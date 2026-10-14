import copy
import json
from pathlib import Path
import unittest
from jsonschema.exceptions import ValidationError
from scripts.trace_link_producer_contract import validate_result, validate_producer_contracts

ROOT = Path(__file__).resolve().parents[1]


class TraceLinkProducerContractTest(unittest.TestCase):
    def setUp(self):
        self.examples = json.loads((ROOT / 'contracts/fixtures/trace-link/domain-primitive-results-v1.json').read_text(encoding='utf-8'))

    def test_native_mapping_checksum_and_arithmetic(self):
        validate_producer_contracts()

    def test_unknown_quality_and_source_coverage_cannot_be_promoted(self):
        for field, value in [('sourceQuality', 'VALID'), ('sourceCoverage', 1), ('availability', 'SUPPORTED'), ('validityAssumption', None)]:
            candidate = copy.deepcopy(self.examples[0])
            candidate[field] = value
            with self.subTest(field=field), self.assertRaises((ValueError, ValidationError)):
                validate_result(candidate)

    def test_dns_and_throughput_reference_limits_are_enforced(self):
        for metric, field, value in [('mobileData.dns.success', 'referenceOnly', False), ('mobileData.activation.success', 'procedureEligibility', 'PROVEN'), ('mobileData.throughput', 'weightingBasis', None)]:
            candidate = copy.deepcopy(next(r for r in self.examples if r['observation']['metricId'] == metric))
            candidate[field] = value
            with self.subTest(metric=metric), self.assertRaises((ValueError, ValidationError)):
                validate_result(candidate)

    def test_truth_unknown_fields_and_changed_identity_fail(self):
        for mutate in [lambda r: r['observation'].update(scenarioType='TRANSPORT_LINK_OVERLOAD'), lambda r: r['observation'].update(eventId='0' * 64), lambda r: r.update(value=0)]:
            candidate = copy.deepcopy(self.examples[0])
            mutate(candidate)
            with self.assertRaises((ValueError, ValidationError)):
                validate_result(candidate)
