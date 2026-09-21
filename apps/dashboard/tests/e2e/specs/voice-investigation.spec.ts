import { test, expect } from '@playwright/test';
import services from '../../../src/fixtures/services.json';
import { voiceWindows, voiceIncidents } from '../../../src/fixtures/voice';

test.describe('Day 5 voice investigation', () => {
  test.skip(!!process.env.E2E_REAL_LOGIN, 'Uses controlled API responses; real login runs separately');
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/auth/me', route => route.fulfill({ json: { analystId: 'voice-test', displayName: 'Voice tester', roles: ['ANALYST'], expiresAt: new Date(Date.now() + 600000).toISOString() } }));
    await page.route('**/api/auth/csrf', route => route.fulfill({ json: { token: 'test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } }));
    await page.route('**/api/services', route => route.fulfill({ json: services.map((item, i) => i === 0 ? { ...item, latestWindow: voiceWindows[9] } : item) }));
    await page.route('**/api/services/*/kpis?**', route => {
      const page = Number(new URL(route.request().url()).searchParams.get('page'));
      return route.fulfill({ json: { items: voiceWindows.slice(page * 5, (page + 1) * 5), total: 10, page, size: 5, observedAt: '2026-09-15T10:10:00Z' } });
    });
    await page.route('**/api/incidents?**', route => route.fulfill({ json: { items: [voiceIncidents[0], { ...voiceIncidents[0], version: 1, technicalState: 'ONGOING' }], total: 2, page: 0, size: 100 } }));
  });
  test('overview opens paginated history with gaps and one recovered/open episode', async ({ page }, info) => {
    await page.goto('/dashboard');
    await page.locator('a[href="/services/VOLTE-MD-CENTRAL"]').first().click();
    await expect(page.getByRole('heading', { name: 'Voice call setup', exact: true })).toBeVisible();
    await expect(page.getByText('8,000 recorded attempts', { exact: false })).toBeVisible();
    await expect(page.locator('.episode-card')).toHaveCount(1);
    await expect(page.locator('.episode-card')).toContainText('RECOVERED');
    await expect(page.locator('.episode-card')).toContainText('OPEN');
    expect((await page.locator('.actual-line').getAttribute('d'))?.match(/M/g)).toHaveLength(3);
    await expect(page.locator('.incident-band')).toHaveCount(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath('voice-laptop.png'), fullPage: true });
    await page.getByText('Show exact values and attempt counts', { exact: false }).click();
    await expect(page.locator('tbody tr')).toHaveCount(10);
    await expect(page.locator('tbody tr').nth(8)).toContainText('Unavailable');
    await page.getByText('View incident evidence', { exact: true }).click();
    await expect(page.getByText(/Probable IMS capacity pressure/)).toBeVisible();
  });
  test('validates time range and sends UTC boundaries to the backend', async ({ page }) => {
    await page.goto('/services/VOLTE-MD-CENTRAL');
    await page.getByLabel('From (UTC)', { exact: true }).fill('2026-09-15T10:00');
    await page.getByLabel('To (UTC, exclusive)').fill('2026-09-15T09:00');
    await page.getByRole('button', { name: 'Apply time range' }).click();
    await expect(page.getByRole('alert')).toContainText('end after the start');
    await page.getByLabel('To (UTC, exclusive)').fill('2026-09-15T10:10');
    const request = page.waitForRequest(request => request.url().includes('/kpis?'));
    await page.getByRole('button', { name: 'Apply time range' }).click();
    const query = new URL((await request).url()).searchParams;
    expect(query.get('from')).toBe('2026-09-15T10:00:00.000Z');
    expect(query.get('to')).toBe('2026-09-15T10:10:00.000Z');
  });
  test('history failure shows an error instead of synthetic evidence', async ({ page }) => {
    await page.route('**/api/services/*/kpis?**', route => route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } }));
    await page.goto('/services/VOLTE-MD-CENTRAL');
    await expect(page.getByRole('alert')).toContainText('could not be reached');
    await expect(page.locator('.actual-line')).toHaveCount(0);
  });
  test('empty history and mobile layout remain readable', async ({ page }, info) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.route('**/api/services/*/kpis?**', route => route.fulfill({ json: { items: [], total: 0, page: 0, size: 100, observedAt: '2026-09-15T10:10:00Z' } }));
    await page.route('**/api/incidents?**', route => route.fulfill({ json: { items: [], total: 0, page: 0, size: 100 } }));
    await page.goto('/services/VOLTE-MD-CENTRAL');
    await expect(page.getByText('No voice KPI history in this time range.')).toBeVisible();
    await expect(page.getByText('No incident episodes overlap this time range.')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath('voice-mobile-empty.png'), fullPage: true });
  });
});
