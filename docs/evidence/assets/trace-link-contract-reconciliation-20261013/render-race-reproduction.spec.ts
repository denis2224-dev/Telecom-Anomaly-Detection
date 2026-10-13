import { test, expect, type Page } from '@playwright/test';
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
const exactCsv='from,value,numerator,denominator\r\n"2026-09-10T09:00:00Z","0","0","10"\r\n"2026-09-10T09:05:00Z",,"0","0"\r\n';
function series(url:URL){
  const params=Object.fromEntries(url.searchParams),seconds=({'5m':300,'15m':900,'1h':3600,'6h':21600} as Record<string,number>)[params.resolution];
  const from=Math.floor(Date.parse(params.from)/(seconds*1000))*seconds*1000,to=Math.ceil(Date.parse(params.to)/(seconds*1000))*seconds*1000;
  return {...params,requestedFrom:params.from,requestedTo:params.to,effectiveFrom:new Date(from).toISOString(),effectiveTo:new Date(to).toISOString(),partialStart:from!==Date.parse(params.from),partialEnd:to!==Date.parse(params.to),sourceResolutionSeconds:300,unit:'PERCENT',aggregation:'RATIO_OF_SUMS',synthetic:true,policyVersion:definition.policyVersion,topologyVersion:'trace-link-md-topology-v1',nextCursor:null,
    points:Array.from({length:(to-from)/seconds/1000},(_,index)=>({from:new Date(from+index*seconds*1000).toISOString(),to:new Date(from+(index+1)*seconds*1000).toISOString(),value:[0,null,50,45][index%4],coverage:1,quality:index%4===3?'STALE':index%4===1?'INSUFFICIENT_EVIDENCE':'VALID',numerator:index%4===1?0:[0,0,5,9][index%4],denominator:index%4===1?0:10,sumValue:null,sampleCount:null}))};
}
async function setup(page:Page){
  await controlledApi(page);const queries:Record<string,string>[]=[];
  await page.route('**/api/v2/**',route=>{
    const url=new URL(route.request().url());
    if(url.pathname==='/api/v2/inventory/cities')return route.fulfill({json:{catalogueVersion:'controlled-native-v1',cities}});
    if(url.pathname==='/api/v2/inventory/links')return route.fulfill({json:{catalogueVersion:'controlled-native-v1',links}});
    if(url.pathname==='/api/v2/inventory/partners')return route.fulfill({json:{catalogueVersion:'controlled-native-v1',partners}});
    if(url.pathname==='/api/v2/kpi-definitions')return route.fulfill({json:[definition]});
    queries.push(Object.fromEntries(url.searchParams));
    if(url.pathname.endsWith('.csv'))return route.fulfill({contentType:'text/csv;charset=utf-8',body:exactCsv});
    return route.fulfill({json:series(url)});
  });return queries;
}

test('controlled reset render race loses the old opener before Enter at 1366px', async ({page}, info) => {
  await setup(page); await page.setViewportSize({width:1366,height:900}); await page.goto(queryUrl);
  const expand=page.getByRole('button',{name:'Expand metric',exact:true});
  await expect(expand).toBeVisible(); const oldOpener=await expand.elementHandle();
  let release!:()=>void, requested=false;
  await page.route('**/api/v2/metrics/series?**',async route=>{
    requested=true; await new Promise<void>(resolve=>release=resolve);
    await route.fulfill({json:series(new URL(route.request().url()))});
  });
  // Force the permitted interleaving: query event, focus, then Angular's scheduled render.
  const focusedBeforeRender=await page.evaluate(()=>{
    const buttons=Array.from(document.querySelectorAll('button'));
    (buttons.find(button=>button.textContent==='Reset zoom') as HTMLButtonElement).click();
    const opener=buttons.find(button=>button.textContent==='Expand metric')!; opener.focus();
    return document.activeElement===opener;
  });
  expect(focusedBeforeRender).toBe(true);
  await expect.poll(()=>requested).toBe(true);
  await expect.poll(()=>oldOpener!.evaluate(element=>element.isConnected)).toBe(false);
  release(); await expect(expand).toBeVisible();
  const focusAfterRender=await page.evaluate(()=>document.activeElement?.tagName);
  console.log(JSON.stringify({focusedBeforeRender,oldOpenerDetached:true,focusAfterRender}));
  await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog',{name:'Expanded persisted metric'})).toContainText('2026-09-10T09:00:00Z');
});
