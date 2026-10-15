import sys
"""Parity evidence must fail closed when any language or identity is missing."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('trace_link_parity', ROOT / 'scripts/check-trace-link-parity.py')
PARITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PARITY)
FIXTURES = ROOT / 'contracts/fixtures/trace-link/arithmetic-cases.json'


def boundary_cases(suite):
    cases = []
    def altered(operation, name, mutate):
        case = copy.deepcopy(next(c for c in suite['cases'] if c['operation'] == operation and 'expected' in c))
        case['id'] = name
        case.pop('expected')
        case['expectedError'] = 'INVALID_INPUT'
        mutate(case)
        cases.append(case)
    altered('histogram', 'boundary-schema-number', lambda c: c['input']['histograms'][0].update(schemaVersion=1))
    altered('histogram', 'boundary-schema-blank', lambda c: c['input']['histograms'][0].update(schemaVersion=' '))
    altered('roaming_summary', 'boundary-country-invalid', lambda c: c['input'].update(country='invalid'))
    altered('roaming_summary', 'boundary-row-country-invalid', lambda c: c['input']['rows'][0].update(countryId='ro'))
    altered('link_snapshot', 'boundary-same-endpoints', lambda c: c['input']['rows'][0].update(b=c['input']['rows'][0]['a']))
    altered('ratio', 'boundary-year-zero', lambda c: c['window'].update({'from': '0000-01-01T00:00:00Z'}))
    zero = copy.deepcopy(next(c for c in suite['cases'] if c['id'] == 'ratio-zero'))
    zero.update(id='boundary-empty-ratio')
    zero['input']['partitions'] = []
    cases.append(zero)
    return cases


class TraceLinkParityTests(unittest.TestCase):
    def setUp(self):
        (ROOT / 'tmp').mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'tmp', prefix='trace-link-parity-test-')
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.suite, self.digest = PARITY.suite_data(FIXTURES)
        self.envelope = {
            'fixtureSha256': self.digest,
            'contexts': PARITY.contexts(self.suite),
            'results': {c['id']: c.get('expected', {'error': c.get('expectedError')}) for c in self.suite['cases']},
        }
        self.exports = {}
        for language in PARITY.LANGUAGES:
            self.exports[language] = self.directory / (language + '.json')
            self.exports[language].write_text(json.dumps(self.envelope), encoding='utf-8')

    def mutate(self, language, change):
        payload = copy.deepcopy(self.envelope)
        change(payload)
        self.exports[language].write_text(json.dumps(payload), encoding='utf-8')

    def test_javascript_calculates_all_hand_derived_goldens(self):
        target = self.exports['javascript']
        subprocess.run(['node', str(ROOT / 'scripts/trace-link-reference.cjs'), '--fixtures', str(FIXTURES), '--output', str(target)], check=True, capture_output=True, text=True)
        count, digest, errors = PARITY.check_exports(FIXTURES, self.exports)
        self.assertEqual(count, len(self.suite['cases']))
        self.assertEqual(digest, self.digest)
        self.assertLessEqual(errors['javascript'], 1e-9)

    def test_each_language_is_mandatory(self):
        for language in PARITY.LANGUAGES:
            exports = dict(self.exports)
            del exports[language]
            with self.subTest(language=language), self.assertRaisesRegex(ValueError, 'All four'):
                PARITY.check_exports(FIXTURES, exports)

    def test_hash_mismatch_rejects_stale_evidence(self):
        self.mutate('sql', lambda p: p.update(fixtureSha256='0' * 64))
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            PARITY.check_exports(FIXTURES, self.exports)

    def test_case_omission_and_extra_results_rejected(self):
        case_id = self.suite['cases'][0]['id']
        self.mutate('java', lambda p: p['results'].pop(case_id))
        with self.assertRaisesRegex(ValueError, 'keys differ'):
            PARITY.check_exports(FIXTURES, self.exports)
        self.mutate('java', lambda p: p['results'].update(invented={'error': 'INVALID_INPUT'}))
        with self.assertRaisesRegex(ValueError, 'keys differ'):
            PARITY.check_exports(FIXTURES, self.exports)

    def test_scope_window_policy_and_units_are_exact(self):
        case_id = self.suite['cases'][0]['id']
        for field in ('scope', 'window', 'policyVersion', 'units'):
            self.mutate('python', lambda p: p['contexts'][case_id].update({field: 'wrong'}))
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'contexts differ'):
                PARITY.check_exports(FIXTURES, self.exports)

    def test_integer_precision_and_nulls_cannot_be_hidden_by_tolerance(self):
        for expected, actual in [(100, 100.0000000001), (None, 0), (1, True), ('VALID', 'STALE')]:
            with self.subTest(expected=expected, actual=actual), self.assertRaises(ValueError):
                PARITY.compare(expected, actual)
        self.assertLessEqual(PARITY.compare(1.5, 1.5000000001), 1e-9)
        with self.assertRaises(ValueError):
            PARITY.compare(1.5, 1.50000001)

    def test_nonfinite_and_duplicate_json_keys_rejected(self):
        for raw in ['{"value":NaN}', '{"value":Infinity}', '{"value":1,"value":2}']:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                PARITY.decode(raw)

    def test_cli_cannot_report_partial_language_success(self):
        process = subprocess.run([sys.executable, str(ROOT / 'scripts/check-trace-link-parity.py'), '--java-output', str(self.exports['java'])], capture_output=True, text=True)
        self.assertNotEqual(process.returncode, 0)
        self.assertIn('all four', process.stderr)

    def test_javascript_arbitrary_input_boundaries(self):
        cases = boundary_cases(self.suite)
        fixtures = self.directory / 'boundary-cases.json'
        fixtures.write_text(json.dumps({'suiteVersion': 'trace-link-arithmetic-v1', 'cases': cases}), encoding='utf-8')
        target = self.directory / 'boundaries.json'
        subprocess.run(['node', str(ROOT / 'scripts/trace-link-reference.cjs'), '--fixtures', str(fixtures), '--output', str(target)], check=True, capture_output=True, text=True)
        actual = PARITY.decode(target.read_text(encoding='utf-8'))['results']
        expected = {c['id']: c.get('expected', {'error': c.get('expectedError')}) for c in cases}
        PARITY.compare(expected, actual)


if __name__ == '__main__':
    unittest.main()
