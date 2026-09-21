import { defineConfig } from "@playwright/test";

// npm run test:theme starts a disposable, pinned Keycloak and sets this URL.
export default defineConfig({
  testDir: "./tests/e2e/specs",
  outputDir: "./test-results/theme",
  testMatch: "keycloak-theme.spec.ts",
  use: {
    baseURL: process.env.E2E_THEME_URL || "http://127.0.0.1:8180",
    viewport: { width: 1366, height: 768 },
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
