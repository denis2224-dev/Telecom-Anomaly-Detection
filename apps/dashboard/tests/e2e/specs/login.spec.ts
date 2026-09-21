import { test, expect } from "@playwright/test";

test.describe("Frontend login behavior (mocked API)", () => {
  test.skip(!!process.env.E2E_REAL_LOGIN, "Run separately from real backend verification");

  test("anonymous deep links show sign-in without fetching service data", async ({ page }, testInfo) => {
    let serviceRequests = 0;
    await page.route("**/api/**", async (route) => {
      if (route.request().url().includes("/api/services")) serviceRequests++;
      await route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } });
    });
    await page.goto("/services/VOLTE-MD-CENTRAL");
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("heading", { name: "Sign in to investigate service issues" })).toBeVisible();
    expect(serviceRequests).toBe(0);
    await page.screenshot({ path: testInfo.outputPath("login-desktop.png"), fullPage: true });
    await page.route("**/oauth2/authorization/keycloak", route => route.fulfill({ body: "Organization sign-in" }));
    await page.getByRole("button", { name: "Continue to sign in", exact: true }).click();
    await expect(page).toHaveURL(/\/oauth2\/authorization\/keycloak$/);
  });

  test("HTML from the API shows a recoverable connection error", async ({ page }) => {
    await page.route("**/api/auth/me", route => route.fulfill({ contentType: "text/html", body: "<html>Login</html>" }));
    await page.goto("/dashboard");
    await expect(page.getByRole("button", { name: "Retry connection" })).toBeVisible();
    await page.route("**/api/auth/me", route => route.fulfill({ status: 401, json: {} }));
    await page.getByRole("button", { name: "Retry connection" }).click();
    await expect(page.getByRole("button", { name: "Continue to sign in", exact: true })).toBeVisible();
  });

  test("direct login survives refresh and never collects credentials", async ({ page }) => {
    await page.route("**/api/auth/me", route => route.fulfill({ status: 401, json: {} }));
    await page.goto("/login");
    await expect(page.getByRole("button", { name: "Continue to sign in", exact: true })).toBeVisible();
    await page.reload();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("button", { name: "Continue to sign in", exact: true })).toBeVisible();
    await expect(page.locator("input")).toHaveCount(0);
    await expect(page.getByRole("link", { name: /telecom service assurance/i })).toBeVisible();
  });

  test("expiry removes the signed-in identity and protected screen", async ({ page }) => {
    await page.clock.install();
    await page.route("**/api/auth/me", route => route.fulfill({ json: {
      analystId: "test-analyst", displayName: "Test analyst", roles: ["ANALYST"],
      expiresAt: new Date(Date.now() + 60000).toISOString(),
    } }));
    await page.route("**/api/auth/csrf", route => route.fulfill({ json: {
      token: "test-only-csrf", headerName: "X-CSRF-TOKEN", parameterName: "_csrf",
    } }));
    await page.route("**/api/services", route => route.fulfill({ json: [] }));
    await page.goto("/dashboard");
    await expect(page.getByText("Test analyst · ANALYST")).toBeVisible();
    await page.clock.fastForward(61000);
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("heading", { name: "Your session has expired" })).toBeVisible();
    await expect(page.getByText("Test analyst · ANALYST")).toHaveCount(0);
    expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  });

  test("signed-out page fits a narrow screen", async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.route("**/api/auth/me", route => route.fulfill({ status: 401, json: {} }));
    await page.goto("/signed-out");
    await expect(page.getByRole("heading", { name: "You’re signed out" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath("signed-out-mobile.png"), fullPage: true });
  });
});

test("real Keycloak login and backend session", async ({ page, context }) => {
  test.skip(!process.env.E2E_REAL_LOGIN, "Requires the real backend and a provisioned test account");
  const { E2E_USERNAME, E2E_PASSWORD, E2E_DISPLAY_NAME } = process.env;
  if (!E2E_USERNAME || !E2E_PASSWORD || !E2E_DISPLAY_NAME || !process.env.E2E_BASE_URL)
    throw new Error("Set E2E_BASE_URL, E2E_USERNAME, E2E_PASSWORD and E2E_DISPLAY_NAME for the real test.");
  try {
    const anonymous = await context.request.get("/api/auth/me", { maxRedirects: 0 });
    expect(anonymous.status()).toBe(401);
    expect(anonymous.headers()["content-type"]).toContain("application/json");
    await page.goto("/login");
    await page.getByRole("button", { name: "Continue to sign in", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Sign in to your workspace" })).toBeVisible();
    await page.getByLabel(/username|email/i).fill(E2E_USERNAME);
    await page.getByLabel("Password", { exact: true }).fill(E2E_PASSWORD);
    await page.getByRole("button", { name: /sign in/i }).click();
    await expect.poll(() => new URL(page.url()).pathname).toBe("/dashboard");
    await expect(page.locator(".identity")).toContainText(E2E_DISPLAY_NAME);
    const identity = await context.request.get("/api/auth/me");
    expect(identity.status()).toBe(200);
    expect((await identity.json()).displayName).toBe(E2E_DISPLAY_NAME);
    expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(page).toHaveURL(/\/signed-out$/);
    await expect(page.getByRole("heading", { name: "You’re signed out" })).toBeVisible();
    expect((await context.request.get("/api/auth/me", { maxRedirects: 0 })).status()).toBe(401);
  } catch {
    // Suppress assertion values / fill call logs that could contain credentials
    // or callback parameters; do not attach the original error as a cause.
    throw new Error("Real login/callback/logout verification failed. Diagnose locally; sensitive details omitted.");
  } finally {
    // Closing before reporting also prevents automatic failure DOM snapshots.
    await context.close();
  }
});
