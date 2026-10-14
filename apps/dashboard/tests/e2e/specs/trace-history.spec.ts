import { test, expect, type Page } from '../helpers/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { controlledApi } from '../helpers/controlled-api';

const csvRows=(name:string)=>{
  const [header,...lines]=readFileSync(resolve(__dirname,`../../../../../contracts/fixtures/trace-link/native-slice/${name}.csv`),'utf8').trim().split(/\r?\n/);
  return lines.map(line=>Object.fromEntries(header.split(',').map((key,index)=>[key,line.split(',')[index]])));
};
const active={active:true,validFrom:'2026-09-10T09:00:00Z',validTo:null,synthetic:true};
const cities=csvRows('cities').map(city=>({...active,id:city.id,name:city.name,latitude:+city.latitude,longitude:+city.longitude,demandShare:+city.demand_share}));
const links=csvRows('links').map(link=>({...active,id:link.id,endpointA:link.a,endpointB:link.b,linkType:'FIBER',nominalCapacityGbps:+link.nominal_capacity_gbps,
  directions:['a-to-b','b-to-a'].map(id=>({id,effectiveCapacityGbps:+link.nominal_capacity_gbps,capacityValidFrom:active.validFrom,capacityValidTo:null,measurementSource:'controlled reference fixture'}))}));
const partners=csvRows('countries').flatMap(country=>['in','out'].map(direction=>({...active,id:country.partner_id,country:country.id,direction})));
const definition={domain:'volte',metric:'voice.success_rate',unit:'PERCENT',aggregation:'RATIO_OF_SUMS',population:'VoLTE call attempts in the selected city',primitiveFields:['numerator','denominator'],policyVersion:'trace-link-md-policy-v1'};
const queryUrl='/trace-link?datasetId=trace-link-md-30d-v1&from=2026-09-10T09%3A00%3A00Z&to=2026-09-10T09%3A20%3A00Z&resolution=5m&cityId=chi';
const exactCsv='from,to,value,numerator,denominator,quality\r\n'
  +'"2026-09-10T09:00:00Z","2026-09-10T09:05:00Z","0","0","10","VALID"\r\n'
  +'"2026-09-10T09:05:00Z","2026-09-10T09:10:00Z",,"0","0","INSUFFICIENT_EVIDENCE"\r\n'
  +'"2026-09-10T09:10:00Z","2026-09-10T09:15:00Z","50","5","10","VALID"\r\n'
  +'"2026-09-10T09:15:00Z","2026-09-10T09:20:00Z","45","9","20","STALE"\r\n';
function series(url:URL){
  const params=Object.fromEntries(url.searchParams),seconds=({'5m':300,'15m':900,'1h':3600,'6h':21600} as Record<string,number>)[params.resolution];
  const from=Math.floor(Date.parse(params.from)/(seconds*1000))*seconds*1000,to=Math.ceil(Date.parse(params.to)/(seconds*1000))*seconds*1000;
  return {...params,requestedFrom:params.from,requestedTo:params.to,effectiveFrom:new Date(from).toISOString(),effectiveTo:new Date(to).toISOString(),partialStart:from!==Date.parse(params.from),partialEnd:to!==Date.parse(params.to),sourceResolutionSeconds:300,unit:'PERCENT',aggregation:'RATIO_OF_SUMS',synthetic:true,policyVersion:definition.policyVersion,topologyVersion:'trace-link-md-topology-v1',nextCursor:null,
    points:Array.from({length:(to-from)/seconds/1000},(_,index)=>({from:new Date(from+index*seconds*1000).toISOString(),to:new Date(from+(index+1)*seconds*1000).toISOString(),value:[0,null,50,45][index%4],coverage:1,quality:index%4===3?'STALE':index%4===1?'INSUFFICIENT_EVIDENCE':'VALID',numerator:index%4===1?0:[0,0,5,9][index%4],denominator:index%4===1?0:index%4===3?20:10,sumValue:null,sampleCount:null}))};
}
async function setup(page:Page){
  await controlledApi(page);const queries:Record<string,string>[]=[];
  await page.route('**/api/v2/**',route=>{
    const url=new URL(route.request().url());
    if(url.pathname==='/api/v2/inventory/cities')return route.fulfill({json:{catalogueVersion:'controlled-native-v1',cities}});
    if(url.pathname==='/api/v2/inventory/links')return route.fulfill({json:{catalogueVersion:'controlled-native-v1',links}});
    if(url.pathname==='/api/v2/inventory/partners')return route.fulfill({json:{catalogueVersion:'controlled-native-v1',partners}});
    if(url.pathname==='/api/v2/kpi-definitions')return route.fulfill({json:[definition]});
    if(url.pathname==='/api/v2/trace-domain-measurements')return route.fulfill({status:404,json:{code:'FEATURE_DISABLED'}});
    queries.push(Object.fromEntries(url.searchParams));
    if(url.pathname.endsWith('.csv'))return route.fulfill({contentType:'text/csv;charset=utf-8',body:exactCsv});
    return route.fulfill({json:series(url)});
  });return queries;
}
for(const width of [1366,390])test(`native history keeps query, inventory, gaps and exact export at ${width}px`,async({page},info)=>{
  const queries=await setup(page);await page.setViewportSize({width,height:900});await page.goto(queryUrl);
  await expect(page.getByRole('region',{name:'Approved topology'})).toContainText('10 active cities · 12 active links');
  await expect(page.getByRole('region',{name:'Persisted metric chart'})).toContainText('Unavailable · zero denominator');
  await expect(page.getByRole('region',{name:'Persisted metric chart'})).toContainText('STALE');
  await expect(page.getByRole('region',{name:'Persisted metric chart'})).toContainText('INSUFFICIENT_EVIDENCE');
  const path=await page.getByRole('region',{name:'Persisted metric chart'}).locator('svg path').getAttribute('d');
  expect(path?.match(/M/g)).toHaveLength(2);
  await expect(page.getByRole('region',{name:'Persisted metric chart'}).locator('svg circle')).toHaveCount(3);
  const selectedFrom=await page.getByLabel('From · UTC inclusive').inputValue();
  await page.getByRole('combobox',{name:'Resolution',exact:true}).selectOption('1h');await expect.poll(()=>queries.at(-1)?.resolution).toBe('1h');
  await expect(page.getByLabel('From · UTC inclusive')).toHaveValue(selectedFrom);
  await page.getByRole('combobox',{name:'Resolution',exact:true}).selectOption('5m');
  await page.getByRole('button',{name:'Zoom in',exact:true}).click();await expect.poll(()=>queries.at(-1)?.from).toBe('2026-09-10T09:05:00.000Z');
  await page.getByRole('button',{name:'Reset zoom',exact:true}).click();
  const expand=page.getByRole('button',{name:'Expand metric',exact:true});await expect(expand).toBeVisible();await expand.focus();await page.keyboard.press('Enter');
  const expanded=page.getByRole('dialog',{name:'Expanded persisted metric'});await expect(expanded).toContainText('2026-09-10T09:00:00Z');
  await page.keyboard.press('Escape');await expect(expanded).not.toBeVisible();await expect(expand).toBeFocused();
  const plot=page.getByRole('region',{name:'Persisted metric chart'}).locator('svg');await plot.scrollIntoViewIfNeeded();const box=(await plot.boundingBox())!;
  await page.mouse.move(box.x+box.width*.25,box.y+box.height/2);await page.mouse.down();await page.mouse.move(box.x+box.width*.75,box.y+box.height/2);await page.mouse.up();
  await expect.poll(()=>queries.at(-1)?.from).not.toBe(selectedFrom);await expect(page.getByLabel('From · UTC inclusive')).toHaveValue(selectedFrom);
  await page.getByRole('button',{name:'Reset zoom',exact:true}).click();
  await expect(page.getByRole('button',{name:'Download exact server CSV'})).toBeEnabled();
  const downloadEvent=page.waitForEvent('download');await page.getByRole('button',{name:'Download exact server CSV'}).click();
  const download=await downloadEvent;const downloadedCsv=readFileSync((await download.path())!,'utf8');expect(downloadedCsv).toBe(exactCsv);
  const exportedValues=downloadedCsv.trim().split(/\r?\n/).slice(1).map(line=>{const value=line.split(',')[2].replaceAll('"','');return value===''?null:Number(value);});
  expect(exportedValues).toEqual([0,null,50,45]);
  await expect(page.getByRole('region',{name:'Persisted metric chart'}).locator('tbody tr td:nth-child(3)')).toHaveText(['0','Unavailable · zero denominator','50','45']);
  expect(queries.at(-1)).toMatchObject({datasetId:'trace-link-md-30d-v1',domain:'volte',metric:'voice.success_rate',scopeType:'CITY',scopeId:'chi',resolution:'5m',from:'2026-09-10T09:00:00Z',to:'2026-09-10T09:20:00Z'});
  await page.getByRole('button',{name:'chi-hin b-to-a',exact:true}).click();await expect(page.getByRole('region',{name:'Approved topology'})).toContainText('Selected chi-hin:b-to-a');
  await expect(page.getByRole('row').filter({has:page.getByRole('button',{name:'chi-hin b-to-a',exact:true})})).toContainText('hin → chi');
  await page.getByRole('combobox',{name:'City',exact:true}).selectOption('bal');await expect(page.getByRole('region',{name:'Approved topology'})).not.toContainText('Selected chi-hin');
  await page.getByRole('button',{name:'Roaming',exact:true}).click();await expect(page.getByRole('region',{name:'Persisted domain panels'})).toContainText('Registered rollups are unavailable');
  await page.getByRole('combobox',{name:'Country',exact:true}).selectOption('RO');await page.getByRole('combobox',{name:'Roaming direction',exact:true}).selectOption('in');
  await expect(page.getByRole('region',{name:'Persisted domain panels'})).toContainText('RO-DEMO-01 · in');
  await expect(page.getByRole('combobox',{name:'City',exact:true})).toHaveValue('bal');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  await page.screenshot({path:info.outputPath(`trace-history-${width}.png`),fullPage:true});
});

test('expanded history restores focus when a pending response replaces the loading state',async({page})=>{
  await setup(page);let release!:()=>void,started=false;
  await page.route('**/api/v2/metrics/series?**',async route=>{
    started=true;await new Promise<void>(resolve=>release=resolve);
    await route.fulfill({json:series(new URL(route.request().url()))});
  });
  await page.goto(queryUrl);await expect.poll(()=>started).toBe(true);
  const expand=page.getByRole('button',{name:'Expand metric',exact:true});
  await expand.focus();await page.keyboard.press('Enter');
  const expanded=page.getByRole('dialog',{name:'Expanded persisted metric'});
  await expect(expanded).toContainText('Loading selected history');
  release();await expect(expanded.getByRole('region',{name:'Persisted metric chart'})).toContainText('INSUFFICIENT_EVIDENCE');
  await page.keyboard.press('Escape');await expect(expanded).not.toBeVisible();await expect(expand).toBeFocused();
});

test('a cancelled old-city response cannot replace the selected city history',async({page})=>{
  await setup(page);let release!:()=>void,started=false;
  await page.route('**/api/v2/metrics/series?**',async route=>{
    const url=new URL(route.request().url());if(url.searchParams.get('scopeId')==='chi'){started=true;await new Promise<void>(resolve=>release=resolve);}
    await route.fulfill({json:series(url)}).catch(()=>{});
  });
  await page.goto(queryUrl);await expect.poll(()=>started).toBe(true);await page.getByRole('combobox',{name:'City',exact:true}).selectOption('hin');
  await expect(page.getByRole('region',{name:'Persisted domain panels'})).toContainText('CITY hin');release();
  await page.waitForTimeout(200);await expect(page.getByRole('region',{name:'Persisted domain panels'})).toContainText('CITY hin');
  await expect(page.getByRole('region',{name:'Persisted domain panels'})).not.toContainText('CITY chi');
});

for(const domain of ['transport','roaming','mobile-data'])test(`native ${domain} preserves complete scope and unknown source quality`,async({page})=>{
  await setup(page);
  const scope=domain==='transport'?{scopeNamespace:'trace-link-reference-v1',domain:'TRANSPORT',scopeType:'LINK_DIRECTION',scopeId:'chi-hin',linkId:'chi-hin',directionId:'a-to-b'}
    :domain==='roaming'?{scopeNamespace:'trace-link-reference-v1',domain:'ROAMING',scopeType:'ROAMING_PROCEDURE',scopeId:'RO-DEMO-01',partnerId:'RO-DEMO-01',countryId:'RO',direction:'out',procedure:'REGISTRATION'}
    :{scopeNamespace:'trace-link-reference-v1',domain:'MOBILE_DATA',scopeType:'CITY_SERVICE',scopeId:'chi',contractCityId:'CHI',procedure:'EPS_BEARER_ACTIVATION',accessTechnology:'LTE'};
  const {createHash}=await import('node:crypto');const scopeKey=createHash('sha256').update(JSON.stringify(scope)).digest('hex');
  const metric=domain==='transport'?'transport.traffic':domain==='roaming'?'roaming.registration.success':'mobileData.activation.success';
  let requested:Record<string,string>|undefined;
  await page.route('**/api/v2/trace-domain-measurements?**',route=>{
    const params=Object.fromEntries(new URL(route.request().url()).searchParams);requested=params;
    return route.fulfill({json:{schemaVersion:'trace-link-domain-series-v1',mappingVersion:'trace-link-domain-measurement-mapping-v1',datasetId:params.datasetId,sourceId:params.sourceId,scopeKey,metricId:metric,domain,unit:'PERCENT',aggregationType:'RATIO_OF_SUMS',from:params.from,to:params.to,sourceResolutionSeconds:300,population:'Controlled independent native population',policyVersion:'controlled-native-v1',catalogueVersion:'controlled-native-v1',topologyVersion:'controlled-native-v1',sourceKind:'NATIVE_300',synthetic:true,scope,
      points:[{from:params.from,to:new Date(Date.parse(params.from)+300000).toISOString(),expectedSlots:1,receivedSlots:1,quality:'INSUFFICIENT_EVIDENCE',value:95,sourceCoverage:null,nullReason:null,measurement:null,qualityReason:'SOURCE_QUALITY_UNAVAILABLE',sourceQuality:null,eventId:'controlled-native',revision:0,finalized:true,primitives:{numerator:95,denominator:100},provenance:{nativeQualification:{procedureEligibility:'Unproven reference eligibility'}},sourcePayloadSha256:'a'.repeat(64)}]}});
  });
  await page.goto(queryUrl+`&domain=${domain}&linkDirectionId=chi-hin%3Aa-to-b&countryId=RO&roamingDirection=out`);
  const native=page.getByRole('region',{name:'Native domain measurements'});
  await expect(native).toContainText('Controlled independent native population');
  expect(requested).toMatchObject({scopeKey,metric,sourceId:'trace-link-native-primitives-v1',datasetId:'trace-link-md-30d-v1'});
  await expect(native.getByRole('region',{name:'Persisted metric chart'})).toContainText('INSUFFICIENT_EVIDENCE');
  await native.getByText('Exact source primitives and qualifications for the visible bucket page').click();
  await expect(native).toContainText('Unproven reference eligibility');
  await page.getByRole('combobox',{name:'Resolution',exact:true}).selectOption('15m');
  await expect(native).toContainText('Choose a dataset and domain scope');
  await expect(native.getByRole('region',{name:'Persisted metric chart'})).toHaveCount(0);
});
