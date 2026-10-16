import {test,expect} from '../helpers/test';
import {controlledApi} from '../helpers/controlled-api';

for(const width of [1440,390])test(`overview combines traffic, selects links and ranks episode evidence at ${width}px`,async({page},info)=>{
 const state=await controlledApi(page);await page.setViewportSize({width,height:900});
 const episodes=[{...structuredClone(state.incident),id:'11111111-1111-4111-8111-111111111111',severity:'CRITICAL'},{...structuredClone(state.incident),id:'22222222-2222-4222-8222-222222222222',severity:'HIGH'}];
 episodes.push({...episodes[1],id:'33333333-3333-4333-8333-333333333333'},{...episodes[1],id:'44444444-4444-4444-8444-444444444444'});
 await page.route('**/api/operations/priority*',route=>route.fulfill({json:{generatedAt:state.summary.observedAt,policyVersion:'controlled-priority',policyStatus:'ACTIVE',page:0,size:20,hasNext:false,items:episodes.map(item=>({incidentId:item.id,cityId:null,cityNullReason:'UNALLOCATED',service:item.service,scopeId:item.scopeId,technicalState:item.technicalState,analystStatus:item.status,severity:item.severity,severityHistorical:false,freshness:'FRESH',priorityBand:'FRESH_ONGOING',comparableImpact:null,impactUnit:null,firstObservedAt:item.firstObservedAt,detectedAt:item.detectedAt,latestWindowEnd:item.lastObservedAt}))}}));
 for(const episode of episodes)await page.route('**/api/incidents/'+episode.id,route=>route.fulfill({json:{...episode,location:{cityId:null,catalogueVersion:null,topologyVersion:null,measuredScopeId:episode.scopeId,containmentPath:[],dependencyNodeIds:[],nullReason:'UNALLOCATED'}}}));
 await page.goto('/dashboard');
 await expect(page.locator('.map-note')).toHaveCount(0);
 await expect(page.locator('.voice-success [data-chart=cssrPct]')).toHaveCount(1);
 await expect(page.locator('.voice-success [data-chart=cssrPct] .traffic-success-bar').first()).toBeVisible();
 await expect(page.locator('.voice-success [data-chart=cssrPct] .actual-line')).toHaveCount(1);
 await expect(page.locator('.voice-success .actual-line')).not.toHaveAttribute('d','');
 await expect(page.locator('.inventory-link')).toHaveCount(12);
 const map=page.locator('.city-map');await map.scrollIntoViewIfNeeded();const before=(await map.boundingBox())!;
 for(const marker of await page.locator('.map-link').all()) {await marker.click();await expect(marker).toHaveAttribute('aria-pressed','true');}
 await page.getByRole('button',{name:'Inspect Chișinău – Hîncești',exact:true}).click();
 await expect(page.getByRole('region',{name:'Directional link investigation'})).toContainText('Chișinău – Hîncești');
 expect((await map.boundingBox())!.y).toBeCloseTo(before.y,0);
 const href=await page.getByRole('link',{name:'Investigate A → B',exact:true}).getAttribute('href');
 expect(new URL(href!,'http://local').searchParams.get('linkDirectionId')).toBe('chi-hin:a-to-b');
 await expect(page.locator('.map-panel > .active-anomalies')).toHaveCount(1);
 const cards=page.locator('.active-anomalies .anomaly-card');await expect(cards).toHaveCount(3);
 await expect(cards.nth(0)).toHaveAttribute('data-state','CRITICAL');await expect(cards.nth(1)).toHaveAttribute('data-state','HIGH');
 await expect(cards.nth(0)).toContainText('Acknowledged');
 await expect(cards.nth(0).locator('dt')).toHaveText(['Where','When','Why']);
 await expect(cards.nth(0)).not.toContainText('pp from baseline');
 await expect(cards.nth(0).getByRole('link',{name:'Episode evidence'})).toHaveAttribute('href','/incidents/'+episodes[0].id);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 await page.screenshot({path:info.outputPath(`overview-${width}.png`),fullPage:true});
});

test('long selected periods scroll and focus measured data without changing query bounds',async({page})=>{
 const state=await controlledApi(page);await page.goto('/dashboard');
 await page.getByRole('button',{name:'30d',exact:true}).click();
 const from=await page.getByLabel('From (UTC)',{exact:true}).inputValue(),to=await page.getByLabel('To (UTC, exclusive)',{exact:true}).inputValue();
 const chart=page.locator('.voice-success [data-chart=cssrPct]');
 await expect(chart.locator('svg')).toHaveCSS('min-width','6000px');
 expect(await chart.locator('.chart-scroll').evaluate(node=>node.scrollWidth>node.clientWidth)).toBe(true);
 await chart.getByRole('button',{name:'Focus recorded data',exact:true}).click();
 await expect(chart.locator('.chart-visible-range')).toContainText('selected period unchanged');
 await expect(chart.locator('.actual-line')).not.toHaveAttribute('d','');
 await expect(page.getByLabel('From (UTC)',{exact:true})).toHaveValue(from);await expect(page.getByLabel('To (UTC, exclusive)',{exact:true})).toHaveValue(to);
 await chart.getByRole('button',{name:'Full selected period',exact:true}).click();await expect(chart.locator('.chart-visible-range')).toHaveCount(0);
});

test('incident actions persist acknowledgement and mark the dedicated service chart period',async({page},info)=>{
 const state=await controlledApi(page);const analystId=state.incident.assigneeId;state.incident.status='OPEN';state.incident.assigneeId=null;
 state.incident.location={cityId:null,catalogueVersion:null,topologyVersion:null,measuredScopeId:state.incident.scopeId,containmentPath:[],dependencyNodeIds:[],nullReason:'UNALLOCATED'};
 await page.route('**/api/operations/priority*',route=>route.fulfill({json:{generatedAt:state.summary.observedAt,policyVersion:'controlled-priority',policyStatus:'ACTIVE',page:0,size:20,hasNext:false,items:[{incidentId:state.incident.id,cityId:null,cityNullReason:'UNALLOCATED',service:state.incident.service,scopeId:state.incident.scopeId,technicalState:state.incident.technicalState,analystStatus:state.incident.status,severity:state.incident.severity,severityHistorical:false,freshness:'FRESH',priorityBand:'FRESH_ONGOING',comparableImpact:null,impactUnit:null,firstObservedAt:state.incident.firstObservedAt,detectedAt:state.incident.detectedAt,latestWindowEnd:state.incident.lastObservedAt}]}}));
 const id=state.incident.id;const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
 await page.route('**/api/incidents/'+id+'/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  if(path.endsWith('/assignment')) {state.incident={...state.incident,assigneeId:analystId,version:state.incident.version+1};return route.fulfill({json:state.incident});}
  if(path.endsWith('/status')) {state.incident={...state.incident,status:'INVESTIGATING',version:state.incident.version+1};return route.fulfill({json:state.incident});}
  if(path.endsWith('/timeline'))return route.fulfill({json:{items:state.incident.status==='OPEN'?[]:[{id:'audit-1',incidentId:id,actorKind:'ANALYST',actorId:'controlled-analyst',action:'STATUS_CHANGED',occurredAt:'2026-10-13T20:00:00Z',requestId:'test-action',before:{status:'OPEN'},after:{status:'INVESTIGATING'},note:null}],total:state.incident.status==='OPEN'?0:1,page:0,size:100}});
  return route.fallback();
 });
 await page.goto('/dashboard');
 const card=page.locator('.anomaly-card').first();await expect(card).toBeVisible();
 await card.getByRole('button',{name:'Acknowledge',exact:true}).click();
 await expect(card.getByRole('button',{name:'Acknowledged',exact:true})).toBeDisabled();
 await expect(card).not.toContainText('controlled-analyst');
 await page.getByRole('button',{name:'24h',exact:true}).click();
 const selectedFrom=await page.getByLabel('From (UTC)',{exact:true}).inputValue(),selectedTo=await page.getByLabel('To (UTC, exclusive)',{exact:true}).inputValue();
 await card.getByRole('button',{name:'Show on dashboard',exact:true}).click();
 await expect(page).toHaveURL(new RegExp('/services/'+state.incident.scopeId+'\\?.*incidentId='+id));
 await expect(page.locator('.incident-focus')).toBeVisible();
 await expect(page.locator('.service-hero .focused-incident-band')).toHaveCount(1);
 const url=new URL(page.url());expect(url.searchParams.get('from')).toBe(new Date(selectedFrom+'Z').toISOString());
 expect(url.searchParams.get('to')).toBe(new Date(selectedTo+'Z').toISOString());
 const chart=page.locator('.service-hero');await expect(chart.locator('.focused-incident-line')).toHaveCount(1);
 await expect(chart.locator('.chart-visible-range')).toHaveCount(0);
 await chart.screenshot({path:info.outputPath('incident-marker-full-period.png')});
 await chart.getByRole('button',{name:'Zoom in',exact:true}).click();await expect(chart.locator('.chart-visible-range')).toBeVisible();await expect(chart.locator('.focused-incident-line')).toHaveCount(1);
 await chart.screenshot({path:info.outputPath('incident-marker-manual-zoom.png')});
 await page.getByRole('button',{name:'Dismiss highlight',exact:true}).click();
 await expect(page.locator('.focused-incident-band')).toHaveCount(0);
 await page.goto('/dashboard');await expect(card.getByRole('button',{name:'Acknowledged',exact:true})).toBeDisabled();
 expect(errors).toEqual([]);
});


test('plain map targets retain their positions while hovering and selecting',async({page})=>{
 await controlledApi(page);await page.goto('/dashboard');
 await expect(page.locator('.city-marker')).toHaveCount(10);
 await expect(page.locator('.link-marker')).toHaveCount(0);
 for(const marker of await page.locator('.city-marker').all()) {
  const position=()=>marker.evaluate(node=>{const box=node.getBoundingClientRect(),map=node.closest('.city-map')!.getBoundingClientRect();return {x:box.x-map.x,y:box.y-map.y,width:box.width,height:box.height};});
  const initial=await position();await marker.hover();expect(await position()).toEqual(initial);
  expect(await marker.locator('.marker-symbol').textContent()).toBe('');
  await marker.click();expect(await position()).toEqual(initial);
 }
 const link=page.getByRole('button',{name:'Inspect Bălți – Ungheni',exact:true});await link.click();
 await expect(page.getByRole('region',{name:'Directional link investigation'})).toContainText('nominal capacity');
 await expect(page.getByRole('region',{name:'Directional link investigation'})).toContainText('Traffic, utilization, loss and baseline unavailable');
});

test('incident summary scrolls with its evidence instead of covering it',async({page})=>{
 const state=await controlledApi(page);await page.goto('/incidents/'+state.incident.id);
 const summary=page.locator('.incident-summary-bar');await expect(summary).toBeVisible();
 await expect(summary).toHaveCSS('position','static');
 const before=(await summary.boundingBox())!.y;
 await page.evaluate(()=>window.scrollBy(0,300));
 expect((await summary.boundingBox())!.y).toBeLessThan(before);
});
