import { recordedRoaming } from '../helpers/recorded-roaming';
import { test, expect } from '../helpers/test';
import { controlledApi } from '../helpers/controlled-api';
import pairs from '../../../../../contracts/fixtures/geography/ten-city-scope-pairs.json';
import { previewScenarios } from '../../../src/app/features/scenario-runner/monitoring-preview';

for (const width of [1366, 390]) {
  test(`roaming overview, live status and incident sheet are connected at ${width}px`, async ({ page }, info) => {
    const state = await controlledApi(page);
    await page.route('**/api/v2/datasets',route=>route.fulfill({json:[]}));
    await page.setViewportSize({ width, height: 844 });
    await page.goto('/dashboard');
    await expect(page.getByRole('navigation', {name:'Workspace'}).getByRole('link', {name:/history|Historical analysis/i})).toHaveCount(0);
    await expect(page.locator('app-connected-overview > section > a').filter({hasText:'Investigate history'})).toHaveAttribute('href', /\/trace-link\?.*from=/);
    await expect(page.getByRole('region', { name: 'Roaming overview' })).toContainText('Sample data');
    await expect(page.locator('.country-panels tbody tr')).toHaveCount(10);
    await expect(page.getByLabel('Key service information')).toContainText('Call setup success');
    await expect(page.getByLabel('Key service information').locator(':scope > div')).toHaveCount(6);
    await expect(page.getByLabel('Key service information')).toContainText('Backhaul peak utilizationUnavailable');
    await expect(page.getByLabel('Key service information')).toContainText('Sample data · 10 countries');
    await expect(page.getByLabel('Key service information')).not.toContainText('IMS CPU');
    const subscribers=(await page.locator('.country-bar strong').allTextContents()).reduce((sum,value)=>sum+Number(value.replaceAll(',','')),0);
    await expect(page.locator('.headline-metrics > div').filter({hasText:'Roaming subscribers'}).locator('strong')).toHaveText(subscribers.toLocaleString('en'));
    await expect(page.getByLabel('Key service information')).not.toContainText('Call drop rate');
    await expect(page.getByLabel('Key service information')).toContainText('Roaming LU success99.1%');
    await expect(page.locator('.transport-link')).toHaveCount(0);
    await expect(page.getByRole('checkbox', { name: 'Show sample links' })).toHaveCount(0);
    await expect(page.locator('.city-marker')).toHaveCount(10);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (width === 1366) {
      const controls = await page.locator('.controls input, .controls select, .period-chips, .apply-field button').evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().top));
      expect(Math.max(...controls) - Math.min(...controls)).toBeLessThan(2);
      const apply = (await page.getByRole('button', { name: 'Apply', exact: true }).boundingBox())!;
      const incidents = (await page.locator('.queue-toggle').boundingBox())!;
      expect(incidents.x).toBeCloseTo(apply.x, 0);
      expect(incidents.y - apply.y - apply.height).toBeGreaterThanOrEqual(6);
      expect(incidents.y - apply.y - apply.height).toBeLessThanOrEqual(10);
      const boxes = await page.locator('.overview-panels .service-panel, .overview-panels > .map-panel').evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect()));
      expect(boxes[2].left).toBeGreaterThan(boxes[0].right);
      expect(boxes[1].top).toBeGreaterThan(boxes[0].bottom);
      expect(boxes[1].top-boxes[0].bottom).toBeLessThanOrEqual(10);
      expect((await page.locator('.city-map').boundingBox())!.width).toBeGreaterThan(350);
    }
    await page.screenshot({ path: info.outputPath(`overview-traffic-${width}.png`), fullPage: true });
    await page.getByRole('link', { name: 'Open roaming services', exact: false }).click();
    await expect(page).toHaveURL(/\/roaming$/);
    await expect(page.locator('.country-panels tbody tr')).toHaveCount(20);
    await expect(page.locator('.roaming-chart .actual-line')).not.toHaveAttribute('d','');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({path:info.outputPath(`roaming-${width}.png`),fullPage:true});
    await page.getByRole('link',{name:'Investigate history',exact:true}).click();
    await expect(page).toHaveURL(/\/trace-link\?/);
    await expect(page.getByRole('heading', { name: 'Historical analysis' })).toBeVisible();
    await expect(page.locator('.country-panels tbody tr')).toHaveCount(0);
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
  await expect(page.getByLabel('Key service information')).not.toContainText('Failed setup share');
  last.quality = 'INCOMPLETE';
  await page.reload();
  await expect(page.getByLabel('VoLTE calls by result')).toContainText('Call result counts unavailable');
  await expect(page.getByLabel('Key service information')).toContainText(/Call setup success\s*Unavailable/);
  await expect(page.locator('.call-donut')).toHaveCount(0);
});

test('unsupported previews are excluded from execution and the legacy IMS preview stays local', async ({ page }, info) => {
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
  for (const scenario of previewScenarios.filter(item => item.id !== 'VOLTE_IMS_OVERLOAD')) {
    await expect(page.getByLabel('Scenario', { exact: true }).locator(`option[value="${scenario.id}"]`)).toHaveCount(0);
  }
  for (const scenario of previewScenarios.filter(item => item.id === 'VOLTE_IMS_OVERLOAD')) {
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

 test('recorded roaming replaces the labelled sample and retries an unavailable source',async({page})=>{
  await controlledApi(page);const source=await recordedRoaming(page);
  await page.goto('/roaming');
  await expect(page.getByRole('region',{name:'Roaming services recorded'})).toBeVisible();
  await expect(page.locator('.country-panels tbody tr')).toHaveCount(20);
  await expect(page.locator('.roaming-kpis')).toContainText('98.0%');
  await expect(page.locator('.country-bar strong')).toHaveText(Array(10).fill('100'));
  await expect(page.locator('.roaming-chart .actual-line')).not.toHaveAttribute('d','');
  source.unavailable=true;await page.reload();
  await expect(page.getByRole('region',{name:'Roaming services recorded'})).toContainText('Unavailable');
  await expect(page.locator('.country-panels tbody tr')).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Retry roaming data'})).toBeVisible();
  source.unavailable=false;await page.getByRole('button',{name:'Retry roaming data'}).click();
  await expect(page.getByRole('region',{name:'Roaming services recorded'})).toBeVisible();
 });
