import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e/specs',
  testMatch: [
    'auth-integration.spec.ts',
    'session-expiry.spec.ts',
  ],

  outputDir: './test-results/auth',
  workers: 1,
  retries: 0,
  timeout: 60_000,

  expect: {
    timeout: 30_000,
  },

  use: {
    baseURL:
      process.env.E2E_BASE_URL ?? 'http://telecom.test:8080',
    viewport: { width: 1366, height: 768 },
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
});