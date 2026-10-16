import {test,expect,type Page,type Locator} from '../helpers/test';
import {controlledApi} from '../helpers/controlled-api';

async function acknowledgedCards(page:Page) {
  const state=await controlledApi(page);
  const items=[0,1].map(index=>({...structuredClone(state.incident),id:`11111111-1111-4111-8111-${String(index).padStart(12,'0')}`,episodeId:String(index+1).repeat(64),status:'INVESTIGATING',version:2}));
  await page.route(/\/api\/incidents\?/,route=>route.fulfill({json:{items,total:2,page:0,size:20}}));
  await page.route(/\/api\/incidents\/[^/]+\/timeline\?/,route=>{
    const id=new URL(route.request().url()).pathname.split('/')[3];
    return route.fulfill({json:{items:[{id:'audit-'+id,incidentId:id,actorKind:'ANALYST',actorId:'cb4468c6-fbdb-4750-bd71-4b5822dfb101',action:'STATUS_CHANGED',occurredAt:'2026-10-13T20:00:00Z',requestId:'controlled-audit',before:{status:'OPEN'},after:{status:'INVESTIGATING'},note:null}],total:1,page:0,size:20}});
  });
  return state;
}
async function equalActions(bar:Locator,width:number) {
  await expect(bar).toBeVisible();
  const metrics=await bar.locator(':scope > button,:scope > a.button').evaluateAll(nodes=>nodes.map(node=>{
    const rect=node.getBoundingClientRect(),style=getComputedStyle(node);
    return {width:Math.round(rect.width),height:Math.round(rect.height),padding:style.padding,font:style.fontSize,radius:style.borderRadius,y:Math.round(rect.y)};
  }));
  expect(metrics).toHaveLength(3);
  for(const item of metrics){expect(Math.abs(item.width-metrics[0].width)).toBeLessThanOrEqual(1);expect({...item,width:0}).toEqual({...metrics[0],width:0});expect(item.width).toBeLessThanOrEqual(128);expect(item.height).toBe(width<=600?44:34);}
}
for(const width of [1440,390]) test(`compact incident actions and persistent short references at ${width}px`,async({page,context},info)=>{
  const state=await acknowledgedCards(page),other=await context.newPage();await acknowledgedCards(other);
  await page.setViewportSize({width,height:900});
  await Promise.all([page.goto('/services/'+state.summary.scope.scopeId),other.goto('/services/'+state.summary.scope.scopeId)]);
  const cards=page.locator('.episode-card');await expect(cards).toHaveCount(2);
  await expect(cards.locator('.acknowledgement-label')).toHaveText([/VOL-\d+ · 13 Oct 20:00 UTC/,/VOL-\d+ · 13 Oct 20:00 UTC/]);
  const palette=await page.evaluate(()=>['HIGH','MEDIUM','LOW','CRITICAL'].map(state=>{
    const badge=document.createElement('span');badge.className='badge';badge.dataset.state=state;document.body.append(badge);
    const style=getComputedStyle(badge),value={foreground:style.color,background:style.backgroundColor};badge.remove();return value;
  }));
  expect(palette[0].foreground).not.toBe(palette[1].foreground);expect(palette[1].foreground).not.toBe(palette[2].foreground);expect(palette[3].background).not.toBe(palette[0].background);
  const labels=await cards.locator('.acknowledgement-label').allTextContents();
  expect(labels.map(value=>value.match(/VOL-\d+/)![0]).sort()).toEqual(['VOL-001','VOL-002']);
  await expect(other.locator('.episode-card .acknowledgement-label')).toHaveText(labels);
  await expect(cards.first()).not.toContainText('cb4468c6');await expect(cards.first().locator('.episode-info')).not.toContainText('Assigned');
  await equalActions(cards.first().locator('.incident-action-bar'),width);
  await expect(cards.first().getByRole('button',{name:'View incident evidence',exact:true})).toHaveCSS('background-color','rgb(227, 154, 91)');
  const actionStart=(await cards.first().locator('.incident-action-bar > :first-child').boundingBox())!;
  expect(actionStart.x-(await cards.first().locator('.incident-action-bar').boundingBox())!.x).toBeLessThan(1);
  await expect(cards.first().locator('.incident-action-bar > :last-child')).toHaveText('Open incident detail');
  await page.reload();await expect(cards.locator('.acknowledgement-label')).toHaveText(labels);
  await cards.first().getByRole('button',{name:'View incident evidence',exact:true}).focus();await page.keyboard.press('Enter');
  const drawer=page.getByRole('dialog',{name:'Incident evidence',exact:true});await expect(drawer).toBeVisible();
  await equalActions(drawer.locator('.incident-action-bar'),width);
  await page.keyboard.press('Escape');await expect(cards.first().getByRole('button',{name:'View incident evidence',exact:true})).toBeFocused();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  await cards.first().screenshot({path:info.outputPath(`incident-actions-${width}.png`)});
  await other.close();
});
for(const width of [1440,390,320]) test(`expanded overview charts and full-page incident queue at ${width}px`,async({page},info)=>{
  const state=await controlledApi(page);
  state.incident.location={cityId:null,catalogueVersion:null,topologyVersion:null,measuredScopeId:state.incident.scopeId,containmentPath:[],dependencyNodeIds:[],nullReason:'UNALLOCATED'};
  await page.route('**/api/operations/priority*',route=>route.fulfill({json:{generatedAt:state.summary.observedAt,policyVersion:'controlled-priority',policyStatus:'ACTIVE',page:0,size:20,hasNext:false,items:[{incidentId:state.incident.id,cityId:null,cityNullReason:'UNALLOCATED',service:state.incident.service,scopeId:state.incident.scopeId,technicalState:state.incident.technicalState,analystStatus:state.incident.status,severity:state.incident.severity,severityHistorical:false,freshness:'FRESH',priorityBand:'FRESH_ONGOING',comparableImpact:null,impactUnit:null,firstObservedAt:state.incident.firstObservedAt,detectedAt:state.incident.detectedAt,latestWindowEnd:state.incident.lastObservedAt}]}}));
  await page.setViewportSize({width,height:900});await page.goto('/dashboard');
  const alert=page.locator('.anomaly-card').first();await equalActions(alert.locator('.incident-action-bar'),width);
  await expect(alert.locator('.incident-action-bar > :first-child')).toHaveText('Episode evidence');
  expect(await alert.evaluate(node=>parseFloat(getComputedStyle(node).paddingLeft))).toBeGreaterThanOrEqual(18);
  const bar=alert.locator('.incident-action-bar');
  const positions=await bar.locator(':scope > *').evaluateAll(nodes=>nodes.map(node=>({left:node.getBoundingClientRect().left,right:node.getBoundingClientRect().right})));
  const barBox=(await bar.boundingBox())!;
  expect(positions[0].left).toBeCloseTo(barBox.x,1);expect(positions[2].right).toBeCloseTo(barBox.x+barBox.width,1);
  await expect(bar.getByRole('link',{name:'Episode evidence',exact:true})).toHaveCSS('background-color','rgb(227, 154, 91)');
  await alert.screenshot({path:info.outputPath(`priority-card-${width}.png`)});
  await expect(page.getByText('Intercity link list',{exact:true})).toHaveCount(0);
  await expect(page.locator('.city-marker')).toHaveCount(10);await expect(page.locator('.map-link')).toHaveCount(12);
  for(const plot of await page.locator('.service-column .chart-scroll:has(svg)').all())expect((await plot.boundingBox())!.height).toBeGreaterThanOrEqual(300);
  await expect(page.locator('.call-donut')).toHaveCSS('width','62px');
  await page.getByRole('button',{name:'Show SMS graph',exact:true}).click();
  expect((await page.locator('[data-chart=deliverySrPct] .chart-scroll').boundingBox())!.height).toBeGreaterThanOrEqual(300);
  await page.getByRole('button',{name:'Open incident investigation',exact:true}).click();
  const queue=page.getByRole('dialog',{name:'Incident queue',exact:true});await expect(queue).toBeVisible();
  const box=(await queue.boundingBox())!;expect(box.x).toBe(0);expect(box.y).toBe(0);expect(box.width).toBe(width);expect(box.height).toBe(900);
  await expect(queue.locator('.queue-filters select')).toHaveCount(3);
  const filterY=await queue.locator('.queue-filters select').evaluateAll(nodes=>nodes.map(node=>node.getBoundingClientRect().y));expect(Math.max(...filterY)-Math.min(...filterY)).toBeLessThan(1);
  await expect(queue).not.toContainText('awaiting acknowledgement');await expect(queue).not.toContainText('UNALLOCATED');await expect(queue).not.toContainText('Impact unavailable');
  await equalActions(queue.locator('.queue-item .incident-action-bar').first(),width);
  await queue.screenshot({path:info.outputPath(`full-page-queue-${width}.png`)});
  await page.getByRole('button',{name:'Close incidents',exact:true}).click();await expect(queue).not.toBeVisible();
});
