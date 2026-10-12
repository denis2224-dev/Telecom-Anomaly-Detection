"""Assemble receiving closure evidence only after the complete entrypoint succeeds."""
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path.cwd()
WORK = ROOT / 'tmp/trace-link-day2/receiving-closure'
OUT = ROOT / 'docs/evidence/assets/trace-link-day2-receiving-closure'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(name, value):
    # Explicit LF makes these newly published JSON bytes portable.
    (OUT / name).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8', newline='\n')


execution = read(WORK / 'execution.json')
assert execution['exitCode'] == 0 and execution['finishedAtUtc'], execution
manifest = read(OUT / 'manifest.json')
assert execution['head'] == manifest['implementationRevision']
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() == execution['head']
subprocess.run(['git', 'diff', '--exit-code', execution['head'], '--', *manifest['sourceHashes']], check=True)
started = datetime.fromisoformat(execution['startedAtUtc'].replace('Z', '+00:00')).timestamp()
before = read(WORK / 'input-hashes-before.json')
original = ROOT / 'docs/evidence/assets/trace-link-day2'
after = {p.name: digest(p) for p in sorted(original.iterdir()) if p.is_file()}
assert before['originalPublication'] == after, 'Original publication changed'
for name, expected in manifest['sourceHashes'].items():
    assert digest(ROOT / name) == expected, name
for name, expected in manifest['artifacts'].items():
    assert digest(OUT / name) == expected, name
frozen = read(OUT / 'frozen-hashes.json')
for name, expected in frozen['files'].items():
    assert digest(ROOT / name) == expected, name

fresh = {
    'arithmetic-java.json': 'services/processor/target/trace-link-java.json',
    'arithmetic-python.json': 'tmp/trace-link-day1-reproduction/python.json',
    'arithmetic-javascript.json': 'tmp/trace-link-day1-reproduction/javascript.json',
    'arithmetic-sql.json': 'tmp/trace-link-day1-reproduction/sql.json',
    'legacy-voice-parity.json': 'services/processor/target/voice-parity-java.json',
    'legacy-sms-parity.json': 'services/processor/target/sms-parity-java.json',
    'geographic-voice-parity.json': 'services/processor/target/geographic-voice-parity-java.json',
    'geographic-sms-parity.json': 'services/processor/target/geographic-sms-parity-java.json',
    'baseline-java-parity.json': 'services/processor/target/trace-link-baseline-java-parity.json',
    'history-1.json': 'tmp/trace-link-day2/reproduction/history-1.json',
    'history-2.json': 'tmp/trace-link-day2/reproduction/history-2.json',
    'run.json': 'tmp/trace-link-day2/reproduction/run.json',
}
freshness = {}
for name, source in fresh.items():
    path = ROOT / source
    assert path.stat().st_mtime >= started, f'Stale: {source}'
    shutil.copyfile(path, OUT / name)
    freshness[name] = {'source': source, 'sourceModifiedAtUtc': datetime.fromtimestamp(
        path.stat().st_mtime, timezone.utc).isoformat(), 'sha256': digest(path)}
histories = [ROOT / f'tmp/trace-link-day2/reproduction/baseline-{n}.json.gz' for n in (1, 2)]
assert all(p.stat().st_mtime >= started for p in histories)
history_hashes = [digest(p) for p in histories]
assert history_hashes[0] == history_hashes[1] == digest(OUT / 'seasonal-baseline.json.gz')

reports = []
totals = dict(tests=0, errors=0, failures=0, skipped=0)
trace_totals = dict(tests=0, errors=0, failures=0, skipped=0)
for module in ('streaming-support', 'event-generator', 'processor'):
    for path in sorted((ROOT / f'services/{module}/target/surefire-reports').glob('TEST-*.xml')):
        if path.stat().st_mtime < started:
            continue
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.attrib[key])
            if suite.attrib['name'].rsplit('.', 1)[-1].startswith('TraceLink'):
                trace_totals[key] += int(suite.attrib[key])
        reports.append(path)
assert totals == manifest['javaTests'], (totals, manifest['javaTests'])
assert trace_totals == dict(tests=38, errors=0, failures=0, skipped=0), trace_totals
with zipfile.ZipFile(OUT / 'surefire-reports.zip', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for path in reports:
        archive.write(path, path.relative_to(ROOT).as_posix())
log = WORK / 'complete-entrypoint.log'
log_text = log.read_text(encoding='utf-8-sig')
assert 'PASS: Day 2 offline reproduction' in log_text
(OUT / 'complete-entrypoint.log.gz').write_bytes(gzip.compress(log.read_bytes(), mtime=0))
save('execution.json', execution)
save('additional-runtimes.json', read(WORK / 'additional-runtimes.json'))
save('original-input-hashes.json', before)
# Keep Git blob hashes distinct from Windows working-byte hashes in manifest.json.
blob_hashes = {name: hashlib.sha256(subprocess.check_output([
    'git', 'show', f"{execution['head']}:{name}"], cwd=ROOT)).hexdigest()
    for name in manifest['sourceHashes']}
save('source-git-blob-hashes.json', {'revision': execution['head'], 'files': blob_hashes,
    'note': 'Git blob bytes; separate from raw Windows working-byte sourceHashes in manifest.json'})
if Path(__file__).resolve() != (OUT / 'evidence-assembly.py').resolve():
    shutil.copyfile(Path(__file__), OUT / 'evidence-assembly.py')
shutil.copyfile(WORK / '0001-native-runtime-launchers.patch', OUT / 'ion-native-launchers.patch')
(OUT / 'corrected-native-launchers.patch').write_bytes(subprocess.check_output([
    'git', 'format-patch', '-1', '--stdout', execution['head']], cwd=ROOT))
(OUT / '.gitattributes').write_text('* -text\n', encoding='utf-8', newline='\n')
save('closure.json', {
    'schemaVersion': 'trace-link-day2-receiving-closure-v1',
    'technicalReproduction': 'PASSED', 'receivingOwnerDecision': 'PENDING_ION_REVIEW',
    'activation': 'OFFLINE_ONLY', 'testedRevision': execution['head'],
    'originalReviewedHead': '55bb638dbb2b941e53e13edc068c80f23b3ce7c9',
    'ionLocalPatchCommit': '3b4a4cc0dc12f7b67020066c5d49bf02b23e9307',
    'ionPatchSha256': digest(WORK / '0001-native-runtime-launchers.patch'),
    'ionReportedMain': 'cbb94d37f7593e99737ee4200e91b14e0512f76f',
    'execution': execution, 'pythonTests': manifest['run']['pythonTests'],
    'javaTests': totals, 'mandatoryTraceLinkJavaTests': trace_totals,
    'javaSkippedTests': manifest['javaSkippedTests'],
    'originalPublicationUnchanged': True, 'originalInputHashes': before,
    'frozenFilesVerified': len(frozen['files']), 'sourceHashesVerified': len(manifest['sourceHashes']),
    'historyBuildSha256': history_hashes, 'freshFiles': freshness,
    'surefireReportCount': len(reports), 'fullLogRawSha256': digest(log),
    'artifactHashes': {p.name: digest(p) for p in sorted(OUT.iterdir()) if p.is_file() and p.name != 'closure.json'},
    'limitations': ['Ion receiving report remains BLOCKED pending his review of this new proof',
        'Local Windows reproduction by a separate execution agent; no independent external receiver acceptance',
        'Manifest commands are reproduction recipes; execution.json records the actual full command',
        'Original raw LF/CRLF hash mismatches remain documented; historical manifest unchanged',
        'Day 3 is unimplemented; no push, PR merge, deployment or live dispatch performed'],
})
print(json.dumps({'tests': totals, 'mandatory': trace_totals, 'sourceHashes': len(blob_hashes),
    'freshFiles': len(fresh), 'reports': len(reports), 'output': str(OUT)}))
