import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { controlledApi, streamEvent } from '../helpers/controlled-api';
import type { Incident, PriorityPage } from '../../../src/app/core/api/telecom-client';

test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled reproductions; authenticated replay has its own configuration.');

async function capture(page: Page, info: TestInfo, defect: string, state: unknown) {
  const stage = process.env.REHEARSAL_CAPTURE_BEFORE === '1' ? 'before' : 'after';
  await page.screenshot({ path: info.outputPath(`${defect}-${stage}.png`), fullPage: true });
  writeFileSync(info.outputPath(`${defect}-${stage}.json`), JSON.stringify({
    mode: 'Controlled API reproduction, not live acceptance', defect, stage,
    capturedAtUTC: new Date().toISOString(), state,
  }, null, 2));
}

for (const width of [1366, 768, 390]) {
  test(`evidence page shrink reconciles recovery and preserves the open workflow at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 });
    const state = await controlledApi(page);
    state.incident.version = 7;
    const records = Array.from({ length: 21 }, (_, index) => ({
      ...structuredClone(state.incident.latestDetection), detectionId: String(index).padStart(64, '0'), sequence: index + 1,
    }));
    let total = 21;
    const requests: number[] = [];
    await page.route('**/api/incidents/*/detections?**', route => {
      const current = Number(new URL(route.request().url()).searchParams.get('page'));
      requests.push(current);
      return route.fulfill({ json: { page: current, size: 20, total,
        items: records.slice(0, total).slice(current * 20, (current + 1) * 20) } });
    });
    await page.goto(`/incidents/${state.incident.id}`);
    await expect(page.locator('[data-detection-id]')).toHaveCount(20);
    await page.getByRole('button', { name: 'Next evidence', exact: true }).click();
    await expect(page.locator('[data-detection-id]')).toHaveCount(1);
    await page.getByRole('button', { name: 'Details & workflow', exact: true }).click();
    const workflow = page.getByRole('dialog', { name: 'Details & workflow', exact: true });
    const comment = workflow.getByLabel('Investigation comment', { exact: true });
    await comment.fill('Keep this investigation draft during reconnect.');
    await comment.focus();
    total = 20;
    state.incident = { ...state.incident, version: 8, technicalState: 'RECOVERED',
      latestDetection: { ...state.incident.latestDetection, phase: 'RECOVERY', technicalState: 'RECOVERED', evidence: [] } };
    const before = requests.length;
    await streamEvent(page, 'error');
    await streamEvent(page, 'open');
    await expect.poll(() => requests.length).toBeGreaterThan(before);
    await expect(page.locator('.refresh-status .spinner')).toHaveCount(0);
    // Capture the settled UI even when the regression assertion fails.
    await page.waitForTimeout(300);
    await capture(page, info, 'evidence-page-shrink', { incidentId: state.incident.id,
      beforeVersion: 7, authoritativeVersion: 8, total, requests,
      renderedSummary: await page.locator('.incident-summary-bar').innerText(),
      errors: await page.locator('[role=status]').allTextContents() });
    await expect(page.locator('.incident-summary-bar')).toContainText('RECOVERED');
    await expect(page.locator('.incident-summary-bar')).toContainText('Awaiting analyst resolution');
    await expect(workflow).toBeVisible();
    await expect(comment).toHaveValue('Keep this investigation draft during reconnect.');
    await expect(comment).toBeFocused();
    await workflow.getByRole('button', { name: 'Close details and workflow' }).click();
    await expect(page.getByRole('navigation', { name: 'Evidence pages' })).toContainText('1 / 1');
    await expect(page.locator('[data-detection-id]')).toHaveCount(20);
    expect(requests.at(-1)).toBe(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  });
}

test('priority queue returns to a populated page after its last page disappears', async ({ page }, info) => {
  const state = await controlledApi(page);
  let incidents: Incident[] = Array.from({ length: 21 }, (_, index) => ({
    ...structuredClone(state.incident), id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    episodeId: String(index).padStart(64, '0'), location: {
      cityId: null, nullReason: 'UNALLOCATED', measuredScopeId: state.incident.scopeId,
      catalogueVersion: null, topologyVersion: null, containmentPath: [], dependencyNodeIds: [],
    },
  }));
  await page.route('**/api/incidents/*', route => {
    const incident = incidents.find(item => item.id === new URL(route.request().url()).pathname.split('/')[3]);
    return incident ? route.fulfill({ json: incident }) : route.fallback();
  });
  await page.route('**/api/operations/priority?**', route => {
    const number = Number(new URL(route.request().url()).searchParams.get('page'));
    const result: PriorityPage = { generatedAt: new Date().toISOString(), policyVersion: 'geographic-priority-v1',
      policyStatus: 'ACTIVE', page: number, size: 20, hasNext: (number + 1) * 20 < incidents.length,
      items: incidents.slice(number * 20, (number + 1) * 20).map(item => ({
        incidentId: item.id, cityId: null, cityNullReason: 'UNALLOCATED', service: item.service,
        scopeId: item.scopeId, technicalState: item.technicalState, analystStatus: item.status,
        severity: item.severity, severityHistorical: false, freshness: 'FRESH', priorityBand: 'FRESH_ONGOING',
        comparableImpact: null, impactUnit: null, firstObservedAt: item.firstObservedAt,
        detectedAt: item.detectedAt, latestWindowEnd: item.latestDetection.windowEnd,
      })) };
    return route.fulfill({ json: result });
  });
  await page.goto('/dashboard');
  await page.getByRole('button', { name: /^Incidents \(/ }).click();
  const queue = page.getByRole('dialog', { name: /^Incident queue/ });
  await expect(queue.locator('.queue-item')).toHaveCount(20);
  await queue.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(queue.locator('.queue-item')).toHaveCount(1);
  const severity = queue.getByLabel(/^Severity/);
  await severity.focus();
  incidents = incidents.slice(0, 20);
  await streamEvent(page, 'open');
  await expect(queue.locator('.queue-refresh').filter({ hasText: 'Refreshing incidents' })).toHaveCount(0);
  await page.waitForTimeout(300);
  await capture(page, info, 'priority-page-shrink', { incidentVersion: state.incident.version,
    incidentId: state.incident.id, authoritativeRows: incidents.length,
    renderedRows: await queue.locator('.queue-item').count(), pagination: await queue.locator('.pagination').innerText() });
  await expect(queue.locator('.queue-item')).toHaveCount(20);
  await expect(queue.locator('.pagination')).toContainText('Page 1');
  await expect(severity).toBeFocused();
  await expect(queue.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
});

test('missing evidence remains undetermined without implying power loss', async ({ page }, info) => {
  const state = await controlledApi(page);
  state.incident.latestDetection.probableCause = 'Confirmed power loss';
  state.incident.latestDetection.evidence = [{ code: 'PING_RESULT', summary: 'Ping failed', nodeId: null, sourceEventIds: [] }];
  await page.goto(`/incidents/${state.incident.id}`);
  await page.locator('.cause-details > summary').click();
  const cause = page.getByRole('region', { name: 'Cause hypothesis', exact: true });
  await expect(cause).toContainText('Cause undetermined');
  await expect(cause).not.toContainText('Confirmed power loss');
  await capture(page, info, 'missing-evidence', { incidentId: state.incident.id, version: state.incident.version,
    evidenceCodes: ['PING_RESULT'], renderedCause: await cause.innerText() });
});

test('TraceLink tab icons are actual image assets', async ({ page, request }) => {
  await controlledApi(page);
  await page.goto('/dashboard');
  for (const selector of ['link[rel=icon][type="image/x-icon"]', 'link[rel=icon][type="image/png"]', 'link[rel=apple-touch-icon]']) {
    const href = await page.locator(selector).getAttribute('href');
    expect(href).toBeTruthy();
    const response = await request.get(href!);
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toMatch(/^image\//);
  }
  const favicon = await request.get('/favicon.ico');
  const bytes = await favicon.body();
  expect(bytes.readUInt16LE(2)).toBe(1);
  expect(bytes.readUInt16LE(4)).toBe(4);
  await expect(page).toHaveTitle('TraceLink');
});
