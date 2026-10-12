"""Frozen Git JSON accepts checkout newline conversion, never changed evidence."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/verify-trace-link-pins.py'
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
