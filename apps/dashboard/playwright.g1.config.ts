import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e/specs', testMatch: 'voice-first-slice.spec.ts',
  outputDir: 'test-results/g1', workers: 1, retries: 0, timeout: 240000,
  expect: { timeout: 30000 },
  use: { baseURL: 'http://telecom.test:8080', viewport: { width: 1366, height: 768 },
    actionTimeout: 15000, navigationTimeout: 20000,
    trace: 'off', screenshot: 'off', video: 'off' },
});
