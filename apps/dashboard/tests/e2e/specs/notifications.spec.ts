import { test, expect } from '../helpers/test';
import { controlledApi } from '../helpers/controlled-api';

test('durable notifications keep failed read state, qualify causes and restore keyboard focus',async({page})=>{
  await controlledApi(page);
  let readAt: string|null=null,fail=true;
  await page.route(/\/api\/notifications(?:\?.*)?$/,route=>route.fulfill({json:{items:[{
    id:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',incidentId:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',kind:'RECOVERED',severity:'HIGH',
    service:'VOLTE',scopeId:'hin',summary:'Technical recovery recorded',occurredAt:'2026-10-12T09:00:00Z',readAt,
    causeStatus:'UNDETERMINED',probableCause:null,causeConfidence:null,incidentUrl:'/incidents/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  }],total:1,unreadCount:readAt?0:1,page:0,size:20}}));
  await page.route('**/api/notifications/*/read',route=>{
    if(fail)return route.fulfill({status:503,json:{code:'UNAVAILABLE'}});
    readAt='2026-10-12T09:01:00Z';return route.fulfill({status:204});
  });
  await page.setViewportSize({width:390,height:900});await page.goto('/dashboard');
  const bell=page.getByRole('button',{name:'Notifications, 1 unread'});await bell.focus();await page.keyboard.press('Enter');
  const dialog=page.getByRole('dialog',{name:'Notification history'});await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('Cause undetermined');await expect(dialog).toContainText('Recovery does not resolve analyst work');
  await dialog.getByRole('button',{name:'Mark read',exact:true}).click();await expect(dialog.getByRole('alert')).toBeVisible();
  await expect(dialog).toContainText('Unread');fail=false;await dialog.getByRole('button',{name:'Mark read',exact:true}).click();
  await expect(dialog.getByRole('button',{name:'Mark read',exact:true})).toHaveCount(0);
  await page.keyboard.press('Escape');await expect(dialog).not.toBeVisible();await expect(page.getByRole('button',{name:'Notifications, 0 unread'})).toBeFocused();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});

test('two authenticated tabs share each new high alert and repeated hints remain silent',async({page,context})=>{
  const second=await context.newPage();let items:object[]=[];
  for(const tab of [page,second]){
    await controlledApi(tab);
    await tab.route(/\/api\/notifications(?:\?.*)?$/,route=>route.fulfill({json:{items,total:items.length,unreadCount:items.length,page:0,size:20}}));
    const loaded=tab.waitForResponse(response=>new URL(response.url()).pathname==='/api/notifications');
    await tab.goto('/dashboard');await loaded;
  }
  items=[{id:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',incidentId:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',kind:'OPEN',severity:'HIGH',service:'VOLTE',scopeId:'hin',summary:'Recorded opening',occurredAt:'2026-10-12T09:00:00Z',readAt:null,causeStatus:'UNDETERMINED',probableCause:null,causeConfidence:null,incidentUrl:'/incidents/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'}];
  for(const tab of [page,second])await tab.evaluate(()=>{for(const source of (window as any).__sources)source.dispatchEvent(new Event('ready'));});
  await expect.poll(async()=>await page.locator('.toast').count()+await second.locator('.toast').count()).toBe(1);
  for(const tab of [page,second])await tab.evaluate(()=>{for(const source of (window as any).__sources)source.dispatchEvent(new Event('ready'));});
  await page.waitForTimeout(400);expect(await page.locator('.toast').count()+await second.locator('.toast').count()).toBe(1);
  for(const tab of [page,second])expect(await tab.evaluate(()=>(window as any).__sources.filter((source:any)=>!source.closed).length)).toBe(1);
});
