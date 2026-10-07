import { test, expect, type Page } from '@playwright/test';
import pairs from '../../../../../contracts/fixtures/geography/ten-city-scope-pairs.json';
import { cityIncidents, citySummaries, cityWindows, fixtureRange } from '../../../src/fixtures/connected-dashboard';
import type { GeographyCatalogue, ServiceSummary, Incident } from '../../../src/app/core/api/telecom-client';
import type { components } from '../../../src/app/core/api/schema';

// Controlled protected-response tests run the LIVE build, not the fixture data source.
async function liveApi(page: Page) {
  const requests: string[] = [];
  const summaries = citySummaries.map(item => ({ ...structuredClone(item),
    scope: { ...item.scope, scopeId: item.scope.scopeId.replace('fixture-', '').replace(/^(VOLTE|SMS)-/, '$1-MD-') },
  })) as ServiceSummary[];
  const windows = cityWindows.map(item => ({ ...structuredClone(item),
    scopeId: item.scopeId.replace('fixture-', '').replace(/^(VOLTE|SMS)-/, '$1-MD-'), topologyVersion: pairs.topologyVersion,
  }));
  for (const summary of summaries) summary.latestWindow = windows.filter(row => row.scopeId === summary.scope.scopeId).at(-1) ?? null;
  const catalogue: GeographyCatalogue = { generatedAt: fixtureRange.to,
    catalogueVersion: pairs.catalogueVersion, topologyVersion: pairs.topologyVersion,
    cities: pairs.cities.map(city => ({ cityId: city.cityId, displayName: city.displayName, synthetic: true,
      catalogueVersion: pairs.catalogueVersion, topologyVersion: pairs.topologyVersion,
      services: (['VOLTE', 'SMS'] as const).map(service => {
        const window = windows.filter(row => row.scopeId === city.scopes[service]).at(-1)!;
        const metric = window.kpis.find(kpi => kpi.name === (service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs'))!;
        return { service, scopeId: city.scopes[service], latestWindowEnd: window.windowEnd, freshness: 'FRESH',
          technicalActiveCount: city.cityId === 'CHI' ? 1 : 0, analystOpenCount: city.cityId === 'CHI' ? 1 : 0,
          coverage: { state: 'COMPLETE', expectedSources: 3, receivedSources: 3, usableSources: 3 },
          metric: { name: service === 'VOLTE' ? 'TECHNICAL_CSSR' : 'SMS_DELIVERY_P95', unit: metric.unit,
            observed: metric.observed, baseline: metric.baseline,
            deltaPp: service === 'VOLTE' ? metric.observed! - metric.baseline! : null,
            delayRatio: service === 'SMS' ? metric.observed! / metric.baseline! : null,
            numerator: metric.numerator, denominator: metric.denominator,
            sampleCount: window.kpis.find(kpi => kpi.name === 'deliveredMessages')?.observed ?? null, nullReason: null },
        } as components['schemas']['GeographyServiceState'];
      }),
    })),
  };
  const state = { catalogue, summaries, windows, requests, incidents: [] as Incident[], geographyStatus: 200, historyStatus: 200 };
  await page.addInitScript(() => { (window as any).EventSource = class extends EventTarget {
    onopen = null; onerror = null; closed = false;
    constructor() { super(); ((window as any).__sources ??= []).push(this); }
    close() { this.closed = true; }
  }; });
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    requests.push(path + url.search);
    let json: unknown;
    if (path === '/api/auth/me') json = { analystId: 'controlled-live', displayName: 'Controlled live analyst', roles: ['ANALYST'], expiresAt: new Date(Date.now() + 3600000).toISOString() };
    else if (path === '/api/auth/csrf') json = { token: 'controlled-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' };
    else if (path === '/api/geography/cities') return route.fulfill({ status: state.geographyStatus, json: state.geographyStatus === 200 ? catalogue : {} });
    else if (path === '/api/services') json = summaries;
    else if (path === '/api/incidents') {
      const pageNumber = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
      const items = state.incidents.filter(item => !url.searchParams.get('service') || item.service === url.searchParams.get('service'));
      json = { items: items.slice(pageNumber * size, (pageNumber + 1) * size), total: items.length, page: pageNumber, size };
    }
    else if (path === '/api/analysts') json = [{ id: 'controlled-live', displayName: 'Controlled live analyst', enabled: true }];
    else if (path.startsWith('/api/incidents/')) {
      const incident = state.incidents.find(item => item.id === path.split('/')[3]);
      if (!incident) return route.fulfill({ status: 404, json: {} });
      json = path.endsWith('/detections') ? { items: [incident.latestDetection], total: 1, page: 0, size: 20 }
        : path.endsWith('/timeline') ? { items: [], total: 0, page: 0, size: 100 } : incident;
    }
    else if (path.startsWith('/api/geography/cities/')) {
      const city = catalogue.cities.find(city => city.cityId === path.split('/')[4])!;
      if (path.endsWith('/kpis')) {
        if (state.historyStatus !== 200) return route.fulfill({ status: state.historyStatus, json: {} });
        const service = url.searchParams.get('service') as 'VOLTE' | 'SMS';
        const scope = city.services.find(state => state.service === service)!;
        const pageNumber = Number(url.searchParams.get('page'));
        const rows = windows.filter(row => row.scopeId === scope.scopeId);
        json = { cityId: city.cityId, service, page: pageNumber, size: 20, hasNext: pageNumber === 0,
          points: rows.slice(pageNumber * 2, pageNumber * 2 + 2).map(row => ({ windowId: row.windowId, scopeId: row.scopeId,
            catalogueVersion: city.catalogueVersion, topologyVersion: row.topologyVersion,
            windowStart: row.windowStart, windowEnd: row.windowEnd, coverage: scope.coverage, metric: scope.metric })) };
      } else json = { ...city, generatedAt: catalogue.generatedAt, footprintNodeIds: [`CELL-MD-${city.cityId}-01`] };
    } else if (path.endsWith('/kpis')) {
      const scope = path.split('/')[3], from = Date.parse(url.searchParams.get('from')!), to = Date.parse(url.searchParams.get('to')!);
      const items = windows.filter(row => row.scopeId === scope && Date.parse(row.windowStart) >= from && Date.parse(row.windowStart) < to);
      json = { items, total: items.length, page: 0, size: 100, observedAt: fixtureRange.to };
    } else if (path.endsWith('/ml-shadow')) json = { items: [{ schemaVersion: 1, evidenceId: 'controlled-shadow', windowId: 'controlled-window', service: 'SMS', scopeId: path.split('/')[3], windowStart: fixtureRange.from, windowEnd: fixtureRange.to, completedAt: fixtureRange.to, topologyVersion: pairs.topologyVersion, mlStatus: 'OK', classifierScore: 0.82, threshold: 0.55, detection: true, modelVersion: 'sms-supervised-v1-2' }], total: 1, page: 0, size: 20 };
    else return route.fulfill({ status: 404, json: {} });
    return route.fulfill({ json });
  });
  return state;
}

for (const width of [1366, 768, 390]) {
  test(`LIVE catalogue and selected-city APIs at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await liveApi(page);
    await page.goto('/dashboard');
    await expect(page.locator('.city-marker')).toHaveCount(9);
    await expect(page.locator('.map-table')).not.toContainText('Mapping pending');
    await expect(page.locator('.city-marker .node-label small')).toHaveCount(9);
    await page.locator('.map-table').getByRole('button', { name: 'Chișinău', exact: true }).click();
    await expect(page.locator('.chart-scope')).toContainText(['Chișinău', 'Chișinău']);
    await expect(page.locator('.chart-scope').nth(0)).toHaveAttribute('data-scope', 'VOLTE-MD-CHI');
    await expect(page.locator('.chart-scope').nth(1)).toHaveAttribute('data-scope', 'SMS-MD-CHI');
    await expect.poll(() => state.requests.filter(path => path.startsWith('/api/geography/cities/CHI/kpis')).length).toBeGreaterThanOrEqual(2);
    await expect(page.locator('[data-chart=cssrPct] .actual-line')).not.toHaveAttribute('d', '');
    await page.getByText('Chișinău · City coverage and history', { exact: true }).click();
    await expect(page.locator('app-city-evidence')).toContainText('CELL-MD-CHI-01');
    await expect(page.locator('[data-city-window]')).toHaveCount(4);
    await page.getByRole('navigation', { name: 'VOLTE city history pages' }).getByRole('button', { name: 'Next', exact: true }).click();
    await expect.poll(() => state.requests.some(path => path.startsWith('/api/geography/cities/CHI/kpis') && path.includes('page=1'))).toBe(true);
    await page.getByRole('button', { name: 'Refresh overview', exact: true }).click();
    await expect.poll(() => state.requests.filter(path => path === '/api/geography/cities').length).toBe(2);
    await expect(page.locator('#city-map-title')).toContainText('Chișinău');
    await page.getByLabel('Service', { exact: true }).selectOption('SMS');
    await expect(page.locator('.city-marker').filter({ hasText: 'Chișinău' })).toContainText('ms');
    await page.screenshot({ path: info.outputPath(`live-geography-${width}.png`), fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole('button', { name: 'Open SMS delivery', exact: true }).click();
    await expect(page).toHaveURL(/services\/SMS-MD-CHI$/);
    await expect(page.locator('app-kpi-cards')).toContainText('P95');
    await page.locator('app-sms-shadow summary').click();
    await expect(page.locator('[data-shadow-id]')).toContainText('0.82');
    expect(state.requests.some(path => path.startsWith('/api/services/SMS-MD-CHI/kpis'))).toBe(true);
    expect(state.requests.some(path => path.includes('fixture-'))).toBe(false);
  });
}

test('LIVE geography outages and missing history never substitute fixture values', async ({ page }) => {
  const state = await liveApi(page);
  state.geographyStatus = 503;
  await page.goto('/dashboard');
  await expect(page.getByRole('status').filter({ hasText: 'City catalogue unavailable' })).toBeVisible();
  await expect(page.locator('.city-marker .node-label small')).toHaveCount(0);
  state.geographyStatus = 200;
  await page.getByRole('button', { name: 'Refresh overview', exact: true }).click();
  await expect(page.locator('.city-marker .node-label small')).toHaveCount(9);
  state.historyStatus = 503;
  await page.getByLabel('Region', { exact: true }).fill('Orhei');
  await expect(page.locator('.chart-scope')).toContainText(['Orhei', 'Orhei']);
  await expect(page.locator('.chart-scope').nth(0)).toHaveAttribute('data-scope', 'VOLTE-MD-ORH');
  await expect(page.locator('.chart-scope').nth(1)).toHaveAttribute('data-scope', 'SMS-MD-ORH');
  await page.getByText('Orhei · City coverage and history', { exact: true }).click();
  await expect(page.locator('app-city-evidence [role=alert]')).toContainText('No substitute');
  await expect(page.locator('[data-city-window]')).toHaveCount(0);
});

for (const width of [1366, 768, 390]) {
  test(`connected Orhei investigation across queue pages and historical filters at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await liveApi(page);
    const base = structuredClone(cityIncidents[0]);
    state.incidents = Array.from({ length: 25 }, (_, index) => ({ ...structuredClone(base),
      id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`, scopeId: 'VOLTE-MD-CHI',
    }));
    const orhei: Incident = { ...base, id: '00000000-0000-4000-8000-999999999999', scopeId: 'VOLTE-MD-ORH',
      status: 'INVESTIGATING', technicalState: 'RECOVERED',
      latestDetection: { ...base.latestDetection, scopeId: 'VOLTE-MD-ORH', phase: 'RECOVERY', technicalState: 'RECOVERED', evidence: [] },
    };
    state.incidents.push(orhei);
    await page.goto('/dashboard');
    await page.getByLabel('Region', { exact: true }).fill('Orhei');
    await expect(page.locator('.chart-scope')).toContainText(['Orhei', 'Orhei']);
    await expect(page.locator('.chart-scope').nth(0)).toHaveAttribute('data-scope', 'VOLTE-MD-ORH');
    await expect(page.locator('.chart-scope').nth(1)).toHaveAttribute('data-scope', 'SMS-MD-ORH');
    await expect(page.getByRole('link', { name: 'Open Orhei VoLTE setup', exact: false })).toHaveAttribute('href', '/services/VOLTE-MD-ORH');
    await expect(page.getByRole('link', { name: 'Open Orhei SMS delivery', exact: false })).toHaveAttribute('href', '/services/SMS-MD-ORH');
    await page.getByLabel('From (UTC)', { exact: true }).fill('2026-09-14T10:00');
    await page.getByLabel('To (UTC, exclusive)', { exact: true }).fill('2026-09-14T10:15');
    await page.getByRole('button', { name: 'Apply', exact: true }).click();
    const investigation = page.getByRole('button', { name: 'Open incident investigation', exact: true });
    await expect(investigation).toBeEnabled();
    await page.getByLabel('Region', { exact: true }).focus();
    await page.evaluate(() => window.scrollTo(0, 320));
    const scrollBefore = await page.evaluate(() => window.scrollY);
    const catalogueReads = state.requests.filter(path => path === '/api/geography/cities').length;
    await page.evaluate(() => (window as any).__sources.find((source: any) => !source.closed).onopen?.());
    await expect.poll(() => state.requests.filter(path => path === '/api/geography/cities').length).toBeGreaterThan(catalogueReads);
    await expect(page.getByLabel('Region', { exact: true })).toHaveValue('Orhei');
    await expect(page.getByLabel('Region', { exact: true })).toBeFocused();
    await expect(page.getByLabel('From (UTC)', { exact: true })).toHaveValue('2026-09-14T10:00');
    expect(await page.evaluate(() => window.scrollY)).toBe(scrollBefore);
    await investigation.click();
    await expect(page).toHaveURL(new RegExp(`/incidents/${orhei.id}$`));
    await expect(page.locator('.incident-summary-bar')).toContainText('Awaiting analyst resolution');
    await expect(page.locator('[aria-label="Current incident times"]')).toContainText('Updated (UTC)');
    await page.locator('.cause-details > summary').click();
    await expect(page.locator('app-cause-evidence')).toContainText('Cause undetermined');
    await expect(page.locator('app-cause-evidence')).toContainText('Affected paths: Unavailable');
    await expect(page.locator('app-cause-evidence')).toContainText('classification: Unavailable');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: info.outputPath(`connected-orhei-${width}.png`), fullPage: true });
    await page.getByRole('button', { name: 'Refresh incident', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Refresh incident', exact: true })).toBeFocused();
    await expect(page.locator('.cause-details')).toHaveAttribute('open', '');
    expect(state.requests.some(path => path.includes('/api/geography/cities/ORH/kpis'))).toBe(true);
    expect(state.requests.some(path => path.includes('fixture-'))).toBe(false);
  });
}
