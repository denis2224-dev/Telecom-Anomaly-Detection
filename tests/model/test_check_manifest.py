import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

CHECKER = Path(__file__).resolve().parents[2] / "infra" / "model" / "check-manifest.py"
spec = importlib.util.spec_from_file_location("check_manifest", CHECKER)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
validate_manifest = module.validate_manifest


class ModelManifestTests(unittest.TestCase):
    def write_case(self, artifact: bytes, feature_version: str = "1") -> Path:
        directory = Path(tempfile.mkdtemp())
        artifact_path = directory / "model.bin"
        artifact_path.write_bytes(artifact)
        manifest_path = directory / "manifest.json"
        manifest_path.write_text(json.dumps({
            "manifestVersion": "1",
            "modelVersion": "test-model",
            "featureVersion": feature_version,
            "artifact": {
                "path": str(artifact_path),
                "sha256": hashlib.sha256(artifact).hexdigest(),
            },
        }))
        return manifest_path

    def test_approved_artifact_is_ready(self):
        manifest = self.write_case(b"approved")
        self.assertEqual(validate_manifest(manifest, "1")[0], True)

    def test_corrupt_artifact_is_not_ready(self):
        manifest = self.write_case(b"approved")
        artifact = Path(json.loads(manifest.read_text())["artifact"]["path"])
        artifact.write_bytes(b"corrupt")
        ready, detail = validate_manifest(manifest, "1")
        self.assertFalse(ready)
        self.assertIn("checksum mismatch", detail)

    def test_feature_mismatch_is_not_ready(self):
        manifest = self.write_case(b"approved", feature_version="2")
        ready, detail = validate_manifest(manifest, "1")
        self.assertFalse(ready)
        self.assertIn("feature version mismatch", detail)
