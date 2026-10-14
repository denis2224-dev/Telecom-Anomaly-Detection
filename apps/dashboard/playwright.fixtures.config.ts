import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir:'./tests/e2e/specs',testMatch:'fixture-map.spec.ts',outputDir:'./test-results/fixtures',workers:1,
  use:{baseURL:'http://127.0.0.1:4223',trace:'off',screenshot:'off',video:'off'},
  webServer:{command:'npm run start:fixtures -- --port 4223',url:'http://127.0.0.1:4223',reuseExistingServer:false},
});
