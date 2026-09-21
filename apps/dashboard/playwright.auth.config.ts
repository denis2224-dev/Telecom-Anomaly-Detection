import { defineConfig } from "@playwright/test";

// Real NGINX + Spring + Keycloak. No dev server or mocked endpoints.
export default defineConfig({
  testDir: "./tests/e2e/specs",
  testMatch: "auth-integration.spec.ts",
  outputDir: "./test-results/auth",
  use: {
    baseURL: process.env.E2E_BASE_URL || "http://telecom.test:8080",
    viewport: { width: 1366, height: 768 },
    trace: "off", screenshot: "off", video: "off",
  },
});
