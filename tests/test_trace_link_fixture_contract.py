"""The shared suite must be complete, versioned, and independent of implementation exports."""
import copy
import unittest

from scripts.trace_link_fixture_contract import read_suite, validate_suite


class TraceLinkFixtureContractTest(unittest.TestCase):
    def setUp(self):
        self.suite = read_suite()

    def test_frozen_suite_covers_every_operation_and_invalid_controls(self):
        validate_suite(self.suite)

    def test_duplicate_case_is_rejected(self):
        self.suite['cases'].append(copy.deepcopy(self.suite['cases'][0]))
        with self.assertRaises(ValueError):
            validate_suite(self.suite)

    def test_goldens_cannot_have_both_expected_and_error(self):
        self.suite['cases'][0]['expectedError'] = 'INVALID_INPUT'
        with self.assertRaises(ValueError):
            validate_suite(self.suite)

    def test_nonfinite_expected_result_is_rejected(self):
        self.suite['cases'][0]['expected']['values']['value'] = float('nan')
        with self.assertRaises(ValueError):
            validate_suite(self.suite)

    def test_malformed_window_or_policy_is_rejected(self):
        for key, value in [('window', {'from': '2026-10-10T08:10:00Z', 'to': '2026-10-10T08:00:00Z'}),
                           ('policyVersion', ''), ('operation', 'unknown')]:
            suite = copy.deepcopy(self.suite)
            suite['cases'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_suite(suite)

    def test_missing_invalid_controls_are_rejected(self):
        self.suite['cases'] = [c for c in self.suite['cases'] if 'expected' in c]
        with self.assertRaises(ValueError):
            validate_suite(self.suite)

    def test_deliberately_invalid_timestamp_can_be_a_negative_input(self):
        case = next(c for c in self.suite['cases'] if 'expectedError' in c)
        case['window']['from'] = '2026-10-10T24:00:00Z'
        validate_suite(self.suite)

    def test_golden_numeric_shape_rejects_boolean_string_and_extra_fields(self):
        for target, key, value in [('primitives', 'numerator', True),
                                   ('primitives', 'numerator', .5),
                                   ('primitives', 'numerator', 9007199254740992),
                                   ('values', 'value', 'not-a-number'),
                                   ('values', 'extra', 1)]:
            suite = copy.deepcopy(self.suite)
            suite['cases'][0]['expected'][target][key] = value
            with self.subTest(target=target, key=key, value=value), self.assertRaises(ValueError):
                validate_suite(suite)

    def test_output_units_match_operation(self):
        self.suite['cases'][0]['units']['value'] = 'SECONDS'
        with self.assertRaises(ValueError):
            validate_suite(self.suite)


if __name__ == '__main__':
    unittest.main()
