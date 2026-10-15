import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir:'./tests/e2e/specs',testMatch:'resource-bounds.spec.ts',outputDir:'./test-results/performance-analyst',workers:1,retries:0,
  use:{baseURL:'http://127.0.0.1:4224',viewport:{width:1366,height:768},trace:'off',screenshot:'off',video:'off'},
  webServer:{command:'npm run build && node node_modules/vite/bin/vite.js preview --outDir dist/dashboard/browser --host 127.0.0.1 --port 4224 --strictPort',url:'http://127.0.0.1:4224',reuseExistingServer:false},
});
