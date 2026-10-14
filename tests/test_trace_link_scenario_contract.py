"""Arithmetic and truth isolation independent of Java's scenario implementation."""
import copy
import sys
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from trace_link_scenario_contract import validate_scenarios, validate_transitions, no_truth, value, read, FIXTURES


class TraceLinkScenarioContractTest(unittest.TestCase):
    def test_independent_domain_arithmetic(self):
        self.assertEqual(144, validate_scenarios())

    def test_transition_expectations_stay_pending_and_separate(self):
        self.assertEqual(12, validate_transitions())

    def test_nested_truth_is_rejected(self):
        for field in ('scenarioTruthId', 'expectedFaultLabel', 'injectedProbableCause', 'futureIncidentOutcome', 'groundTruthTimeline', 'evaluationPassFail'):
            with self.assertRaises(ValueError):
                no_truth({'primitives': {field: 'sentinel'}})

    def test_zero_denominator_is_null_and_not_healthy(self):
        e = copy.deepcopy(read(FIXTURES / 'domain-measurements-v1.json')[3]['windows'][0][0])
        e['primitives'] = {'numerator': 0, 'denominator': 0}
        e['quality'] = 'INSUFFICIENT_EVIDENCE'
        self.assertIsNone(value(e))


if __name__ == '__main__':
    unittest.main()
