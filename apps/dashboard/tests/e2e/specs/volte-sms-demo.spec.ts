import {test,expect,type Page} from '@playwright/test';
import {createHash,randomUUID} from 'node:crypto';
import {readFileSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import type {components} from '../../../src/app/core/api/schema';
import {api,login,ownedCompose,post,read,sql,stackContext} from '../helpers/pr77-stack';

type Run=components['schemas']['ScenarioRun'];
type Incident=components['schemas']['Incident'];
type Detection=components['schemas']['ServiceDetection'];
const stack=stackContext();
const candidate=readFileSync(join(stack.outputDir,'candidate.json'));
const candidateSha256=createHash('sha256').update(candidate).digest('hex');

// Both runs share source, images, accounts, policies and configuration. No routing mocks.
test.describe.serial('limited Orhei demonstration',()=>{
for(const repetition of [1,2])test(`complete VoLTE/SMS demo repetition ${repetition}`,async({browser},info)=>{
 test.setTimeout(16*60_000);
 const report={sourceSha:stack.sourceSha,candidateSha256,repetition,startedAtUTC:new Date().toISOString(),
  status:'FAILED',scope:'LIMITED_VOLTE_SMS_DEMO',humanAcceptance:'PENDING',runs:[] as Run[],
  incidents:[] as object[],trace:[] as object[],checks:[] as string[]};
 const analyst=await login(browser,stack,'analyst');
 const supervisor=await login(browser,stack,'supervisor');
 const find=async(run:Run)=>(await read<{items:Incident[]}>(analyst,`/api/incidents?scopeId=${run.scopeId}&size=100`)).items
  .filter(i=>Date.parse(i.firstObservedAt)>=Date.parse(run.scheduledStartAt)&&Date.parse(i.firstObservedAt)<Date.parse(run.scheduledEndAt));
 const click=async(page:Page,name:string,path:string)=>{
  const response=page.waitForResponse(r=>new URL(r.url()).pathname===path&&r.request().method()==='POST');
  await page.getByRole('button',{name,exact:true}).click();expect((await response).status()).toBe(200);
 };
 const capture=async(page:Page,name:string)=>{
  for(const width of [1366,390]){
   await page.setViewportSize({width,height:900});
   expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
   await page.screenshot({path:info.outputPath(`${name}-${width}.png`),fullPage:true,
    mask:[page.locator('.identity'),page.locator('app-incident-actions')]});
  }
  await page.setViewportSize({width:1366,height:900});
 };
 try{
  expect(createHash('sha256').update(readFileSync(join(stack.outputDir,'candidate.json'))).digest('hex')).toBe(candidateSha256);
  expect((await post(analyst,'/api/simulator/scenarios/NORMAL_CONTROL',{requestId:randomUUID(),scopeId:'VOLTE-MD-ORH',seed:42})).status()).toBe(403);
  expect(await read(analyst,'/api/notifications/push/config')).toMatchObject({enabled:false});
  report.checks.push('Real OIDC, roles, rotated session/CSRF and private storage checks; analyst scenario restriction; push off');
  for(const [scopeId,type] of [['VOLTE-MD-ORH','VOLTE_IMS_OVERLOAD'],['SMS-MD-ORH','SMS_QUEUE_DELAY'],
    ['VOLTE-MD-BAL','NORMAL_CONTROL'],['SMS-MD-CAH','TELEMETRY_GAP']] as const){
   const runner=await supervisor.context.newPage();
   await runner.goto('/scenarios');
   await runner.getByLabel('Scenario',{exact:true}).selectOption(type);
   await runner.getByLabel('Service scope',{exact:true}).selectOption(scopeId);
   const accepted=runner.waitForResponse(r=>new URL(r.url()).pathname===`/api/simulator/scenarios/${type}`&&r.request().method()==='POST');
   await runner.getByRole('button',{name:'Start scenario',exact:true}).click();
   const response=await accepted;expect(response.status()).toBe(202);
   const run=await response.json() as Run;expect(run.scopeId).toBe(scopeId);report.runs.push(run);
   const retry=await post(supervisor,`/api/simulator/scenarios/${type}`,response.request().postDataJSON());
   expect(retry.status()).toBe(202);expect((await retry.json()).runId).toBe(run.runId);
   await runner.close();
  }
  writeFileSync(info.outputPath('progress.json'),JSON.stringify(report,null,2));
  const [volte,sms,normal,gap]=report.runs;
  for(const run of [volte,sms]){
   await expect.poll(async()=>(await find(run))[0]?.technicalState,{timeout:6*60_000,intervals:[5000]}).toBe('ONGOING');
   const incident=(await find(run))[0];
   await analyst.page.goto(`/incidents/${incident.id}`);
   await expect(analyst.page.locator('.incident-summary-bar')).toContainText('ONGOING');
   await expect(analyst.page.locator('[data-detection-id]').first()).toBeVisible();
   await capture(analyst.page,`${run.scopeId}-ongoing`);
   await analyst.page.getByRole('button',{name:'Details & workflow',exact:true}).click();
   await click(analyst.page,'Claim for myself',`/api/incidents/${incident.id}/assignment`);
   await click(analyst.page,'Start investigation',`/api/incidents/${incident.id}/status`);
   const note=`Demo repetition ${repetition}: investigate synthetic ${run.scopeId} evidence.`;
   await analyst.page.getByLabel('Investigation comment',{exact:true}).fill(note);
   await click(analyst.page,'Add comment',`/api/incidents/${incident.id}/comments`);
   await expect(analyst.page.getByRole('region',{name:'Investigation timeline'})).toContainText(note);
   await expect(analyst.page.getByRole('button',{name:'Resolve incident',exact:true})).toBeDisabled();
   expect((await read<Incident>(analyst,`/api/incidents/${incident.id}`)).status).toBe('INVESTIGATING');
  }
  await analyst.page.goto('/dashboard');await analyst.page.getByLabel('Region',{exact:true}).fill('Orhei');
  await expect(analyst.page.locator('.chart-scope').first()).toHaveAttribute('data-scope',volte.scopeId);
  const streams=()=>analyst.network.responses.filter(r=>r.path==='/api/incidents/stream'&&r.status===200).length;
  const before=streams();ownedCompose(stack,['stop','proxy'],'proxy');
  try{await expect(analyst.page.getByText(/Live connection interrupted/).first()).toBeVisible();}
  finally{ownedCompose(stack,['start','proxy'],'proxy');}
  await expect.poll(streams,{timeout:60_000}).toBeGreaterThan(before);
  await expect(analyst.page.getByLabel('Region',{exact:true})).toHaveValue('Orhei');
  report.checks.push('UI-started fault/control/gap scenarios, idempotent retry, live incidents, claim, investigation, persisted comment, blocked premature resolution, reconnect');
  await expect.poll(async()=>{
   const states=await Promise.all(report.runs.map(r=>read<Run>(supervisor,`/api/simulator/runs/${r.runId}`)));
   expect(states.some(r=>['FAILED','STOPPED'].includes(r.status))).toBe(false);
   return states.every(r=>r.status==='COMPLETED');
  },{timeout:9*60_000,intervals:[10_000]}).toBe(true);
  for(const run of [volte,sms]){
   await expect.poll(async()=>(await find(run))[0]?.technicalState,{timeout:90_000,intervals:[5000]}).toBe('RECOVERED');
   const incident=(await find(run))[0];expect(incident.status).toBe('INVESTIGATING');
   const detections=(await read<components['schemas']['DetectionPage']>(analyst,`/api/incidents/${incident.id}/detections?size=100`)).items;
   expect(detections.map(d=>d.phase)).toEqual(['OPEN','UPDATE','UPDATE','UPDATE','RECOVERY']);
   expect(incident.episodeId).toMatch(/^[0-9a-f]{64}$/);
   expect(JSON.parse(sql(stack,'incidents_db',`SELECT json_agg(payload ORDER BY sequence) FROM app.detection_evidence WHERE episode_id='${incident.episodeId}';`))).toEqual(detections);
   for(const d of detections){
    const ids=[...new Set(d.evidence.flatMap(e=>e.sourceEventIds))];expect(ids.length).toBeGreaterThan(0);
    expect(ids.every(id=>/^[0-9a-f-]{36}$/.test(id))).toBe(true);
    const feature=JSON.parse(sql(stack,'processing_db',`SELECT payload FROM app.feature_outbox WHERE scope_id='${run.scopeId}' AND window_start='${new Date(d.windowStart).toISOString()}';`));
    expect(feature.kpis).toEqual(d.kpis);expect(ids.every(id=>feature.sourceEventIds.includes(id))).toBe(true);
    const receipts=JSON.parse(sql(stack,'processing_db',`SELECT json_agg(t) FROM (SELECT event_id,payload_hash,kafka_topic,kafka_offset FROM app.observation_receipt WHERE event_id IN (${ids.map(id=>`'${id}'`).join(',')}))t;`));
    expect(receipts).toHaveLength(ids.length);expect(receipts.every((r:any)=>r.kafka_topic==='telecom.observations.v2'&&r.kafka_offset>=0&&/^[0-9a-f]{64}$/.test(r.payload_hash))).toBe(true);
    report.trace.push({incidentId:incident.id,detectionId:d.detectionId,scopeId:run.scopeId,windowStart:d.windowStart,receipts});
   }
   await analyst.page.goto(`/incidents/${incident.id}`);
   await expect(analyst.page.locator('.incident-summary-bar')).toContainText('Awaiting analyst resolution');
   await capture(analyst.page,`${run.scopeId}-recovered`);
   await analyst.page.getByRole('button',{name:'Details & workflow',exact:true}).click();
   await analyst.page.getByLabel('Resolution note',{exact:true}).fill(`Verified measured recovery and source evidence in demo repetition ${repetition}.`);
   await click(analyst.page,'Resolve incident',`/api/incidents/${incident.id}/status`);
   const resolved=await read<Incident>(analyst,`/api/incidents/${incident.id}`);
   expect(resolved.status).toBe('RESOLVED');expect(resolved.technicalState).toBe('RECOVERED');
   await analyst.page.reload();await expect(analyst.page.locator('.incident-summary-bar')).toContainText('RESOLVED');
   report.incidents.push({runId:run.runId,incidentId:incident.id,episodeId:incident.episodeId,detections:detections.length,status:resolved.status,technicalState:resolved.technicalState});
  }
  expect(await find(normal)).toEqual([]);expect(await find(gap)).toEqual([]);
  const gapHistory=await read<components['schemas']['GeographyKpiPage']>(analyst,'/api/geography/cities/CAH/kpis?'+new URLSearchParams({service:'SMS',from:gap.scheduledStartAt,to:gap.scheduledEndAt,size:'100'}));
  for(const offset of [2,3,4]){
   const p=gapHistory.points.find(p=>Date.parse(p.windowStart)===Date.parse(gap.scheduledStartAt)+offset*60_000);
   expect(p?.coverage.state).toBe('MISSING');expect(p?.metric.observed).toBeNull();
  }
  report.checks.push('Completed real eight-minute runs; DB/API/KPI/source parity; five ordered detections per incident; recovery preserves investigation; explicit resolution persists; no false control/gap incidents');
  for(const actor of [analyst,supervisor]){
   await actor.page.goto('/dashboard');await actor.page.getByRole('button',{name:'Sign out',exact:true}).click();
   await expect(actor.page).toHaveURL(/\/signed-out$/);
   expect((await api(actor,'/api/auth/me')).status()).toBe(401);
   await expect.poll(()=>actor.network.activeStreams.size).toBe(0);expect(actor.network.errors).toEqual([]);
  }
  report.status='PASSED';
 }finally{
  await analyst.context.close();await supervisor.context.close();
  writeFileSync(info.outputPath('demo-repetition.json'),JSON.stringify({...report,finishedAtUTC:new Date().toISOString()},null,2)+'\n');
 }
});
});
