"""Exercise native launcher selection and verifier failure propagation."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from publish_trace_link_day2 import java_executable


class VerifierPortabilityTest(unittest.TestCase):
    def test_resolved_native_launcher_can_execute(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / 'bin').mkdir()
            launcher = home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
            # A relocated Windows virtualenv Python launcher needs pyvenv.cfg.
            # Use the platform shell to exercise executable selection independently.
            native = os.environ['COMSPEC'] if os.name == 'nt' else shutil.which('sh')
            shutil.copy2(native, launcher)
            arguments = ['/d', '/c', 'echo native-launcher-ok'] if os.name == 'nt' else [
                '-c', 'printf native-launcher-ok']
            output = subprocess.check_output(
                [java_executable(home), *arguments], text=True)
            self.assertEqual(output.strip(), 'native-launcher-ok')

    def test_missing_native_launcher_fails_before_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'Java launcher missing'):
                java_executable(directory)

    @unittest.skipUnless(shutil.which('pwsh'), 'PowerShell runtime required for wrapper regression')
    def test_day1_invokes_native_wrapper_and_propagates_failure(self):
        source = Path(__file__).resolve().parents[1] / 'scripts/verify-trace-link-day1.ps1'
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            for name in ('scripts', 'tests', 'services/ml-service/tests', 'jdk', 'reference'):
                (repo / name).mkdir(parents=True)
            shutil.copy2(source, repo / 'scripts' / source.name)
            for name in ('verify-trace-link-pins.py', 'check-contracts.py'):
                (repo / 'scripts' / name).write_text('# Successful prerequisite stub\n')
            for name in ('tests', 'services/ml-service/tests'):
                (repo / name / 'test_prerequisite.py').write_text(
                    'import unittest\nclass Prerequisite(unittest.TestCase):\n'
                    '    def test_ready(self):\n        self.assertTrue(True)\n')
            marker = repo / 'wrapper-invoked'
            if os.name == 'nt':
                wrapper = repo / 'mvnw.cmd'
                wrapper.write_text('@echo off\necho invoked>"%~dp0wrapper-invoked"\nexit /b 27\n')
            else:
                wrapper = repo / 'mvnw'
                wrapper.write_text('#!/bin/sh\nprintf invoked > wrapper-invoked\nexit 27\n')
                wrapper.chmod(0o755)
            result = subprocess.run([
                shutil.which('pwsh'), '-NoProfile', '-File', str(repo / 'scripts' / source.name),
                '-Python', sys.executable, '-JavaHome', str(repo / 'jdk'),
                '-ReferenceRoot', str(repo / 'reference')],
                cwd=repo, capture_output=True, text=True, timeout=30)
            self.assertTrue(marker.is_file(), result.stdout + result.stderr)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('failed with exit code 27', result.stdout + result.stderr)
            self.assertFalse((repo / 'services/processor/target/trace-link-java.json').exists())


if __name__ == '__main__':
    unittest.main()
