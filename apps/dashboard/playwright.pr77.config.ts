import { defineConfig } from '@playwright/test';
import { join } from 'node:path';
import { stackContext } from './tests/e2e/helpers/pr77-stack';

const stack = stackContext();
export default defineConfig({
  testDir: './tests/e2e/specs', testMatch: 'pr77-connected.spec.ts',
  outputDir: join(stack.outputDir, 'browser'),
  workers: 1, fullyParallel: false, retries: 0, timeout: 25 * 60_000,
  globalTimeout: 75 * 60_000, expect: { timeout: 20_000 },
  reporter: [['list'], ['./tests/e2e/helpers/pr77-stack.ts']],
  use: {
    baseURL: stack.baseURL, trace: 'off', screenshot: 'off', video: 'off',
    launchOptions: { args: ['--host-resolver-rules=MAP telecom.test 127.0.0.1', '--no-proxy-server'] },
  },
});
