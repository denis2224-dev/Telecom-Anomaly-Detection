import { test, expect, type Page } from '@playwright/test';
import { controlledApi } from '../helpers/controlled-api';
import pairs from '../../../../../contracts/fixtures/geography/ten-city-scope-pairs.json';
import { citySummaries, cityWindows, fixtureRange } from '../../../src/fixtures/connected-dashboard';
import type { GeographyCatalogue, Incident } from '../../../src/app/core/api/telecom-client';

test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled city service navigation; real connections run separately.');

async function cityApi(page: Page) {
  const base = await controlledApi(page);
  const windows = cityWindows.map(row => ({ ...structuredClone(row),
    scopeId: row.scopeId.replace('fixture-', '').replace(/^(VOLTE|SMS)-/, '$1-MD-') }));
  const summaries = citySummaries.map(row => ({ ...structuredClone(row), scope: { ...row.scope,
    scopeId: row.scope.scopeId.replace('fixture-', '').replace(/^(VOLTE|SMS)-/, '$1-MD-') } }));
  for (const summary of summaries) summary.latestWindow = windows.filter(row => row.scopeId === summary.scope.scopeId).at(-1)!;
  const catalogue: GeographyCatalogue = { generatedAt: fixtureRange.to,
    catalogueVersion: pairs.catalogueVersion, topologyVersion: pairs.topologyVersion,
    cities: pairs.cities.map(city => ({ cityId: city.cityId, displayName: city.displayName, synthetic: true,
      catalogueVersion: pairs.catalogueVersion, topologyVersion: pairs.topologyVersion,
      services: (['VOLTE', 'SMS'] as const).map(service => ({ service, scopeId: city.scopes[service],
        latestWindowEnd: fixtureRange.to, freshness: 'FRESH', technicalActiveCount: 1, analystOpenCount: 1,
        coverage: { state: 'COMPLETE', expectedSources: 3, receivedSources: 3, usableSources: 3 },
        metric: { name: service === 'VOLTE' ? 'TECHNICAL_CSSR' : 'SMS_DELIVERY_P95',
          unit: service === 'VOLTE' ? 'PERCENT' : 'MILLISECONDS', observed: null, baseline: null,
          deltaPp: null, delayRatio: null, nullReason: 'UNKNOWN' },
      })),
    })),
  };
  catalogue.cities.sort((a, b) => a.displayName.localeCompare(b.displayName));
  const incidents: Incident[] = summaries.map((summary, index) => {
    const city = catalogue.cities.find(city => city.services.some(row => row.scopeId === summary.scope.scopeId))!;
    return { ...structuredClone(base.incident), id: `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
      episodeId: String(index).padStart(64, '0'), scopeId: summary.scope.scopeId, service: summary.scope.service,
      latestDetection: { ...structuredClone(base.incident.latestDetection), scopeId: summary.scope.scopeId,
        service: summary.scope.service, anomalyType: summary.scope.service === 'VOLTE' ? 'VOLTE_SETUP_DEGRADATION' : 'SMS_DELIVERY_DELAY',
        kpis: structuredClone(summary.latestWindow!.kpis) },
      location: { cityId: city.cityId, measuredScopeId: summary.scope.scopeId, catalogueVersion: pairs.catalogueVersion,
        topologyVersion: pairs.topologyVersion, containmentPath: [], dependencyNodeIds: [], nullReason: null },
    };
  });
  const state = { catalogue, summaries, windows, incidents, requests: [] as string[], geographyStatus: 200, failedScope: '' };
  await page.route('**/api/geography/cities', route => route.fulfill({ status: state.geographyStatus,
    json: state.geographyStatus === 200 ? catalogue : { code: 'UNAVAILABLE' } }));
  await page.route('**/api/services', route => route.fulfill({ json: summaries }));
  await page.route('**/api/services/*/kpis?**', route => {
    const url = new URL(route.request().url());
    state.requests.push(url.pathname + url.search);
    const scopeId = url.pathname.split('/')[3];
    if (scopeId === state.failedScope) return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } });
    const items = windows.filter(row => row.scopeId === scopeId
      && Date.parse(row.windowStart) >= Date.parse(url.searchParams.get('from')!)
      && Date.parse(row.windowStart) < Date.parse(url.searchParams.get('to')!));
    return route.fulfill({ json: { items, total: items.length, observedAt: fixtureRange.to, page: 0, size: 100 } });
  });
  await page.route('**/api/incidents?**', route => {
    const scopeId = new URL(route.request().url()).searchParams.get('scopeId');
    const items = incidents.filter(item => item.scopeId === scopeId);
    return route.fulfill({ json: { items, total: items.length, page: 0, size: 20 } });
  });
  await page.route('**/api/incidents/*/detections?**', route => {
    const item = incidents.find(item => route.request().url().includes(`/incidents/${item.id}/`))!;
    const first = { ...structuredClone(item.latestDetection), episodeId: item.episodeId, phase: 'OPEN', sequence: 1,
      kpis: item.service === 'VOLTE'
        ? [{ name: 'cssrPct', observed: 96.2, baseline: 99.3, unit: 'PERCENT', numerator: 962, denominator: 1000 }]
        : [{ name: 'p95DeliveryMs', observed: 3400, baseline: 2000, unit: 'MILLISECONDS', numerator: null, denominator: null },
          { name: 'deliveredMessages', observed: 1000, baseline: null, unit: 'COUNT', numerator: null, denominator: null }],
    };
    return route.fulfill({ json: { items: [first], total: 1, page: 0, size: 1 } });
  });
  return state;
}

for (const service of ['VOLTE', 'SMS'] as const) for (const width of [1366, 768, 390]) {
  test(`${service} city choice precedes dates and keeps graphs, incidents and period aligned at ${width}px`, async ({ page }, info) => {
    const state = await cityApi(page);
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/services/${pairs.cities[0].scopes[service]}`);
    const selector = page.getByRole('combobox', { name: 'City', exact: true });
    await expect(selector.locator('option')).toHaveCount(10);
    await expect(selector).toHaveValue(pairs.cities[0].scopes[service]);
    const from = page.getByLabel('From (UTC)', { exact: true });
    const to = page.getByLabel('To (UTC, exclusive)', { exact: true });
    const cityBox = (await selector.boundingBox())!, fromBox = (await from.boundingBox())!;
    expect(width > 600 ? cityBox.x + cityBox.width <= fromBox.x : cityBox.y + cityBox.height <= fromBox.y).toBe(true);
    await from.fill('2026-09-15T10:00');
    await to.fill('2026-09-15T10:05');
    await page.getByRole('button', { name: 'Apply time range', exact: true }).click();
    for (const city of pairs.cities) {
      await selector.selectOption(city.scopes[service]);
      await expect(page.locator('.heading-copy')).toContainText(city.scopes[service]);
      await expect(from).toHaveValue('2026-09-15T10:00');
      await expect(to).toHaveValue('2026-09-15T10:05');
      await expect(page.locator('.episode-card')).toHaveCount(1);
      await expect(page.locator('.episode-location')).toContainText(city.displayName);
      await expect(page.locator('.episode-description')).toContainText(service === 'VOLTE'
        ? '3.1 percentage points below' : '1,400 ms above');
      await expect(page.locator('.service-hero [data-chart]')).toHaveAttribute('data-chart', service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs');
      await expect.poll(() => state.requests.some(path => {
        const url = new URL(path, 'http://test.local');
        return url.pathname === `/api/services/${city.scopes[service]}/kpis`
          && url.searchParams.get('from') === '2026-09-15T10:00:00.000Z'
          && url.searchParams.get('to') === '2026-09-15T10:05:00.000Z';
      })).toBe(true);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await page.locator('.service-toolbar').screenshot({ path: info.outputPath(`${service}-city-${width}.png`) });
    await page.locator('.episode-card').screenshot({ path: info.outputPath(`${service}-location-${width}.png`) });
  });
}

test('failed city history stays isolated and the selector can recover to another city', async ({ page }) => {
  const state = await cityApi(page);
  await page.goto('/services/VOLTE-MD-CHI');
  state.failedScope = 'VOLTE-MD-ORH';
  await page.getByRole('combobox', { name: 'City', exact: true }).selectOption('VOLTE-MD-ORH');
  await expect(page.getByRole('alert')).toContainText('could not be reached');
  await expect(page.locator('.episode-card, .service-hero')).toHaveCount(0);
  await page.getByRole('combobox', { name: 'City', exact: true }).selectOption('VOLTE-MD-BAL');
  await expect(page.locator('.episode-location')).toContainText('Bălți');
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('captured incident city takes precedence over current scope membership', async ({ page }) => {
  const state = await cityApi(page);
  state.incidents[0].location!.cityId = 'BAL';
  await page.goto('/services/VOLTE-MD-CHI');
  await expect(page.getByRole('combobox', { name: 'City', exact: true })).toHaveValue('VOLTE-MD-CHI');
  await expect(page.locator('.episode-location')).toContainText('Bălți');
  await expect(page.locator('.episode-location')).not.toContainText('Chișinău');
});

test('city catalogue failure keeps service evidence usable and can be retried', async ({ page }) => {
  const state = await cityApi(page);
  state.geographyStatus = 503;
  await page.goto('/services/SMS-MD-CHI');
  await expect(page.getByRole('combobox', { name: 'City', exact: true })).toBeDisabled();
  await expect(page.locator('.service-hero')).toBeVisible();
  await expect(page.locator('.episode-location')).toContainText('CHI');
  state.geographyStatus = 200;
  await page.getByRole('button', { name: 'Retry cities', exact: true }).click();
  await expect(page.getByRole('combobox', { name: 'City', exact: true })).toBeEnabled();
  await expect(page.locator('.episode-location')).toContainText('Chișinău');
});
