import { test, expect } from '@playwright/test';
import type { GeographyCatalogue, ServiceSummary } from '../../../src/app/core/api/telecom-client';

// Opt-in, read-only G2 audit: no route interception, fixtures, simulator commands, or saved session secrets.
test('authenticated G2 geography catalogue and selected-city KPI history reach the LIVE dashboard', async ({ page, context }, info) => {
  test.skip(process.env.G2_GEOGRAPHY_LIVE_AUDIT !== '1', 'Requires a running integrated G2 stack and a local analyst account');
  const username = process.env.G2_USERNAME, password = process.env.G2_PASSWORD;
  if (!username || !password) throw new Error('Set G2_USERNAME and G2_PASSWORD for the read-only authenticated audit.');
  const requests: string[] = [];
  const responses: { path: string; status: number }[] = [];
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/geography/') || /\/api\/services\/[^/]+\/kpis$/.test(url.pathname)) requests.push(url.pathname);
  });
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith('/api/geography/') || /\/api\/services\/[^/]+\/kpis$/.test(path)) responses.push({ path, status: response.status() });
  });
  try {
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect(page).toHaveURL(/\/dashboard$/);
  } catch { throw new Error('Authenticated G2 login failed; credential and session details omitted.'); }
  const catalogueResponse = await context.request.get('/api/geography/cities');
  expect(catalogueResponse.status()).toBe(200);
  const catalogue = await catalogueResponse.json() as GeographyCatalogue;
  expect(catalogue.cities).toHaveLength(10);
  const scopes = catalogue.cities.flatMap(city => city.services.map(state => state.scopeId));
  expect(new Set(scopes).size).toBe(20);
  const servicesResponse = await context.request.get('/api/services');
  expect(servicesResponse.status()).toBe(200);
  const services = await servicesResponse.json() as ServiceSummary[];
  expect(scopes.every(scope => services.some(item => item.scope.scopeId === scope)), 'Every authoritative city scope must be navigable through the existing service inventory').toBe(true);
  await expect.poll(() => requests.includes('/api/geography/cities')).toBe(true);
  await expect(page.locator('.map-table')).not.toContainText('Mapping pending');
  for (const city of catalogue.cities) {
    await page.getByLabel('Region', { exact: true }).fill(city.displayName);
    const expected = ['VOLTE', 'SMS'].map(service => city.services.find(state => state.service === service)!.scopeId);
    await expect(page.locator('.chart-scope')).toHaveText(expected);
    for (const scope of expected) await expect.poll(() => requests.includes(`/api/services/${scope}/kpis`)).toBe(true);
    await expect.poll(() => requests.filter(path => path === `/api/geography/cities/${city.cityId}/kpis`).length).toBeGreaterThanOrEqual(2);
    await expect.poll(() => responses.filter(item => item.path === `/api/geography/cities/${city.cityId}/kpis` && item.status === 200).length).toBeGreaterThanOrEqual(2);
    await expect(page.locator('app-city-evidence [role=alert]')).toHaveCount(0);
  }
  expect(requests.some(path => path.includes('fixture-'))).toBe(false);
  await info.attach('g2-live-geography-requests', { body: Buffer.from(JSON.stringify({
    testedCommit: process.env.G2_TESTED_COMMIT ?? 'unrecorded', cities: catalogue.cities.length,
    authoritativeScopes: scopes.length, requests, responses,
  }, null, 2)), contentType: 'application/json' });
});
