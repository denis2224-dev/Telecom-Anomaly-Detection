"""Unit tests for demo backup and restore tooling."""

from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

from importlib.machinery import SourceFileLoader

BACKUP_MOD = SourceFileLoader("backup_demo", str(ROOT / "scripts/backup-demo")).load_module()
RESTORE_MOD = SourceFileLoader("restore_demo", str(ROOT / "scripts/restore-demo")).load_module()


class BackupRestoreTests(unittest.TestCase):
    def setUp(self):
        self.tmpdir = Path(tempfile.mkdtemp(prefix="test_backup_restore_"))

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_backup_creates_manifest_and_sha256(self):
        output_dir = self.tmpdir / "backup"
        manifest = BACKUP_MOD.create_backup(
            output_dir=output_dir,
            databases=["processing_db", "incidents_db"],
            root_dir=ROOT,
            dry_run=True,
        )

        manifest_file = output_dir / "backup-manifest.json"
        self.assertTrue(manifest_file.exists())
        self.assertEqual(manifest["datasetId"], "trace-link-md-30d-v1")
        self.assertTrue(manifest["secretsStoredSeparately"])
        self.assertIn("processing_db", manifest["databases"])
        self.assertIn("incidents_db", manifest["databases"])

        # Check dump files exist and match hashes
        for db, meta in manifest["databases"].items():
            dump_file = output_dir / meta["filename"]
            self.assertTrue(dump_file.exists())
            self.assertEqual(BACKUP_MOD.compute_sha256(dump_file), meta["sha256"])

    def test_restore_verify_only_succeeds(self):
        output_dir = self.tmpdir / "backup"
        BACKUP_MOD.create_backup(
            output_dir=output_dir,
            databases=["processing_db"],
            root_dir=ROOT,
            dry_run=True,
        )

        # verify_only should return True
        self.assertTrue(RESTORE_MOD.restore_backup(output_dir, verify_only=True))

    def test_restore_detects_tampered_dump(self):
        output_dir = self.tmpdir / "backup"
        manifest = BACKUP_MOD.create_backup(
            output_dir=output_dir,
            databases=["processing_db"],
            root_dir=ROOT,
            dry_run=True,
        )

        # Tamper with the dump file
        dump_file = output_dir / manifest["databases"]["processing_db"]["filename"]
        dump_file.write_text("TAMPERED CONTENT\n", encoding="utf-8")

        with self.assertRaises(ValueError) as ctx:
            RESTORE_MOD.restore_backup(output_dir, verify_only=True)
        self.assertIn("Checksum mismatch", str(ctx.exception))

    def test_restore_refuses_overwrite_without_prefix(self):
        output_dir = self.tmpdir / "backup"
        BACKUP_MOD.create_backup(
            output_dir=output_dir,
            databases=["processing_db"],
            root_dir=ROOT,
            dry_run=True,
        )

        with self.assertRaises(PermissionError) as ctx:
            RESTORE_MOD.restore_backup(
                output_dir,
                target_prefix="",
                dry_run=True,
                allow_overwrite_source=False,
            )
        self.assertIn("Refusing to restore over source database volumes", str(ctx.exception))

    def test_restore_dry_run_with_isolated_prefix(self):
        output_dir = self.tmpdir / "backup"
        BACKUP_MOD.create_backup(
            output_dir=output_dir,
            databases=["processing_db", "incidents_db"],
            root_dir=ROOT,
            dry_run=True,
        )

        # Dry run with isolated_ prefix should succeed
        success = RESTORE_MOD.restore_backup(
            output_dir,
            target_prefix="isolated_",
            dry_run=True,
        )
        self.assertTrue(success)


if __name__ == "__main__":
    unittest.main()
