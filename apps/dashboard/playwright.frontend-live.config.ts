import {defineConfig} from '@playwright/test';

if(process.env.E2E_REAL_LOGIN!=='1'||process.env.E2E_RELEASE_LIVE!=='1'||process.env.SESSION_LONG_TESTS!=='1')
 throw new Error('Set E2E_REAL_LOGIN=1, E2E_RELEASE_LIVE=1 and SESSION_LONG_TESTS=1; required live checks cannot pass by skipping.');
if(!process.env.SESSION_USERNAME||!process.env.SESSION_PASSWORD)
 throw new Error('Configure SESSION_USERNAME and SESSION_PASSWORD for an existing enabled analyst; no users or archive records will be created.');

// Existing enabled analyst only; no provisioning, scenario commands or imports.
export default defineConfig({
 testDir:'./tests/e2e/specs',testMatch:['trace-link-release.spec.ts','session-expiry.spec.ts'],
 grep:/requires populated persisted history|real 15-minute idle expiry|real 30-minute absolute expiry despite activity/,
 fullyParallel:true,workers:2,retries:0,globalTimeout:36*60_000,timeout:33*60_000,
 outputDir:'./test-results/frontend-live',expect:{timeout:20_000},
 use:{baseURL:process.env.E2E_BASE_URL??'http://telecom.test:8080',viewport:{width:1366,height:768},
  trace:'off',screenshot:'off',video:'off',launchOptions:{args:['--host-resolver-rules=MAP telecom.test 127.0.0.1','--no-proxy-server']}},
});
