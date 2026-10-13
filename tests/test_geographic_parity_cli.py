"""Geographic acceptance must compare an explicitly selected Java export."""
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


class GeographicParityCliTest(unittest.TestCase):
    def test_both_geographic_modes_require_explicit_export(self):
        for service in ('voice', 'sms'):
            for mode in ('--geographic', '--geographic-persisted'):
                with self.subTest(service=service, mode=mode):
                    result = subprocess.run([sys.executable, str(ROOT / f'scripts/check-{service}-parity.py'), mode],
                                            cwd=ROOT, capture_output=True, text=True)
                    self.assertEqual(result.returncode, 2)
                    self.assertIn('requires an explicit fresh --java-output', result.stderr)
                    self.assertNotIn('PASS', result.stdout)

    def test_geographic_modes_are_mutually_exclusive(self):
        for service in ('voice', 'sms'):
            with self.subTest(service=service):
                result = subprocess.run([sys.executable, str(ROOT / f'scripts/check-{service}-parity.py'),
                                         '--geographic', '--geographic-persisted', '--java-output', 'unused.json'],
                                        cwd=ROOT, capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
                self.assertIn('not allowed with argument', result.stderr)


if __name__ == '__main__':
    unittest.main()
