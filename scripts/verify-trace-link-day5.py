"""Run Day 5 study/drills in a new evidence directory, retaining failures and raw logs."""
import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def windows_host():
    return sys.platform == 'win32'


def verify(output, reference_root):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    executions = []
    def run(name, command):
        started = datetime.now(timezone.utc).isoformat()
        tick = time.perf_counter()
        failure_reason = None
        with (output/(name+'.log')).open('w', encoding='utf-8') as log:
            try:
                code = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
            except OSError as error:
                failure_reason=str(error)
                log.write(failure_reason+'\n'); code = 1
        executions.append(dict(name=name, command=command, startedAt=started,
            finishedAt=datetime.now(timezone.utc).isoformat(), elapsedSeconds=time.perf_counter()-tick,
            exitCode=code, failureReason=failure_reason))
        return code == 0
    python = sys.executable
    windows = windows_host()
    scope = 'WINDOWS_FULL' if windows else 'PORTABLE_RUNTIME'
    wrapper = str(ROOT / ('mvnw.cmd' if windows else 'mvnw'))
    study, export = output/'study', output/'processor.json'
    ready = run('generate', [python, 'services/ml-service/training/trace_link_study.py', '--output', str(study)])
    if ready:
        ready = run('processor-export', [wrapper, '-pl', 'services/processor', '-am', 'test',
            '-Dtest=TraceLinkStudyExportTest', '-Dsurefire.failIfNoSpecifiedTests=false',
            '-DtraceLink.study.inputs='+str(study/'inputs.json.gz'), '-DtraceLink.study.output='+str(export),
            '--batch-mode', '--no-transfer-progress'])
    if ready:
        command = [python, 'services/ml-service/training/trace_link_evaluation.py', '--dataset', str(study),
            '--processor-export', str(export), '--protocol', str(study/'protocol.json')]
        ready = run('evaluate', command+['--output',str(output/'evaluation')])
        if ready:
            ready = run('reproduce-evaluation', command+['--output',str(output/'repeated')])
            if ready:
                for name in ('results.json', 'predictions.json', 'thresholds.json'):
                    try:
                        equal=(output/'evaluation'/name).read_bytes() == (output/'repeated'/name).read_bytes()
                        reason='Non-reproducible result'
                    except OSError as error:
                        equal=False; reason=str(error)
                    if not equal:
                        ready = False
                        executions.append(dict(name='compare-'+name, exitCode=1, reason=reason))
    run('contracts', [python, 'scripts/check-contracts.py'])
    run('frozen-pins', [python, 'scripts/verify-trace-link-pins.py', '--reference-root', str(reference_root.resolve())])
    run('root-python', [python, '-m', 'unittest', 'discover', '-s', 'tests', '-v'])
    run('ml-python', [python, '-m', 'unittest', 'discover', '-s', 'services/ml-service/tests', '-v'])
    byte_gate = dict(status='NOT_RUN', requiredPlatform='Windows',
                     reason='Windows byte reproduction is a separate required gate; this run covers portable checks')
    if windows:
        byte_passed=run('windows-byte-reproduction', [python, 'services/ml-service/tests/windows_candidate_reproduction.py', '-v'])
        byte_gate=dict(status='PASS' if byte_passed else 'FAIL', requiredPlatform='Windows',
                       reason='Explicit clean-process packaged-byte gate; see windows-byte-reproduction.log')
    before_maven = time.time()
    run('processor-regression-and-drills', [wrapper, '-pl', 'services/processor', '-am', 'verify', '--batch-mode', '--no-transfer-progress'])
    for report in (ROOT/'services').glob('*/target/surefire-reports/TEST-*.xml'):
        if report.stat().st_mtime < before_maven: continue
        destination=output/'junit'/report.parents[2].name/report.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(report,destination)
    backlog=ROOT/'services/processor/target/day5-backlog-drill.json'
    if backlog.exists() and backlog.stat().st_mtime >= before_maven: shutil.copy2(backlog,output/backlog.name)
    run('trace-link-parity', [python, 'scripts/check-trace-link-ml-parity.py'])
    for service in ('voice', 'sms'):
        run(service+'-parity', [python, 'scripts/check-'+service+'-parity.py', '--geographic', '--java-output',
            'services/processor/target/geographic-'+service+'-parity-java.json'])
    run('local-http', [python, 'scripts/verify-trace-link-ml-http.py'])
    try:
        revision = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True,stderr=subprocess.STDOUT).strip()
    except (OSError, subprocess.CalledProcessError) as error:
        revision = None
        (output/'source-revision.log').write_text(str(error)+'\n', encoding='utf-8')
        executions.append(dict(name='source-revision', command=['git','rev-parse','HEAD'],
                               exitCode=1, failureReason=str(error)))
    passed = ready and all(e['exitCode'] == 0 for e in executions)
    receipts = {p.relative_to(output).as_posix():sha256(p.read_bytes()).hexdigest() for p in output.rglob('*') if p.is_file()}
    manifest = dict(status='PASS' if passed else 'INCOMPLETE', sourceRevision=revision,
        platform=platform.platform(), python=sys.version, processor=platform.processor(), logicalCpuCount=os.cpu_count(),
        verificationScope=scope, windowsByteReproduction=byte_gate,
        commands=executions, files=receipts, secondPersonReproduction='PENDING', sharedReleaseApproval=False)
    (output/'verification.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')
    print(manifest['status']+' ('+scope+'); Windows byte reproduction '+byte_gate['status']+'; independent reproduction remains PENDING')
    return 0 if passed else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--reference-root',type=Path,default=ROOT.parent/'Trace_Link_Integration_Pack')
    args=parser.parse_args()
    sys.exit(verify(args.output,args.reference_root))
