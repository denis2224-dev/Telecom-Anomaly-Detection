import { test, expect, type Page } from '@playwright/test';
import { voiceIncidents } from '../../../src/fixtures/voice';
import type { components } from '../../../src/app/core/api/schema';

type Detection = components['schemas']['ServiceDetection'];
const incident = structuredClone(voiceIncidents[0]);
const history = (['OPEN', 'UNKNOWN', 'RECOVERY'] as const).map((phase, index): Detection => ({
  ...structuredClone(incident.latestDetection),
  detectionId: String(index + 1).padStart(64, '0'), sequence: index + 1, phase,
  technicalState: phase === 'RECOVERY' ? 'RECOVERED' : phase === 'UNKNOWN' ? 'UNKNOWN' : 'ONGOING',
  windowStart: `2026-09-15T10:0${index}:00Z`, windowEnd: `2026-09-15T10:0${index + 1}:00Z`,
  detectedAt: `2026-09-15T10:0${index + 1}:10Z`,
  kpis: [{ name: 'cssrPct', observed: [0, null, 99.6][index], baseline: 99.3,
    unit: 'PERCENT', numerator: [0, null, 996][index], denominator: index === 1 ? null : 1000 }],
  probableCause: `Window ${index + 1} capacity hypothesis`,
  recommendedChecks: [`Inspect source window ${index + 1}`],
  evidence: [{ code: 'WINDOW_SOURCE', summary: `Observed evidence for window ${index + 1}`,
    nodeId: 'IMS-A', sourceEventIds: [`00000000-0000-4000-8000-00000000000${index + 1}`] }],
}));

async function mockSessionAndIncident(page: Page) {
  await page.route('**/api/auth/me', route => route.fulfill({ json: {
    analystId: 'day8-test', displayName: 'Day 8 tester', roles: ['ANALYST'],
    expiresAt: new Date(Date.now() + 600000).toISOString(),
  } }));
  await page.route('**/api/incidents/*/timeline?**', route => route.fulfill({ json: { items: [], total: 0, page: 0, size: 100 } }));
  await page.route('**/api/analysts?**', route => route.fulfill({ json: [] }));
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: {
    token: 'test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
  } }));
  await page.route('**/api/incidents/*', route => route.fulfill({ json: {
    ...incident, latestDetection: history[2], latestSequence: 3,
  } }));
}

test.describe('Day 8 controlled historical evidence', () => {
  test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled API responses do not verify live ingestion or login');

  for (const width of [1366, 390]) {
    test(`shows each historical window and source at ${width}px without page overflow`, async ({ page }, info) => {
      await page.setViewportSize({ width, height: 844 });
      await mockSessionAndIncident(page);
      const requested: number[] = [];
      await page.route('**/api/incidents/*/detections?**', route => {
        const pageNumber = Number(new URL(route.request().url()).searchParams.get('page'));
        requested.push(pageNumber);
        return route.fulfill({ json: { items: history, total: 3, page: pageNumber, size: 20 } });
      });
      await page.goto(`/incidents/${incident.id}`);
      const updates = page.locator('[data-detection-id]');
      await expect(updates).toHaveCount(3);
      expect(requested).toEqual([0]);
      await expect(updates.locator('h3')).toHaveText(['Update 1 · OPEN', 'Update 2 · UNKNOWN', 'Update 3 · RECOVERY']);
      await expect(updates.nth(0).locator('[data-kpi] td').first()).toHaveText('0');
      await expect(updates.nth(1).locator('[data-kpi] td').first()).toHaveText('Unavailable');
      await expect(updates.nth(2).locator('[data-kpi] td').first()).toHaveText('99.6');
      await expect(updates.nth(1)).toContainText('does not prove recovery');
      await expect(page.locator('.incident-summary-bar')).toContainText('RECOVERED');
      await expect(page.locator('.incident-summary-bar')).toContainText('Latest KPI deviation');
      await expect(page.getByRole('link', { name: 'Back to service' })).toHaveAttribute('href', `/services/${incident.scopeId}`);
      for (let index = 0; index < 3; index++) {
        const update = updates.nth(index);
        await expect(update).toContainText(`Source scope: ${incident.scopeId}`);
        await expect(update.locator('[data-kpi] td').nth(1)).toHaveText('99.3');
        await expect(update.locator('[data-kpi] td').nth(2)).toHaveText('PERCENT');
        await update.getByText('Cause hypothesis & recommended checks', { exact: true }).click();
        await expect(update.getByRole('region', { name: 'Cause hypothesis' })).toContainText(`Window ${index + 1} capacity hypothesis`);
        await expect(update).toContainText(`Inspect source window ${index + 1}`);
        await expect(update).toContainText('Unique customers: Unavailable');
        await update.getByText('Source evidence', { exact: true }).click();
        await expect(update.getByText(history[index].evidence[0].sourceEventIds[0], { exact: true })).toBeVisible();
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      if (width === 390) {
        const table = updates.first().getByRole('region', { name: 'KPI evidence for update 1' });
        expect(await table.evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true);
        await table.evaluate(element => { element.scrollLeft = element.scrollWidth; });
        expect(await table.evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
        await table.evaluate(element => { element.scrollLeft = 0; });
      }
      await page.screenshot({ path: info.outputPath(`day08-evidence-${width}.png`), fullPage: true });
    });
  }

  test('clears a failed evidence page and retries from the first page', async ({ page }) => {
    await mockSessionAndIncident(page);
    let failSecondPage = true;
    const firstPage = Array.from({ length: 20 }, (_, index) => ({
      ...history[0], detectionId: `page-one-${index}`, sequence: index + 1,
    }));
    await page.route('**/api/incidents/*/detections?**', route => {
      const pageNumber = Number(new URL(route.request().url()).searchParams.get('page'));
      if (pageNumber === 1 && failSecondPage) return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } });
      return route.fulfill({ json: { items: pageNumber === 0 ? firstPage : [history[2]], total: 21, page: pageNumber, size: 20 } });
    });
    await page.goto(`/incidents/${incident.id}`);
    await expect(page.locator('[data-detection-id]')).toHaveCount(20);
    await page.getByRole('button', { name: 'Next evidence' }).click();
    await expect(page.getByRole('alert')).toContainText('could not be reached');
    await expect(page.locator('[data-detection-id]')).toHaveCount(0);
    failSecondPage = false;
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.locator('[data-detection-id]')).toHaveCount(20);
  });
});
