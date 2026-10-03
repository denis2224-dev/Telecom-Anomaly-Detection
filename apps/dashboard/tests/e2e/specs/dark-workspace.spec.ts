import { test, expect } from '@playwright/test';
import services from '../../../src/fixtures/services.json';
import { voiceIncidents, voiceWindows } from '../../../src/fixtures/voice';

// Controlled API responses exercise the real routes and write handlers without changing live incidents.
for (const width of [1366, 768, 390]) {
  test(`dark workspace presentation and interactions at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 });
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    const actor = { analystId: 'ui-review', displayName: 'Alex Morgan', roles: ['SUPERVISOR'], expiresAt: new Date(Date.now() + 3600000).toISOString() };
    let incident = { ...voiceIncidents[0], assigneeId: null as string | null };
    let run: object | null = null;
    await page.addInitScript(() => {
      class PreviewSource { onopen: (() => void) | null = null; onerror = null; constructor() { setTimeout(() => this.onopen?.(), 0); } addEventListener() {} close() {} }
      (window as any).EventSource = PreviewSource;
    });
    await page.route('**/api/**', async route => {
      const req = route.request(), url = new URL(req.url()), path = url.pathname;
      let json: unknown;
      if (path === '/api/auth/me') json = actor;
      else if (path === '/api/auth/csrf') json = { token: 'preview-only', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' };
      else if (path === '/api/services') json = services.map(item => item.scope.service === 'VOLTE' ? { ...item, openIncidents: 1, latestWindow: { ...voiceWindows.at(-1)!, scopeId: item.scope.scopeId } } : { ...item, latestWindow: item.latestWindow ? { ...item.latestWindow, windowStart: voiceWindows.at(-1)!.windowStart, windowEnd: voiceWindows.at(-1)!.windowEnd } : null });
      else if (path.endsWith('/kpis')) {
        const sms = path.includes('SMS');
        const source = sms ? services.find(item => item.scope.service === 'SMS')!.latestWindow : null;
        const windows = sms ? voiceWindows.map((row, i) => ({ ...source, windowId: `sms-${i}`, windowStart: row.windowStart, windowEnd: row.windowEnd })) : voiceWindows;
        json = { items: windows, total: windows.length, page: 0, size: 100, observedAt: voiceWindows.at(-1)!.windowEnd };
      } else if (path === '/api/incidents') json = { items: [incident], total: 1, page: 0, size: 20 };
      else if (path.endsWith('/detections')) json = { items: [incident.latestDetection], total: 1, page: 0, size: 20 };
      else if (path.endsWith('/timeline')) json = { items: [], total: 0, page: 0, size: 100 };
      else if (path === '/api/analysts') json = [{ id: actor.analystId, displayName: actor.displayName, enabled: true }];
      else if (path.startsWith('/api/incidents/')) {
        if (req.method() !== 'GET') incident = { ...incident, assigneeId: actor.analystId, version: incident.version + 1 };
        json = incident;
      } else if (path.startsWith('/api/simulator/')) {
        if (req.method() === 'POST') run = { runId: 'ui-run', status: 'RUNNING', scheduledStartAt: new Date(Date.now() - 150000).toISOString(), scheduledEndAt: new Date(Date.now() + 330000).toISOString(), scenarioType: 'VOLTE_IMS_OVERLOAD', scopeId: 'VOLTE-MD-CENTRAL' };
        json = run;
      } else return route.fulfill({ status: 404, json: { message: path } });
      await route.fulfill({ json });
    });
    const capture = async (name: string) => {
      await page.evaluate(() => window.scrollTo(0, 0));
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${name} fits viewport`).toBe(true);
      await page.screenshot({ path: info.outputPath(`${name}-${width}.png`), fullPage: true, animations: 'disabled' });
    };
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Network overview' })).toBeVisible();
    await capture('overview');
    if (width === 390) {
      await page.getByRole('button', { name: 'Open navigation' }).click();
      await expect(page.getByRole('link', { name: 'Scenario runner', exact: true })).toBeVisible();
      await capture('navigation');
      await page.getByRole('button', { name: 'Close navigation' }).click({ position: { x: 350, y: 200 } });
    } else {
      await page.getByRole('button', { name: 'Collapse sidebar' }).click();
      await expect(page.locator('.app-shell')).toHaveClass(/sidebar-collapsed/);
      await page.getByRole('button', { name: 'Expand sidebar' }).click();
    }
    await page.goto('/services/VOLTE-MD-CENTRAL');
    await expect(page.getByRole('heading', { name: 'Call setup success rate' })).toBeVisible();
    await page.getByRole('button', { name: '15m', exact: true }).click();
    await expect(page.getByRole('button', { name: '15m', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await page.getByLabel('Scrollable CSSR chart').focus();
    await page.keyboard.press('Home');
    await expect(page.locator('.chart-tooltip')).toHaveClass(/tooltip-visible/);
    await capture('volte');
    const sms = services.find(item => item.scope.service === 'SMS')!;
    await page.goto(`/services/${sms.scope.scopeId}`);
    await expect(page.getByRole('heading', { name: 'SMS delivery and queue' })).toBeVisible();
    await capture('sms');
    await page.goto(`/incidents/${incident.id}`);
    const summary = page.locator('.incident-summary-bar');
    await expect(summary).toContainText('Latest KPI deviation');
    await expect(summary).toContainText('cssrPct: +0.3 pp');
    await expect(summary).toContainText('Unassigned');
    expect((await summary.boundingBox())!.y + (await summary.boundingBox())!.height).toBeLessThan(900);
    const drawer = page.locator('.workflow-drawer');
    await expect(drawer).toBeHidden();
    await page.getByRole('button', { name: 'Details & workflow' }).click();
    await expect(drawer).toBeVisible();
    await expect(page.getByLabel('Investigation comment', { exact: true })).toBeDisabled();
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await page.getByRole('button', { name: 'Details & workflow' }).click();
    if (width !== 390) {
      await page.mouse.click(5, 200);
      await expect(drawer).toBeHidden();
      await page.getByRole('button', { name: 'Details & workflow' }).click();
    }
    await capture('incident-unassigned');
    await page.getByRole('button', { name: 'Claim for myself' }).click();
    await expect(page.getByLabel('Investigation comment', { exact: true })).toBeEnabled();
    await page.getByLabel('Investigation comment', { exact: true }).fill('Reviewed recovery evidence and queue samples.');
    await page.getByRole('button', { name: 'Add comment', exact: true }).click();
    await expect(page.locator('.toast')).toContainText('Comment added');
    await capture('incident-details');
    await page.getByRole('button', { name: 'Close details and workflow' }).click();
    await expect(summary).toContainText('Alex Morgan');
    await capture('incident');
    await page.goto('/scenarios');
    await expect(page.getByRole('heading', { name: 'Eight-minute profile' })).toBeVisible();
    await page.getByLabel('Service scope', { exact: true }).selectOption('VOLTE-MD-CENTRAL');
    await capture('scenario-idle');
    await page.getByRole('button', { name: 'Start scenario', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Server run' })).toBeVisible();
    await expect(page.locator('.scenario-stepper .is-current')).toHaveCount(1);
    await expect(page.locator('.server-run-grid')).toContainText('Scheduled window');
    const stop = await page.getByRole('button', { name: 'Stop telemetry' }).boundingBox();
    const refresh = await page.getByRole('button', { name: 'Refresh status' }).boundingBox();
    expect(Math.abs(stop!.y - refresh!.y)).toBeLessThan(2);
    await capture('scenario-running');
    expect(errors).toEqual([]);
  });
}
