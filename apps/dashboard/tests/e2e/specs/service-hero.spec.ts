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
    await expect(page.locator('.service-selection .badge')).toHaveCount(0);
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
    expect((await action.boundingBox())!.height).toBeGreaterThanOrEqual(40);
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
