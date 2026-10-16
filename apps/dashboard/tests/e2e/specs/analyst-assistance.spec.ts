import {test,expect} from '../helpers/test';
import {controlledApi} from '../helpers/controlled-api';
import {readFileSync,readdirSync} from 'node:fs';
import {resolve} from 'node:path';

for(const width of [1440,390]){
  test(`suggested checks and paged related incidents recover from failure at ${width}px`,async({page},info)=>{
    const state=await controlledApi(page);await page.setViewportSize({width,height:900});
    let fail=true;const reads:number[]=[];
    const related={...structuredClone(state.incident),id:'11111111-1111-4111-8111-111111111111',episodeId:'b'.repeat(64)};
    const other={...structuredClone(state.incident),id:'22222222-2222-4222-8222-222222222222',episodeId:'c'.repeat(64)};
    await page.route(/\/api\/incidents\?/,route=>{
      const query=new URL(route.request().url()).searchParams,pageIndex=Number(query.get('page')??0);
      if(query.get('size')!=='100')return route.fulfill({json:{items:[state.incident],total:1,page:pageIndex,size:20}});
      reads.push(pageIndex);
      if(fail)return route.fulfill({status:503,json:{code:'UNAVAILABLE'}});
      const items=pageIndex===0?[state.incident,related,...Array.from({length:98},(_,index)=>({...structuredClone(state.incident),id:`unrelated-${index}`,episodeId:String(index).padStart(64,'0'),scopeId:'other-scope',location:null,latestDetection:{...structuredClone(state.incident.latestDetection),topologyVersion:'other'}}))]:[other];
      return route.fulfill({json:{items,total:101,page:pageIndex,size:100}});
    });
    await page.goto('/incidents/'+state.incident.id);
    await expect(page.getByRole('region',{name:'Suggested next checks'})).toContainText('SIP 503');expect(reads).toEqual([]);
    const panel=page.locator('app-incident-assist details');await panel.locator('summary').click();
    await expect(panel.getByRole('button',{name:'Retry related incidents'})).toBeVisible();
    fail=false;await panel.getByRole('button',{name:'Retry related incidents'}).click();
    await expect(panel).toContainText('Checked 100 of 101 episodes');await expect(panel.getByRole('link')).toHaveCount(1);
    await expect(panel).toContainText('Same scope · overlapping time');await expect(panel).not.toContainText('unrelated-');
    await panel.getByRole('button',{name:'Check more incidents'}).click();
    await expect(panel).toContainText('Checked 101 of 101 episodes');await expect(panel.getByRole('link')).toHaveCount(2);
    await expect(panel.getByRole('button',{name:'Check more incidents'})).toHaveCount(0);
    await expect(panel.getByRole('link').first()).toHaveAttribute('href','/incidents/'+related.id);
    expect(state.writes).toBe(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
    await panel.screenshot({path:info.outputPath(`related-${width}.png`)});
  });
  test(`handover exports an honest current-view snapshot at ${width}px`,async({page},info)=>{
    const state=await controlledApi(page);await page.setViewportSize({width,height:900});
    state.incident.location={cityId:null,catalogueVersion:null,topologyVersion:null,measuredScopeId:state.incident.scopeId,containmentPath:[],dependencyNodeIds:[],nullReason:'UNALLOCATED'};
    await page.addInitScript(()=>Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async(value:string)=>{(window as any).__copiedSummary=value;}}}));
    await page.route('**/api/operations/priority*',route=>route.fulfill({json:{generatedAt:state.summary.observedAt,policyVersion:'controlled-priority',policyStatus:'ACTIVE',page:0,size:20,hasNext:true,items:[{incidentId:state.incident.id,cityId:null,cityNullReason:'UNALLOCATED',service:state.incident.service,scopeId:state.incident.scopeId,technicalState:state.incident.technicalState,analystStatus:state.incident.status,severity:state.incident.severity,severityHistorical:false,freshness:'FRESH',priorityBand:'FRESH_ONGOING',comparableImpact:null,impactUnit:null,firstObservedAt:state.incident.firstObservedAt,detectedAt:state.incident.detectedAt,latestWindowEnd:state.incident.lastObservedAt}]}}));
    await page.goto('/dashboard');const open=page.getByRole('button',{name:'Shift handover',exact:true});await expect(open).toBeEnabled();await open.focus();await page.keyboard.press('Enter');
    const drawer=page.getByRole('dialog',{name:'Shift handover',exact:true});await expect(drawer).toBeVisible();
    const textbox=drawer.getByRole('textbox',{name:'Handover summary',exact:true});
    await expect(textbox).toHaveValue(/1 shown · at least 2 episodes/);
    const summary=await textbox.inputValue();
    expect(summary).toContain('1 shown · at least 2 episodes');expect(summary).toContain('partial snapshot');expect(summary).toContain('1 investigating');expect(summary).toContain('Next check (suggested)');
    await drawer.getByRole('button',{name:'Copy summary'}).click();await expect(drawer.getByRole('status')).toHaveText('Summary copied.');expect(await page.evaluate(()=>(window as any).__copiedSummary)).toBe(summary);
    const downloaded=page.waitForEvent('download');await drawer.getByRole('button',{name:'Download summary'}).click();const file=await downloaded;
    expect(file.suggestedFilename()).toBe('trace-link-handover.txt');expect(readFileSync((await file.path())!,'utf8')).toBe(summary);
    await page.evaluate(()=>Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async()=>{throw new Error('Unavailable');}}}));
    await drawer.getByRole('button',{name:'Copy summary'}).click();await expect(drawer.getByRole('status')).toContainText('Select and copy');
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);await drawer.screenshot({path:info.outputPath(`handover-${width}.png`)});
    await page.keyboard.press('Escape');await expect(open).toBeFocused();expect(state.writes).toBe(0);
  });
  test(`chart freshness distinguishes measurements, gaps and unavailable history at ${width}px`,async({page})=>{
    const state=await controlledApi(page);await page.setViewportSize({width,height:900});
    await page.goto('/services/'+state.summary.scope.scopeId);
    const freshness=page.locator('.service-hero app-data-freshness');await expect(freshness).toContainText('Historical');
    const to=state.windows.at(-1)!.windowEnd,from=state.windows[0].windowStart;
    const query=new URLSearchParams({from,to:new Date(Date.parse(to)+3_600_000).toISOString(),follow:'fixed'});
    await page.goto('/services/'+state.summary.scope.scopeId+'?'+query);await expect(freshness).toContainText('before selected end');
    state.historyStatus=503;await page.reload();await expect(page.getByRole('alert').first()).toBeVisible();await expect(page.locator('.service-hero')).toHaveCount(0);
    expect(state.writes).toBe(0);
  });
}

test('native roaming suggestions and related episodes retain archive and partner scope',async({page})=>{
  await controlledApi(page);
  const directory=resolve(__dirname,'../../../../../contracts/fixtures/trace-link');
  const envelope=JSON.parse(readFileSync(resolve(directory,readdirSync(directory).find(name=>name.endsWith('detection-envelopes.json'))!),'utf8')).detections[0];
  envelope.context.domain='ROAMING';envelope.context.scope={...envelope.context.scope,id:'RO-01:out',cityIds:[],partnerId:'RO-01',directionId:'out'};
  const incident={episodeId:envelope.lifecycle.episodeId,firstObservedAt:envelope.lifecycle.window.from,detectedAt:envelope.context.evaluatedAt,latestWindowEnd:envelope.lifecycle.window.to,latestSequence:1,technicalState:'ONGOING',severity:'HIGH',latestDetection:envelope};
  await page.route('**/api/v2/trace-link/incidents/**',route=>{
    const path=new URL(route.request().url()).pathname;
    if(path.endsWith('/workflow')){const id=path.split('/').at(-2);return route.fulfill({json:{id:'workflow',episodeId:id,status:id===incident.episodeId?'OPEN':'INVESTIGATING',assigneeId:null,version:1,resolutionNote:null}});}
    if(path.endsWith('/evidence'))return route.fulfill({json:{items:[envelope],total:1,limit:50,offset:0}});
    if(path.endsWith('/audit'))return route.fulfill({json:[]});
    return route.fulfill({json:incident});
  });
  const related={...structuredClone(incident),episodeId:'d'.repeat(64)};
  await page.route(/\/api\/v2\/trace-link\/incidents\?/,route=>{
    const query=new URL(route.request().url()).searchParams;expect(query.get('datasetId')).toBe(envelope.context.datasetId);expect(query.get('from')).toBe(incident.firstObservedAt);
    return route.fulfill({json:{items:[incident,related],total:2,limit:50,offset:0}});
  });
  await page.goto('/trace-link/incidents/'+incident.episodeId);
  await expect(page.getByRole('region',{name:'Suggested next checks'})).toContainText('partner, country and roaming direction');
  const details=page.locator('app-incident-assist details');await details.locator('summary').click();
  await expect(details.getByRole('link')).toHaveText('ROAMING · RO-01:out');await expect(details).toContainText('Same scope');
  const query=new URLSearchParams({datasetId:envelope.context.datasetId,from:incident.firstObservedAt,to:incident.latestWindowEnd,resolution:'15m',timezone:'UTC',cityId:'all',domain:'roaming',countryId:'RO',roamingDirection:'out',partnerDirectionId:'RO-01:out'});
  await page.goto('/trace-link?'+query);
  await expect(page.locator('app-trace-incidents').getByRole('button',{name:'Acknowledged',exact:true})).toBeVisible();
  await expect(page.locator('app-trace-incidents').getByRole('button',{name:'Acknowledge',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'Shift handover',exact:true}).click();
  const summary=page.getByRole('textbox',{name:'Handover summary',exact:true});
  await expect(summary).toHaveValue(/1 awaiting acknowledgement · 1 investigating/);
  expect(await summary.inputValue()).not.toContain('workflow states unavailable');

});
