import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]


def module(name, file):
    spec=importlib.util.spec_from_file_location(name, ROOT/'scripts'/file)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result)
    return result


class ReferenceImportSafety(unittest.TestCase):
    def test_bounded_slice_is_rejected_before_any_database_command(self):
        importer=module('reference_import','import-trace-link-reference.py')
        with tempfile.TemporaryDirectory() as temporary, patch.object(importer.subprocess,'run') as run:
            with self.assertRaises(ValueError):
                importer.validate(ROOT/'contracts/fixtures/trace-link/native-slice',Path(temporary))
            run.assert_not_called()

    def test_import_sql_is_atomic_locked_conflict_checked_and_does_not_activate(self):
        importer=module('reference_import_sql','import-trace-link-reference.py')
        manifest=json.loads(importer.PIN.read_text())
        sql=importer.import_sql(manifest,'a'*64,"/tmp/source's projection.csv")
        self.assertIn('BEGIN;',sql);self.assertTrue(sql.endswith('COMMIT;\n'))
        self.assertIn('pg_advisory_xact_lock',sql)
        self.assertIn('Conflicting immutable inventory',sql)
        self.assertIn('Unexpected catalogue membership',sql)
        self.assertIn('declared-nominal-unmeasured',sql)
        self.assertIn("source''s projection.csv",sql)
        self.assertNotIn('UPDATE app.trace_catalogue',sql)
        self.assertNotIn('TRUNCATE',sql)

    def test_failed_dump_never_creates_a_success_manifest(self):
        backup=module('reference_backup','backup-trace-release.py')
        with tempfile.TemporaryDirectory() as temporary,patch.object(backup.subprocess,'run',side_effect=subprocess.CalledProcessError(1,['docker'])):
            output=Path(temporary)/'backup'
            with self.assertRaises(subprocess.CalledProcessError):backup.backup('isolated-test',output,['incidents_db'])
            self.assertFalse((output/'backup-manifest.json').exists())

    def test_offline_verification_rejects_changed_or_unsafe_members(self):
        release=module('reference_package','package-trace-release.py')
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary);payload=directory/'source.tar';payload.write_bytes(b'controlled bytes')
            manifest={'files':[{'file':'source.tar','bytes':payload.stat().st_size,'sha256':release.sha(payload)}]}
            (directory/'release-manifest.json').write_text(json.dumps(manifest))
            release.verify(directory)
            payload.write_bytes(b'changed')
            with self.assertRaises(ValueError):release.verify(directory)
            manifest['files'][0]['file']='../source.tar'
            (directory/'release-manifest.json').write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):release.verify(directory)

    def test_offline_packaging_requires_explicit_images_and_never_reports_placeholder_success(self):
        release=module('reference_package_missing','package-trace-release.py')
        with tempfile.TemporaryDirectory() as temporary,patch.object(release,'command') as command:
            output=Path(temporary)/'release'
            with self.assertRaises(ValueError):release.package(Path(temporary),[],output,Path(temporary))
            command.assert_not_called();self.assertFalse(output.exists())


if __name__=='__main__':unittest.main()
