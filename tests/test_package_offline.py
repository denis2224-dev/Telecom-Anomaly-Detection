"""Unit tests for offline packaging script."""

from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from importlib.machinery import SourceFileLoader
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PKG_MOD = SourceFileLoader("package_offline", str(ROOT / "scripts/package-offline")).load_module()


class PackageOfflineTests(unittest.TestCase):
    def setUp(self):
        self.tmpdir = Path(tempfile.mkdtemp(prefix="test_pkg_offline_"))

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_package_offline_dry_run_creates_manifest(self):
        output_dir = self.tmpdir / "pkg"
        manifest = PKG_MOD.package_offline(
            output_dir=output_dir,
            root_dir=ROOT,
            dry_run=True,
        )

        self.assertEqual(manifest["releaseName"], "trace-link-release-candidate")
        self.assertEqual(manifest["version"], "1.0.0")
        self.assertEqual(manifest["dataset"]["id"], "trace-link-md-30d-v1")
        self.assertTrue(manifest["secretIsolationVerified"])

        # Check required component entries exist in manifest
        expected_components = [
            "contracts",
            "infra_postgres_init",
            "infra_observability",
            "infra_nginx",
            "infra_keycloak_realm",
            "runbooks",
            "compose_config",
            "env_example",
        ]
        for comp in expected_components:
            self.assertIn(comp, manifest["components"])

    def test_compute_sha256(self):
        test_file = self.tmpdir / "test.txt"
        test_file.write_text("hello trace link\n", encoding="utf-8")
        sha = PKG_MOD.compute_sha256(test_file)
        self.assertEqual(len(sha), 64)


if __name__ == "__main__":
    unittest.main()
