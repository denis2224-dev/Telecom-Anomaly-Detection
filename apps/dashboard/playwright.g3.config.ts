import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e/specs', testMatch: 'service-explanations.spec.ts',
  outputDir: './test-results/g3', workers: 1, retries: 0, timeout: 60_000,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://telecom.test:8080',
    viewport: { width: 1366, height: 768 }, trace: 'off', screenshot: 'off', video: 'off',
    launchOptions: { args: ['--host-resolver-rules=MAP telecom.test 127.0.0.1', '--no-proxy-server'] },
  },
});
