import { test, expect } from '@playwright/test';
import { controlledApi } from '../helpers/controlled-api';
import suite from '../../../../../contracts/fixtures/detections/service-explanation-cases.json';

test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled hero UI cases; real login runs separately');

for (const service of ['VOLTE', 'SMS'] as const) for (const width of [1366, 768, 390]) {
  test(`${service} principal KPI, technical selection, incident highlighting and layout at ${width}px`, async ({ page }, info) => {
    const state = await controlledApi(page);
    const trajectory = suite.cases.find(item => item.id === `${service.toLowerCase()}-fault`)!;
    state.windows = structuredClone(trajectory.windows.map(item => item.feature)) as typeof state.windows;
    state.summary.scope.service = service;
    state.summary.scope.scopeId = state.windows[0].scopeId;
    state.summary.latestWindow = state.windows.at(-1)!;
    state.summary.observedAt = state.windows.at(-1)!.windowEnd;
    state.incident.scopeId = state.summary.scope.scopeId;
    state.incident.service = service;
    state.incident.firstObservedAt = state.windows[0].windowStart;
    state.incident.lastObservedAt = state.windows.at(-1)!.windowEnd;
    state.incident.latestDetection = structuredClone(trajectory.detections.at(-1)!) as typeof state.incident.latestDetection;
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/services/${state.summary.scope.scopeId}`);
    const hero = page.locator('.service-hero');
    const chart = hero.locator('[data-chart]');
    await expect(chart).toHaveAttribute('data-chart', service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs');
    await expect(hero.locator('svg[role="img"]')).toHaveCount(1);
    await expect(hero.locator('.chart-legend')).toHaveCount(1);
    await expect(hero.locator('.incident-band')).toHaveCount(1);
    await expect(page.locator('.kpi-switcher button')).toHaveCount(service === 'VOLTE' ? 8 : 5);
    await expect(page.locator('app-kpi-cards svg, app-kpi-cards button')).toHaveCount(0);
    await expect(hero.locator('path.traffic-success, path.traffic-failed')).toHaveCount(0);
    await expect(hero.locator('.actual-line')).not.toHaveAttribute('d', '');
    await expect(hero.locator('.expected-line')).not.toHaveAttribute('d', '');
    await expect(page.locator('app-service-context')).toHaveCount(0);
    await expect(page.locator('.service-toolbar')).not.toContainText('Source:');
    await expect(page.locator('.exact-values')).not.toHaveAttribute('open');
    const requestsBefore = state.requests.length;
    const heightBefore = (await hero.boundingBox())!.height;
    await hero.locator('svg[role="img"]').hover();
    await expect(hero.locator('.chart-tooltip')).toHaveClass(/tooltip-visible/);
    await expect(page.locator('.episode-card')).toHaveClass(/is-highlighted/);
    expect((await hero.boundingBox())!.height).toBe(heightBefore);
    const plot = hero.locator('.chart-scroll');
    await plot.focus();
    await page.keyboard.press('Home');
    await expect(hero.locator('.chart-tooltip')).toHaveClass(/tooltip-visible/);
    await expect(hero.locator('.chart-tooltip')).toContainText('UTC');
    await page.keyboard.press('End');
    await expect(hero.locator('.chart-tooltip')).toContainText(state.windows.at(-1)!.windowStart.slice(11, 19));
    await expect(hero.locator('.chart-tooltip')).toContainText('Baseline:');
    const plotHeight = (await plot.boundingBox())!.height;
    const technical = service === 'VOLTE' ? 'RRC SR' : 'Queue Depth';
    const technicalId = service === 'VOLTE' ? 'rrcSrPct' : 'queueDepth';
    const technicalButton = hero.getByRole('button', { name: technical, exact: true });
    await technicalButton.click();
    await expect(chart).toHaveAttribute('data-chart', technicalId);
    await expect(technicalButton).toHaveAttribute('aria-pressed', 'true');
    expect((await hero.locator('.chart-scroll').boundingBox())!.height).toBe(plotHeight);
    await hero.getByRole('button', { name: service === 'VOLTE' ? 'CSSR' : 'P95 Delivery Delay', exact: true }).click();
    await expect(chart).toHaveAttribute('data-chart', service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs');
    const action = page.locator('.episode-card').getByRole('link', { name: 'Open incident detail', exact: true });
    await expect(action).toHaveClass(/primary/);
    expect((await action.boundingBox())!.height).toBeGreaterThanOrEqual(36);
    await expect(page.locator('.episode-card .badge')).toHaveCount(0);
    await expect(page.locator('.episode-info')).toContainText('Assigned');
    await expect(page.locator('.episode-info')).not.toContainText(state.incident.assigneeId!);
    await expect(page.locator('.episode-info')).not.toContainText('observed');
    expect(await page.locator('.evidence-toggle span').evaluate(label => {
      const range = document.createRange();
      range.selectNodeContents(label);
      return range.getClientRects().length;
    })).toBe(1);
    await expect(page.locator('.kpi-summary thead')).toContainText('Change');
    await action.focus();
    await expect(page.locator('.episode-card')).not.toHaveClass(/is-highlighted/);
    expect(state.requests.length).toBe(requestsBefore);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    expect((await plot.boundingBox())!.height).toBeGreaterThanOrEqual(260);
    expect((await hero.boundingBox())!.y).toBeLessThan((await page.locator('app-kpi-cards').boundingBox())!.y);
    if (width === 1366) {
      const controls = await page.locator('.service-toolbar input, .service-toolbar select, .time-filter button').evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().bottom));
      expect(Math.max(...controls) - Math.min(...controls)).toBeLessThan(3);
    }
    expect(await hero.locator('svg[role="img"]').evaluate(svg => {
      const box = svg.getBoundingClientRect();
      return [...svg.querySelectorAll('text')].every(text => {
        const label = text.getBoundingClientRect();
        return label.left >= box.left && label.right <= box.right;
      });
    })).toBe(true);
    await page.screenshot({ path: info.outputPath(`hero-${service}-${width}.png`), fullPage: true, animations: 'disabled' });
  });
}

test('missing principal KPI observations keep graph space and never fabricate a reading', async ({ page }) => {
  const state = await controlledApi(page);
  for (const row of state.windows) {
    const rate = row.kpis.find(kpi => kpi.name === 'cssrPct')!;
    Object.assign(rate, { observed: null, numerator: null, denominator: null });
  }
  await page.goto(`/services/${state.summary.scope.scopeId}`);
  const hero = page.locator('.service-hero');
  const height = (await hero.locator('.chart-scroll').boundingBox())!.height;
  await expect(hero.getByRole('status')).toContainText('No usable observations');
  await expect(hero.locator('.hero-reading strong')).toHaveText('Unavailable');
  await expect(hero.locator('.actual-line')).toHaveAttribute('d', '');
  await expect(hero.locator('.traffic-success, .traffic-failed')).toHaveCount(0);
  expect((await hero.locator('.chart-scroll').boundingBox())!.height).toBe(height);
  await expect(hero.locator('.incident-band')).toHaveCount(1);
});

for (const service of ['VOLTE', 'SMS'] as const) for (const width of [1366, 768, 390]) test(`${service} episode drawer preserves the workspace at ${width}px`, async ({ page }, info) => {
  const state = await controlledApi(page);
  state.summary.scope.service = service;
  state.incident.service = service;
  const items = Array.from({ length: 20 }, (_, index) => ({ ...structuredClone(state.incident),
    id: `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
    episodeId: String(index).padStart(64, '0') }));
  items[7].latestDetection.probableCause = state.incident.latestDetection.probableCause.repeat(100);
  await page.route(/\/api\/incidents\?/, route => route.fulfill({ json: { items, total: 20, page: 0, size: 20 } }));
  await page.setViewportSize({ width, height: 900 });
  await page.goto(`/services/${state.summary.scope.scopeId}`);
  const rows = page.locator('.episode-card');
  const toggles = rows.getByRole('button', { name: 'View incident evidence', exact: true });
  await expect(rows).toHaveCount(20);
  await expect(rows.locator('.badge')).toHaveCount(0);
  await expect(page.getByText('Percent or percentage points?', { exact: true })).toHaveCount(0);
  await toggles.nth(7).scrollIntoViewIfNeeded();
  const snapshot = () => page.evaluate(() => ({ page: scrollY, list: document.querySelector('.episode-list')!.scrollTop,
    width: document.querySelector('.episode-list')!.clientWidth, height: document.querySelector('.episode-list')!.clientHeight }));
  const before = await snapshot();
  await toggles.nth(7).click();
  const drawer = page.getByRole('dialog', { name: 'Incident evidence', exact: true });
  await expect(drawer).toBeVisible();
  await expect(page.locator('dialog[open]')).toHaveCount(1);
  await expect(drawer.locator('.incident-story')).toHaveAttribute('data-episode-id', items[7].episodeId);
  await expect(drawer).toContainText(state.incident.scopeId);
  await expect(drawer).toContainText('Technical state');
  await expect(drawer).toContainText('Workflow state');
  await expect(drawer).toContainText('Detected (UTC)');
  await expect(drawer).not.toContainText('Supporting evidence');
  await expect(drawer).not.toContainText('Recommended checks');
  expect(await snapshot()).toEqual(before);
  expect((await drawer.boundingBox())!.width).toBeCloseTo(Math.min(480, width - (width <= 600 ? 24 : 32)), 0);
  const close = drawer.getByRole('button', { name: 'Close incident evidence', exact: true });
  await expect(close).not.toBeFocused();
  await expect(close).toHaveCSS('box-shadow', 'none');
  await close.hover(); await expect(close).toHaveCSS('box-shadow', 'none');
  await page.screenshot({ path: info.outputPath(`evidence-summary-${service}-${width}.png`), animations: 'disabled' });
  const body = drawer.locator('.drawer-content');
  await body.hover(); await page.mouse.wheel(0, 350);
  await expect.poll(() => body.evaluate(node => node.scrollTop)).toBeGreaterThan(0);
  expect(await snapshot()).toEqual(before);
  await page.screenshot({ path: info.outputPath(`evidence-${service}-${width}.png`), fullPage: true, animations: 'disabled' });
  if (width > 768) await page.mouse.click(20, 450);
  else if (width === 768) await page.keyboard.press('Escape');
  else await close.click();
  await expect(drawer).not.toBeVisible();
  await expect(toggles.nth(7)).toBeFocused();
  expect(await snapshot()).toEqual(before);
  await toggles.nth(8).click();
  await expect(drawer).toBeVisible();
  await expect(drawer.locator('.incident-story')).toHaveAttribute('data-episode-id', items[8].episodeId);
  await expect(page.locator('dialog[open]')).toHaveCount(1);
  expect(await body.evaluate(node => node.scrollTop)).toBe(0);
  const compact = (await drawer.boundingBox())!;
  expect(compact.height).toBeLessThan(850);
  expect(compact.y).toBe(width <= 600 ? 12 : 16);
  await page.screenshot({ path: info.outputPath(`compact-evidence-${service}-${width}.png`), animations: 'disabled' });
  await page.keyboard.press('Escape');
  await expect(rows.nth(8).getByRole('link', { name: 'Open incident detail', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});
