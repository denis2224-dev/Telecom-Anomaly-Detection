import { defineConfig } from "@playwright/test";

const port = Number(process.env.E2E_PORT || 4200);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error("Invalid E2E_PORT");
const localUrl = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: "./tests/e2e/specs",
  testIgnore: "**/keycloak-theme.spec.ts",
  fullyParallel: true,
  use: {
    baseURL: process.env.E2E_BASE_URL || localUrl,
    viewport: { width: 1366, height: 768 },
    // Real-login runs must not save credentials, cookies or CSRF values in traces.
    trace: "off",
  },
  webServer: process.env.E2E_REAL_LOGIN ? undefined : {
    command: `npm start -- --port ${port}`,
    url: localUrl,
    reuseExistingServer: false,
  },
});
