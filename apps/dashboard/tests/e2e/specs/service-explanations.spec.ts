import { test, expect } from '@playwright/test';
import suite from '../../../../../contracts/fixtures/detections/service-explanation-cases.json';
import services from '../../../src/fixtures/services.json';
import { voiceIncidents } from '../../../src/fixtures/voice';

// Controlled, schema-validated trajectories test rendering; live ingestion is reviewed separately.
for (const width of [1366, 390]) for (const trajectory of suite.cases) {
  test(`G3 explanation ${trajectory.id} at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 });
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.name));
    await page.route('**/api/auth/me', route => route.fulfill({ json: {
      analystId: 'g3-review', displayName: 'G3 reviewer', roles: ['ANALYST'],
      expiresAt: new Date(Date.now() + 600000).toISOString(),
    } }));
    await page.route('**/api/incidents/*/timeline?**', route => route.fulfill({ json: { items: [], total: 0, page: 0, size: 100 } }));
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: {
      token: 'controlled-test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
    } }));
    await page.route('**/api/analysts?**', route => route.fulfill({ json: [] }));
    const feature = trajectory.windows.at(-1)!.feature;
    const detections = trajectory.detections;
    const latest = detections.at(-1);
    const incident = latest ? {
      ...voiceIncidents[0], service: trajectory.service, scopeId: feature.scopeId,
      episodeId: latest.episodeId, firstObservedAt: latest.firstObservedAt,
      lastObservedAt: latest.windowEnd, technicalState: latest.technicalState,
      severity: latest.severity, latestSequence: latest.sequence, latestDetection: latest,
    } : null;
    await page.route('**/api/services', route => route.fulfill({ json: services.map(item =>
      item.scope.scopeId === feature.scopeId ? { ...item, latestWindow: feature } : item) }));
    await page.route('**/api/services/*/kpis?**', route => route.fulfill({ json: {
      items: trajectory.windows.map(step => step.feature), total: trajectory.windows.length,
      page: 0, size: 100, observedAt: feature.windowEnd,
    } }));
    await page.route('**/api/incidents?**', route => route.fulfill({ json: {
      items: incident ? [incident] : [], total: incident ? 1 : 0, page: 0, size: 20,
    } }));
    await page.route('**/api/incidents/*', route => route.fulfill({ json: incident }));
    await page.route('**/api/incidents/*/detections?**', route => route.fulfill({ json: {
      items: detections, total: detections.length, page: 0, size: 20,
    } }));
    if (!incident) {
      await page.goto(`/services/${feature.scopeId}`);
      await expect(page.locator('.episode-card')).toHaveCount(0);
      await expect(page.getByText('No incident episodes on this page.')).toBeVisible();
    } else {
      await page.goto(`/incidents/${incident.id}`);
      await expect(page.locator('[data-detection-id]')).toHaveCount(detections.length);
      for (const detection of detections) {
        const article = page.locator(`[data-detection-id="${detection.detectionId}"]`);
        await expect(article).toContainText(detection.probableCause);
        await expect(article).toContainText(`Cause confidence: ${detection.causeConfidence}`);
        await expect(article).toContainText(`Model anomaly rank: ${detection.anomalyRank ?? 'Unavailable'}`);
        await expect(article).toContainText('not a failure probability');
        await expect(article).toContainText('Unique customers: Unavailable');
        for (const check of detection.recommendedChecks) await expect(article).toContainText(check);
        for (const kpi of detection.kpis) {
          const unsupportedRate = (kpi.unit === 'PERCENT' || kpi.unit === 'RATIO') && kpi.denominator === 0;
          const unsupportedP95 = kpi.name === 'p95DeliveryMs'
            && !detection.kpis.some(sample => sample.name === 'deliveredMessages'
              && sample.unit === 'COUNT' && sample.observed !== null && sample.observed > 0);
          const shown = detection.phase === 'UNKNOWN' || unsupportedRate || unsupportedP95 || kpi.observed === null
            ? 'Unavailable' : String(kpi.observed);
          await expect(article.locator(`[data-kpi="${kpi.name}"] td`).first())
            .toHaveText(shown);
        }
      }
      if (latest!.phase === 'UNKNOWN') await expect(page.locator('[data-detection-id]').last()).toContainText('does not prove recovery');
      await expect(page.locator('app-incident-detail')).toContainText('Workflow state: OPEN');
      if (latest!.technicalState !== 'RECOVERED') {
        incident.status = 'INVESTIGATING';
        incident.assigneeId = 'g3-review';
        await page.reload();
        await page.getByRole('button', { name: 'Details & workflow' }).click();
        await page.getByLabel('Resolution note', { exact: true }).fill('This note cannot substitute for recovery');
        await expect(page.getByRole('button', { name: 'Resolve incident', exact: true })).toBeDisabled();
      }
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(errors).toEqual([]);
    await page.screenshot({ path: info.outputPath(`${trajectory.id}-${width}.png`), fullPage: true });
  });
}

test('captured catalogue provenance stays readable at mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const catalogueVersion = `2-geography-day2-${'a'.repeat(64)}`;
  const incident = { ...structuredClone(voiceIncidents[0]), location: {
    cityId: 'CHI', measuredScopeId: 'VOLTE-MD-CHI', catalogueVersion,
    topologyVersion: '2-geography-g1', containmentPath: ['CITY-MD-CHI', 'CELL-MD-CHI-01'],
    dependencyNodeIds: ['IMS-MD-CHI-01'], nullReason: null,
  } };
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/incidents/stream') return route.fulfill({ contentType: 'text/event-stream', body: ': controlled layout fixture\n\n' });
    const json = path === '/api/auth/me'
      ? { analystId: 'layout-review', displayName: 'Layout reviewer', roles: ['ANALYST'], expiresAt: new Date(Date.now() + 600_000).toISOString() }
      : path === '/api/auth/csrf'
        ? { token: 'controlled-test-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' }
        : path === '/api/services' ? services
          : path === '/api/analysts' ? []
            : path.endsWith('/detections') ? { items: [incident.latestDetection], total: 1, page: 0, size: 20 }
              : path.endsWith('/timeline') ? { items: [], total: 0, page: 0, size: 100 }
                : path === `/api/incidents/${incident.id}` ? incident : {};
    await route.fulfill({ json });
  });
  await page.goto(`/incidents/${incident.id}`);
  const disclosure = page.locator('.evidence-column > .evidence-disclosure');
  await disclosure.getByText('Current impact and cause', { exact: true }).click();
  const provenance = disclosure.locator('p').filter({ hasText: 'Captured catalogue:' });
  await expect(provenance).toContainText(catalogueVersion);
  expect(await provenance.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});
