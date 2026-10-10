"""Check immutable V2 and external reference hashes without changing either source."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'docs/evidence/assets/trace-link-day1/input-manifest.json'


def verify(reference_root=None):
    pinned = json.loads(MANIFEST.read_text(encoding='utf-8'))
    reference = Path(reference_root or pinned['referenceRoot'])
    checked = 0
    for base, name in [(ROOT, 'frozenFiles'), (reference, 'referenceFiles')]:
        for relative, expected in pinned[name].items():
            actual = hashlib.sha256((base / relative).read_bytes()).hexdigest()
            if actual != expected:
                raise ValueError(f'Immutable input changed: {relative}')
            checked += 1
    actual_fixture = hashlib.sha256((ROOT / 'contracts/fixtures/trace-link/arithmetic-cases.json').read_bytes()).hexdigest()
    if actual_fixture != pinned['fixtureSha256']:
        raise ValueError('Golden suite changed after input freeze')
    print(f'PASS: {checked} frozen V2/reference files and independent fixture hash unchanged')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference-root', type=Path)
    verify(parser.parse_args().reference_root)
