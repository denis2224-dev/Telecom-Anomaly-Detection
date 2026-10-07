import { test, expect } from '@playwright/test';
import { cityIncidents, citySummaries, cityWindows, fixtureRange } from '../../../src/fixtures/connected-dashboard';
import { controlledApi } from '../helpers/controlled-api';
import suite from '../../../../../contracts/fixtures/detections/service-explanation-cases.json';

test('overview SMS chart uses the measured delivery success rate, not delay', async ({ page }) => {
  const state = await controlledApi(page);
  const trajectory = suite.cases.find(item => item.id === 'sms-fault')!;
  state.windows = structuredClone(trajectory.windows.map(item => item.feature)) as typeof state.windows;
  state.summary.scope.service = 'SMS';
  state.summary.scope.scopeId = state.windows[0].scopeId;
  state.summary.latestWindow = state.windows.at(-1)!;
  state.summary.observedAt = state.windows.at(-1)!.windowEnd;
  state.incident.scopeId = state.summary.scope.scopeId;
  state.incident.service = 'SMS';
  state.incident.firstObservedAt = state.windows[0].windowStart;
  state.incident.lastObservedAt = state.windows.at(-1)!.windowEnd;
  state.incident.latestDetection = structuredClone(trajectory.detections.at(-1)!) as typeof state.incident.latestDetection;
  await page.goto('/dashboard');
  const chart = page.locator('[data-chart=deliverySrPct]');
  await expect(chart.locator('.actual-line')).not.toHaveAttribute('d', '');
  await expect(chart.locator('.expected-line')).not.toHaveAttribute('d', '');
  await expect(chart.locator('.incident-band')).toHaveCount(1);
  await chart.locator('.chart-scroll').focus();
  await page.keyboard.press('End');
  await expect(chart.locator('.chart-tooltip')).toContainText('83.333 %');
  await expect(page.locator('.sms-table tbody tr')).toHaveCount(1);
  await expect(page.locator('.sms-table tbody td').nth(1)).toHaveText('83.333');
  await expect(page.locator('.volte-table tbody')).toContainText('No monitored scopes');
});

for (const width of [1366, 768, 390]) {
  test(`connected dashboard at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: width === 1366 ? 768 : 1000 });
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(() => {
      class Source {
        onopen: (() => void) | null = null;
        onerror = null;
        addEventListener() {}
        close() {}
      }
      (window as any).EventSource = Source;
    });
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url()), path = url.pathname;
      let json: unknown;
      if (path === '/api/auth/me') json = { analystId: 'design-review', displayName: 'Design reviewer', roles: ['ANALYST'], expiresAt: new Date(Date.now() + 600000).toISOString() };
      else if (path === '/api/auth/csrf') json = { token: 'controlled-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' };
      else if (path === '/api/services') json = citySummaries;
      else if (path.endsWith('/kpis')) {
        const scopeId = decodeURIComponent(path.split('/')[3]);
        const from = url.searchParams.get('from')!, to = url.searchParams.get('to')!;
        const items = cityWindows.filter(row => row.scopeId === scopeId
          && Date.parse(row.windowStart) >= Date.parse(from) && Date.parse(row.windowStart) < Date.parse(to));
        json = { items, total: items.length, page: 0, size: 100, observedAt: fixtureRange.to };
      } else if (path === '/api/incidents') {
        const service = url.searchParams.get('service');
        const items = cityIncidents.filter(item => !service || item.service === service);
        json = { items, total: items.length, page: 0, size: 20 };
      } else if (path.endsWith('/detections')) json = { items: [cityIncidents[0].latestDetection], total: 1, page: 0, size: 20 };
      else if (path.endsWith('/timeline')) json = { items: [], total: 0, page: 0, size: 100 };
      else if (path.startsWith('/api/incidents/')) json = cityIncidents[0];
      else return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } });
      await route.fulfill({ json });
    });

    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Network overview' })).toBeVisible();
    await expect(page.locator('.city-marker')).toHaveCount(9);
    await expect(page.locator('.overview-panels > section')).toHaveCount(3);
    await expect(page.locator('.map-table tbody tr')).toHaveCount(5);
    await expect(page.locator('.featured-city, .featured-sources, .map-notes, .map-source, .search-results')).toHaveCount(0);
    await expect(page.locator('.city-marker').first()).toHaveAttribute('data-state', 'MAPPING PENDING');
    await expect(page.locator('.city-marker .node-label small')).toHaveCount(0);
    for (const table of await page.locator('.overview-panels table').all()) {
      expect(await table.locator('tbody tr').count()).toBeLessThanOrEqual(5);
      expect(await table.evaluate(node => node.scrollWidth <= node.clientWidth + 1 && node.getBoundingClientRect().height <= node.parentElement!.clientHeight + 1)).toBe(true);
    }
    if (width === 1366) {
      const panels = await page.locator('.overview-panels > section').evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect()));
      expect(Math.max(...panels.map(r => r.width)) - Math.min(...panels.map(r => r.width))).toBeLessThan(1);
      expect(Math.max(...panels.map(r => r.y)) - Math.min(...panels.map(r => r.y))).toBeLessThan(1);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath(`three-panels-unmapped-${width}.png`), fullPage: true, animations: 'disabled' });
    await page.getByLabel('Region', { exact: true }).fill('Orhei');
    await expect(page.locator('#city-map-title')).toContainText('Orhei');
    await expect(page.locator('.service-panel .chart-scope')).toHaveText(['Unavailable', 'Unavailable']);
    await page.getByLabel('Region', { exact: true }).fill('');
    await expect(page.locator('.volte-table tbody tr')).toHaveCount(5);
    await expect(page.getByRole('button', { name: 'Refresh city evidence', exact: true })).toHaveCount(0);
    const apply = (await page.getByRole('button', { name: 'Apply', exact: true }).boundingBox())!;
    const queueToggle = page.getByRole('button', { name: /^Incidents \(/ });
    const toggle = (await queueToggle.boundingBox())!;
    expect(toggle.y).toBeGreaterThan(apply.y);
    expect(toggle.x + toggle.width).toBeCloseTo(apply.x + apply.width, 0);
    expect(toggle.height).toBe(apply.height);
    const drawer = page.getByRole('dialog');
    const investigation = page.getByRole('button', { name: 'Open incident investigation', exact: true });
    await investigation.click();
    await expect(drawer).toBeVisible();
    await expect(page).toHaveURL(/\/dashboard$/);
    await page.keyboard.press('Escape');
    await expect(investigation).toBeFocused();
    await queueToggle.click();
    await expect(drawer).toBeVisible();
    await expect(drawer.locator(':scope > .helper')).toHaveCount(0);
    const close = drawer.getByRole('button', { name: 'Close incidents', exact: true });
    await expect(close).toHaveClass(/primary/);
    await expect(close).not.toBeFocused();
    await expect(close).toHaveCSS('box-shadow', 'none');
    const evidence = drawer.getByRole('link', { name: 'Open incident evidence', exact: true }).first();
    await expect(evidence).toHaveClass(/primary/);
    expect((await evidence.boundingBox())!.height).toBe((await close.boundingBox())!.height);
    await page.screenshot({ path: info.outputPath(`queue-polish-${width}.png`), fullPage: true, animations: 'disabled' });
    await page.keyboard.press('Tab');
    await expect(close).toBeFocused();
    await expect(close).toHaveCSS('outline-style', 'solid');
    await page.keyboard.press('Escape');
    await expect(drawer).not.toBeVisible();
    expect(errors).toEqual([]);
  });
}

for (const width of [1366, 768, 390]) {
  test(`fixture city selection links map, charts, service and incident detail at ${width}px`, async ({ page }, info) => {
    test.skip(process.env.E2E_CITY_FIXTURE !== '1', 'Run explicitly against the fixture server');
    await page.setViewportSize({ width, height: width === 1366 ? 768 : 1000 });
    await page.goto('/dashboard');
    await expect(page.getByText(/SYNTHETIC FIXTURE PREVIEW/)).toBeVisible();
    await expect(page.locator('.city-marker')).toHaveCount(9);
    await expect(page.locator('.overview-panels > section')).toHaveCount(3);
    await expect(page.locator('.map-table tbody tr')).toHaveCount(5);
    await expect(page.locator('.overview-panels [data-chart]')).toHaveCount(2);
    await expect(page.locator('[data-chart=cssrPct] .actual-line')).not.toHaveAttribute('d', '');
    // This older fixture has delay and message counts, but no delivery success KPI.
    await expect(page.locator('[data-chart=deliverySrPct] .actual-line')).toHaveAttribute('d', '');
    await expect(page.locator('[data-chart=deliverySrPct]')).toContainText(/No usable observations|No KPI history/);
    await expect(page.getByLabel('Technology', { exact: true })).toBeDisabled();
    await expect(page.locator('details.source-inventory')).not.toHaveAttribute('open');
    await page.screenshot({ path: info.outputPath(`three-panels-fixture-${width}.png`), fullPage: true, animations: 'disabled' });
    for (const table of await page.locator('.overview-panels table').all()) {
      expect(await table.locator('tbody tr').count()).toBeLessThanOrEqual(5);
      expect(await table.evaluate(node => node.scrollWidth <= node.clientWidth + 1 && node.getBoundingClientRect().height <= node.parentElement!.clientHeight + 1)).toBe(true);
    }
    if (width === 1366) {
      const controls = await page.locator('.controls input:not([type=hidden]), .controls select, .period-chips, .apply-field button').evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect()));
      expect(Math.max(...controls.map(r => r.y)) - Math.min(...controls.map(r => r.y))).toBeLessThan(2);
      expect(controls.every(r => r.height === 36)).toBe(true);
      expect(await page.locator('.overview-panels').evaluate(node => node.getBoundingClientRect().bottom)).toBeLessThanOrEqual(768);
    }
    expect(await page.locator('.map-panel').evaluate(panel => {
      const box = panel.getBoundingClientRect();
      const labels = [...panel.querySelectorAll('.node-label')].map(label => ({ rect: label.getBoundingClientRect(), name: label.textContent }));
      return labels.flatMap(({ rect, name }, index) => [
        ...(rect.left < box.left || rect.right > box.right ? [`${name} outside panel`] : []),
        ...labels.slice(index + 1).filter(({ rect: other }) => rect.right > other.left && other.right > rect.left
          && rect.bottom > other.top && other.bottom > rect.top).map(other => `${name} overlaps ${other.name}`),
      ]);
    })).toEqual([]);
    await page.screenshot({ path: info.outputPath(`three-panels-fixture-${width}.png`), fullPage: true, animations: 'disabled' });
    await page.getByRole('button', { name: '24h', exact: true }).click();
    await expect(page.getByRole('button', { name: '24h', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await page.getByLabel('From (UTC)', { exact: true }).fill('2026-10-05T10:00');
    await page.getByLabel('To (UTC, exclusive)', { exact: true }).fill('2026-10-07T12:00');
    await page.getByRole('button', { name: 'Apply', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('at most 24 hours');
    await page.getByRole('button', { name: '1h', exact: true }).click();
    await expect(page.getByLabel('To (UTC, exclusive)', { exact: true })).toHaveValue(fixtureRange.to.slice(0, 16));
    await page.locator('.map-table').getByRole('button', { name: 'Chișinău', exact: true }).click();
    await expect(page.locator('.city-marker[aria-label="Chișinău, DEGRADED"]')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.map-table tr.selected-city')).toContainText('-5.3 pp');
    await expect(page.locator('.volte-table tbody tr')).toHaveCount(1);
    await expect(page.locator('[data-chart=cssrPct] .incident-band')).toHaveCount(1);
    const chart = page.locator('[data-chart=cssrPct] .chart-scroll');
    await chart.focus(); await page.keyboard.press('End');
    await expect(page.locator('[data-chart=cssrPct] .chart-tooltip')).toContainText('UTC');
    await page.getByLabel('Service', { exact: true }).selectOption('SMS');
    await expect(page.locator('.volte-table tbody')).toContainText('No monitored scopes');
    await expect(page.locator('.sms-table tbody tr')).toHaveCount(1);
    await expect(page.locator('.city-marker').filter({ hasText: 'Chișinău' })).toContainText('ms');
    await page.getByLabel('Service', { exact: true }).selectOption('ALL');
    await page.getByRole('button', { name: /^Incidents \(/ }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'Open incident investigation', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page).toHaveURL(/\/dashboard$/);
    await page.locator('dialog a[href="/incidents/00000000-0000-4000-8000-000000000001"]').click();
    await expect(page).toHaveURL(/\/incidents\/00000000-0000-4000-8000-000000000001$/);
    await page.getByRole('button', { name: 'Details & workflow', exact: true }).click();
    await expect(page.locator('app-incident-actions')).toContainText('Actions are unavailable in the fixture preview.');
    await expect(page.locator('app-incident-actions button, app-incident-actions textarea, app-incident-actions select')).toHaveCount(0);
    await page.keyboard.press('Escape');
    await page.goto('/dashboard');
    await page.getByLabel('Region', { exact: true }).fill('Orhei');
    await expect(page.locator('.volte-table tbody tr')).toHaveCount(1);
    await page.getByRole('button', { name: 'Open VoLTE setup', exact: true }).click();
    await expect(page).toHaveURL(/\/services\/fixture-VOLTE-ORH$/);
    await expect(page.getByLabel('Service', { exact: true })).toHaveValue('VOLTE');
    await page.goto('/dashboard');
    await page.getByRole('button', { name: 'Open SMS delivery', exact: true }).click();
    await expect(page).toHaveURL(/\/services\/SMS-MD-ROUTE-A$/);
    await expect(page.locator('app-kpi-cards tbody tr')).toHaveCount(5);
    await page.goto('/dashboard#incident-queue');
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page).toHaveURL(/\/dashboard$/);
  });
}
