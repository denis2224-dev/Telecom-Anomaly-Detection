"""Verifier outcomes must distinguish portable checks from Windows reproduction."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('day5_verifier', ROOT/'scripts/verify-trace-link-day5.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class Day5VerifierTests(unittest.TestCase):
    def exercise(self, windows, gate_code=0, failed_step=None, missing_step=None):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)/'run'
            commands = []
            def execute(command, **kwargs):
                commands.append(command)
                if missing_step and missing_step in ' '.join(command):
                    raise FileNotFoundError('Missing prerequisite: '+missing_step)
                if '--output' in command and 'trace_link_evaluation.py' in ' '.join(command):
                    target = Path(command[command.index('--output')+1]); target.mkdir(parents=True)
                    for name in ('results.json', 'predictions.json', 'thresholds.json'):
                        (target/name).write_text('{}')
                if any('windows_candidate_reproduction.py' in item for item in command): code = gate_code
                elif failed_step and failed_step in ' '.join(command): code = 1
                else: code = 0
                return subprocess.CompletedProcess(command, code)
            with patch.object(verifier, 'windows_host', return_value=windows), \
                 patch.object(verifier.subprocess, 'run', side_effect=execute), \
                 patch.object(verifier.subprocess, 'check_output',
                    side_effect=FileNotFoundError('Missing git') if missing_step == 'git' else None,
                    return_value='source-sha\n'):
                code = verifier.verify(output, Path(directory)/'reference')
            return code, json.loads((output/'verification.json').read_bytes()), commands

    def test_linux_pass_is_scoped_and_does_not_run_windows_gate(self):
        code, report, commands = self.exercise(False)
        self.assertEqual(code, 0)
        self.assertEqual(report['status'], 'PASS')
        self.assertEqual(report['verificationScope'], 'PORTABLE_RUNTIME')
        self.assertEqual(report['windowsByteReproduction']['status'], 'NOT_RUN')
        self.assertFalse(any('windows_candidate_reproduction.py' in ' '.join(c) for c in commands))
        self.assertEqual(report['secondPersonReproduction'], 'PENDING')
        self.assertFalse(report['sharedReleaseApproval'])

    def test_windows_runs_required_gate_exactly_once(self):
        code, report, commands = self.exercise(True)
        self.assertEqual(code, 0)
        self.assertEqual(report['verificationScope'], 'WINDOWS_FULL')
        self.assertEqual(report['windowsByteReproduction']['status'], 'PASS')
        self.assertEqual(sum('windows_candidate_reproduction.py' in ' '.join(c) for c in commands), 1)
        self.assertEqual(report['secondPersonReproduction'], 'PENDING')

    def test_failed_windows_gate_cannot_be_hidden_by_passing_portable_suite(self):
        code, report, _ = self.exercise(True, gate_code=1)
        self.assertEqual(code, 1)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertEqual(report['windowsByteReproduction']['status'], 'FAIL')

    def test_linux_applicable_failure_is_incomplete(self):
        code, report, _ = self.exercise(False, failed_step='scripts/check-contracts.py')
        self.assertEqual(code, 1)
        self.assertEqual(report['status'], 'INCOMPLETE')

    def test_missing_prerequisite_preserves_partial_report(self):
        code, report, _ = self.exercise(False, missing_step='mvnw')
        self.assertEqual(code, 1)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertTrue(any('Missing prerequisite' in (step.get('failureReason') or '')
                            for step in report['commands']))

    def test_missing_git_retains_logs_and_incomplete_manifest(self):
        code, report, _ = self.exercise(False, missing_step='git')
        self.assertEqual(code, 1)
        self.assertIsNone(report['sourceRevision'])
        self.assertIn('source-revision.log', report['files'])


class WindowsGateBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        gate_spec = importlib.util.spec_from_file_location('windows_gate',
            ROOT/'services/ml-service/tests/windows_candidate_reproduction.py')
        cls.gate = importlib.util.module_from_spec(gate_spec)
        gate_spec.loader.exec_module(cls.gate)

    def test_non_windows_is_rejected_instead_of_skipped(self):
        with patch.object(self.gate.sys, 'platform', 'linux'):
            with self.assertRaisesRegex(RuntimeError, 'requires Windows'):
                self.gate.require_windows()

    def test_corruption_and_inventory_mismatch_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            packaged, rebuilt = Path(directory)/'packaged', Path(directory)/'rebuilt'
            packaged.mkdir(); rebuilt.mkdir()
            (packaged/'model.joblib').write_bytes(b'frozen')
            (rebuilt/'model.joblib').write_bytes(b'frozen')
            self.gate.assert_artifact_bytes(packaged, rebuilt)
            (rebuilt/'model.joblib').write_bytes(b'corrupt')
            with self.assertRaisesRegex(AssertionError, 'Artifact byte mismatch'):
                self.gate.assert_artifact_bytes(packaged, rebuilt)
            (rebuilt/'model.joblib').unlink()
            with self.assertRaisesRegex(AssertionError, 'inventory mismatch'):
                self.gate.assert_artifact_bytes(packaged, rebuilt)


if __name__ == '__main__': unittest.main()
