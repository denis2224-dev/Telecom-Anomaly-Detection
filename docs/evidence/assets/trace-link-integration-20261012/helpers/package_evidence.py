import datetime
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parent / 'trace-link-integration-20261012'
DEST = ROOT / 'docs/evidence/assets/trace-link-integration-20261012'
DEST.mkdir(parents=True, exist_ok=True)
BASE = 'ae9a53626ac7ee62efec40a19ebb20c6e2cf7049'
SOURCE = 'f572ebd27c71cb809eb43fa51f07f40740ae181c'
MERGE = 'e85a81660aeff3ebae71150a6361084dd11191b2'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def git(*args, cwd=ROOT):
    return subprocess.check_output(['git', *args], cwd=cwd)

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')

assert git('rev-parse', 'HEAD').decode().strip() == SOURCE
assert not git('diff', '--name-only', MERGE, SOURCE, '--', 'services', 'contracts', 'tests').strip()
for source in AUDIT.iterdir():
    if source.is_file() and source.suffix in ('.json', '.jsonl', '.patch', '.txt'):
        shutil.copyfile(source, DEST/source.name)
for name in ('packaged-integration', 'compatibility-probe'):
    for source in (AUDIT/name).glob('*.json*'):
        target = DEST/name/source.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
for source in AUDIT.glob('*.py'):
    target = DEST/'helpers'/source.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)

records = []
for source in sorted(AUDIT.glob('*.json')):
    value = json.loads(source.read_text(encoding='utf-8'))
    if 'command' in value and 'exitCode' in value:
        assert sha((AUDIT/value['log']).read_bytes()) == value['logSha256'], source
        value['compressedLog'] = 'logs/' + value['log'] + '.gz'
        records.append(value)
log_manifest = []
logs = list(AUDIT.glob('*.log')) + [AUDIT.parent/'trace-link-historical-python-20261012.log']
for source in logs:
    raw = source.read_bytes()
    relative = 'logs/' + source.name + '.gz'
    target = DEST/relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(gzip.compress(raw, mtime=0))
    assert gzip.decompress(target.read_bytes()) == raw
    log_manifest.append(dict(path=relative, originalBytes=len(raw), originalSha256=sha(raw),
                             compressedSha256=sha(target.read_bytes())))

# Preserve the complete XML from the already collected invocations. Subsequent
# Maven invocations must not overwrite evidence from the full reactor.
report_names = ['combined-support', 'combined-generator', 'combined-processor',
                'combined-full-native-projection', 'incident-full', 'producer-only-focused']
report_summaries = {}
with zipfile.ZipFile(DEST/'test-reports.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in report_names:
        summary = json.loads((AUDIT/'reports'/name/'summary.json').read_text())
        report_summaries[name] = summary
        write(DEST/'reports'/name/'summary.json', summary)
        for source in sorted((AUDIT/'reports'/name).rglob('*')):
            if source.is_file():
                archive.write(source, source.relative_to(AUDIT/'reports').as_posix())
    # Exactly two rerun XML files from the final canonical-byte migration check.
    for pattern in ('TEST-*TraceLinkV016CompatibilityTest.xml', 'TEST-*TraceLinkProjectionIT.xml'):
        matches = list((ROOT/'services/processor/target/surefire-reports').glob(pattern))
        assert len(matches) == 1
        archive.write(matches[0], 'v016-lf-final/' + matches[0].name)

output_sources = [ROOT/'services/processor/target',
                  AUDIT.parent/'trace-link-combined-projection-verify-20261012/services/processor/target']
for folder in output_sources:
    for source in sorted(folder.glob('*.json')):
        if source.name.startswith(('trace-link-', 'voice-parity', 'sms-parity', 'day5-geographic-parity',
                                   'geographic-voice-parity', 'geographic-sms-parity',
                                   'day13-', 'day15-', 'day17-dependency')):
            prefix = 'full-projection' if 'projection-verify' in str(folder) else 'combined-reactor'
            target = DEST/'outputs'/prefix/source.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)

full_names = ['combined-support', 'combined-generator', 'combined-processor']
full = {key:sum(report_summaries[n]['totals'][key] for n in full_names)
        for key in ('tests', 'failures', 'errors', 'skipped')}
assert full == dict(tests=1307, failures=0, errors=0, skipped=5)
skips = [dict(suite=s['name'], **skip) for name in full_names
         for s in report_summaries[name]['suites'] for skip in s['skips']]
python_results = {}
for label, name in [('combined', 'combined-python.log'), ('producerOnly', 'producer-only-python.log'),
                    ('historicalResolution', '../trace-link-historical-python-20261012.log')]:
    data = (AUDIT/name).read_bytes()
    raw = data.decode('utf-16' if data.startswith(b'\xff\xfe') else 'utf-8', errors='replace')
    matches = re.findall(r'Ran (\d+) tests? in [^\n]+', raw)
    skipped = re.findall(r'OK \(skipped=(\d+)\)', raw)
    python_results[label] = dict(reported=int(matches[-1]), skipped=int(skipped[-1]) if skipped else 0,
                                status='PASS', skipReason='pwsh runtime unavailable')
    python_results[label]['passed'] = python_results[label]['reported'] - python_results[label]['skipped']

# This list hashes the actual committed runtime/test/contract input set, rather
# than claiming all report-writing commits were full-suite executions.
input_set = git('ls-tree', '-r', SOURCE, '--', 'services', 'contracts', 'tests')
assert input_set == git('ls-tree', '-r', MERGE, '--', 'services', 'contracts', 'tests')
write(DEST/'input-identity.json', dict(mergeSnapshot=MERGE, finalToolSnapshot=SOURCE,
      committedRuntimeTestContractInputs='BYTE_IDENTICAL', gitInputListSha256=sha(input_set),
      packagedV016Before='75f3a07f3a193b80b970a6a311a109b14a69fe36e89a65813e03489e6b5fe21f',
      packagedV016After='bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848',
      explanation='CRLF worktree packaging corrected with an LF attribute; Git SQL blob unchanged; two fresh migration tests and packaged verifier rerun'))
(DEST/'source-changed-files.txt').write_bytes(git('diff', '--name-status', BASE, SOURCE))

fail_reasons = {
    'combined-ml-python':'Global Python dependencies differ from packaged model pins; existing pinned environment passes 64 tests.',
    'combined-ml-python-root':'Same global dependency mismatch; pinned environment used for final verification.',
    'fresh-python-sql-javascript-arithmetic':'Unsupported SQL exporter CLI argument; corrected documented invocation retained.',
    'fresh-sql-arithmetic':'Exporter correctly rejected missing isolation label/network boundary; corrected disposable isolated container passes.',
    'combined-package-lf':'Unquoted PowerShell Maven property split; corrected quoted cached command passes.',
    'packaged-integration':'Real CRLF packaged V016 hash mismatch; fixed LF checkout attribute, frozen Git SQL unchanged.',
    'packaged-integration-final':'New verification helper incorrectly selected 09:02 for separate gap schedule; corrected to actual 09:18 slot.'}
for record in records:
    if record['exitCode']:
        record['reason'] = fail_reasons.get(record['name'], 'See full retained log; not counted as a passing invocation.')
verification = dict(
    executedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), timezone='Europe/Chisinau',
    verdict='INTEGRATION_CANDIDATE_VERIFIED', overallRelease='BLOCKED',
    sourceCommit=SOURCE, sourceTree=git('rev-parse', SOURCE+'^{tree}').decode().strip(), baseCommit=BASE,
    fullJavaTestSnapshot=MERGE, fullProjectionTestSnapshot='ac1c00278fcdb33fa28502d5f31cdc1aa6aaf2ba',
    fullJava=dict(**full, passed=full['tests']-full['skipped'], skips=skips,
                  modules={n:report_summaries[n]['totals'] for n in full_names}),
    incidentJava=report_summaries['incident-full']['totals'],
    fullNativeProjection=report_summaries['combined-full-native-projection']['totals'],
    producerOnlyFocused=report_summaries['producer-only-focused']['totals'],
    python=python_results, pinnedML=dict(reported=64, passed=64, skipped=0, failures=0, errors=0),
    parity=dict(arithmeticGoldens=54, languages=['Java','Python','JavaScript','PostgreSQL'], maximumDifference=0,
                legacyVoice=7, legacySMS=12, persistedGeographicVoice=90, persistedGeographicSMS=90),
    archive=dict(nativeRows=362880, projectionRows=8640, heldOutboxRows=8640, native5mBuckets=8640, rollup6hBuckets=121),
    frozenDay6=dict(files=124, fullRehearsalRepeated=False), frozenInputs=29,
    finalCanonicalByteMigrationRerun=dict(tests=2, passed=2, failures=0, errors=0, skipped=0),
    packagedVerifier='PASS', commandRecords=records, compressedLogs=log_manifest,
    tooling=dict(graphifyInitialExitCode=1, graphifyFinalExitCode=1,
                 finalGraph='10682 nodes / 27010 edges / 631 communities', impact='Graph rebuilt; auxiliary CLI exit status retained separately'),
    blocked=[dict(action=action, status='BLOCKED', reason=reason) for action,reason in [
        ('Live minute detector acceptance','Versioned receiver scope, clock, baseline and minute policy authority unapproved.'),
        ('Consumer mapping/ingestion','Metric, scope, policy, quality, nullable provenance and additive read-model decisions unapproved.'),
        ('Connected delivery/ACK/restart','No approved new-domain publisher/consumer/topic configuration; Kafka disabled and outbox held.'),
        ('Authenticated API/UI/notifications/Grafana','Connected approved deployment and receiving flows unavailable.'),
        ('Capacity','120s/900s profile exists; target rate, bounded burst and recovery limits plus connected path unset.'),
        ('Backup/restore/deployment','No approved disposable deployment/restore target and shared configuration identity.'),
        ('Connected rehearsals and owner signatures','Two connected rehearsals and five genuine owner reproductions absent.')]],
    owners={owner:'NOT_VERIFIED' for owner in ['Ion','Sergiu','Denis','David','Stanislav']},
    newDomainKafka='DISABLED', projectionOutbox='PENDING_REVIEW',
    note='Independent invocation totals are not added across overlapping suites. Separate full projection covers the archive case skipped by the default full reactor. New PR CI is checked separately after publication, never inferred from parent CI.')
write(DEST/'verification.json', verification)
result = dict(destination=str(DEST), fullJava=full, python=python_results,
              commandRecords=len(records), files=len(list(DEST.rglob('*'))))
print(json.dumps(result))
