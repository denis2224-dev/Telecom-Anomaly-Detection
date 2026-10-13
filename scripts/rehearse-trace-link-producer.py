"""Two fresh JVM rehearsals of packaged producers, with independent measurement checks.

Build the Java reactor first. Requires Java 21 and the repository's Python contract
dependencies. The destination must not exist. This never starts application services.
"""
import argparse
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

from jsonschema import Draft202012Validator, FormatChecker
from trace_link_scenario_contract import identity, no_truth, value

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'), parse_float=Decimal)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def hashes(directory):
    return {p.relative_to(directory).as_posix(): digest(p)
            for p in sorted(directory.rglob('*')) if p.is_file()}


def verify(config, directory):
    validator = Draft202012Validator(read(ROOT / 'contracts/observations/trace-link-domain-window-v1.schema.json'),
                                     format_checker=FormatChecker())
    dictionary = {m['metricId']: m for m in read(ROOT / 'contracts/kpi/trace-link-kpi-definitions-v1.json')['metrics']}
    summary, all_ids, measured = [], set(), {}
    for case in config['scenarios']:
        name, c = case['name'], case['command']
        events = [json.loads(line, parse_float=Decimal) for line in (directory / (name + '.jsonl')).read_text().splitlines()]
        params, scope = c['parameters'], c['scope']
        scope_key = '|'.join(str(scope[f]) if scope[f] is not None else '-'
                             for f in ('domain', 'id', 'direction', 'country', 'procedure', 'topologyVersion'))
        width = {'TRANSPORT': 3, 'ROAMING': 1, 'MOBILE_DATA': 2}[scope['domain']]
        require(len(events) == 8 * width, name + ': incomplete schedule')
        minutes = []
        for ordinal in range(8):
            start = datetime.fromisoformat(c['start']) + timedelta(minutes=ordinal)
            time = start.isoformat().replace('+00:00', 'Z')
            end = (start + timedelta(minutes=1)).isoformat().replace('+00:00', 'Z')
            fault = 2 <= ordinal < 5 and c['type'] != 'NORMAL_CONTROL'
            missing = ordinal in params['missingMinutes'] or fault and c['type'] == 'TELEMETRY_GAP'
            h = hashlib.sha256(f"trace-link-minute-generator-v1|{c['version']}|{c['seed']}|{scope_key}|{time}".encode()).hexdigest()
            samples = []
            for e in events[ordinal * width:(ordinal + 1) * width]:
                validator.validate(e)
                no_truth(e)
                require(e['eventId'] == identity(e) and e['eventId'] not in all_ids, name + ': duplicate/unstable event')
                all_ids.add(e['eventId'])
                require(e['windowStart'] == time and e['windowEnd'] == end and e['emittedAt'] == end
                        and e['sourceResolutionSeconds'] == 60 and e['datasetId'] == config['liveDataset']['datasetId']
                        and e['sourceId'] == f"trace-link-minute-generator-v1-seed-{c['seed']}"
                        and e['scopeId'] == scope['id'], name + ': source/scope/time mismatch')
                m = dictionary[e['metricId']]
                require(all(e[k] == m[k] for k in ('domain', 'population', 'unit', 'policyVersion'))
                        and e['aggregationType'] == m['aggregation'], name + ': metric mismatch')
                p = e['primitives']
                actual = value(e)
                if missing:
                    require(e['quality'] == 'MISSING' and e['coverage'] == 0
                            and all(v is None for v in p.values()) and actual is None, name + ': gap became recovery')
                elif scope['domain'] == 'TRANSPORT':
                    bp = params['degradedUtilizationBp'] if fault and c['type'] == 'TRANSPORT_LINK_OVERLOAD' else params['normalUtilizationBp']
                    bp = max(0, bp - int(h[:4], 16) % 51)
                    traffic = Decimal(params['capacityGbps']) * bp / 10000
                    expected = traffic if e['metricId'] == 'transport.traffic' else Decimal(bp) / 100 if e['metricId'] == 'transport.link.utilization' else Decimal(params['capacityGbps'])
                    require(e['directionId'] == scope['direction'] and actual == expected
                            and e['transport']['trafficGbps'] == traffic
                            and e['transport']['effectiveCapacityGbps'] == params['capacityGbps']
                            and e['quality'] == 'VALID' and e['coverage'] == 1, name + ': transport arithmetic/isolation')
                else:
                    variation = int(hashlib.sha256(f"{h}|{e['metricId']}".encode()).hexdigest()[:4], 16) % 51
                    attempts = params['attempts'] + variation if params['attempts'] else 0
                    impaired = fault and (c['type'] == 'ROAMING_REGISTRATION_FAILURE'
                                           or c['type'] == 'MOBILE_DATA_DNS_FAILURE' and e['metricId'] == 'mobileData.dns.success')
                    bp = params['degradedSuccessBp'] if impaired else params['healthySuccessBp']
                    require(p == {'numerator': attempts * bp // 10000, 'denominator': attempts}
                            and e['quality'] == ('VALID' if attempts else 'INSUFFICIENT_EVIDENCE')
                            and e['coverage'] == 1, name + ': independent population arithmetic')
                    if scope['domain'] == 'ROAMING':
                        require(e['partnerId'] == scope['id'] and e['direction'] == scope['direction']
                                and e['countryId'] == scope['country'] and e['procedure'] == scope['procedure'], name + ': direction leak')
                samples.append({'metricId': e['metricId'], 'primitives': p,
                                'value': None if actual is None else str(actual), 'quality': e['quality'],
                                'eventId': e['eventId']})
            minutes.append({'minute': ordinal + 1, 'windowStart': time,
                            'schedulePhase': 'NORMAL' if ordinal < 2 else 'DEGRADED' if ordinal < 5 else 'RECOVERY',
                            'measurements': samples})
        measured[name] = events
        summary.append({'name': name, 'observations': len(events), 'minutes': minutes})
    # Counterfactual healthy compile on the same LTE source/window is checked by
    # the existing Java tests. Here verify healthy activation even during DNS fault.
    for e in measured['mobile-data-fault']:
        if e['metricId'] == 'mobileData.activation.success':
            require(value(e) > 98, 'DNS fault degraded independent activation')
    for name in ('transport-opposite', 'roaming-opposite', 'mobile-data-other-city'):
        require(all(e['quality'] == 'VALID' for e in measured[name]), 'Independent scope became missing')
    legacy_count = 0
    for file in sorted(directory.glob('legacy-*.json')):
        windows = read(file)
        require(len(windows) == 8, 'Legacy schedule changed')
        for i, window in enumerate(windows):
            gap = 'gap' in file.name and 2 <= i < 5
            require(bool(window) != gap, 'Legacy gap fabricated values')
            for raw in window:
                e = json.loads(raw)
                require(e['schemaVersion'] == 2 and e['windowStart'] ==
                        (datetime.fromisoformat(config['legacy']['start']) + timedelta(minutes=i)).isoformat().replace('+00:00', 'Z'),
                        'Legacy version/time changed')
                metric = e['metrics']
                degraded = ('fault' in file.name or 'ims_overload' in file.name) and 2 <= i < 5
                if e.get('service') == 'VOLTE':
                    require(metric['technicalFailures'] == (60 if degraded else 7), 'Legacy voice schedule changed')
                if e.get('service') == 'SMS':
                    delays = metric['deliveryDelayMs']
                    require(all(30000 <= v <= 60000 for v in delays) if degraded else all(1000 <= v <= 3500 for v in delays),
                            'Legacy SMS schedule changed')
                if e.get('nodeId') == 'SMSC-A':
                    require(metric['queueDepth'] >= 150 if degraded else metric['queueDepth'] == 0, 'Legacy queue schedule changed')
                legacy_count += 1
    return {'status': 'VERIFIED', 'newDomainObservations': len(all_ids), 'legacyObservations': legacy_count,
            'scenarios': summary, 'detectorTransitions': 'NOT_VERIFIED', 'Kafka': 'DISABLED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    config_path = ROOT / 'contracts/fixtures/demo/service-seeds.json'
    config = read(config_path)
    require(config['runOrder'] == [s['name'] for s in config['scenarios']], 'Run order mismatch')
    jar = ROOT / 'services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar'
    library = ROOT / 'services/event-generator/target/event-generator-0.3.0-SNAPSHOT-library.jar'
    build = output / 'build'
    build.mkdir()
    dependencies = []
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                destination = build / Path(name).name
                destination.write_bytes(archive.read(name))
                dependencies.append(destination)
    cp = os.pathsep.join(str(p) for p in [library, *dependencies, build])
    java = str(args.java_home / 'bin/java') if args.java_home else 'java'
    javac = str(args.java_home / 'bin/javac') if args.java_home else 'javac'
    driver = ROOT / 'scripts/rehearsal/TraceLinkProducerRehearsal.java'
    subprocess.run([javac, '-cp', cp, '-d', str(build), str(driver)], check=True)
    results, manifests = [], []
    for number in (1, 2):
        run = output / f'run-{number}'
        subprocess.run([java, '-cp', cp, 'TraceLinkProducerRehearsal', str(config_path), str(run)], check=True)
        results.append(verify(config, run))
        manifests.append(hashes(run))
    require(manifests[0] == manifests[1], 'Independent rehearsal hashes differ')
    pinned = [config_path, driver, Path(__file__),
              ROOT / 'contracts/datasets/trace-link-live-synthetic-v1.json',
              ROOT / 'contracts/datasets/trace-link-md-30d-v1.manifest.json',
              ROOT / 'contracts/topology/trace-link-links-v1.json',
              ROOT / 'contracts/geography/trace-link-geography-v2.json',
              ROOT / 'contracts/kpi/trace-link-kpi-definitions-v1.json',
              ROOT / 'contracts/observations/trace-link-domain-window-v1.schema.json',
              ROOT / 'contracts/observations/telecom-observation-v2.schema.json',
              ROOT / 'contracts/features/feature-order-v2.json', ROOT / 'contracts/policies/service-rules-v2.json']
    report = {'executionCompletedAtUtc': datetime.now(timezone.utc).isoformat(),
              'milestoneDate': '2026-10-15', 'executionMode': 'OFFLINE_LOGICAL_CLOCK',
              'producerBaseCommit': '8fb0596b415b4fef137b29b38774948d3112c1a0',
              'rehearsalSourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'rehearsalSourceTree': subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=ROOT, text=True).strip(),
              'inputHashBasis': 'Raw checked-out bytes; Git content hashes separately identify immutable repository inputs',
              'configurationSha256': digest(config_path),
              'pinnedFiles': {p.relative_to(ROOT).as_posix(): digest(p) for p in pinned},
              'gitContentPins': {p.relative_to(ROOT).as_posix(): hashlib.sha256(subprocess.check_output(
                  ['git', 'show', 'HEAD:' + p.relative_to(ROOT).as_posix()], cwd=ROOT)).hexdigest() for p in pinned},
              'packagedArtifacts': {jar.name: digest(jar), library.name: digest(library)},
              'rehearsalHashesMatch': True, 'artifactHashes': manifests[0],
              'runs': [{'number': i + 1, 'status': result['status'],
                        'newDomainObservations': result['newDomainObservations'],
                        'legacyObservations': result['legacyObservations']} for i, result in enumerate(results)],
              'receivingOwnerReproduction': 'NOT_VERIFIED', 'overallRelease': 'BLOCKED'}
    for i, result in enumerate(results, 1):
        (output / f'run-{i}-measurements.json').write_text(json.dumps(result, indent=2, default=str) + '\n', encoding='utf-8')
    (output / 'rehearsals.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('configurationSha256', 'rehearsalHashesMatch', 'runs')}))


if __name__ == '__main__':
    main()
