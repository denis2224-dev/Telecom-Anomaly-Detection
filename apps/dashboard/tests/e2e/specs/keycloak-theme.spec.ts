import { test, expect } from "@playwright/test";

const authorize = "/realms/telecom/protocol/openid-connect/auth?" + new URLSearchParams({
  client_id: "telecom-web",
  redirect_uri: "http://telecom.test:8080/login/oauth2/code/keycloak",
  response_type: "code",
  scope: "openid",
  state: "theme-layout-test",
  code_challenge: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  code_challenge_method: "S256",
}).toString();

test("branded provider form preserves password controls and validation", async ({ page }, info) => {
  await page.goto(authorize);
  await expect(page.getByRole("heading", { name: "Sign in to your workspace" })).toBeVisible();
  await expect(page.locator('link[href*="telecom.css"]')).toHaveCount(1);
  await expect(page.locator("#kc-login")).toHaveCSS("background-color", "rgb(25, 95, 85)");
  await expect(page.locator("body")).toHaveCSS("background-color", "rgb(243, 245, 242)");
  await page.screenshot({ path: info.outputPath("login-desktop.png"), fullPage: true });
  await page.locator("#username").fill("nonexistent-theme-test-user");
  await page.locator("#password").fill("not-a-real-password");
  await page.getByRole("button", { name: "Show password", exact: true }).click();
  await expect(page.locator("#password")).toHaveAttribute("type", "text");
  await page.getByRole("button", { name: "Hide password", exact: true }).click();
  await expect(page.locator("#password")).toHaveAttribute("type", "password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByText("Invalid username or password.")).toBeVisible();
  await page.screenshot({ path: info.outputPath("login-error.png"), fullPage: true });
});

test("mobile login fits the viewport", async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(authorize);
  await expect(page.locator("#username")).toBeVisible();
  await expect(page.locator("#password")).toBeVisible();
  await expect(page.locator("#kc-login")).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath("login-mobile.png"), fullPage: true });
});
