"""Release evidence must fail closed; these fixtures are not rehearsal receipts."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('day6', ROOT/'scripts/verify-sergiu-day6.py')
day6 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(day6)


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root/'artifact').write_bytes(b'frozen')
        self.manifest = {'files': {'artifact': day6.digest(self.root/'artifact')}}
        self.release = self.root/'release.json'
        self.release.write_text(json.dumps({'releaseId': 'test-only', 'detectionInputs': self.manifest['files']}))
        self.first = {
            'runId': 'one', 'mode': 'CONNECTED', 'releaseSha256': day6.digest(self.release),
            'startedAt': '2026-10-15T10:00:00Z', 'finishedAt': '2026-10-15T10:01:00Z',
            'latencyMs': 10, 'startingStateSha256': 'a'*64,
            'gates': {name: {'status': 'PASS', 'evidenceFile': 'artifact'} for name in day6.GATES},
            'commands': [{'argv': ['test'], 'exitCode': 0}],
            'files': {'artifact': day6.digest(self.root/'artifact')},
            'traces': [{'displayId': name, 'datasetId': 'synthetic', 'windowId': 'w',
                        'primitive': {'numerator': 9, 'denominator': 10}, 'unit': 'PERCENT',
                        'baselineVersion': 'b', 'policyVersion': 'p',
                        'detectionId': 'd', 'incidentEvidenceId': 'e',
                        'evidenceFile': 'artifact'} for name in day6.DOMAINS],
            'outcomes': {}}
        outcomes = {'counts': {'episodes': 1, 'detections': 1, 'duplicates': 0},
                    'episodeIds': ['episode-1'], 'detectionIds': ['detection-1'],
                    'controls': {'healthy': 'NO_DETECTION'},
                    'recovery': {'transport': 'TECHNICALLY_RECOVERED'},
                    'replay': {'restart': 'IDENTITIES_UNCHANGED'}}
        self.first['outcomes'] = {'expected': outcomes, 'actual': copy.deepcopy(outcomes)}
        self.other = copy.deepcopy(self.first)
        self.other.update(runId='two', startedAt='2026-10-15T11:00:00Z',
                          finishedAt='2026-10-15T11:01:00Z', latencyMs=25)
        self.release.write_text(json.dumps({'detectionInputs': self.manifest['files'],
            'detectionRehearsal': {'startingStateSha256': self.first['startingStateSha256'],
                                  'expectedOutcomes': self.first['outcomes']['expected']}}))
        for run in (self.first, self.other): run['releaseSha256'] = day6.digest(self.release)

    def verify(self):
        return day6.verify_rehearsals(self.root, self.manifest, self.release,
                                     [self.first, self.other])

    def approve_outcomes(self, expected, actual=None):
        shared = json.loads(self.release.read_bytes())
        shared['detectionRehearsal']['expectedOutcomes'] = expected
        self.release.write_text(json.dumps(shared))
        for run in (self.first, self.other):
            run['releaseSha256'] = day6.digest(self.release)
            run['outcomes'] = {'expected': copy.deepcopy(expected),
                               'actual': copy.deepcopy(expected if actual is None else actual)}

    def cli(self):
        scripts = self.root/'scripts'
        scripts.mkdir(exist_ok=True)
        shutil.copyfile(ROOT/'scripts/verify-sergiu-day6.py', scripts/'verify-sergiu-day6.py')
        inputs = [self.manifest, self.first, self.other]
        paths = [self.root/name for name in ('manifest.json', 'one.json', 'two.json')]
        for path, value in zip(paths, inputs):
            path.write_text(json.dumps(value))
        output = self.root/'verification.json'
        result = subprocess.run([sys.executable, str(scripts/'verify-sergiu-day6.py'),
            '--manifest', str(paths[0]), '--release', str(self.release),
            '--run', str(paths[1]), '--run', str(paths[2]), '--output', str(output)],
            text=True, capture_output=True)
        self.addCleanup(output.unlink, missing_ok=True)
        return result, output

    def test_matching_results_allow_real_timing_differences_without_human_approval(self):
        result = self.verify()
        self.assertEqual(result['technicalStatus'], 'PASS')
        self.assertEqual(result['humanAcceptance'], 'PENDING')
        self.assertFalse(result['sharedReleaseApproval'])

    def test_changed_or_missing_artifact_rejected(self):
        (self.root/'artifact').write_bytes(b'changed')
        with self.assertRaises(ValueError): self.verify()
        (self.root/'artifact').unlink()
        with self.assertRaises(ValueError): self.verify()

    def test_incomplete_or_mismatched_runs_rejected(self):
        for field, value in [('releaseSha256', 'b'*64), ('mode', 'OFFLINE'),
                             ('runId', 'one'), ('startingStateSha256', 'b'*64),
                             ('traces', []), ('commands', []), ('files', {}),
                             ('outcomes', {'episodes': [], 'detections': []})]:
            with self.subTest(field=field):
                saved = self.other
                self.other = copy.deepcopy(saved); self.other[field] = value
                with self.assertRaises(ValueError): self.verify()
                self.other = saved

    def test_failed_command_or_required_gate_rejected(self):
        self.other['commands'][0]['exitCode'] = 1
        with self.assertRaises(ValueError): self.verify()
        self.other['commands'][0]['exitCode'] = 0
        self.other['gates'].pop(day6.GATES[0])
        with self.assertRaises(ValueError): self.verify()

    def test_missing_run_rejected(self):
        with self.assertRaises(ValueError):
            day6.verify_rehearsals(self.root, self.manifest, self.release, [self.first])

    def test_healthy_trace_requires_reason_for_absent_detection(self):
        trace = self.first['traces'][0]
        trace['detectionId'] = None; trace['incidentEvidenceId'] = None
        with self.assertRaises(ValueError): self.verify()
        trace['absenceReason'] = 'HEALTHY'
        self.verify()

    def test_paths_cannot_escape_evidence_root(self):
        self.manifest['files'] = {'../outside': 'a'*64}
        with self.assertRaises(ValueError): self.verify()

    def test_nonfinite_latency_rejected(self):
        self.other['latencyMs'] = float('nan')
        with self.assertRaises(ValueError): self.verify()

    def test_identical_but_unexpected_outcomes_rejected(self):
        for run in (self.first, self.other):
            run['outcomes']['actual']['episodeIds'] = ['different-episode']
        with self.assertRaises(ValueError): self.verify()

    def test_release_must_pin_detection_inputs(self):
        self.release.write_text('{}')
        for run in (self.first, self.other): run['releaseSha256'] = day6.digest(self.release)
        with self.assertRaises(ValueError): self.verify()

    def test_gate_evidence_must_be_pinned(self):
        self.other['gates']['positive']['evidenceFile'] = 'unrecorded'
        with self.assertRaises(ValueError): self.verify()

    def test_runs_cannot_redefine_release_expected_outcomes(self):
        for run in (self.first, self.other):
            for value in run['outcomes'].values(): value['episodeIds'] = ['unapproved-episode']
        with self.assertRaisesRegex(ValueError, 'approved expected outcomes'): self.verify()

    def test_nonfinite_outcome_rejected(self):
        for run in (self.first, self.other):
            run['outcomes']['actual']['delay'] = float('nan')
        with self.assertRaises(ValueError): self.verify()

    def test_boolean_latency_is_not_a_measurement(self):
        self.other['latencyMs'] = True
        with self.assertRaises(ValueError): self.verify()

    def test_boolean_exit_code_cannot_claim_success(self):
        self.other['commands'][0]['exitCode'] = False
        with self.assertRaises(ValueError): self.verify()

    def test_command_requires_an_argument_array(self):
        self.other['commands'][0]['argv'] = 'echo success'
        with self.assertRaises(ValueError): self.verify()

    def test_command_preserves_valid_empty_arguments(self):
        self.other['commands'][0]['argv'] = ['test', '--optional-value', '']
        self.verify()
        self.other['commands'][0]['argv'][0] = ''
        with self.assertRaises(ValueError): self.verify()

    def test_scalar_outcomes_rejected_even_when_release_agrees(self):
        self.approve_outcomes('PASS')
        with self.assertRaisesRegex(ValueError, 'outcomes'):
            self.verify()

    def test_incomplete_outcomes_rejected_even_when_release_agrees(self):
        self.approve_outcomes({'detections': 1, 'duplicates': 0})
        with self.assertRaisesRegex(ValueError, 'outcomes'):
            self.verify()

    def test_boolean_counts_cannot_equal_integer_counts(self):
        actual = copy.deepcopy(self.first['outcomes']['expected'])
        actual['counts'].update(detections=True, duplicates=False)
        self.approve_outcomes(self.first['outcomes']['expected'], actual)
        with self.assertRaisesRegex(ValueError, 'counts'):
            self.verify()

    def test_invalid_outcome_fields_rejected_even_when_release_agrees(self):
        valid = copy.deepcopy(self.first['outcomes']['expected'])
        for field, value in [('counts', {'episodes': 1, 'detections': 1, 'duplicates': False}),
                             ('counts', {'episodes': 1, 'detections': 1.0, 'duplicates': 0}),
                             ('counts', {'episodes': 1, 'detections': -1, 'duplicates': 0}),
                             ('counts', {'episodes': 1, 'detections': 1}),
                             ('episodeIds', 'episode-1'), ('episodeIds', []),
                             ('episodeIds', ['episode-1', 'episode-1']),
                             ('detectionIds', [True]), ('controls', {}),
                             ('controls', {'healthy': True}), ('recovery', None),
                             ('replay', {'restart': ''})]:
            with self.subTest(field=field, value=value):
                invalid = copy.deepcopy(valid); invalid[field] = value
                self.approve_outcomes(invalid)
                with self.assertRaises(ValueError): self.verify()

    def test_each_outcome_field_is_required_in_manifest_expected_and_actual(self):
        valid = copy.deepcopy(self.first['outcomes']['expected'])
        for field in valid:
            for location in ('manifest', 'expected', 'actual'):
                with self.subTest(field=field, location=location):
                    self.approve_outcomes(valid)
                    if location == 'manifest':
                        incomplete = copy.deepcopy(valid); incomplete.pop(field)
                        self.approve_outcomes(incomplete)
                    else:
                        self.other['outcomes'][location].pop(field)
                    with self.assertRaises(ValueError): self.verify()

    def test_cli_invalid_top_level_structures_produce_incomplete_reports(self):
        manifest = self.manifest
        other = self.other
        release = self.release.read_bytes()
        for location in ('manifest', 'release', 'scenario', 'run'):
            with self.subTest(location=location):
                self.manifest = manifest; self.other = copy.deepcopy(other)
                self.release.write_bytes(release)
                if location == 'manifest': self.manifest = None
                elif location == 'release': self.release.write_text('null')
                elif location == 'scenario':
                    shared = json.loads(release); shared['detectionRehearsal'] = None
                    self.release.write_text(json.dumps(shared))
                else: self.other = None
                result, output = self.cli()
                self.assertEqual(result.returncode, 1, result.stderr)
                report = json.loads(output.read_text())
                self.assertEqual(report['technicalStatus'], 'INCOMPLETE')
                self.assertTrue(report['reason'])
                self.assertNotIn('Traceback', result.stderr)
                output.unlink()

    def test_cli_invalid_receipts_produce_incomplete_reports(self):
        valid = copy.deepcopy(self.other)
        for field, value in [('traces', [None]), ('traces', {}),
                             ('startedAt', None), ('finishedAt', 10),
                             ('gates', None), ('outcomes', None)]:
            with self.subTest(field=field, value=value):
                self.other = copy.deepcopy(valid); self.other[field] = value
                result, output = self.cli()
                self.assertEqual(result.returncode, 1, result.stderr)
                report = json.loads(output.read_text())
                self.assertEqual(report['technicalStatus'], 'INCOMPLETE')
                self.assertTrue(report['reason'])
                self.assertEqual(report['humanAcceptance'], 'PENDING')
                self.assertFalse(report['sharedReleaseApproval'])
                self.assertNotIn('Traceback', result.stderr)
                output.unlink()

    def test_cli_matching_receipts_produce_pass_report(self):
        result, output = self.cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(output.read_text())
        self.assertEqual(report['technicalStatus'], 'PASS')
        self.assertEqual(report['humanAcceptance'], 'PENDING')
        self.assertFalse(report['sharedReleaseApproval'])


if __name__ == '__main__': unittest.main()
