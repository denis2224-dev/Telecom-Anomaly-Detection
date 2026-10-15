import { test, expect } from '../helpers/test';
import { controlledApi } from '../helpers/controlled-api';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';

const directory=resolve(__dirname,'../../../../../contracts/fixtures/trace-link');
const envelope=JSON.parse(readFileSync(resolve(directory,readdirSync(directory).find(name=>name.endsWith('detection-envelopes.json'))!),'utf8')).detections[0];
for(const width of [1366,390])test(`controlled native investigation preserves recovery and workflow at ${width}px`,async({page})=>{
 const controlled=await controlledApi(page);await page.setViewportSize({width,height:900});
 const incident={episodeId:envelope.lifecycle.episodeId,firstObservedAt:envelope.lifecycle.window.from,detectedAt:envelope.context.evaluatedAt,latestWindowEnd:envelope.lifecycle.window.to,latestSequence:1,technicalState:'ONGOING',severity:'HIGH',latestDetection:envelope};
 const workflow={id:'61581a38-b146-4d64-b96a-221fc9102e74',episodeId:incident.episodeId,status:'OPEN',assigneeId:null as string|null,version:1,resolutionNote:null as string|null};
 const audit:unknown[]=[];const mutations:string[]=[];
 await page.route('**/api/analysts*',route=>route.fulfill({json:[{id:controlled.incident.assigneeId,displayName:'Controlled analyst',enabled:true}]}));
 await page.route('**/api/v2/trace-link/incidents/**',async route=>{
  const path=new URL(route.request().url()).pathname,method=route.request().method();
  if(method==='POST'){
   const body=route.request().postDataJSON();expect(body.version).toBe(workflow.version);mutations.push(path.split('/').at(-1)!);
   if(path.endsWith('/assignment'))workflow.assigneeId=body.assigneeId;
   if(path.endsWith('/status')){workflow.status=body.status;workflow.resolutionNote=body.resolutionNote;}
   workflow.version++;audit.push({action:body.status??mutations.at(-1),version:workflow.version,note:body.note??body.resolutionNote??null,occurredAt:incident.latestWindowEnd});
   return route.fulfill({json:workflow});
  }
  if(path.endsWith('/workflow'))return route.fulfill({json:workflow});
  if(path.endsWith('/evidence'))return route.fulfill({json:{items:[envelope],total:1,limit:50,offset:0}});
  if(path.endsWith('/audit'))return route.fulfill({json:audit});
  return route.fulfill({json:incident});
 });
 const parameters=new URLSearchParams({datasetId:'controlled-native',from:incident.firstObservedAt,to:incident.latestWindowEnd,resolution:'15m',timezone:'Europe/Chisinau',cityId:'all',domain:'transport'});
 await page.goto('/trace-link/incidents/'+incident.episodeId+'?'+parameters);
 await expect(page.getByRole('region',{name:'Native technical incident'})).toContainText('ONGOING');
 await expect(page.getByRole('button',{name:'Resolve reviewed incident'})).toBeDisabled();
 await page.getByRole('button',{name:'Acknowledge',exact:true}).click();
 await expect(page.getByRole('button',{name:'Acknowledged',exact:true})).toBeDisabled();
 await expect(page.getByRole('region',{name:'Native technical incident'})).toContainText('analyst identity unavailable');
 await page.getByLabel('Investigation comment').fill('Controlled evidence reviewed');await page.getByRole('button',{name:'Add comment',exact:true}).click();
 await expect(page.getByRole('region',{name:'Append-only native audit'})).toContainText('Controlled evidence reviewed');
 incident.technicalState='RECOVERED';
 await page.reload();await expect(page.getByRole('link',{name:'Back to shared investigation'})).toHaveAttribute('href',/resolution=15m/);await expect(page.getByRole('region',{name:'Native technical incident'})).toContainText('Technical recovery does not resolve analyst work.');
 await expect(page.getByRole('region',{name:'Native analyst workflow'})).toContainText('INVESTIGATING');
 await page.getByLabel('Resolution note').fill('Recovery verified in controlled fixture');await page.getByRole('button',{name:'Resolve reviewed incident'}).click();
 await expect(page.getByRole('region',{name:'Native analyst workflow'})).toContainText('RESOLVED');expect(mutations).toEqual(['assignment','status','comments','status']);
 await page.getByRole('button',{name:'Show on dashboard',exact:true}).click();
 await expect(page).toHaveURL(/trace-link\?.*incidentId=/);
 const focused=new URL(page.url());expect(focused.searchParams.get('linkDirectionId')).toBe('chi-hin:a-to-b');expect(focused.searchParams.get('resolution')).toBe('15m');
 const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth);expect(overflow).toBe(false);
});
