"""Training-only preprocessing and artifact reproducibility; no held-out scoring."""
from hashlib import sha256
import json
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'services/ml-service/training'))
from trace_link_candidate import train, SPLITS


class TraceLinkCandidateTests(unittest.TestCase):
    def test_rebuild_is_byte_exact_and_held_out_seed_cannot_change_the_model(self):
        packaged=ROOT/'services/ml-service/candidate-models/trace-link-shadow-v1'
        with tempfile.TemporaryDirectory() as folder:
            output=Path(folder)/'rebuilt'
            # Verify the documented CLI boundary. Prior model unpickling can
            # change pickle string memoization without changing estimator state.
            subprocess.run([sys.executable,str(ROOT/'services/ml-service/training/trace_link_candidate.py'),
                            '--output',str(output)],check=True,capture_output=True,text=True)
            expected_manifest=json.loads((packaged/'manifest.json').read_text())
            actual_manifest=json.loads((output/'manifest.json').read_text())
            if expected_manifest!=actual_manifest:
                import shutil
                shutil.copytree(output,ROOT/'tmp/day4-rebuild-failed',dirs_exist_ok=True)
                diagnostic=ROOT/'tmp/day4-rebuild-diff.json';diagnostic.parent.mkdir(exist_ok=True)
                diagnostic.write_text(json.dumps({'expected':expected_manifest,'actual':actual_manifest},indent=2))
            for path in packaged.iterdir():
                self.assertEqual(sha256(path.read_bytes()).hexdigest(),sha256((output/path.name).read_bytes()).hexdigest(),path.name)
            original=train(Path(folder)/'baseline-in-process')
            with patch.dict(SPLITS,heldOut=(99991,26,30)):train(Path(folder)/'changed-held-out')
            changed=json.loads((Path(folder)/'changed-held-out/manifest.json').read_text())
            for domain in original['domains']:
                for field in ('modelSha256','calibrationSha256','baselineSha256'):
                    self.assertEqual(original['domains'][domain][field],changed['domains'][domain][field],(domain,field))
            self.assertNotEqual(original['splitManifestSha256'],changed['splitManifestSha256'])
