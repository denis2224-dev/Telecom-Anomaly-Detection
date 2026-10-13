import { test, expect } from '@playwright/test';
import { controlledApi } from '../helpers/controlled-api';

// Controlled HTTP responses in a real browser. This is not connected acceptance.
const executable = ['TRANSPORT_LINK_OVERLOAD','ROAMING_REGISTRATION_FAILURE','MOBILE_DATA_DNS_FAILURE','NORMAL_CONTROL','TELEMETRY_GAP'];
const unsupported = ['VOLTE_SETUP_FAILURE','VOLTE_SETUP_DELAY','VOLTE_MEDIA_QUALITY','VOLTE_HANDOVER_FAILURE','TRANSPORT_PACKET_LOSS','TRANSPORT_RTT_INCREASE','TRANSPORT_CAPACITY_REDUCTION','ROAMING_DATA_FAILURE','ROAMING_PARTNER_DEGRADATION','MOBILE_DATA_ACTIVATION_FAILURE','SMS_DELIVERY_DELAY','SMS_QUEUE_CONGESTION','SMS_ROUTE_FAILURE'];
const defaults = {normalUtilizationBp:5000,degradedUtilizationBp:9800,capacityGbps:20,attempts:1000,healthySuccessBp:9900,degradedSuccessBp:7000,missingMinutes:[]};
const scope = (domain:string,id:string,direction:string|null, country:string|null=null,procedure:string|null=null) => ({domain,id,direction,country,procedure,topologyVersion:'trace-link-links-v1'});
const scopes = [
  ...['a-to-b','b-to-a'].map(direction=>({scope:scope('TRANSPORT','chi-hin',direction),label:direction==='a-to-b'?'chi → hin':'hin → chi',sourceCity:direction==='a-to-b'?'chi':'hin',destinationCity:direction==='a-to-b'?'hin':'chi',nominalCapacityGbps:20})),
  ...['in','out'].map(direction=>({scope:scope('ROAMING','RO-DEMO-01',direction,'RO','REGISTRATION'),label:`RO / RO-DEMO-01 / ${direction}`})),
  {scope:scope('MOBILE_DATA','chi',null,null,'DNS'),label:'Chișinău / LTE / DNS'},
];
const definitions = [...executable,...unsupported].map(type=>({type,version:'trace-link-scenarios-v1',implemented:executable.includes(type),domains:type==='NORMAL_CONTROL'||type==='TELEMETRY_GAP'?['TRANSPORT','ROAMING','MOBILE_DATA']:[type.startsWith('TRANSPORT')?'TRANSPORT':type.startsWith('ROAMING')?'ROAMING':'MOBILE_DATA'],scopeTypes:[],parameterRanges:{},expectedPrimitiveChanges:'Measured primitives only',positiveControl:'',negativeControl:''}));

for (const width of [1366,390]) test(`Trace Link scopes, exact input and interrupted history at ${width}px`,async({page},info)=>{
  await page.setViewportSize({width,height:900});
  await controlledApi(page);
  await page.route('**/api/auth/me',route=>route.fulfill({json:{analystId:'trace-browser',displayName:'Controlled supervisor',roles:['SUPERVISOR'],expiresAt:new Date(Date.now()+3600000).toISOString()}}));
  const calls:{method:string;path:string;body:any}[]=[];
  await page.route('**/api/trace-link/v1/scenarios/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;calls.push({method:request.method(),path,body:request.postDataJSON()});
    if(path.endsWith('/catalogue')) return route.fulfill({json:{enabled:true,synthetic:true,version:'trace-link-scenarios-v1',sourceNamespace:'trace-link-reference-v1',topologyVersion:'trace-link-links-v1',deliveryStatus:'DISABLED_PENDING_REVIEW',definitions,scopes,defaults}});
    if(request.method()==='GET'&&path.endsWith('/runs')) return route.fulfill({json:[]});
    if(request.method()==='POST'&&path.endsWith('/runs')) {
      const body=request.postDataJSON();
      return route.fulfill({status:202,json:{runId:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',fingerprint:'f'.repeat(64),command:{...body,start:'2026-10-12T09:00:00Z'},status:'INTERRUPTED',completedWindows:0,generatedObservations:0,deliveryStatus:'DISABLED_PENDING_REVIEW',offered:0,acknowledged:0,persisted:null,accepted:null,history:[]}});
    }
    return route.fulfill({status:404,json:{code:'NOT_FOUND'}});
  });
  await page.goto('/scenarios');
  await expect(page.getByRole('heading',{name:'Trace Link synthetic scenarios'})).toBeVisible();
  await expect(page.locator('#trace-type option')).toHaveCount(5);
  await page.getByLabel('Trace Link scenario',{exact:true}).selectOption('ROAMING_REGISTRATION_FAILURE');
  await expect(page.getByLabel('Country / synthetic partner')).toHaveValue('RO-DEMO-01');
  await page.getByLabel('Roaming direction',{exact:true}).selectOption('out');
  await expect(page.getByText('Country RO; partner RO-DEMO-01; procedure REGISTRATION.',{exact:false})).toBeVisible();
  await page.getByLabel('Trace Link scenario',{exact:true}).selectOption('MOBILE_DATA_DNS_FAILURE');
  await expect(page.getByLabel('Reference city / LTE DNS')).toHaveValue('chi');
  await expect(page.getByLabel('Link direction',{exact:true})).toHaveCount(0);
  await page.getByLabel('Degraded success (%)',{exact:true}).fill('70.001');
  await expect(page.getByRole('button',{name:'Start Trace Link scenario',exact:true})).toBeDisabled();
  await page.getByLabel('Degraded success (%)',{exact:true}).fill('70.01');
  await page.getByLabel('Missing minute indices (0–7, comma separated)',{exact:true}).fill('5,6,7');
  await page.getByRole('button',{name:'Start Trace Link scenario',exact:true}).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('INTERRUPTED',{exact:true})).toBeVisible();
  await expect(page.getByText('Incomplete executions do not resume',{exact:false})).toBeVisible();
  await expect(page.getByRole('button',{name:'Start Trace Link scenario',exact:true})).toBeEnabled();
  await expect(page.getByLabel('Trace Link scenario',{exact:true})).toHaveValue('MOBILE_DATA_DNS_FAILURE');
  const submitted=calls.find(call=>call.method==='POST'&&call.path.endsWith('/runs'))!.body;
  expect(submitted.parameters.degradedSuccessBp).toBe(7001);expect(submitted.parameters.missingMinutes).toEqual([5,6,7]);
  expect(submitted.scope).toEqual(scope('MOBILE_DATA','chi',null,null,'DNS'));
  await page.waitForTimeout(5200);
  expect(calls.filter(call=>call.method==='GET'&&/\/runs\//.test(call.path))).toHaveLength(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  await page.screenshot({path:info.outputPath(`trace-scenarios-${width}.png`),fullPage:true,animations:'disabled'});
});

test('feature off keeps legacy controls available',async({page})=>{
  await controlledApi(page);
  await page.route('**/api/auth/me',route=>route.fulfill({json:{analystId:'trace-browser',displayName:'Controlled supervisor',roles:['SUPERVISOR'],expiresAt:new Date(Date.now()+3600000).toISOString()}}));
  await page.route('**/api/trace-link/v1/scenarios/catalogue',route=>route.fulfill({json:{enabled:false,synthetic:true,version:'trace-link-scenarios-v1',deliveryStatus:'DISABLED_PENDING_REVIEW'}}));
  await page.goto('/scenarios');
  await expect(page.getByText('Trace Link scenarios are disabled in this environment.',{exact:false})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Legacy VoLTE and SMS scenarios'})).toBeVisible();
  await expect(page.getByLabel('Scenario',{exact:true})).toBeEnabled();
  await expect(page.getByRole('button',{name:'Start Trace Link scenario',exact:true})).toHaveCount(0);
});
