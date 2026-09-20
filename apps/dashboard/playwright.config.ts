import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e/specs",
  fullyParallel: true,
  use: {
    baseURL: process.env.E2E_BASE_URL || "http://127.0.0.1:4200",
    viewport: { width: 1366, height: 768 },
    // Real-login runs must not save credentials, cookies or CSRF values in traces.
    trace: "off",
  },
  webServer: process.env.E2E_REAL_LOGIN ? undefined : {
    command: "npm start",
    url: "http://127.0.0.1:4200",
    reuseExistingServer: false,
  },
});
