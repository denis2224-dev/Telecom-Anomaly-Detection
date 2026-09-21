import { test, expect } from "@playwright/test";
import branding from "../../../../../design/branding.json";

for (const viewport of [{ width: 1366, height: 768 }, { width: 390, height: 844 }]) {
  test(`anonymous handoff and branded provider at ${viewport.width}px`, async ({ page }, info) => {
    await page.setViewportSize(viewport);
    await page.goto("/login");
    await expect(page.getByRole("heading", { name: "Sign in to investigate service issues" })).toBeVisible();
    await expect(page.locator('input[type="password"]')).toHaveCount(0);
    await page.reload();
    const handoff = page.getByRole("button", { name: "Continue to sign in", exact: true });
    await expect(handoff).toBeVisible();
    // Compare the actual rendered systems, not two hard-coded expected palettes.
    const buttonColor = await handoff.evaluate(el => getComputedStyle(el).backgroundColor);
    const font = await page.locator("body").evaluate(el => getComputedStyle(el).fontFamily);
    const card = await page.locator(".login-card").evaluate(el => ({
      radius: getComputedStyle(el).borderRadius,
      shadow: getComputedStyle(el).boxShadow,
    }));
    expect(card.radius).toBe(branding["border-radius"]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath("angular-login.png"), fullPage: true });
    await handoff.click();
    // Assert only the pathname so a failing test never prints OIDC query values.
    await expect.poll(() => new URL(page.url()).pathname).toBe("/auth/realms/telecom/protocol/openid-connect/auth");
    await expect(page.getByRole("heading", { name: "Sign in to your workspace" })).toBeVisible();
    await expect(page.locator("#username")).toBeVisible();
    await expect(page.locator("#password")).toHaveAttribute("type", "password");
    await expect(page.locator("#kc-login")).toHaveCSS("background-color", buttonColor);
    await expect(page.locator(".login-pf-page")).toHaveCSS("font-family", font);
    await expect(page.locator(".card-pf")).toHaveCSS("border-radius", card.radius);
    await expect(page.locator(".card-pf")).toHaveCSS("box-shadow", card.shadow);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath("keycloak-login.png"), fullPage: true });
  });
}
