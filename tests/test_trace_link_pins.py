"""Portable reference roots must supply the exact pinned inputs."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/verify-trace-link-pins.py'


class TraceLinkPinTests(unittest.TestCase):
    def setUp(self):
        (ROOT / 'tmp').mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'tmp', prefix='trace-link-pins-')
        self.addCleanup(self.temporary.cleanup)
        base = Path(self.temporary.name)
        self.repo, self.reference = base / 'checkout', base / 'elsewhere' / 'reference'
        self.fixture = self.repo / 'contracts/fixtures/trace-link/arithmetic-cases.json'
        self.fixture.parent.mkdir(parents=True)
        self.fixture.write_bytes(b'golden fixture')
        self.source = self.reference / 'source/engine.js'
        self.source.parent.mkdir(parents=True)
        self.source.write_bytes(b'pinned reference')
        (self.repo / 'frozen.json').write_bytes(b'frozen geography')
        manifest = {
            'frozenFiles': {'frozen.json': hashlib.sha256(b'frozen geography').hexdigest()},
            'referenceFiles': {'source/engine.js': hashlib.sha256(self.source.read_bytes()).hexdigest()},
            'fixtureSha256': hashlib.sha256(self.fixture.read_bytes()).hexdigest(),
        }
        spec = importlib.util.spec_from_file_location('trace_link_pins', SCRIPT)
        self.pins = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.pins)
        self.pins.ROOT = self.repo
        self.pins.MANIFEST = self.repo / 'input-manifest.json'
        self.pins.MANIFEST.write_text(json.dumps(manifest), encoding='utf-8')

    def test_arbitrary_extraction_location_needs_no_author_path(self):
        self.pins.verify(self.reference)

    def test_missing_and_changed_inputs_fail_before_reproduction(self):
        self.source.unlink()
        with self.assertRaisesRegex(ValueError, 'Required pinned input missing'):
            self.pins.verify(self.reference)
        self.source.write_bytes(b'changed reference')
        with self.assertRaisesRegex(ValueError, 'Immutable input changed'):
            self.pins.verify(self.reference)

    def test_changed_golden_fixture_is_rejected(self):
        self.fixture.write_bytes(b'changed fixture')
        with self.assertRaisesRegex(ValueError, 'Golden suite changed'):
            self.pins.verify(self.reference)

    def test_cli_requires_explicit_reference_root(self):
        process = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(process.returncode, 2)
        self.assertIn('--reference-root', process.stderr)


if __name__ == '__main__':
    unittest.main()
