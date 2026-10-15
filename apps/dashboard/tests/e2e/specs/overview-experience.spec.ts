import {test,expect} from '../helpers/test';
import {controlledApi} from '../helpers/controlled-api';

for(const width of [1366,390])test(`overview separates traffic, selects links and ranks episode evidence at ${width}px`,async({page},info)=>{
 const state=await controlledApi(page);await page.setViewportSize({width,height:900});
 const episodes=[{...structuredClone(state.incident),id:'11111111-1111-4111-8111-111111111111',severity:'CRITICAL'},{...structuredClone(state.incident),id:'22222222-2222-4222-8222-222222222222',severity:'HIGH'}];
 await page.route('**/api/operations/priority*',route=>route.fulfill({json:{generatedAt:state.summary.observedAt,policyVersion:'controlled-priority',policyStatus:'ACTIVE',page:0,size:20,hasNext:false,items:episodes.map(item=>({incidentId:item.id,cityId:null,cityNullReason:'UNALLOCATED',service:item.service,scopeId:item.scopeId,technicalState:item.technicalState,analystStatus:item.status,severity:item.severity,severityHistorical:false,freshness:'FRESH',priorityBand:'FRESH_ONGOING',comparableImpact:null,impactUnit:null,firstObservedAt:item.firstObservedAt,detectedAt:item.detectedAt,latestWindowEnd:item.lastObservedAt}))}}));
 for(const episode of episodes)await page.route('**/api/incidents/'+episode.id,route=>route.fulfill({json:{...episode,location:{cityId:null,catalogueVersion:null,topologyVersion:null,measuredScopeId:episode.scopeId,containmentPath:[],dependencyNodeIds:[],nullReason:'UNALLOCATED'}}}));
 await page.goto('/dashboard');
 await expect(page.locator('.map-note')).toHaveCount(0);
 await expect(page.locator('[data-chart=callTraffic]')).toHaveCount(1);
 await expect(page.locator('.voice-success [data-chart=cssrPct]')).toHaveCount(1);
 await expect(page.locator('[data-chart=callTraffic] .traffic-success-bar').first()).toBeVisible();
 await expect(page.locator('[data-chart=callTraffic] .actual-line')).toHaveCount(0);
 await expect(page.locator('.voice-success .actual-line')).not.toHaveAttribute('d','');
 await expect(page.locator('.inventory-link')).toHaveCount(12);
 const map=page.locator('.city-map');await map.scrollIntoViewIfNeeded();const before=(await map.boundingBox())!;
 for(const marker of await page.locator('.link-marker').all()) {await marker.click();await expect(marker).toHaveAttribute('aria-pressed','true');}
 await page.getByRole('button',{name:'Inspect Chișinău – Hîncești',exact:true}).click();
 await expect(page.getByRole('region',{name:'Directional link investigation'})).toContainText('chi-hin');
 expect((await map.boundingBox())!.y).toBeCloseTo(before.y,0);
 const href=await page.getByRole('link',{name:'Investigate A → B',exact:true}).getAttribute('href');
 expect(new URL(href!,'http://local').searchParams.get('linkDirectionId')).toBe('chi-hin:a-to-b');
 const cards=page.locator('.active-anomalies .anomaly-card');await expect(cards).toHaveCount(2);
 await expect(cards.nth(0)).toHaveAttribute('data-state','CRITICAL');await expect(cards.nth(1)).toHaveAttribute('data-state','HIGH');
 await expect(cards.nth(0)).toContainText('evidence records');
 await expect(cards.nth(0).getByRole('link',{name:'Open episode evidence'})).toHaveAttribute('href','/incidents/'+episodes[0].id);
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
