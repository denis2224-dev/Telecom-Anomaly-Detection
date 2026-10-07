import { test, expect } from '@playwright/test';
import { controlledApi } from '../helpers/controlled-api';

test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled acceptance; real demonstration has its own test.');

test('the fallback keeps the existing authentication guard', async ({ page }) => {
  const state = await controlledApi(page);
  state.authStatus = 401;
  await page.goto('/dashboard?view=scopes');
  await expect(page.getByRole('button', { name: 'Continue to sign in', exact: true })).toBeVisible();
  await expect(page.locator('.service-assurance-card')).toHaveCount(0);
  expect(state.requests.some(request => request === 'GET /api/services')).toBe(false);
});

for (const width of [1366, 768, 390]) {
  test(`scope fallback and reversible connected view at ${width}px`, async ({ page }, info) => {
    const state = await controlledApi(page);
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/dashboard?view=scopes&service=VOLTE');
    await expect(page.getByRole('heading', { name: 'Service overview', exact: true })).toBeVisible();
    await expect(page.locator('app-connected-overview')).toHaveCount(0);
    await expect(page.locator('.service-assurance-card')).toHaveCount(1);
    await page.getByRole('button', { name: 'Refresh overview' }).click();
    await expect(page.getByLabel('Service', { exact: true })).toHaveValue('VOLTE');
    await expect(page.locator('.service-assurance-card')).toContainText(state.summary.scope.scopeId);
    await page.reload();
    await expect(page.locator('.source-inventory')).toHaveAttribute('open', '');
    await page.getByRole('link', { name: 'Open connected overview' }).click();
    await expect(page.locator('app-connected-overview')).toBeVisible();
    await expect(page.getByLabel('Service', { exact: true })).toHaveValue('VOLTE');
    await page.locator('details.source-inventory > summary').click();
    await page.getByRole('link', { name: 'Open scope overview' }).click();
    await expect(page.locator('app-connected-overview')).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await page.screenshot({ path: info.outputPath(`scope-fallback-${width}.png`), fullPage: true });
  });
}

test('200% zoom keeps overview and service graph labels, focus and navigation usable', async ({ page }, info) => {
  const state = await controlledApi(page);
  await page.setViewportSize({ width: 1366, height: 900 });
  await page.goto('/dashboard');
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
  await expect(page.getByRole('heading', { name: 'Network overview' })).toBeVisible();
  const refresh = page.getByRole('button', { name: 'Refresh overview' });
  await refresh.focus();
  await expect(refresh).toBeFocused();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  expect(await page.locator('.period-chips button').evaluateAll(buttons => buttons.every(button => button.scrollWidth <= button.clientWidth + 1))).toBe(true);
  const panels = await page.locator('.overview-panels > section').evaluateAll(panels => panels.map(panel => {
    const rect = panel.getBoundingClientRect();
    const table = panel.querySelector('table')!.getBoundingClientRect();
    const navigation = panel.querySelector('.panel-navigation')!.getBoundingClientRect();
    return { left: rect.left, top: rect.top, bottom: rect.bottom, tableBottom: table.bottom, navigationTop: navigation.top };
  }));
  expect(panels).toHaveLength(3);
  expect(panels.every(panel => Math.abs(panel.left - panels[0].left) < 1 && panel.tableBottom <= panel.navigationTop)).toBe(true);
  expect(panels[1].top).toBeGreaterThanOrEqual(panels[0].bottom);
  expect(panels[2].top).toBeGreaterThanOrEqual(panels[1].bottom);
  await page.screenshot({ path: info.outputPath('overview-zoom.png'), fullPage: true });
  await page.goto(`/services/${state.summary.scope.scopeId}`);
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
  const chart = page.getByLabel('Scrollable CSSR chart', { exact: true });
  await chart.focus();
  await page.keyboard.press('End');
  await expect(page.locator('.service-hero .chart-tooltip')).toContainText('UTC');
  await expect(page.locator('.service-hero .chart-tooltip')).toContainText('Baseline');
  await page.getByText('Range options', { exact: true }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.getByText('Range options', { exact: true }).click();
  expect(await page.locator('.kpi-switcher button').evaluateAll(buttons => buttons.every(button => button.scrollWidth <= button.clientWidth + 1))).toBe(true);
  await page.screenshot({ path: info.outputPath('service-zoom.png'), fullPage: true });
});
