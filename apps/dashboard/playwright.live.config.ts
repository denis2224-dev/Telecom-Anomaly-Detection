import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/e2e/specs', testMatch: 'live-investigation.spec.ts',
  outputDir: './test-results/live', workers: 1, retries: 0, timeout: 12 * 60_000,
  expect: { timeout: 20_000 },
  use: {
    baseURL: 'http://telecom.test:8080', trace: 'off', screenshot: 'off', video: 'off',
    launchOptions: { args: ['--host-resolver-rules=MAP telecom.test 127.0.0.1', '--no-proxy-server'] },
  },
});
