import {test,expect} from '../helpers/test';
import {controlledApi} from '../helpers/controlled-api';

test('browser alerts request nothing on startup and feature-off retains durable history',async({page})=>{
  await page.addInitScript(()=>Object.defineProperty(Notification,'permission',{get:()=> 'default'}));
  await controlledApi(page);let configRequests=0,workerRequests=0;
  await page.route('**/api/notifications/push/config',route=>{configRequests++;return route.fulfill({json:{enabled:false,vapidPublicKey:null}});});
  await page.route('**/push-worker.js',route=>{workerRequests++;return route.abort();});
  await page.goto('/dashboard');await page.getByRole('button',{name:/Notifications,/}).click();
  expect(configRequests).toBe(0);expect(workerRequests).toBe(0);
  await page.getByRole('button',{name:'Enable browser alerts',exact:true}).click();
  await expect(page.getByRole('region',{name:'Browser alert settings'})).toContainText('disabled by the service');
  expect(configRequests).toBe(1);expect(workerRequests).toBe(0);
  await expect(page.getByRole('heading',{name:'Notification history'})).toBeVisible();
});
test('denied browser permission does not request configuration or prompt again',async({page})=>{
  await controlledApi(page);let requests=0;
  await page.addInitScript(()=>Object.defineProperty(Notification,'permission',{get:()=> 'denied'}));
  await page.route('**/api/notifications/push/config',route=>{requests++;return route.fulfill({json:{enabled:true,vapidPublicKey:'unused'}});});
  await page.goto('/dashboard');await page.getByRole('button',{name:/Notifications,/}).click();
  await page.getByRole('button',{name:'Enable browser alerts',exact:true}).click();
  await expect(page.getByRole('region',{name:'Browser alert settings'})).toContainText('denied');
  await expect(page.getByRole('button',{name:'Enable browser alerts',exact:true})).toBeDisabled();expect(requests).toBe(0);
});
