import { test, expect } from "@playwright/test";
import branding from "../../../../../design/branding.json";

function rgb(hex: string): string {
  return `rgb(${[1, 3, 5].map(offset => parseInt(hex.slice(offset, offset + 2), 16)).join(", ")})`;
}

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
  const styles = await page.locator('link[rel="stylesheet"]').evaluateAll(links => links.map(link => (link as HTMLLinkElement).href));
  expect(styles.findIndex(href => href.endsWith("/css/login.css"))).toBeGreaterThanOrEqual(0);
  expect(styles.findIndex(href => href.endsWith("/css/login.css"))).toBeLessThan(styles.findIndex(href => href.endsWith("/css/telecom.css")));
  await expect(page.locator("#kc-login")).toHaveCSS("background-color", rgb(branding.primary));
  await expect(page.locator("body")).toHaveCSS("background-color", rgb(branding["page-background"]));
  await expect(page.locator(".login-pf-page")).toHaveCSS("font-family", branding["font-family"]);
  await expect(page.locator(".card-pf")).toHaveCSS("border-radius", branding["border-radius"]);
  expect(await page.locator("#kc-login").evaluate(el => el.getBoundingClientRect().height)).toBeGreaterThanOrEqual(46);
  await expect(page.getByText("Need access? Contact your project administrator.")).toBeVisible();
  // The mark is decorative, while real text retains the brand's accessible name.
  await expect(page.locator("#kc-header-wrapper")).toContainText("Telecom");
  await expect(page.locator("#kc-header-wrapper")).toHaveCSS("background-image", /mark\.svg/);
  await page.locator("#kc-login").hover();
  await expect(page.locator("#kc-login")).toHaveCSS("background-color", rgb(branding["primary-hover"]));
  await page.mouse.move(0, 0);
  await page.screenshot({ path: info.outputPath("login-desktop.png"), fullPage: true });
  await page.locator("#username").fill("nonexistent-theme-test-user");
  await page.locator("#password").fill("not-a-real-password");
  await page.getByRole("button", { name: "Show password", exact: true }).click();
  await expect(page.locator("#password")).toHaveAttribute("type", "text");
  await page.getByRole("button", { name: "Hide password", exact: true }).click();
  await expect(page.locator("#password")).toHaveAttribute("type", "password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByText("Invalid username or password.")).toBeVisible();
  await expect(page.locator("#username")).toHaveAttribute("aria-invalid", "true");
  await expect(page.locator("#username")).toHaveCSS("border-top-color", rgb(branding.danger));
  await expect(page.locator("#password")).toHaveValue("");
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

test("keyboard focus and labels remain usable at 320px", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto(authorize);
  const username = page.getByLabel(/username or email/i);
  await expect(username).toBeFocused();
  await expect(username).toHaveCSS("outline-color", rgb(branding.focus));
  await expect(username).toHaveCSS("outline-width", "3px");
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Password", { exact: true })).toBeFocused();
  await page.keyboard.press("Tab");
  const visibility = page.getByRole("button", { name: "Show password", exact: true });
  await expect(visibility).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("#password")).toHaveAttribute("type", "text");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("inherited recovery page keeps labels, navigation and branding", async ({ page }) => {
  test.skip(!process.env.E2E_THEME_RECOVERY, "Use npm run test:theme for the disposable recovery-enabled realm");
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(authorize);
  await page.getByRole("link", { name: "Forgot Password?" }).click();
  await expect(page.getByRole("heading", { name: "Forgot Your Password?" })).toBeVisible();
  await expect(page.getByLabel(/username or email/i)).toBeVisible();
  const submit = page.getByRole("button", { name: "Submit", exact: true });
  await expect(submit).toBeVisible();
  await expect(submit).toHaveCSS("background-color", rgb(branding.primary));
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  // Do not submit, send mail, or change any account.
  await page.getByRole("link", { name: /back to login/i }).click();
  await expect(page.getByRole("heading", { name: "Sign in to your workspace" })).toBeVisible();
});

test("inherited provider error page remains readable", async ({ page }) => {
  await page.goto(authorize.replace("client_id=telecom-web", "client_id=nonexistent-theme-client"));
  await expect(page.getByRole("heading", { name: "We are sorry..." })).toBeVisible();
  await expect(page.getByText("Client not found.", { exact: true })).toBeVisible();
  await expect(page.locator(".card-pf")).toHaveCSS("background-color", rgb(branding.surface));
  await expect(page.locator(".telecom-help")).toBeVisible();
});
