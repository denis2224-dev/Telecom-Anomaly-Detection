"""Archive and verify retrained defaults before restoring the five frozen Git blobs."""
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def restore():
    freeze = json.loads((ROOT / 'docs/evidence/2026-10-05-sergiu-day1-contract-manifest.json').read_bytes())
    files = [f for f in freeze['unchangedFiles'] if f['path'].startswith('services/ml-service/models/')]
    assert len(files) == 5
    # Resolve every source and check the frozen hashes before touching the workspace.
    blobs = {}
    for item in files:
        raw = subprocess.check_output(['git', 'show', freeze['integrationBase'] + ':' + item['path']], cwd=ROOT)
        assert sha256(raw).hexdigest() == item['gitBlobSha256'], item['path']
        assert sha256(raw if item['normalization'] == 'BINARY' else raw.replace(b'\r\n', b'\n')).hexdigest() == item['normalizedSha256']
        blobs[item['path']] = raw
    manifest_hash = json.loads((ROOT / 'services/ml-service/models/manifest.json').read_bytes())['datasetManifestSha256']
    candidates = list((ROOT / 'tmp').glob('*/split_manifest.json'))
    training = [p for p in candidates if sha256(p.read_bytes()).hexdigest() == manifest_hash]
    if len(training) != 1:
        raise ValueError('Require exactly one verified retraining dataset manifest')
    archive = ROOT / 'tmp/sms-ml-shadow-archives' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    archive.mkdir(parents=True, exist_ok=False)
    recorded = []
    for source in [ROOT / f['path'] for f in files] + training:
        raw = source.read_bytes()
        destination = archive / source.name
        destination.write_bytes(raw)
        assert destination.read_bytes() == raw
        recorded.append(dict(source=source.relative_to(ROOT).as_posix(), archive=destination.relative_to(ROOT).as_posix(),
                             rawSha256=sha256(raw).hexdigest(), normalizedSha256=sha256(raw.replace(b'\r\n', b'\n') if source.suffix == '.json' else raw).hexdigest()))
    report = dict(archiveVerified=True, sourceCommit=freeze['integrationBase'], files=recorded,
                  restored=[dict(path=f['path'], sha256=sha256(blobs[f['path']]).hexdigest()) for f in files])
    (archive / 'archive-manifest.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    for name, raw in blobs.items():
        (ROOT / name).write_bytes(raw)
        assert (ROOT / name).read_bytes() == raw
    with (ROOT / 'tasks/sms-ml-shadow-integration/restoration.json').open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(report, indent=2) + '\n')
    print('Verified archive and restored five frozen defaults:', archive)


if __name__ == '__main__':
    restore()
