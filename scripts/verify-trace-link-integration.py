"""Read-only checks of an assembled candidate; never enables delivery or adapts evidence."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import zipfile

from jsonschema import Draft202012Validator, FormatChecker
from trace_link_scenario_contract import identity, no_truth

ROOT = Path(__file__).resolve().parents[1]
V016 = 'services/processor/src/main/resources/db/migration/V016__trace_link_domain_windows.sql'
V016_SHA256 = 'bf9bb63333a69c199f96ba2a380b394be3a716d6521b19dd9143d7ebebc7e848'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-ref', required=True)
    p.add_argument('--producer-ref', required=True)
    p.add_argument('--projection-ref', required=True)
    p.add_argument('--java-home', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True, help='New output directory')
    a = p.parse_args()
    refs = {name: git('rev-parse', ref).decode().strip() for name, ref in
            [('base', a.base_ref), ('producer', a.producer_ref), ('projection', a.projection_ref)]}
    for name, ref in refs.items():
        require(subprocess.run(['git', 'merge-base', '--is-ancestor', ref, 'HEAD'], cwd=ROOT).returncode == 0,
                'Candidate is missing ' + name)
    protected = ['services/incident-service',
                 'services/processor/src/main/java/md/utm/telecom/processing/detection',
                 'services/processor/src/main/java/md/utm/telecom/processing/baseline',
                 'services/processor/src/main/java/md/utm/telecom/processing/correlation']
    require(not git('diff', '--name-only', refs['base'], 'HEAD', '--', *protected).strip(),
            'Merged detector or consumer source differs from base')
    require(sha(git('show', 'HEAD:' + V016)) == V016_SHA256, 'Frozen V016 Git bytes changed')
    jar = ROOT / 'services/processor/target/processor-0.3.0-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as package:
        names = package.namelist()
        migrations = sorted(n for n in names if n.startswith('BOOT-INF/classes/db/migration/') and n.endswith('.sql'))
        require(len(migrations) == 16, 'Expected all sixteen processor migrations')
        versions = [Path(n).name.split('__')[0] for n in migrations]
        require(versions == [f'V{i:03}' for i in range(1, 17)], 'Migration discovery differs')
        require(sha(package.read('BOOT-INF/classes/db/migration/' + Path(V016).name)) == V016_SHA256,
                'Packaged V016 differs from frozen SQL')
        for resource in ['contracts/evidence/trace-link-evidence-v1.schema.json',
                         'contracts/detections/trace-link-evaluation-v1.schema.json',
                         'contracts/kpi/trace-link-primitive-projection-v1.schema.json',
                         'contracts/baselines/trace-link-baseline-policy-v1.json']:
            require('BOOT-INF/classes/' + resource in names, 'Missing processor resource: ' + resource)
        support = [n for n in names if n.startswith('BOOT-INF/lib/streaming-support-') and n.endswith('.jar')]
        require(len(support) == 1, 'Expected one streaming-support library')
        with zipfile.ZipFile(io.BytesIO(package.read(support[0]))) as library:
            for resource in ['contracts/geography/trace-link-geography-v2.json',
                             'contracts/topology/trace-link-links-v1.json',
                             'contracts/observations/trace-link-domain-window-v1.schema.json',
                             'contracts/datasets/trace-link-md-30d-v1.manifest.json']:
                require(resource in library.namelist(), 'Missing shared resource: ' + resource)
        require(not any(n.startswith('BOOT-INF/classes/contracts/fixtures/trace-link/') for n in names),
                'Test-only Trace Link fixtures leaked into the production JAR')
    env = os.environ.copy()
    for key in ['TRACE_LINK_BOOTSTRAP_MODE', 'TRACE_LINK_PROJECTION_MODE', 'HISTORY_BOOTSTRAP_MODE']:
        env[key] = 'true'
    collision = subprocess.run([str(a.java_home/'bin/java.exe' if os.name == 'nt' else a.java_home/'bin/java'),
                                '-jar', str(jar)], cwd=ROOT, env=env, capture_output=True, timeout=30)
    message = (collision.stdout + collision.stderr).decode('utf-8', errors='replace')
    require(collision.returncode != 0 and 'Select only one historical mode' in message,
            'Conflicting entrypoint modes did not fail before startup')
    require('Starting ProcessorApplication' not in message, 'Mode collision started the live application')
    schema = json.loads((ROOT/'contracts/observations/trace-link-domain-window-v1.schema.json').read_text())
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    sources = [('transport-fault.jsonl', 'transport.link.utilization', 'VALID'),
               ('transport-high-utilization-control.jsonl', 'transport.link.utilization', 'VALID'),
               ('transport-opposite.jsonl', 'transport.link.utilization', 'VALID'),
               ('roaming-fault.jsonl', 'roaming.registration.success', 'VALID'),
               ('roaming-opposite.jsonl', 'roaming.registration.success', 'VALID'),
               ('mobile-data-fault.jsonl', 'mobileData.dns.success', 'VALID'),
               ('mobile-data-fault.jsonl', 'mobileData.activation.success', 'VALID'),
               ('mobile-data-gap.jsonl', 'mobileData.dns.success', 'MISSING')]
    examples, pins = [], []
    for filename, metric, quality in sources:
        path = ROOT/'docs/evidence/assets/trace-link-day6/run-1'/filename
        for number, line in enumerate(path.read_bytes().splitlines(), 1):
            value = json.loads(line)
            if (value['metricId'] == metric and value['quality'] == quality
                    and value['windowStart'].endswith('09:02:00Z')):
                validator.validate(value)
                no_truth(value)
                require(value['eventId'] == identity(value), 'Producer event identity changed')
                examples.append(line)
                pins.append(dict(path=path.relative_to(ROOT).as_posix(), line=number,
                                 sourceFileSha256=sha(path.read_bytes()), payloadSha256=sha(line),
                                 eventId=value['eventId']))
                break
        else:
            raise ValueError('Missing source example: ' + filename)
    a.output.mkdir(parents=True, exist_ok=False)
    (a.output/'producer-examples.jsonl').write_bytes(b'\n'.join(examples)+b'\n')
    report = dict(sourceCommit=git('rev-parse', 'HEAD').decode().strip(),
                  sourceTree=git('rev-parse', 'HEAD^{tree}').decode().strip(), refs=refs,
                  frozenV016Sha256=V016_SHA256, migrations=migrations,
                  packagedResourceChecks='PASS', entrypointCollision='PASS',
                  processorJarSha256=sha(jar.read_bytes()), producerExamples=pins,
                  protectedSourceComparison='BYTE_IDENTICAL_TO_BASE',
                  kafka='DISABLED', consumerAgreement='PENDING_REVIEW',
                  liveDetectorAgreement='PENDING_REVIEW', overallRelease='BLOCKED')
    (a.output/'verification.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print('PASS: frozen V016, sixteen packaged migrations, schemas, entrypoint isolation and eight unchanged producer examples')


if __name__ == '__main__':
    main()
