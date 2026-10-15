import {test as base} from '@playwright/test';
export * from '@playwright/test';

// Controlled workflows must never accidentally call a running private backend.
export const test=base.extend({
  page:async({page},use)=>{
    const live=Object.entries(process.env).some(([key,value])=>key.startsWith('E2E_')&&/LIVE|REAL|AUTH/.test(key)&&value&&value!=='0');
    if(!live)await page.route(/\/api\/notifications(?:\?.*)?$/,route=>route.fulfill({json:{items:[],total:0,unreadCount:0,page:0,size:20}}));
    await use(page);
  },
});
