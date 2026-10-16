"""Release evidence must fail closed; these fixtures are not rehearsal receipts."""
import copy
import importlib.util
import json
from pathlib import Path
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
            'outcomes': {'expected': {'episodes': ['stable'], 'detections': ['stable']},
                         'actual': {'episodes': ['stable'], 'detections': ['stable']}}}
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
        for run in (self.first, self.other): run['outcomes']['actual']['episodes'] = []
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
            run['outcomes'] = {'expected': {'episodes': []}, 'actual': {'episodes': []}}
        with self.assertRaises(ValueError): self.verify()

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


if __name__ == '__main__': unittest.main()
