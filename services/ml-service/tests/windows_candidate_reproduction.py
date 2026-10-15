"""Explicit Windows artifact byte gate, outside portable test discovery."""
from hashlib import sha256
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[3]
PACKAGED = ROOT/'services/ml-service/candidate-models/trace-link-shadow-v1'


def require_windows():
    if sys.platform != 'win32':
        raise RuntimeError('Windows artifact byte reproduction requires Windows; use portable checks on this platform')


def assert_artifact_bytes(packaged, rebuilt):
    expected = {p.name for p in packaged.iterdir() if p.is_file()}
    actual = {p.name for p in rebuilt.iterdir() if p.is_file()}
    if expected != actual: raise AssertionError('Artifact file inventory mismatch')
    for name in sorted(expected):
        if sha256((packaged/name).read_bytes()).hexdigest() != sha256((rebuilt/name).read_bytes()).hexdigest():
            raise AssertionError('Artifact byte mismatch: '+name)


class WindowsCandidateReproductionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): require_windows()

    def test_clean_cli_rebuild_matches_every_packaged_byte(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)/'rebuilt'
            subprocess.run([sys.executable,str(ROOT/'services/ml-service/training/trace_link_candidate.py'),
                            '--output',str(output)],check=True,capture_output=True,text=True)
            try:
                self.assertEqual(json.loads((PACKAGED/'manifest.json').read_bytes()),
                                 json.loads((output/'manifest.json').read_bytes()))
                assert_artifact_bytes(PACKAGED,output)
            except AssertionError:
                diagnostic = ROOT/'tmp'/('day5-windows-rebuild-failed-'+uuid4().hex)
                shutil.copytree(output,diagnostic)
                (diagnostic/'comparison.json').write_text(json.dumps(dict(
                    expected=json.loads((PACKAGED/'manifest.json').read_bytes()),
                    actual=json.loads((output/'manifest.json').read_bytes())),indent=2)+'\n',encoding='utf-8')
                raise
            # A corrupted rebuilt copy must fail this same strict comparison.
            target=output/'TRANSPORT.joblib'; target.write_bytes(target.read_bytes()+b'corrupt')
            with self.assertRaisesRegex(AssertionError,'Artifact byte mismatch: TRANSPORT.joblib'):
                assert_artifact_bytes(PACKAGED,output)


if __name__ == '__main__': unittest.main()
