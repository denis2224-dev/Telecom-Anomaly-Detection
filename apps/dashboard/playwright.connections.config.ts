import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e/specs', testMatch: 'project-connections.spec.ts',
  outputDir: './test-results/connections-live', workers: 1, retries: 0, timeout: 5 * 60_000,
  expect: { timeout: 20_000 },
  use: { baseURL: 'http://telecom.test:8080', viewport: { width: 1366, height: 900 },
    actionTimeout: 20_000, navigationTimeout: 30_000,
    trace: 'off', screenshot: 'off', video: 'off',
    launchOptions: { args: ['--host-resolver-rules=MAP telecom.test 127.0.0.1', '--no-proxy-server'] },
  },
});
