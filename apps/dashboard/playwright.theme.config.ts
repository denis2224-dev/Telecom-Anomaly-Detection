import { defineConfig } from "@playwright/test";

// Run against an isolated Keycloak instance with the telecom realm imported.
export default defineConfig({
  testDir: "./tests/e2e/specs",
  testMatch: "keycloak-theme.spec.ts",
  use: {
    baseURL: process.env.E2E_THEME_URL || "http://127.0.0.1:8180",
    viewport: { width: 1366, height: 768 },
    trace: "off",
  },
});
