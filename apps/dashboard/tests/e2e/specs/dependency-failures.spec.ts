import { test, expect } from '@playwright/test';
import { controlledApi, streamEvent } from '../helpers/controlled-api';

test.skip(!!process.env.E2E_REAL_LOGIN, 'Controlled failures are separate from live drills');

test('identity discovery unavailable: retry access without claiming telecom degradation', async ({ page }) => {
  const state = await controlledApi(page);
  state.authStatus = 503;
  await page.goto('/login');
  await expect(page.getByRole('alert')).toContainText('session could not be verified');
  await expect(page.locator('.service-assurance-card')).toHaveCount(0);
  expect(state.requests.some(path => path === 'GET /api/services')).toBe(false);
  state.authStatus = 200;
  await page.getByRole('button', { name: 'Retry connection' }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.locator('.identity')).toContainText('Controlled analyst');
});

test('overview API interruption has a retry and recovers', async ({ page }) => {
  const state = await controlledApi(page);
  state.servicesStatus = 503;
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Services could not be loaded' })).toBeVisible();
  await expect(page.locator('.identity')).toBeVisible();
  state.servicesStatus = 200;
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Network overview' })).toBeVisible();
});

test('KPI API interruption retries the same selected range', async ({ page }) => {
  const state = await controlledApi(page);
  state.historyStatus = 503;
  await page.goto(`/services/${state.summary.scope.scopeId}`);
  await expect(page.getByRole('heading', { name: 'Evidence unavailable' })).toBeVisible();
  state.historyStatus = 200;
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.getByLabel('To (UTC, exclusive)', { exact: true }))
    .toHaveValue('2026-09-15T10:10');
  await expect(page.locator('.service-hero svg[role="img"]')).toBeVisible();
});

for (const screen of ['overview', 'service', 'incident'] as const) {
  test(`${screen}: interrupted stream stays visible through REST refresh and clears on reconnect`, async ({ page }) => {
    const state = await controlledApi(page);
    const url = screen === 'overview' ? '/dashboard'
      : screen === 'service' ? `/services/${state.summary.scope.scopeId}`
      : `/incidents/${state.incident.id}`;
    await page.goto(url);
    await expect.poll(() => page.evaluate(() => (window as any).__sources?.length ?? 0))
      .toBeGreaterThan(0);
    await streamEvent(page, 'error');
    const banner = page.locator(screen === 'service' ? '.service-alerts .service-connection' : 'app-status-banner');
    await expect(banner).toContainText('Live connection interrupted');
    await banner.getByRole('button', { name: screen === 'service' ? 'Retry' : 'Refresh evidence', exact: true }).click();
    // A successful REST read is useful but cannot prove the SSE connection recovered.
    await expect(banner).toContainText('Live connection interrupted');
    await streamEvent(page, 'open');
    await expect(banner).toHaveCount(0);
    await expect(page.locator('.identity')).toBeVisible();
  });
}

for (const failure of ['503', 'network', '409'] as const) {
  test(`failed comment (${failure}) keeps draft and never says saved`, async ({ page }) => {
    const state = await controlledApi(page);
    state.abortWrite = failure === 'network';
    state.writeStatus = failure === '409' ? 409 : 503;
    await page.goto(`/incidents/${state.incident.id}`);
    await page.getByRole('button', { name: 'Details & workflow' }).click();
    const workflow = page.locator('app-incident-actions');
    await page.getByLabel('Investigation comment').fill('Keep this unsaved investigation note.');
    await page.getByRole('button', { name: 'Add comment' }).click();
    await expect(workflow.getByRole('alert')).toContainText(
      failure === '409' ? 'incident changed' : 'could not be reached');
    await expect(page.getByLabel('Investigation comment'))
      .toHaveValue('Keep this unsaved investigation note.');
    await expect(workflow).not.toContainText('Comment saved.');
    await expect(page.locator('.toast:not(.toast-error)')).toHaveCount(0);
    expect(state.writes).toBe(1);
    await page.getByRole('button', { name: 'Reload incident' }).click();
    await expect(workflow.getByRole('alert')).toContainText('Incident refreshed');
    await expect(page.getByLabel('Investigation comment'))
      .toHaveValue('Keep this unsaved investigation note.');
  });
}

test('failed resolution retains note and INVESTIGATING workflow', async ({ page }) => {
  const state = await controlledApi(page);
  state.incident.technicalState = 'RECOVERED';
  state.incident.latestDetection.technicalState = 'RECOVERED';
  state.incident.latestDetection.phase = 'RECOVERY';
  await page.goto(`/incidents/${state.incident.id}`);
  await page.getByRole('button', { name: 'Details & workflow' }).click();
  await page.getByLabel('Resolution note', { exact: true }).fill('Recovery checked; resolution not saved.');
  await page.getByRole('button', { name: 'Resolve incident' }).click();
  const workflow = page.locator('app-incident-actions');
  await expect(workflow.getByRole('alert')).toContainText('could not be reached');
  await expect(workflow.locator('[data-state="INVESTIGATING"]')).toBeVisible();
  await expect(page.getByLabel('Resolution note', { exact: true }))
    .toHaveValue('Recovery checked; resolution not saved.');
  await expect(page.locator('.toast:not(.toast-error)')).toHaveCount(0);
});

for (const status of ['UNAVAILABLE', 'TIMEOUT'] as const) {
  test(`ML ${status}: measured evidence and rule severity remain usable`, async ({ page }) => {
    const state = await controlledApi(page);
    state.incident.latestDetection.mlStatus = status;
    // Even a stale supplied rank must not be presented as a usable result.
    state.incident.latestDetection.anomalyRank = 0.95;
    await page.goto(`/incidents/${state.incident.id}`);
    await page.getByText('Cause hypothesis & recommended checks', { exact: true }).click();
    const cause = page.getByRole('region', { name: 'Cause hypothesis' });
    await expect(cause).toContainText('Model anomaly rank: Unavailable');
    await expect(cause.locator('[data-topic="ml-unavailable"]')).toContainText('rule-based severity');
    await expect(page.locator('[data-kpi="cssrPct"] td').first()).not.toHaveText('Unavailable');
    await expect(page.locator('.incident-summary-bar')).toContainText(state.incident.severity);
    await expect(page.locator('.incident-summary-bar')).toContainText('ONGOING');
  });
}

test('telemetry gap is UNKNOWN and null measurements are unavailable', async ({ page }) => {
  const state = await controlledApi(page);
  state.incident.technicalState = 'UNKNOWN';
  Object.assign(state.incident.latestDetection, { phase: 'UNKNOWN', technicalState: 'UNKNOWN' });
  state.incident.latestDetection.kpis = state.incident.latestDetection.kpis.map(kpi => ({
    ...kpi, observed: null, numerator: null, denominator: null,
  }));
  await page.goto(`/incidents/${state.incident.id}`);
  await expect(page.locator('.incident-summary-bar')).toContainText('UNKNOWN');
  await expect(page.locator('[data-detection-id]')).toContainText('does not prove recovery');
  await expect(page.locator('[data-kpi="cssrPct"] td').first()).toHaveText('Unavailable');
});
