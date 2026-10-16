from datetime import datetime, timezone
from pathlib import Path
import json
import subprocess
import time

root=Path.cwd()
target=root/'docs/evidence/assets/trace-link-day5-independent-20261015'
project=json.loads((root/'tmp/day5-review-local-project.json').read_text(encoding='utf-8-sig'))
assert project['project'].startswith('trace-link-day5-')
assert Path(project['envFile']).parent.name==project['project']
assert all(Path(project[key]).resolve().is_relative_to((root/'tmp').resolve()) for key in ('envFile','overrideFile'))
compose=['docker','compose','--project-name',project['project'],'--env-file',project['envFile'],
    '-f','compose.yaml','-f',project['overrideFile']]
records=[]
def run(name,args):
    start=datetime.now(timezone.utc).isoformat(); tick=time.perf_counter()
    result=subprocess.run(compose+args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',timeout=240)
    (target/(name+'.log')).write_text(result.stdout,encoding='utf-8')
    records.append(dict(name=name,command=compose+args,startedAt=start,elapsedSeconds=time.perf_counter()-tick,exitCode=result.returncode))
    if result.returncode: raise RuntimeError(name+' failed; raw log retained')
    return result.stdout
sql="""BEGIN READ ONLY;
SELECT count(*) AS pending, min(w.created_at) AS oldest_pending_source FROM app.trace_link_job j JOIN app.trace_link_window w USING(window_id) WHERE j.completed_at IS NULL;
SELECT count(*) AS pending, min(e.evaluated_at) AS oldest_pending_evaluation FROM app.trace_link_shadow_job j JOIN app.trace_link_evaluated_window e USING(window_id) WHERE j.completed_at IS NULL;
SELECT lease_until, completed_at FROM app.trace_link_shadow_job WHERE completed_at IS NULL ORDER BY lease_until NULLS FIRST LIMIT 20;
ROLLBACK;"""
passed=False
try:
    before=run('local-before',['ps','--all','--format','json'])
    run('local-java',['exec','-T','processor','java','-version'])
    run('local-sql-before',['exec','-T','postgres','psql','-X','-v','ON_ERROR_STOP=1','-U','processing_app','-d','processing_db','-c',sql])
    run('local-stop',['stop','--timeout','30','processor'])
    stopped=run('local-stopped',['ps','--all','--format','json','processor'])
    assert json.loads(stopped)['State']=='exited'
    run('local-start',['up','-d','--no-deps','--wait','--wait-timeout','180','processor'])
    after=run('local-after',['ps','--all','--format','json'])
    before_rows=[json.loads(line) for line in before.splitlines() if line.strip()]
    after_rows=[json.loads(line) for line in after.splitlines() if line.strip()]
    for service in ('postgres','kafka','processor'):
        earlier=next(row for row in before_rows if row['Service']==service)
        later=next(row for row in after_rows if row['Service']==service)
        assert earlier['ID']==later['ID'] and later['Health']=='healthy'
        assert all(port.get('PublishedPort',0)==0 for port in later.get('Publishers',[])), 'Host port unexpectedly published'
    run('local-sql-after',['exec','-T','postgres','psql','-X','-v','ON_ERROR_STOP=1','-U','processing_app','-d','processing_db','-c',sql])
    run('local-processor',['logs','--no-color','--tail','100','processor'])
    passed=True
finally:
    run('local-cleanup',['down','--volumes','--remove-orphans'])
    (target/'local-commands.json').write_text(json.dumps(dict(recordedAtUtc=datetime.now(timezone.utc).isoformat(),
        status='PASS' if passed else 'FAIL',scope='EMPTY_DISPOSABLE_SQL_CONNECTION_AND_NORMAL_PROCESS_RESTART',
        backlogOrLiveAcceptance=False,project=project['project'],commands=records,
        harnessErrors=['Initial extraction of the Markdown PowerShell block truncated a backtick; fixed in harness before provisioning']),indent=2)+'\n',encoding='utf-8')
print('Disposable SQL and process stop/start: PASS; project removed')
