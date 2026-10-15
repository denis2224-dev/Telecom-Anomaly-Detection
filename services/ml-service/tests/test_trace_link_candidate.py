"""Portable training-only leakage checks; no held-out scoring."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'services/ml-service/training'))
from trace_link_candidate import train, SPLITS


class TraceLinkCandidateTests(unittest.TestCase):
    def test_held_out_seed_cannot_change_model_calibration_or_baselines(self):
        with tempfile.TemporaryDirectory() as folder:
            original=train(Path(folder)/'baseline-in-process')
            with patch.dict(SPLITS,heldOut=(99991,26,30)):train(Path(folder)/'changed-held-out')
            changed=json.loads((Path(folder)/'changed-held-out/manifest.json').read_text())
            for domain in original['domains']:
                for field in ('modelSha256','calibrationSha256','baselineSha256'):
                    self.assertEqual(original['domains'][domain][field],changed['domains'][domain][field],(domain,field))
            self.assertNotEqual(original['splitManifestSha256'],changed['splitManifestSha256'])
