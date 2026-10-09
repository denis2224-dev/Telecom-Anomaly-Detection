import { test, expect } from '@playwright/test';
import { controlledApi } from '../helpers/controlled-api';
import pairs from '../../../../../contracts/fixtures/geography/ten-city-scope-pairs.json';
import { previewScenarios } from '../../../src/app/features/scenario-runner/monitoring-preview';

for (const width of [1366, 390]) {
  test(`roaming overview, live status and incident sheet are connected at ${width}px`, async ({ page }, info) => {
    const state = await controlledApi(page);
    await page.setViewportSize({ width, height: 844 });
    await page.goto('/dashboard');
    await expect(page.getByRole('region', { name: 'Roaming overview' })).toContainText('not live subscriber counts');
    await expect(page.locator('[data-chart=registrationSrPct] .actual-line')).not.toHaveAttribute('d', '');
    await expect(page.locator('.country-panels tbody tr')).toHaveCount(20);
    expect(await page.locator('.compact .country-panels table').evaluateAll(tables => tables.every(table => table.scrollWidth <= table.parentElement!.clientWidth + 1))).toBe(true);
    await expect(page.getByLabel('Key service information')).toContainText('Call setup success');
    await expect(page.getByLabel('Key service information')).toContainText('Backhaul trafficUnavailable');
    await expect(page.locator('.transport-link')).toHaveCount(8);
    await expect(page.getByLabel('Illustrative intercity traffic')).toContainText('8.9 Gbps');
    await page.getByRole('checkbox', { name: 'Show sample links' }).uncheck();
    await expect(page.locator('.transport-link')).toHaveCount(0);
    await expect(page.locator('.city-marker')).toHaveCount(9);
    await page.getByRole('checkbox', { name: 'Show sample links' }).check();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (width === 1366) {
      const boxes = await page.locator('.overview-panels > section').evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect()));
      expect(boxes[1].left).toBeGreaterThan(boxes[0].right);
      expect(boxes[2].top).toBeGreaterThan(boxes[0].bottom);
      expect(boxes[1].bottom).toBeCloseTo(boxes[2].bottom, 0);
      expect((await page.locator('.city-map').boundingBox())!.width).toBeGreaterThan(350);
    }
    await page.screenshot({ path: info.outputPath(`overview-traffic-${width}.png`), fullPage: true });
    await page.getByRole('link', { name: 'Open roaming preview', exact: false }).click();
    await expect(page).toHaveURL(/\/roaming$/);
    await expect(page.locator('.country-panels tbody tr')).toHaveCount(20);
    await page.getByRole('combobox', { name: 'Sort roaming countries' }).selectOption('voice');
    await expect(page.locator('.country-panels tbody').first().locator('tr').first()).toContainText('Ukraine');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath(`roaming-${width}.png`), fullPage: true });
    await page.goto('/dashboard?view=live');
    await expect(page.getByRole('region', { name: 'Live status' })).toContainText('1 active incidents');
    await expect(page.locator('app-connected-overview')).toHaveCount(0);
    await page.locator('.live-incident').click();
    await expect(page).toHaveURL(new RegExp(`/incidents/${state.incident.id}$`));
    await expect(page.getByRole('heading', { name: 'Incident investigation', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Details & workflow' }).click();
    const drawer = page.getByRole('dialog'); await expect(drawer).toBeVisible();
    if (width === 390) {
      const box = (await drawer.boundingBox())!; expect(box.x).toBe(0); expect(box.y).toBe(0);
      expect(box.width).toBe(390); expect(box.height).toBe(844);
    }
  });
}

test('overview call results use the loaded window and keep missing evidence unavailable', async ({ page }) => {
  const state = await controlledApi(page);
  const last = state.windows.at(-1)!;
  last.kpis.find(kpi => kpi.name === 'cssrPct')!.numerator = 980;
  last.kpis.find(kpi => kpi.name === 'cssrPct')!.denominator = 1000;
  last.kpis.find(kpi => kpi.name === 'cssrPct')!.observed = 98;
  await page.goto('/dashboard');
  await expect(page.getByLabel('VoLTE calls by result')).toContainText('980');
  await expect(page.getByLabel('Key service information')).toContainText('Call setup success98%');
  await expect(page.getByLabel('Key service information')).toContainText('Failed setup share2%');
  last.quality = 'INCOMPLETE';
  await page.reload();
  await expect(page.getByLabel('VoLTE calls by result')).toContainText('Call result counts unavailable');
  await expect(page.getByLabel('Key service information')).toContainText('Call setup successUnavailable');
  await expect(page.locator('.call-donut')).toHaveCount(0);
});

test('all local scenario controls render seeded evidence without posting unsupported commands', async ({ page }, info) => {
  const state = await controlledApi(page);
  await page.route('**/api/auth/me', route => route.fulfill({ json: { analystId: 'preview-review', displayName: 'Preview reviewer', roles: ['SUPERVISOR'], expiresAt: new Date(Date.now() + 3600000).toISOString() } }));

  const sms = { ...structuredClone(state.summary), scope: { ...state.summary.scope, scopeId: 'SMS-MD-ROUTE-A', service: 'SMS' } };
  const cityScopes = pairs.cities.flatMap(city => (['VOLTE','SMS'] as const).map(service => ({ ...structuredClone(state.summary), scope: { ...state.summary.scope, service, scopeId: city.scopes[service], region: city.displayName } })));
  await page.route('**/api/services', route => route.fulfill({ json: [state.summary, sms, ...cityScopes] }));
  await page.route('**/api/geography/cities', route => route.fulfill({ json: { catalogueVersion: pairs.catalogueVersion, topologyVersion: pairs.topologyVersion, cities: pairs.cities.map(city => ({ catalogueVersion: pairs.catalogueVersion, topologyVersion: pairs.topologyVersion, services: (['VOLTE','SMS'] as const).map(service => ({ service, scopeId: city.scopes[service] })) })) } }));
  let scenarioPosts = 0;
  await page.route('**/api/scenarios/**', route => { if (route.request().method() === 'POST') scenarioPosts++; return route.fulfill({ status: 503, json: {} }); });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/scenarios');
  for (const scenario of previewScenarios) {
    await page.getByLabel('Scenario', { exact: true }).selectOption(scenario.id);
    await page.getByLabel('Service scope', { exact: true }).selectOption(scenario.id.startsWith('SMS') ? sms.scope.scopeId : scenario.id === 'VOLTE_CITY_TRANSPORT_OVERLOAD' ? 'VOLTE-MD-ORH' : state.summary.scope.scopeId);
    await page.getByLabel('Seed', { exact: true }).fill('73');
    if (scenario.id.includes('ROAMING')) await page.getByLabel('Country', { exact: true }).selectOption('Romania');
    await page.getByRole('button', { name: scenario.id === 'VOLTE_IMS_OVERLOAD' ? 'Preview IMS profile' : 'Generate preview', exact: true }).click();
    const preview = page.getByRole('region', { name: 'Local scenario preview' });
    await expect(preview).toContainText('Seed 73'); await expect(preview).toContainText('no server run or recorded incident');
    await expect(preview.locator('.preview-timeline li')).toHaveCount(6);
    await expect(preview.locator('.actual-line').first()).not.toHaveAttribute('d', '');
    if (scenario.id === 'SMS_DELAYED_DELIVERY') await expect(preview).toContainText('lost: 0');
    if (scenario.id.includes('OVERLOAD')) await expect(preview.locator('.threshold-line')).toHaveCount(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  expect(scenarioPosts).toBe(0);
  await page.screenshot({ path: info.outputPath('sms-preview-phone.png'), fullPage: true });
});
