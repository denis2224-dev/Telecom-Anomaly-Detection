import {defineConfig} from '@playwright/test';
import original from './playwright.config';

export default defineConfig({...original,
 testMatch:['accessibility.spec.ts','trace-history.spec.ts','notifications.spec.ts','trace-scenarios.spec.ts','native-investigation.spec.ts','reconnect.spec.ts'],
 testIgnore:[],workers:2,retries:0,timeout:60_000,
 outputDir:'./test-results/frontend-matrix',
 projects:[{name:'chromium',use:{browserName:'chromium'}},{name:'firefox',use:{browserName:'firefox',launchOptions:{firefoxUserPrefs:{'accessibility.tabfocus':7}}}},{name:'webkit',use:{browserName:'webkit'}}],
});
