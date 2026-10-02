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
      items: incident ? [incident] : [], total: incident ? 1 : 0, page: 0, size: 100,
    } }));
    await page.route('**/api/incidents/*', route => route.fulfill({ json: incident }));
    await page.route('**/api/incidents/*/detections?**', route => route.fulfill({ json: {
      items: detections, total: detections.length, page: 0, size: 100,
    } }));
    if (!incident) {
      await page.goto(`/services/${feature.scopeId}`);
      await expect(page.locator('.episode-card')).toHaveCount(0);
      await expect(page.getByText('No incident episodes overlap this time range.')).toBeVisible();
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
          await expect(article.locator(`[data-kpi="${kpi.name}"] td`).first())
            .toHaveText(kpi.observed === null ? 'Unavailable' : String(kpi.observed));
        }
      }
      if (latest!.phase === 'UNKNOWN') await expect(page.locator('[data-detection-id]').last()).toContainText('does not prove recovery');
      await expect(page.locator('app-incident-detail')).toContainText('Workflow state: OPEN');
      if (latest!.technicalState !== 'RECOVERED') {
        incident.status = 'INVESTIGATING';
        incident.assigneeId = 'g3-review';
        await page.reload();
        await page.getByLabel('Resolution note', { exact: true }).fill('This note cannot substitute for recovery');
        await expect(page.getByRole('button', { name: 'Resolve incident', exact: true })).toBeDisabled();
      }
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(errors).toEqual([]);
    await page.screenshot({ path: info.outputPath(`${trajectory.id}-${width}.png`), fullPage: true });
  });
}
