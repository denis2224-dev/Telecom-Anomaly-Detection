import {nativeIncidentFixture} from '../helpers/native-incident';
import {test,expect} from '../helpers/test';
import {controlledApi} from '../helpers/controlled-api';
import {recordedRoaming} from '../helpers/recorded-roaming';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';

const from='2026-10-07T00:00:00Z',to='2026-10-07T03:00:00Z';
const params=()=>new URLSearchParams({datasetId:'recorded-roaming',from,to,resolution:'15m',roamingDirection:'in',countryId:'RO',partnerDirectionId:'RO-DEMO-01:in'});


for(const width of [1440,390])test(`missing roaming sources never generate dashboard numbers at ${width}px`,async({page})=>{
  const state=await controlledApi(page);
  await page.route('**/api/v2/datasets',route=>route.fulfill({json:[]}));
  await page.setViewportSize({width,height:900});await page.goto('/dashboard');
  const panel=page.getByRole('region',{name:'Roaming overview'});
  await expect(panel).toContainText('No recorded roaming dataset is available');
  await expect(panel).not.toContainText('Sample data');await expect(panel.locator('tbody tr')).toHaveCount(0);
  await expect(page.locator('.headline-metrics > div').filter({hasText:'Roaming registration success'}).locator('strong')).toHaveText('Unavailable');
  await expect(page.locator('.headline-metrics > div').filter({hasText:'Partner user estimates'}).locator('strong')).toHaveText('Unavailable');
  await page.getByRole('link',{name:'Open roaming services →',exact:true}).click();
  await expect(page.locator('.kpi-summary tbody td:nth-child(2)')).toHaveText(['—','—','—','—']);
  await expect(page.locator('.roaming')).not.toContainText('Sample data');expect(state.writes).toBe(0);
});

test('roaming cards reproduce the original native CSV counters with synthetic provenance intact',async({page})=>{
  await controlledApi(page);
  const [header,line]=readFileSync(resolve(__dirname,'../../../../../contracts/fixtures/trace-link/native-slice/roaming_kpi_5m.csv'),'utf8').split(/\r?\n/);
  const row=Object.fromEntries(header.split(',').map((key,index)=>[key,line.split(',')[index]]));
  const end=new Date(Date.parse(row.ts)+300_000).toISOString();
  await page.route('**/api/v2/datasets',route=>route.fulfill({json:[{datasetId:'native-source',from:row.ts,to:end,synthetic:true,archiveReady:true,catalogueVersion:'native-source'}]}));
  await page.route('**/api/v2/inventory/partners?**',route=>route.fulfill({json:{catalogueVersion:'native-source',partners:[{id:row.partner_id,country:row.country_id,direction:row.direction,active:true,synthetic:true}]}}));
  await page.route('**/api/v2/metrics/series?**',route=>{
    const query=Object.fromEntries(new URL(route.request().url()).searchParams),active=query.metric.includes('.active');
    const prefix=query.metric==='roaming.registration.success'?'reg':query.metric==='roaming.voice.success'?'voice':'data';
    const numerator=active?null:Number(row[prefix+'_success']),denominator=active?null:Number(row[prefix+'_attempts']);
    return route.fulfill({json:{...query,requestedFrom:query.from,requestedTo:query.to,unit:active?'COUNT':'PERCENT',aggregation:active?'SNAPSHOT_LAST':'RATIO_OF_SUMS',synthetic:true,
      points:[{from:row.ts,to:end,numerator,denominator,value:active?Number(row.active_users):numerator!/denominator!*100,quality:'INSUFFICIENT_EVIDENCE',coverage:1,snapshotAt:active?row.ts:null}]}});
  });
  const query=new URLSearchParams({datasetId:'native-source',from:row.ts,to:end,resolution:'5m',roamingDirection:row.direction});
  await page.goto('/roaming?'+query);
  const rate=(name:string)=>String(+(Number(row[name+'_success'])/Number(row[name+'_attempts'])*100).toFixed(2))+'%';
  await expect(page.locator('.kpi-summary tbody td:nth-child(2)')).toHaveText([rate('reg'),rate('data'),rate('voice'),Number(row.active_users).toLocaleString('en')]);
  await expect(page.locator('.preview-note')).toContainText('Synthetic recorded');
  await expect(page.locator('.roaming-chart .expected-line')).toHaveAttribute('d','');
});

test('dashboard headlines use the same recorded readings and clear them on a failed read',async({page})=>{
  await controlledApi(page);const source=await recordedRoaming(page);await page.goto('/dashboard');
  const registration=page.locator('.headline-metrics > div').filter({hasText:'Roaming registration success'});
  await expect(registration.locator('strong')).toHaveText('98.0%');await expect(registration).toContainText('Synthetic recorded');
  await expect(page.locator('.headline-metrics > div').filter({hasText:'Partner user estimates'}).locator('strong')).toHaveText('1,000');
  source.unavailable=true;await page.reload();await expect(page.getByRole('button',{name:'Retry roaming data'})).toBeVisible();
  await expect(registration.locator('strong')).toHaveText('Unavailable');
  await expect(page.locator('.headline-metrics > div').filter({hasText:'Partner user estimates'}).locator('strong')).toHaveText('Unavailable');
});

for(const width of [1440,390])test(`roaming filters and committed evidence retain source context at ${width}px`,async({page},info)=>{
  const state=await controlledApi(page),source=await recordedRoaming(page),{incident,records}=nativeIncidentFixture();
  const incidentQueries:Record<string,string>[]=[];let evidenceUnavailable=false;
  await page.route('**/api/v2/trace-link/incidents?**',route=>{
    incidentQueries.push(Object.fromEntries(new URL(route.request().url()).searchParams));
    return route.fulfill({json:{items:[incident],total:1,limit:50,offset:0}});
  });
  await page.route('**/api/v2/trace-link/incidents/*',route=>route.fulfill({json:incident}));
  await page.route('**/api/v2/trace-link/incidents/*/workflow',route=>route.fulfill({json:{episodeId:incident.episodeId,id:'workflow',status:'OPEN',version:1,assigneeId:null}}));
  await page.route('**/api/v2/trace-link/incidents/*/evidence?**',route=>evidenceUnavailable?route.fulfill({status:503,json:{code:'UNAVAILABLE'}}):route.fulfill({json:{items:records,total:2,limit:50,offset:0}}));
  await page.setViewportSize({width,height:900});await page.goto('/roaming?'+params());
  await expect(page.locator('.kpi-summary tbody td:nth-child(2)')).toHaveText(['98%','98%','98%','100']);
  await expect(page.getByRole('combobox',{name:'Country',exact:true})).toHaveValue('RO');await expect(page.getByRole('combobox',{name:'Partner / direction',exact:true})).toHaveValue('RO-DEMO-01:in');
  expect(source.queries).toHaveLength(4);expect(source.queries.every(query=>query.scopeId==='RO-DEMO-01:in' && query.from===from && query.to===to)).toBe(true);
  await expect.poll(()=>incidentQueries.length).toBeGreaterThan(0);
  expect(incidentQueries.at(-1)).toMatchObject({datasetId:'recorded-roaming',domain:'roaming',countryId:'RO',roamingDirection:'in',from,to});
  const evidenceButton=page.getByRole('button',{name:'View incident evidence',exact:true});
  await evidenceButton.focus();await page.keyboard.press('Enter');
  const drawer=page.getByRole('dialog',{name:'Incident evidence',exact:true});await expect(drawer).toBeVisible();
  await expect(drawer).toContainText('RO · RO-DEMO-01 · in · REGISTRATION');await expect(drawer).toContainText('Workflow OPEN');
  await expect(drawer.locator('.evidence-record')).toHaveCount(2);await expect(drawer).toContainText('80%');
  await expect(drawer).toContainText('99%');await expect(drawer).toContainText('analyst resolution is a separate workflow action');
  await expect(drawer).toContainText('controlled-source-sha');await expect(drawer).toContainText('Synthetic source');
  expect(await drawer.evaluate(node=>node.scrollWidth<=node.clientWidth+1)).toBe(true);
  await page.screenshot({path:info.outputPath(`roaming-evidence-${width}.png`),fullPage:true});
  await page.keyboard.press('Escape');await expect(drawer).not.toBeVisible();await expect(evidenceButton).toBeFocused();
  evidenceUnavailable=true;await evidenceButton.click();await expect(drawer.getByRole('button',{name:'Retry evidence'})).toBeVisible();
  await expect(drawer.locator('.evidence-record')).toHaveCount(0);evidenceUnavailable=false;
  await drawer.getByRole('button',{name:'Retry evidence'}).click();await expect(drawer.locator('.evidence-record')).toHaveCount(2);
  await drawer.getByRole('button',{name:'Close incident evidence'}).click();
  await page.getByRole('button',{name:'Show on dashboard',exact:true}).click();
  await expect(page).toHaveURL(/\/roaming\?.*incidentId=/);await expect(page.locator('.focused-incident-band')).toBeVisible();await expect(page.locator('.focused-incident-line')).toHaveCSS('stroke','rgb(255, 80, 105)');
  const focused=new URL(page.url());expect(focused.searchParams.get('from')).toBe('2026-10-07T00:00:00.000Z');expect(focused.searchParams.get('to')).toBe('2026-10-07T03:00:00.000Z');
  expect(focused.searchParams.get('partnerDirectionId')).toBe('RO-DEMO-01:in');
  await page.getByRole('button',{name:'Dismiss incident highlight'}).click();await expect(page.locator('.focused-incident-line')).toHaveCount(0);
  await page.getByRole('button',{name:'Voice call success',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Roaming voice call success rate',exact:true})).toBeVisible();
  await expect(page.locator('[data-chart=roamingVoiceSrPct] .actual-line')).not.toHaveAttribute('d','');
  await page.getByRole('combobox',{name:'Roaming direction',exact:true}).selectOption('out');
  await expect(page.locator('.kpi-summary tbody td:nth-child(2)').first()).toHaveText('98%');
  await expect.poll(()=>source.queries.at(-1)?.scopeId.endsWith(':out')).toBe(true);
  await expect(page).toHaveURL(/roamingDirection=out/);expect(state.writes).toBe(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
