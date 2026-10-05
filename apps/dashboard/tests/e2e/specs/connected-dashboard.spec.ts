import { test, expect } from '@playwright/test';
import { cityIncidents, citySummaries, cityWindows, fixtureCities, fixtureRange } from '../../../src/fixtures/connected-dashboard';

for (const width of [1366, 768, 390]) {
  test(`connected dashboard at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 });
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
    await expect(page.getByRole('heading', { name: 'City service overview' })).toBeVisible();
    // Production configuration must NOT infer mappings from these synthetic API scope IDs.
    await expect(page.locator('.city-marker')).toHaveCount(9);
    await expect(page.locator('.featured-city')).toHaveCount(5);
    await expect(page.locator('.city-marker').first()).toHaveAttribute('data-state', 'MAPPING PENDING');
    await page.getByLabel('Region', { exact: true }).fill('Orhei');
    await page.getByRole('button', { name: 'Orhei', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Orhei service detail' })).toBeVisible();
    await expect(page.locator('.city-detail')).toContainText('Mapping pending');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath(`live-unmapped-design-${width}.png`), fullPage: true });
    expect(errors).toEqual([]);
  });
}

for (const width of [1366, 768, 390]) {
test(`fixture city selection links map, charts, service and incident detail at ${width}px`, async ({ page }, info) => {
  test.skip(process.env.E2E_CITY_FIXTURE !== '1', 'Run explicitly against the fixture server');
  await page.setViewportSize({ width, height: 1000 });
  await page.goto('/dashboard');
  await expect(page.locator('.city-marker')).toHaveCount(9);
  await expect(page.locator('.featured-city')).toHaveCount(5);
  await expect(page.getByLabel('Service', { exact: true })).toHaveValue('ALL');
  await expect(page.getByLabel('Technology', { exact: true })).toBeDisabled();
  await expect(page.getByRole('link', { name: 'VoLTE setup', exact: true, includeHidden: true })).toHaveAttribute('href', '/dashboard?service=VOLTE');
  await expect(page.getByRole('link', { name: 'SMS delivery', exact: true, includeHidden: true })).toHaveAttribute('href', '/dashboard?service=SMS');
  await expect(page.locator('details.source-inventory')).not.toHaveAttribute('open');
  await page.getByLabel('Service', { exact: true }).selectOption('SMS');
  await expect(page.locator('.map-table caption')).toContainText('SMS delivery');
  await expect(page.locator('.city-marker').filter({ hasText: 'Chișinău' })).toContainText('ms');
  await expect(page.locator('app-city-trend')).toHaveCount(5);
  await page.getByLabel('Service', { exact: true }).selectOption('VOLTE');
  await expect(page.locator('.map-table caption')).toContainText('VoLTE setup');
  await expect(page.locator('.city-marker').filter({ hasText: 'Chișinău' })).toContainText('%');
  await page.getByLabel('From (UTC)', { exact: true }).fill('2026-10-05T10:00');
  await page.getByLabel('To (UTC, exclusive)', { exact: true }).fill('2026-10-05T12:00');
  await page.getByRole('button', { name: 'Apply', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('at most one hour');
  await page.getByLabel('Chart period', { exact: true }).selectOption('60');
  await expect(page.getByLabel('To (UTC, exclusive)', { exact: true })).toHaveValue(fixtureRange.to.slice(0, 16));
  await page.getByLabel('Service', { exact: true }).selectOption('ALL');
  // Callouts must remain inside the map panel, including on phones.
  expect(await page.locator('.map-panel').evaluate(panel => {
    const box = panel.getBoundingClientRect();
    return [...panel.querySelectorAll('.node-label')].every(label => {
      const rect = label.getBoundingClientRect();
      return rect.left >= box.left && rect.right <= box.right;
    });
  })).toBe(true);
  await expect(page.locator('.city-marker[aria-label="Chișinău, DEGRADED"]')).toBeVisible();
  await page.locator('.city-marker').filter({ hasText: 'Chișinău' }).click();
  await expect(page.getByRole('heading', { name: 'Chișinău service detail' })).toBeVisible();
  await expect(page.locator('.city-detail')).toContainText('-5.3 pp from baseline');
  await expect(page.locator('.queue-item')).toContainText('Chișinău');
  await expect(page.locator('app-city-trend')).toHaveCount(10);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath(`connected-fixture-${width}.png`), fullPage: true });
  await page.getByRole('link', { name: 'Open incident evidence', exact: true }).click();
  await expect(page).toHaveURL(/\/incidents\/00000000-0000-4000-8000-000000000001$/);
  await expect(page.getByLabel('Service', { exact: true })).toHaveValue('VOLTE');
  await page.getByRole('button', { name: 'Details & workflow', exact: true }).click();
  await expect(page.locator('.workflow-drawer')).toContainText('First observed');
  await page.goto('/dashboard');
  await page.getByLabel('Region', { exact: true }).fill('Orhei');
  await page.getByRole('button', { name: 'Orhei', exact: true }).click();
  await page.getByRole('link', { name: 'Open Orhei VoLTE evidence', exact: true }).click();
  await expect(page).toHaveURL(/\/services\/fixture-VOLTE-ORH$/);
  await expect(page.getByRole('heading', { name: 'Call setup success rate', exact: true })).toBeVisible();
  await expect(page.getByLabel('Service', { exact: true })).toHaveValue('VOLTE');
  await page.getByLabel('Service', { exact: true }).selectOption('SMS');
  await expect(page).toHaveURL(/\/dashboard\?service=SMS$/);
  await expect(page.getByLabel('Service', { exact: true })).toHaveValue('SMS');
  await expect(page.locator('.map-table caption')).toContainText('SMS delivery');
  await page.getByLabel('Region', { exact: true }).fill('Orhei');
  await expect(page.getByRole('heading', { name: 'Orhei service detail' })).toBeVisible();
  await page.getByLabel('Region', { exact: true }).fill('');
  await expect(page.locator('.city-detail')).toHaveCount(0);
  expect(fixtureCities.find(city => city.id === 'ORH')?.marker).toBeNull();
});
}
