"""Run isolated cross-service replay; disposable broker/DB, packaged HTTP model, test clocks."""
import argparse
from datetime import datetime,timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
from zipfile import ZipFile

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'services/ml-service'),str(Path(__file__).parent)]
from generate_replay import generate
from app.validation.sms_real_data import metrics,acceptance,read_inputs,write_report
from app.inference.sms_classifier import load_classifier,classifier_scores
import numpy as np


def wait_file(path,process,seconds=120):
    deadline=time.monotonic()+seconds
    while not path.exists() and time.monotonic()<deadline:
        if process.poll() is not None: raise RuntimeError(f'Process exited {process.returncode} before {path.name}; inspect logs')
        time.sleep(.5)
    if not path.exists(): raise TimeoutError(str(path))


def run(directory,jdk,seed):
    manifest=generate(directory,seed)
    print(f"Fresh replay generated: {manifest['windows']} windows",flush=True)
    env=dict(os.environ,JAVA_HOME=str(jdk.resolve()),SMS_SHADOW_REPLAY_DIR=str(directory.resolve()),
             ML_SMS_SHADOW_ENABLED='true',OMP_NUM_THREADS='1',OPENBLAS_NUM_THREADS='1')
    container=None; processes=[]
    try:
        application_jar=ROOT/'services/processor/target/processor-0.3.0-SNAPSHOT.jar'
        if not application_jar.exists():
            subprocess.run([str(ROOT/'mvnw.cmd'),'-pl','services/processor','-am','package'],cwd=ROOT,env=env,check=True)
        libraries=directory/'publisher-libs'
        libraries.mkdir()
        with ZipFile(application_jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (libraries/Path(name).name).write_bytes(archive.read(name))
        container=subprocess.check_output(['docker','run','-d','--rm','-p','127.0.0.1::8090',
              '-e','ML_SMS_SHADOW_ENABLED=true','-e','OMP_NUM_THREADS=1','-e','OPENBLAS_NUM_THREADS=1',
              'telecom-sms-shadow-validation'],text=True).strip()
        port=subprocess.check_output(['docker','port',container,'8090/tcp'],text=True).strip().split(':')[-1]
        env['ML_SERVICE_URL']='http://127.0.0.1:'+port
        deadline=time.monotonic()+60
        while True:
            try:
                with urllib.request.urlopen(env['ML_SERVICE_URL']+'/health/ready',timeout=2) as response: assert response.status==200
                break
            except Exception:
                if time.monotonic()>deadline: raise TimeoutError('Classifier container readiness failed')
                time.sleep(.5)
        # Warm the trusted model before the timed cohort, using one cohort row without selection.
        window=json.loads((directory/'features.jsonl').read_text().splitlines()[0])
        for _ in range(2):
            request=urllib.request.Request(env['ML_SERVICE_URL']+'/internal/inference/sms-classifier',data=json.dumps(window).encode(),headers={'Content-Type':'application/json'})
            with urllib.request.urlopen(request,timeout=2) as response: assert json.load(response)['mlStatus']=='OK'
        print('Classifier image ready; starting processor replay',flush=True)
        with (directory/'processor.log').open('w') as processor_log,(directory/'incident.log').open('w') as incident_log, (directory/'concurrent-publisher.log').open('w') as publisher_log:
            processor=subprocess.Popen([str(ROOT/'mvnw.cmd'),'-pl','services/processor','-am','-Dtest=SmsShadowReplayTest','-Dsurefire.failIfNoSpecifiedTests=false','test'],cwd=ROOT,env=env,stdout=processor_log,stderr=subprocess.STDOUT)
            processes.append(processor)
            wait_file(directory/'runtime.json',processor,180)
            incident=subprocess.Popen([str(ROOT/'services/incident-service/mvnw.cmd'),'-f',str(ROOT/'services/incident-service/pom.xml'),'-Dtest=SmsShadowReplayTest','test'],cwd=ROOT,env=env,stdout=incident_log,stderr=subprocess.STDOUT)
            processes.append(incident)
            publisher=subprocess.Popen([str(jdk.resolve()/'bin'/('java.exe' if os.name=='nt' else 'java')),
                '-cp',str(ROOT/'services/processor/target/classes')+os.pathsep+str(libraries/'*'),
                str(ROOT/'tasks/sms-ml-shadow-integration/ReplayPublisher.java'),str(directory.resolve())],
                cwd=ROOT,env=env,stdout=publisher_log,stderr=subprocess.STDOUT)
            processes.append(publisher)
            last_update=0
            while any(process.poll() is None for process in processes):
                if time.monotonic()-last_update>30:
                    lines=(directory/'processor.log').read_text(errors='replace').splitlines()
                    progress=[line for line in lines if 'Replay finalized' in line]
                    print(progress[-1] if progress else 'Waiting for cross-service replay...',flush=True)
                    last_update=time.monotonic()
                if any(process.poll() not in (None,0) for process in processes):
                    raise RuntimeError('Replay process failed; inspect immutable run logs')
                time.sleep(1)
            if any(process.returncode for process in processes): raise RuntimeError('Replay failed')
        windows,labels=read_inputs(directory/'processor-features.jsonl',directory/'labels.csv')
        events=[json.loads(line) for line in (directory/'shadow-results.jsonl').read_bytes().splitlines()]
        by_id={event['windowId']:event for event in events}
        loaded=load_classifier(ROOT/'services/ml-service/candidate-models/sms-supervised-v1-2')
        keys=sorted(windows)
        scores=classifier_scores(loaded[1],np.asarray([windows[k]['featureValues'] for k in keys])[:,loaded[0]['projection']])
        rows=[]
        for key,offline in zip(keys,scores):
            event=by_id[windows[key]['windowId']]
            if event['mlStatus']!='OK' or abs(offline-event['classifierScore'])>1e-12: raise AssertionError('Persisted HTTP/offline scoring parity')
            rows.append(dict(labels[key],detection=event['detection']))
        result=metrics(rows)
        missing=dict(unlabelledFeatures=len(windows.keys()-labels.keys()),labelsWithoutFeatures=len(labels.keys()-windows.keys()))
        status=acceptance(result,missing,min_faults=720,min_healthy=2016)
        latencies=np.asarray(json.loads((directory/'latencies.json').read_bytes()))
        processor_summary=json.loads((directory/'processor-done.json').read_bytes())
        incident_summary=json.loads((directory/'incident-done.json').read_bytes())
        report=dict(schemaVersion=1,evaluationKind='ISOLATED_SYNTHETIC_DELIVERY_REPLAY',datasetId=manifest['datasetId'],
                    modelVersion=loaded[0]['modelVersion'],threshold=.55,scoreSemantics=loaded[0]['scoreSemantics'],**result,missingCoverage=missing,acceptanceStatus=status,
                    coverageRequirements=dict(faultsPerFamily=720,healthyPerProfile=2016),
                    inference=dict(requests=len(latencies),meanMs=float(np.mean(latencies)),p50Ms=float(np.quantile(latencies,.5)),
                                   p95Ms=float(np.quantile(latencies,.95)),p99Ms=float(np.quantile(latencies,.99)),maxMs=float(np.max(latencies)),budgetMs=250,concurrency=2),
                    pipeline=dict(**processor_summary,**incident_summary,offlineHttpPersistedParity=True,historyBootstrapUsed=False,concurrentLeasedPublishers=True),
                    realNetworkValidation='PENDING_DATA',provenance=dict(modelSha256=loaded[0]['modelSha256'],
                        packageManifestSha256=sha256((ROOT/'services/ml-service/candidate-models/sms-supervised-v1-2/manifest.json').read_bytes()).hexdigest(),
                        datasetManifestSha256=sha256((directory/'dataset.json').read_bytes()).hexdigest(),
                        containerImageId=subprocess.check_output(['docker','inspect','--format','{{.Image}}',container],text=True).strip(),
                        sourceCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                        implementationFiles={name:sha256((ROOT/name).read_bytes().replace(b'\r\n',b'\n')).hexdigest() for name in (
                            'tasks/sms-ml-shadow-integration/generate_replay.py','tasks/sms-ml-shadow-integration/run_replay.py',
                            'tasks/sms-ml-shadow-integration/ReplayPublisher.java',
                            'services/processor/src/test/java/md/utm/telecom/processing/kpi/SmsShadowReplayTest.java',
                            'services/incident-service/src/test/java/md/utm/telecom/shadow/SmsShadowReplayTest.java',
                            'services/ml-service/app/inference/sms_classifier.py','services/ml-service/app/validation/sms_real_data.py',
                            'services/processor/src/main/java/md/utm/telecom/processing/shadow/SmsShadowWorker.java',
                            'services/processor/src/main/java/md/utm/telecom/processing/shadow/SmsShadowPublisher.java',
                            'services/processor/src/main/resources/db/migration/V010__feature_worker_indexes.sql',
                            'services/incident-service/src/main/java/md/utm/telecom/shadow/SmsShadowStore.java')},
                        files={path.name:sha256(path.read_bytes()).hexdigest() for path in directory.iterdir() if path.suffix in ('.jsonl','.csv')}))
        write_report(report,directory/'report.json')
        print(json.dumps(dict(acceptanceStatus=status,overall=result['overall'],pipeline=report['pipeline'],inference=report['inference']),indent=2),flush=True)
        if status!='PASSED': raise AssertionError('Frozen replay acceptance gates failed')
        return report
    finally:
        for process in processes:
            if process.poll() is None:
                if os.name=='nt': subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                else: process.terminate()
        if container: subprocess.run(['docker','stop',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--jdk',type=Path,required=True)
    parser.add_argument('--seed',type=int,default=3000000)
    args=parser.parse_args()
    run(args.output,args.jdk,args.seed)
