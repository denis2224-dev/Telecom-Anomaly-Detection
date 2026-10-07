import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e/specs',
  testMatch: ['service-scenarios.spec.ts', 'geography-integration.spec.ts'],
  outputDir: './test-results/g2',
  workers: 1,
  retries: 0,
  timeout: 15 * 60_000,

  expect: {
    timeout: 30_000,
  },

  reporter: [
    ['list'],
    ['html', {
      outputFolder: 'playwright-report/g2',
      open: 'never',
    }],
  ],

  use: {
    baseURL:
      process.env.E2E_BASE_URL ?? 'http://telecom.test:8080',
    viewport: { width: 1366, height: 768 },
    actionTimeout: 30_000,
    navigationTimeout: 30_000,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
});
