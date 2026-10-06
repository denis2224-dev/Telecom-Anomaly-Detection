import { test, expect } from '@playwright/test';
import suite from '../../../../../contracts/fixtures/detections/service-explanation-cases.json';
import services from '../../../src/fixtures/services.json';
import { voiceIncidents } from '../../../src/fixtures/voice';

// Controlled canonical processor trajectories. These screenshots are UI evidence, not live telemetry.
test.skip(!!process.env.E2E_REAL_LOGIN, 'Uses controlled API responses; real login runs separately');
for (const width of [1366, 390]) for (const id of ['volte-normal', 'volte-fault', 'volte-recovered', 'volte-missing-source', 'sms-normal', 'sms-fault', 'sms-recovered', 'sms-missing-source']) {
  test(`Assurance ${id} at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 });
    await page.route('**/api/auth/me', route => route.fulfill({ json: { analystId: 'controlled-assurance', displayName: 'Controlled UI review', roles: ['ANALYST'], expiresAt: new Date(Date.now() + 600000).toISOString() } }));
    await page.route('**/api/auth/csrf', route => route.fulfill({ json: { token: 'controlled-test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } }));
    await page.route('**/api/incidents/stream', route => route.fulfill({ contentType: 'text/event-stream', body: ': controlled UI fixture\n\n' }));
    const trajectory = suite.cases.find(item => item.id === id)!;
    const feature = trajectory.windows.at(-1)!.feature, latest = trajectory.detections.at(-1);
    const summary = { ...services.find(item => item.scope.scopeId === feature.scopeId)!, latestWindow: feature, observedAt: feature.windowEnd, freshness: feature.quality === 'COMPLETE' ? 'FRESH' : 'MISSING' };
    const incident = latest ? { ...voiceIncidents[0], scopeId: feature.scopeId, service: trajectory.service, episodeId: latest.episodeId, firstObservedAt: latest.firstObservedAt, lastObservedAt: latest.windowEnd, technicalState: latest.technicalState, severity: latest.severity, latestSequence: latest.sequence, latestDetection: latest } : null;
    summary.openIncidents = incident ? 1 : 0;
    const requests: { from: string; to: string }[] = [];
    await page.route('**/api/services', route => route.fulfill({ json: [summary] }));
    await page.route('**/api/services/*/kpis?**', route => {
      const query = new URL(route.request().url()).searchParams;
      const from = query.get('from')!, to = query.get('to')!; requests.push({ from, to });
      const items = trajectory.windows.map(item => item.feature).filter(item => Date.parse(item.windowStart) >= Date.parse(from) && Date.parse(item.windowStart) < Date.parse(to));
      return route.fulfill({ json: { items, total: items.length, page: 0, size: 100, observedAt: feature.windowEnd } });
    });
    await page.route('**/api/incidents?**', route => route.fulfill({ json: { items: incident ? [incident] : [], total: incident ? 1 : 0, page: 0, size: 100 } }));
    await page.route('**/api/incidents/*/detections?**', route => route.fulfill({ json: { items: trajectory.detections, total: trajectory.detections.length, page: 0, size: 100 } }));
    await page.goto('/dashboard');
    await expect(page.locator('.service-assurance-card')).toHaveCount(1);
    await page.screenshot({ path: info.outputPath(`controlled-${id}-overview-${width}.png`), fullPage: true });
    await page.locator('details.source-inventory > summary').click();
    await page.locator('.service-assurance-card a').click();
    await expect(page.locator('app-kpi-cards [data-kpi-card]')).toHaveCount(trajectory.service === 'VOLTE' ? 8 : 5);
    await expect(page.locator('.service-hero [data-chart]')).toHaveCount(1);
    await expect(page.locator('.service-hero [data-chart]')).toHaveAttribute('data-chart', trajectory.service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs');
    await expect(page.locator('.service-hero .chart-legend')).toHaveCount(1);
    if (trajectory.service === 'SMS') await expect(page.locator('[data-node-id="TRANSPORT-A"]')).toContainText('NO CURRENT MEASUREMENT');
    if (latest) {
      await page.getByText('View incident evidence', { exact: true }).click();
      await expect(page.locator('.incident-story')).toContainText(latest.probableCause);
      await expect(page.locator('.incident-story')).not.toContainText('Supporting evidence');
      await expect(page.locator('.incident-story')).not.toContainText('Unique subscribers:');
      await expect(page.locator(`.incident-story[data-phase="${latest.phase}"]`)).toBeVisible();
      await page.keyboard.press('Escape');
      if (latest.phase === 'RECOVERY') await expect(page.locator('svg [data-phase="RECOVERY"]').first()).toBeVisible();
      if (latest.phase === 'UNKNOWN') await expect(page.locator('svg [data-phase="UNKNOWN"]').first()).toBeVisible();
    }
    await page.getByText('Range options', { exact: true }).click();
    const beforeRange = requests.length;
    await page.getByRole('button', { name: '24h', exact: true }).click();
    await expect.poll(() => requests.length).toBe(beforeRange + 1);
    for (const request of requests) expect(Date.parse(request.to) - Date.parse(request.from)).toBeLessThanOrEqual(86400000);
    await expect(page.locator('.kpi-summary')).toBeVisible();
    expect(await page.evaluate(() => [...document.querySelectorAll('body *')].filter(element => {
      const rect = element.getBoundingClientRect(); return rect.right > innerWidth + 1 && getComputedStyle(element).overflowX !== 'auto' && !element.closest('.chart-scroll');
    }).map(element => ({ tag: element.tagName, class: element.className, width: element.getBoundingClientRect().width })))).toEqual([]);
    await page.screenshot({ path: info.outputPath(`controlled-${id}-detail-${width}.png`), fullPage: true });
  });
}
