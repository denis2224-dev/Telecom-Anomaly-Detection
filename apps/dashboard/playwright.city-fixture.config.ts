import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e/specs',
  testMatch: '**/connected-dashboard.spec.ts',
  grep: /fixture city selection/,
  outputDir: './test-results/city-fixture',
  use: { baseURL: 'http://127.0.0.1:4311', trace: 'off', screenshot: 'off', video: 'off' },
  webServer: {
    command: 'npm run start:fixtures -- --port 4311',
    url: 'http://127.0.0.1:4311',
    reuseExistingServer: true,
  },
});
