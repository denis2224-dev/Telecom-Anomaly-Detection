import {test,expect} from '@playwright/test';
import {createHash} from 'node:crypto';
import {readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {api,login,ownedCompose,read,sql,stackContext} from '../helpers/pr77-stack';
import {requireArchive,requireExactExport} from '../helpers/trace-release-acceptance';

const stack=stackContext();
const manifestBytes=readFileSync(resolve(__dirname,'../../../../../contracts/datasets/trace-link-md-30d-v1.manifest.json'));
const manifest=JSON.parse(manifestBytes.toString()),checksum=createHash('sha256').update(manifestBytes).digest('hex');
const bounds={datasetId:manifest.dataset_id,from:'2026-10-09T09:00:00Z',to:'2026-10-10T09:00:00Z',cityId:'chi',resolution:'5m'};

for(const width of [1366,390])test(`populated authenticated history preserves exact context at ${width}px`,async({browser},info)=>{
 const actor=await login(browser,stack,'analyst');const evidence:object[]=[];
 try {
  await actor.page.setViewportSize({width,height:900});
  const datasets=await read<any[]>(actor,'/api/v2/datasets');requireArchive(datasets.find(item=>item.datasetId===manifest.dataset_id),manifest,checksum);
  const definitions=await read<any[]>(actor,'/api/v2/kpi-definitions');
  expect(sql(stack,'incidents_db',"SELECT count(*) FROM app.trace_catalogue WHERE active")).toBe('0');
  await actor.page.goto('/trace-link?'+new URLSearchParams(bounds));
  await expect(actor.page.getByRole('region',{name:'Approved topology'})).toContainText('10 active cities · 12 active links');
  await expect(actor.page.getByRole('region',{name:'Persisted metric chart'})).toBeVisible();
  const from=await actor.page.getByLabel('From · UTC inclusive').inputValue();
  for(const resolution of ['15m','1h','6h','5m']){
   await actor.page.getByRole('combobox',{name:'Resolution',exact:true}).selectOption(resolution);
   await expect(actor.page.getByLabel('From · UTC inclusive')).toHaveValue(from);
   await expect(actor.page.getByRole('button',{name:'Download exact server CSV'})).toBeEnabled();
  }
  await actor.page.getByRole('combobox',{name:'Resolution',exact:true}).selectOption('1h');await actor.page.getByRole('button',{name:'6h',exact:true}).click();
  await expect(actor.page.getByRole('combobox',{name:'Resolution',exact:true})).toHaveValue('1h');
  for(const hours of [24,168,720]){
   const query=new URLSearchParams({datasetId:manifest.dataset_id,domain:'volte',metric:'volte.setup.success',scopeType:'CITY',scopeId:'chi',from:new Date(Date.parse(bounds.to)-hours*3600000).toISOString(),to:bounds.to,resolution:'5m'});
   const result=await read<any>(actor,'/api/v2/metrics/series?'+query);expect(result.points).toHaveLength(hours*12);expect(result.points.every((point:any)=>point.value!==null)).toBe(true);
   evidence.push({hours,buckets:result.points.length});
  }
  const choices=[['VoLTE','volte.setup.success'],['SMS','sms.queue'],['Transport','transport.traffic'],['Roaming','roaming.registration.success'],['LTE mobile data','mobileData.activation.success'],['LTE mobile data','mobileData.dns.success']];
  for(const [domain,metric] of choices){
   if(domain==='Transport')await actor.page.getByRole('button',{name:'chi-hin a-to-b',exact:true}).click();
   await actor.page.getByRole('button',{name:domain,exact:true}).click();
   if(domain==='Roaming'){
    await actor.page.getByRole('combobox',{name:'Country',exact:true}).selectOption('RO');await actor.page.getByRole('combobox',{name:'Roaming direction',exact:true}).selectOption('out');
    await actor.page.getByRole('combobox',{name:'Series partner and direction',exact:true}).selectOption('RO-DEMO-01:out');
   }
   await actor.page.getByRole('combobox',{name:'Metric',exact:true}).selectOption(metric);
   const chart=actor.page.getByRole('region',{name:'Persisted metric chart'});await expect(chart).toContainText(metric);
   await expect(actor.page.getByRole('button',{name:'Download exact server CSV'})).toBeEnabled();
   const context=new URL(actor.page.url()).searchParams;
   const query=new URLSearchParams({datasetId:manifest.dataset_id,domain:context.get('domain')!,metric,scopeType:domain==='Transport'?'LINK_DIRECTION':domain==='Roaming'?'PARTNER_DIRECTION':'CITY',scopeId:domain==='Transport'?'chi-hin:a-to-b':domain==='Roaming'?'RO-DEMO-01:out':'chi',from:context.get('visibleFrom')??context.get('from')!,to:context.get('visibleTo')??context.get('to')!,resolution:context.get('resolution')!});
   const result=await read<any>(actor,'/api/v2/metrics/series?'+query),definition=definitions.find(item=>item.metric===metric);expect(definition).toBeDefined();
   if(definition.referenceAvailability==='REQUIRES_ADDITIONAL_PRIMITIVES'){
    expect(result.points.every((point:any)=>point.value===null&&point.nullReason==='ADDITIONAL_PRIMITIVES_REQUIRED'),metric).toBe(true);
    await expect(chart).toContainText('Unavailable');
   }else expect(result.points.some((point:any)=>point.value!==null),metric).toBe(true);
   const event=actor.page.waitForEvent('download');await actor.page.getByRole('button',{name:'Download exact server CSV'}).click();const download=await event;
   const csv=readFileSync((await download.path())!,'utf8');requireExactExport(result,csv);evidence.push({metric,buckets:result.points.length,csvSha256:createHash('sha256').update(csv).digest('hex')});
  }
  const expand=actor.page.getByRole('button',{name:'Expand metric',exact:true});await expand.click();await expect(actor.page.getByRole('dialog',{name:'Expanded persisted metric'})).toContainText('mobileData.dns.success');await actor.page.keyboard.press('Escape');await expect(expand).toBeFocused();
  expect(await actor.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  expect(await read<any>(actor,'/api/notifications/push/config')).toMatchObject({enabled:false});
  await actor.page.screenshot({path:info.outputPath(`populated-history-${width}.png`),fullPage:true,mask:[actor.page.locator('.identity')]});
  writeFileSync(info.outputPath('populated-history.json'),JSON.stringify({sourceSha:stack.sourceSha,width,manifestSha256:checksum,mode:'Actual persisted archive and real OIDC in an owned isolated stack',evidence,completeRehearsal:false},null,2));
 }finally{writeFileSync(info.outputPath('history-progress.json'),JSON.stringify({sourceSha:stack.sourceSha,width,evidence},null,2));await actor.context.close();}
});

test('populated history reconnects and clears protected state after real logout',async({browser})=>{
 const actor=await login(browser,stack,'analyst');
 try{
  await actor.page.goto('/trace-link?'+new URLSearchParams(bounds));await expect(actor.page.getByRole('region',{name:'Persisted metric chart'})).toBeVisible();
  ownedCompose(stack,['stop','proxy'],'proxy');
  try{await expect(actor.page.getByText(/Live connection interrupted/).first()).toBeVisible();}finally{ownedCompose(stack,['start','proxy'],'proxy');}
  await expect.poll(()=>actor.network.activeStreams.size,{timeout:30_000}).toBe(1);
  await actor.page.reload();await expect(actor.page.getByRole('region',{name:'Persisted metric chart'})).toBeVisible();
  await actor.page.getByRole('button',{name:'Sign out',exact:true}).click();await expect.poll(()=>new URL(actor.page.url()).pathname).toBe('/signed-out');
  expect((await api(actor,'/api/v2/datasets')).status()).toBe(401);await expect.poll(()=>actor.network.activeStreams.size).toBe(0);
 }finally{await actor.context.close();}
});
