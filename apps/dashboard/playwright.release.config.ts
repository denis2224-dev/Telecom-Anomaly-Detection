import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir:'./tests/e2e/specs',testMatch:['trace-link-release.spec.ts','release-workflows.spec.ts'],outputDir:'./test-results/release',workers:1,retries:0,timeout:1200000,
  expect:{timeout:20000},use:{baseURL:'http://telecom.test:8080',viewport:{width:1366,height:768},trace:'off',screenshot:'off',video:'off',
    launchOptions:{args:['--host-resolver-rules=MAP telecom.test 127.0.0.1','--no-proxy-server']}},
});
