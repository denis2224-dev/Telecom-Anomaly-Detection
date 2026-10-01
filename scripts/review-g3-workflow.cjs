const { chromium, expect } = require('../apps/dashboard/node_modules/@playwright/test');
const fs=require('fs');
const env=process.env;
if(!env.G3_USERNAME||!env.G3_PASSWORD||!process.argv[2]){console.error('Set G3_USERNAME/G3_PASSWORD and supply the public runs JSON path.');process.exit(1);}
const runs=JSON.parse(fs.readFileSync(process.argv[2]));
const output=process.argv[3]??'output/g3-workflow';fs.mkdirSync(output,{recursive:true});
const origin='http://telecom.test:8080';
let browser;
(async()=>{
 browser=await chromium.launch({args:['--host-resolver-rules=MAP telecom.test 127.0.0.1','--no-proxy-server']});
 const page=await browser.newPage({viewport:{width:1366,height:768}});
 const errors=[];page.on('pageerror',e=>errors.push(e.name));
 await page.goto(origin+'/login');
 await page.getByRole('button',{name:'Continue to sign in',exact:true}).click();
 await page.getByLabel(/username|email/i).fill(env.G3_USERNAME);
 await page.getByLabel('Password',{exact:true}).fill(env.G3_PASSWORD);
 await page.getByRole('button',{name:/sign in/i}).click();
 await page.waitForURL('**/dashboard');
 async function service(run){
  const got=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/incidents'&&r.request().method()==='GET');
  await page.goto(origin+'/services/'+run.scopeId);
  const list=(await(await got).json()).items;
  return list.find(i=>i.scopeId===run.scopeId&&Date.parse(i.firstObservedAt)>=Date.parse(run.scheduledStartAt)&&Date.parse(i.firstObservedAt)<Date.parse(run.scheduledEndAt));
 }
 async function detail(item){
  const got=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/incidents/${item.id}/detections`);
  await page.goto(origin+'/incidents/'+item.id);
  const detections=(await(await got).json()).items;
  await expect(page.locator('[data-detection-id]')).toHaveCount(detections.length);
  return detections;
 }
 const evidence=[];
 for(const run of runs){
  let item;const deadline=Date.now()+6*60000;
  while(!(item=await service(run))){if(Date.now()>deadline)throw Error('Opening missing');await page.waitForTimeout(10000);}
  await detail(item);
  await expect(page.getByRole('button',{name:'Claim for myself',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Claim for myself',exact:true}).click();
  await page.getByRole('button',{name:'Start investigation',exact:true}).click();
  await expect(page.getByLabel('Resolution note',{exact:true})).toBeVisible();
  await page.getByLabel('Investigation comment',{exact:true}).fill('G3: reviewed the measured service impact, aligned source evidence, and capacity hypothesis; rank is not outage probability.');
  await page.getByRole('button',{name:'Add comment',exact:true}).click();
  await expect(page.getByText('Comment saved.',{exact:true})).toBeVisible();
  await page.getByLabel('Resolution note',{exact:true}).fill('Premature resolution check');
  if(item.technicalState!=='RECOVERED')await expect(page.getByRole('button',{name:'Resolve incident',exact:true})).toBeDisabled();
  await page.screenshot({path:`${output}/${run.scopeId}-investigating.png`,fullPage:true});
  evidence.push({run,incidentId:item.id,claimed:true,investigating:true,commentSaved:true,earlyResolutionBlocked:item.technicalState!=='RECOVERED'});
  console.log(run.scopeId+': live claim, investigation, comment, and resolution eligibility recorded');
 }
 for(const result of evidence){
  let item;const deadline=Date.now()+10*60000;
  while((item=await service(result.run))?.technicalState!=='RECOVERED'){
   if(Date.now()>deadline)throw Error('Recovery missing');console.log(result.run.scopeId+': awaiting three healthy finalized intervals');await page.waitForTimeout(15000);
  }
  expect(item.status).toBe('INVESTIGATING');
  const detections=await detail(item);
  expect(detections.map(d=>d.phase)).toEqual(['OPEN','UPDATE','UPDATE','UPDATE','RECOVERY']);
  expect(detections.map(d=>d.sequence)).toEqual([1,2,3,4,5]);
  expect(detections[0].causeConfidence).toBe('MEDIUM');
  for(const d of detections){expect(d.impact.uniqueSubscribers).toBeNull();expect(d.mlStatus).toBe('OK');expect(d.anomalyRank).not.toBeNull();}
  const before=JSON.stringify(detections);
  await page.getByLabel('Resolution note',{exact:true}).fill('Reviewed three consecutive healthy intervals and dependency evidence. Service recovered; completed the investigation.');
  await page.getByRole('button',{name:'Resolve incident',exact:true}).click();
  await expect(page.locator('app-incident-detail')).toContainText('Workflow state: RESOLVED');
  expect(JSON.stringify(await detail(item))).toBe(before);
  await page.screenshot({path:`${output}/${result.run.scopeId}-resolved.png`,fullPage:true});
  result.detections=detections;result.recoveredBeforeResolution=true;result.resolved=true;result.evidenceUnchanged=true;
  console.log(result.run.scopeId+': live recovery, manual resolution, and saved evidence immutability passed');
 }
 expect(errors).toEqual([]);
 fs.writeFileSync(`${output}/live-workflow.json`,JSON.stringify(evidence,null,2));
 await browser.close();
})().catch(async e=>{console.error('Workflow failed: '+e.name+'\n'+e.stack.split('\n').slice(1,3).join('\n'));if(browser)await browser.close();process.exitCode=1});
