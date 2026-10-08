import { test, expect } from '@playwright/test';
import services from '../../../src/fixtures/services.json';
import { voiceWindows, voiceIncidents } from '../../../src/fixtures/voice';

test.describe('Voice investigation', () => {
  test.skip(!!process.env.E2E_REAL_LOGIN, 'Uses controlled API responses; real login runs separately');
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/incidents/stream', route => route.fulfill({ contentType: 'text/event-stream', body: ': controlled UI fixture\n\n' }));
    await page.route('**/api/auth/me', route => route.fulfill({ json: { analystId: 'voice-test', displayName: 'Voice tester', roles: ['ANALYST'], expiresAt: new Date(Date.now() + 600000).toISOString() } }));
    await page.route('**/api/auth/csrf', route => route.fulfill({ json: { token: 'test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } }));
    await page.route('**/api/geography/cities', route => route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } }));
    await page.route('**/api/operations/priority?**', route => route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } }));
    await page.route('**/api/services', route => route.fulfill({ json: services.map((item, i) => i === 0 ? { ...item, latestWindow: voiceWindows[9] } : item) }));
    await page.route('**/api/services/*/kpis?**', route => route.fulfill({ json: {
      items: voiceWindows, total: 10, page: 0, size: 100, observedAt: '2026-09-15T10:10:00Z',
    } }));
    await page.route('**/api/incidents?**', route => route.fulfill({ json: { items: [voiceIncidents[0], { ...voiceIncidents[0], version: 1, technicalState: 'ONGOING' }], total: 2, page: 0, size: 100 } }));
    await page.route('**/api/incidents/*', route => route.fulfill({ json: voiceIncidents[0] }));
    await page.route('**/api/analysts?**', route => route.fulfill({ json: [] }));
    await page.route('**/api/incidents/*/timeline?**', route => route.fulfill({ json: {
      items: [], total: 0, page: 0, size: 100,
    } }));
    await page.route('**/api/incidents/*/detections?**', route => route.fulfill({ json: {
      items: [voiceIncidents[0].latestDetection], total: 1, page: 0, size: 100,
    } }));
  });
  test('overview opens paginated history with gaps and one recovered/open episode', async ({ page }, info) => {
    await page.goto('/dashboard');
    await page.locator('details.source-inventory > summary').click();
    await page.locator('a[href="/services/VOLTE-MD-CENTRAL"]').first().click();
    await expect(page.getByRole('heading', { name: 'VoLTE setup assurance', exact: true })).toBeVisible();
    await page.locator('.exact-values > summary').click();
    await expect(page.getByText('8,000 recorded attempts', { exact: false }).last()).toBeVisible();
    await expect(page.locator('.episode-card')).toHaveCount(1);
    await expect(page.locator('.episode-card')).toContainText('RECOVERED');
    await expect(page.locator('.episode-card')).toContainText('Awaiting analyst resolution');
    await expect(page.locator('.episode-info span[title="OPEN · Awaiting analyst resolution"]')).toBeVisible();
    expect((await page.locator('.service-hero .actual-line').getAttribute('d'))?.match(/M/g)).toHaveLength(3);
    await expect(page.locator('.incident-band')).toHaveCount(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath('voice-laptop.png'), fullPage: true });

    await expect(page.locator('app-kpi-chart tbody tr')).toHaveCount(10);
    await expect(page.locator('app-kpi-chart tbody tr').nth(8)).toContainText('Unavailable');
    await page.getByText('View incident evidence', { exact: true }).click();
    await expect(page.locator('.episode-evidence').getByText(/Probable IMS capacity pressure/)).toBeVisible();
    await page.keyboard.press('Escape');
    await page.getByRole('link', { name: 'Open incident detail' }).click();
    await expect(page.getByRole('heading', { name: 'Incident investigation' })).toBeVisible();
    await expect(page.locator('[data-detection-id]')).toHaveCount(1);
    await page.getByText('Cause hypothesis & recommended checks', { exact: true }).click();
    await expect(page.getByRole('region', { name: 'Cause hypothesis' })).toContainText('Probable IMS capacity pressure');
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
    await expect(page.locator('.service-hero .actual-line')).toHaveCount(0);
  });
  test('empty history and mobile layout remain readable', async ({ page }, info) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.route('**/api/services/*/kpis?**', route => route.fulfill({ json: { items: [], total: 0, page: 0, size: 100, observedAt: '2026-09-15T10:10:00Z' } }));
    await page.route('**/api/incidents?**', route => route.fulfill({ json: { items: [], total: 0, page: 0, size: 100 } }));
    await page.goto('/services/VOLTE-MD-CENTRAL');
    await expect(page.locator('.service-hero').getByText('No KPI history in this time range.', { exact: true })).toBeVisible();
    await expect(page.getByText('No incident episodes on this page.')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: info.outputPath('voice-mobile-empty.png'), fullPage: true });
  });
});
