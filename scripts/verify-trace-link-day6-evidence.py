"""Verify archived Day 6 evidence bytes, both independent runs and source pins."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'docs/evidence/assets/trace-link-day6'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    manifest = json.loads((ASSETS / 'artifact-manifest.json').read_text(encoding='utf-8'))
    actual = {p.relative_to(ASSETS).as_posix(): sha(p) for p in ASSETS.rglob('*')
              if p.is_file() and p.name != 'artifact-manifest.json'}
    if actual != manifest['files']:
        raise ValueError('Evidence files missing, added or changed')
    rehearsals = json.loads((ASSETS / 'rehearsals.json').read_text(encoding='utf-8'))
    for number in (1, 2):
        run = ASSETS / f'run-{number}'
        found = {p.relative_to(run).as_posix(): sha(p) for p in run.rglob('*') if p.is_file()}
        if found != rehearsals['artifactHashes']:
            raise ValueError(f'Run {number} does not match the frozen rehearsal hashes')
    config = ROOT / 'contracts/fixtures/demo/service-seeds.json'
    if sha(config) != rehearsals['configurationSha256']:
        raise ValueError('Pinned demo configuration changed')
    for relative, expected in rehearsals['gitContentPins'].items():
        blob = subprocess.check_output(['git', 'show', 'HEAD:' + relative], cwd=ROOT)
        if hashlib.sha256(blob).hexdigest() != expected:
            raise ValueError('Pinned source Git content changed: ' + relative)
    verification = json.loads((ASSETS / 'verification.json').read_text(encoding='utf-8'))
    reactor = verification['producerReactor']
    if reactor['failures'] or reactor['errors'] or verification['producerPython']['failures']:
        raise ValueError('Producer verification failed')
    print(f"PASS: {len(actual)} immutable evidence files; both rehearsal hashes and source/configuration pins match")


if __name__ == '__main__':
    main()
