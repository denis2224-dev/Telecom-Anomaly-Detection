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


from unittest.mock import patch

spec = importlib.util.spec_from_file_location('trace_link_pins', SCRIPT)
pins = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pins)


class TraceLinkPinsTest(unittest.TestCase):
    def verify(self, actual, *, external=False, suffix='.json'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reference = root / 'reference'
            reference.mkdir()
            relative = 'contract' + suffix
            (reference if external else root).joinpath(relative).write_bytes(actual)
            fixture = root / 'contracts/fixtures/trace-link/arithmetic-cases.json'
            fixture.parent.mkdir(parents=True)
            fixture.write_bytes(b'goldens')
            frozen = {relative: hashlib.sha256(b'{"frozen":true}\n').hexdigest()}
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps({'frozenFiles': {} if external else frozen,
                                            'referenceFiles': frozen if external else {},
                                            'fixtureSha256': hashlib.sha256(b'goldens').hexdigest()}))
            with patch.object(pins, 'ROOT', root), patch.object(pins, 'MANIFEST', manifest):
                pins.verify(reference)

    def test_git_json_checkout_crlf_preserves_frozen_content(self):
        self.verify(b'{"frozen":true}\r\n')

    def test_changed_json_even_with_crlf_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Immutable input changed'):
            self.verify(b'{"frozen":false}\r\n')

    def test_external_reference_bytes_are_never_normalized(self):
        with self.assertRaisesRegex(ValueError, 'Immutable input changed'):
            self.verify(b'{"frozen":true}\r\n', external=True)

    def test_binary_frozen_bytes_are_never_normalized(self):
        with self.assertRaisesRegex(ValueError, 'Immutable input changed'):
            self.verify(b'{"frozen":true}\r\n', suffix='.joblib')


if __name__ == '__main__':
    unittest.main()
