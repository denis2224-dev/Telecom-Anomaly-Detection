"""Validate and publish fresh Day 2 test exports; never evaluates live traffic."""
import argparse
from datetime import datetime
import hashlib
import json
from importlib.metadata import version
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012
from trace_link_contract import ROOT, read, require
from trace_link_day2_contract import validate_result

OUT = ROOT / 'docs/evidence/assets/trace-link-day2'
TARGET = ROOT / 'services/processor/target'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def java_executable(java_home):
    """Resolve the native JDK launcher without assuming a Windows checkout."""
    java = Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    require(java.is_file(), f'Java launcher missing: {java}')
    return str(java)


def save(name, data, directory=OUT):
    (directory / name).write_text(json.dumps(data, indent=2, sort_keys=True) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', required=True, type=Path)
    parser.add_argument('--output-dir', type=Path, default=OUT,
        help='Publish a separate reproduction record without replacing earlier evidence')
    args = parser.parse_args()
    destination = args.output_dir.resolve()
    run = json.loads(args.run.read_text(encoding='utf-8-sig'))
    require(run['exitCode'] == 0 and run['historyBuilds'] == 2, 'Incomplete reproduction')
    registry = Registry().with_resources((p.resolve().as_uri(), Resource.from_contents(
        json.loads(p.read_text(encoding='utf-8')), default_specification=DRAFT202012))
        for p in (ROOT / 'contracts').rglob('*.schema.json'))
    cause_validator = Draft202012Validator({'$ref': (ROOT / 'contracts/explanations/trace-link-cause-result-v1.schema.json').as_uri()},
        registry=registry, format_checker=FormatChecker())
    exports = {
        'domain-rules.json': 'trace-link-domain-rules.json',
        'lifecycle.json': 'trace-link-lifecycle.json',
        'cause-controls.json': 'trace-link-cause-controls.json',
        'integrated.json': 'trace-link-day2-integrated.json',
        'reference-eligibility.json': 'trace-link-reference-eligibility.json',
        'service-transport.json': 'trace-link-service-transport.json',
        'loaded-fallbacks.json': 'trace-link-loaded-fallbacks.json',
    }
    started = datetime.fromisoformat(run['startedAt'].replace('Z', '+00:00')).timestamp()
    for source in exports.values():
        require((TARGET / source).stat().st_mtime >= started, f'Stale test export: {source}')
    data = {name: json.loads((TARGET / source).read_text(encoding='utf-8')) for name, source in exports.items()}
    cases = read('contracts/fixtures/trace-link/detection-cases.json')['cases']
    causes = read('contracts/fixtures/trace-link/cause-control-cases.json')['cases']
    domain_rows = data['domain-rules.json']['cases']
    require({c['id'] for c in cases} == {c['id'] for c in domain_rows}, 'Domain fixture export incomplete')
    require({c['id'] for c in causes} == {c['id'] for c in data['cause-controls.json']}, 'Cause fixture export incomplete')
    for row in domain_rows:
        for result in row['results']:
            validate_result(result)
    for row in data['cause-controls.json']:
        cause_validator.validate(row['result'])
    for row in data['integrated.json']:
        validate_result(row['rule'])
        cause_validator.validate(row['supportedCause'])
        cause_validator.validate(row['unresolvedCause'])
    for row in data['reference-eligibility.json']:
        for result in row['results']:
            validate_result(result)
            require(result['verdict'] == 'UNKNOWN', 'Reference freshness/population invented')
    for result in data['service-transport.json']['results']:
        validate_result(result)
    for step in data['lifecycle.json']['trajectory']:
        validate_result(step['result'])
    frozen_path = OUT / 'frozen-hashes.json'
    if not frozen_path.exists():
        frozen_path = ROOT / 'tmp/trace-link-day2/frozen-before.json'
    frozen = json.loads(frozen_path.read_text(encoding='utf-8-sig'))
    for name, expected in frozen['files'].items():
        require(digest(ROOT / name) == expected, f'Frozen V2/model changed: {name}')
    matrix = {
        'schemaVersion': 'trace-link-day2-fixture-matrix-v1', 'activation': 'OFFLINE_ONLY',
        'rules': [{'id': c['id'], 'domain': c['context']['domain'], 'expected': c['expected']} for c in cases],
        'causes': [{'id': c['id'], 'expected': c['expected']} for c in causes],
        'additionalJavaSuites': ['TraceLinkInputsTest', 'TraceLinkSeasonalBaselineTest',
            'TraceLinkRecoveryPolicyTest', 'TraceLinkOfflineIntegrationTest', 'TraceLinkServiceTransportTest']}
    suites = []
    skipped = []
    for module in ('services/streaming-support', 'services/event-generator', 'services/processor'):
        for report in (ROOT / module / 'target/surefire-reports').glob('TEST-*.xml'):
            if report.stat().st_mtime < started:
                continue
            suite = ET.parse(report).getroot()
            suites.append({k: suite.attrib[k] for k in ('name', 'tests', 'errors', 'failures', 'skipped')})
            for test in suite.findall('testcase'):
                skip = test.find('skipped')
                if skip is not None:
                    skipped.append({'suite': suite.attrib['name'], 'test': test.attrib['name'],
                        'reason': skip.attrib.get('message') or (skip.text or '').strip()[:500] or skip.attrib.get('type')})
    expected_suites = {
        'TraceLinkCausePolicyTest', 'TraceLinkDomainRulesTest', 'TraceLinkInputsTest',
        'TraceLinkOfflineIntegrationTest', 'TraceLinkRecoveryPolicyTest',
        'TraceLinkServiceTransportTest', 'TraceLinkArithmeticTest', 'TraceLinkSeasonalBaselineTest'}
    require(expected_suites <= {s['name'].rsplit('.', 1)[-1] for s in suites}, 'Missing fresh Day 2 suites')
    totals = {k: sum(int(s[k]) for s in suites) for k in ('tests', 'errors', 'failures', 'skipped')}
    require(totals['tests'] > 0 and totals['errors'] == totals['failures'] == 0, 'Java failure')
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    source_files = set((ROOT / 'contracts').rglob('*.json'))
    for pattern in ('services/processor/src/**/TraceLink*.java', 'services/processor/src/**/SeasonalBaselineEstimator.java',
                    'services/processor/src/**/TransportRule.java', 'services/processor/src/**/RoamingRule.java',
                    'services/processor/src/**/MobileDataRule.java', 'scripts/*trace*link*day2*',
                    'scripts/trace_link_history_baseline.py', 'tests/test_trace_link*.py'):
        source_files.update(ROOT.glob(pattern))
    source_files.update(ROOT / p for p in ('requirements-dev.txt', 'services/processor/pom.xml', 'scripts/check-contracts.py',
        'scripts/verify-trace-link-day1.ps1', 'scripts/verify-trace-link-pins.py',
        'scripts/check-trace-link-parity.py', 'scripts/trace-link-reference.cjs',
        'scripts/trace-link-reference.sql', 'scripts/check-voice-parity.py', 'scripts/check-sms-parity.py',
        'scripts/trace_link_contract.py', 'scripts/trace_link_fixture_contract.py',
        'scripts/geography_contract.py', 'scripts/geographic_numeric_contract.py',
        'scripts/geographic_feature_parity.py', '.gitattributes',
        'mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties',
        'services/processor/src/test/java/md/utm/telecom/processing/HealthProbeTest.java'))
    source_hashes = {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted(source_files) if p.is_file()}
    java = java_executable(os.environ['JAVA_HOME'])
    runtimes = {
        'python': sys.version, 'tzdata': version('tzdata'),
        'java': subprocess.check_output([java, '-version'], stderr=subprocess.STDOUT, text=True).strip(),
        'node': subprocess.check_output(['node', '--version'], text=True).strip(),
        'dockerServer': subprocess.check_output(['docker', 'version', '--format', '{{.Server.Version}}'], text=True).strip(),
        'postgresImage': 'postgres:16.4-alpine',
    }
    # Validate every input and external prerequisite before refreshing published files.
    destination.mkdir(parents=True, exist_ok=True)
    if destination != OUT.resolve():
        for name in ('seasonal-baseline.json.gz', 'history-fit-report.json'):
            shutil.copyfile(OUT / name, destination / name)
    for name, source in exports.items():
        shutil.copyfile(TARGET / source, destination / name)
    save('frozen-hashes.json', frozen, destination)
    save('fixture-matrix.json', matrix, destination)
    save('manifest.json', {
        'schemaVersion': 'trace-link-day2-evidence-v1', 'activation': 'OFFLINE_ONLY',
        'implementationRevision': revision, 'integrationRevision': frozen['integrationSha'],
        'sourceHashes': source_hashes,
        'run': run, 'runtimes': runtimes, 'javaTests': totals, 'javaSuites': suites, 'javaSkippedTests': skipped,
        'commands': [
            r'.\.venv\Scripts\python.exe scripts/check-contracts.py',
            r'.\.venv\Scripts\python.exe -m unittest discover -s tests -v',
            r'.\.venv\Scripts\python.exe -m unittest discover -s services/ml-service/tests -v',
            r'.\mvnw.cmd -pl services/processor -am --batch-mode --no-transfer-progress verify',
            'scripts/verify-trace-link-day1.ps1 -Python <python> -JavaHome <JDK21> -ReferenceRoot <reference>',
            'scripts/trace_link_history_baseline.py --reference-root <reference> --output <build-N.json.gz> --report <history-N.json> (N=1,2)',
            'scripts/publish_trace_link_day2.py --run tmp/trace-link-day2/reproduction/run.json'],
        'domainFixtures': len(cases), 'causeFixtures': len(causes),
        'policies': {p: digest(ROOT / p) for p in [
            'contracts/policies/trace-link-domain-rules-v1.json', 'contracts/baselines/trace-link-baseline-policy-v1.json']},
        'artifacts': {p.name: digest(p) for p in sorted(destination.iterdir()) if p.is_file() and p.name != 'manifest.json'},
        'limitations': ['Synthetic reference data and hand-calculated target populations',
            'Historical source freshness remains UNKNOWN', 'No live worker, persisted windows, Kafka or incident-service registration',
            'Reference EPS, post-activation DNS, packet-loss and mean-RTT populations unavailable',
            'Reference eligibility export is a negative eligibility fixture, not a claim of fresh source measurements']}, destination)
    print(json.dumps({'fixtures': len(cases) + len(causes), 'java': totals, 'manifest': str(destination / 'manifest.json')}))


if __name__ == '__main__':
    main()
