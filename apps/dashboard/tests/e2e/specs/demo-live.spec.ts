import { test, expect, type Page } from '@playwright/test';
import { randomBytes, createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { command, admin, sql } from '../helpers/live-stack';

test.skip(process.env.E2E_DEMO_LIVE !== '1', 'Opt-in authenticated local stack; never fixture acceptance.');

test('authenticated map, Orhei, approved scenarios, recovery and reversible overview', async ({ page, context }, info) => {
  const username = 'demo-check-' + randomBytes(6).toString('hex');
  const password = randomBytes(24).toString('base64url') + '!Aa1';
  const results = {
    startedAt: new Date().toISOString(), revision: command('git', ['rev-parse', 'HEAD']),
    patchHash: createHash('sha256').update(command('git', ['diff', 'HEAD'])).digest('hex'),
    mode: 'Authenticated LIVE; no request interception or fixture fallback',
    requests: [] as { path: string; status: number }[],
    captures: [] as { file: string; width: number; capturedAt: string }[],
    runs: [] as any[], checks: [] as string[], blockers: [
      'Orhei scenarios: backend accepts only VOLTE-MD-CENTRAL and SMS-MD-ROUTE-A.',
      'Device topology and local measurements are not implemented by the backend.',
      'Teammate review and handoff require the named teammates.',
    ], passed: false,
  };
  if (process.env.DEMO_RESUME_RESULTS) {
    const previous = JSON.parse(readFileSync(process.env.DEMO_RESUME_RESULTS, 'utf8'));
    if (!previous.passed || previous.runs?.length !== 2) throw new Error('Resume requires a successful real scenario verification.');
    results.runs = previous.runs;
    results.checks.push(`Rechecking real scenario runs from ${previous.startedAt}`);
  }
  let userId = '';
  const capture = async (target: Page, name: string, width: number) => {
    await target.setViewportSize({ width, height: 768 });
    await expect(target.locator('app-status-banner, .service-connection')).not.toBeVisible({ timeout: 60_000 });
    await target.evaluate(() => window.scrollTo(0, 0));
    expect(await target.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    const file = `${name}-${width}.png`;
    await target.screenshot({ path: info.outputPath(file), fullPage: true, animations: 'disabled' });
    results.captures.push({ file, width, capturedAt: new Date().toISOString() });
    if (name.startsWith('recovery-')) {
      const viewportFile = `${name}-viewport-${width}.png`;
      await target.screenshot({ path: info.outputPath(viewportFile), animations: 'disabled' });
      results.captures.push({ file: viewportFile, width, capturedAt: new Date().toISOString() });
    }
  };
  const observe = (target: Page) => target.on('response', response => {
    const url = new URL(response.url());
    if (/^\/api\/(geography|services|incidents|simulator)/.test(url.pathname)) {
      results.requests.push({ path: url.pathname + url.search, status: response.status() });
    }
  });
  observe(page);
  try {
    expect((await context.request.get('/api/geography/cities')).status()).toBe(401);
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Live', lastName: 'Demonstration',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Unexpected temporary user identity.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'SUPERVISOR']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Live demonstration']);
    await page.goto('/login');
    await capture(page, 'login', 1366);
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    try {
      await page.getByLabel(/username|email/i).fill(username);
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect(page).toHaveURL(/\/dashboard$/);
    } catch { throw new Error('Real identity login failed; credentials omitted.'); }
    await expect(page.getByRole('heading', { name: 'Network overview' })).toBeVisible();
    await expect(page.getByText(/SYNTHETIC FIXTURE PREVIEW/)).toHaveCount(0);
    const catalogueResponse = await context.request.get('/api/geography/cities');
    expect(catalogueResponse.status()).toBe(200);
    const catalogue = await catalogueResponse.json();
    const orhei = catalogue.cities.find((city: any) => city.cityId === 'ORH');
    expect(orhei).toBeDefined();
    expect(orhei.services.map((service: any) => service.scopeId).sort()).toEqual(['SMS-MD-ORH', 'VOLTE-MD-ORH']);
    results.checks.push(`Protected catalogue: ${catalogue.cities.length} cities, authoritative Orhei scopes`);

    // Run supported eight-minute profiles concurrently, using only authenticated public controls.
    if (!results.runs.length) for (const [type, scopeId] of [['VOLTE_IMS_OVERLOAD', 'VOLTE-MD-CENTRAL'], ['SMS_QUEUE_DELAY', 'SMS-MD-ROUTE-A']]) {
      const runner = await context.newPage(); observe(runner);
      await runner.goto('/scenarios');
      await runner.getByLabel('Scenario', { exact: true }).selectOption(type);
      await runner.getByLabel('Service scope', { exact: true }).selectOption(scopeId);
      await expect(runner.getByLabel('Service scope').locator('option')).toHaveCount(2);
      await runner.getByLabel('Seed', { exact: true }).fill('42');
      const started = runner.waitForResponse(response => new URL(response.url()).pathname === `/api/simulator/scenarios/${type}` && response.request().method() === 'POST');
      await runner.getByRole('button', { name: 'Start scenario', exact: true }).click();
      const response = await started;
      expect([200, 201, 202]).toContain(response.status());
      const run = await response.json();
      results.runs.push({ type, scopeId, ...run });
      await expect(runner.getByRole('heading', { name: 'Server run' })).toBeVisible();
      await capture(runner, `scenario-${type}`, 1366);
      await runner.close();
    }
    console.log('Authenticated scenarios available; verifying real completion and recovery.');

    await page.getByLabel('Region', { exact: true }).fill('Orhei');
    await expect(page.getByRole('heading', { name: 'Orhei investigation' })).toBeVisible();
    await page.getByText('Orhei · City coverage and history', { exact: true }).click();
    await expect(page.locator('app-city-evidence [data-city-window]').first()).toBeVisible();
    for (const width of [1366, 768, 390]) await capture(page, 'orhei-overview', width);
    expect(results.requests.some(request => request.path.startsWith('/api/geography/cities/ORH/kpis?') && request.status === 200)).toBe(true);
    await page.getByRole('button', { name: 'Refresh overview' }).click();
    await expect(page.getByLabel('Region', { exact: true })).toHaveValue('Orhei');
    await expect(page.getByRole('heading', { name: 'Orhei investigation' })).toBeVisible();
    results.checks.push('Real selected-city geography history, selection retained on refresh, synthetic footprint labeled');
    await page.setViewportSize({ width: 1366, height: 768 });
    const historicalEnd = Date.parse((await page.getByLabel('To (UTC, exclusive)').inputValue()) + 'Z') - 120_000;
    await page.getByLabel('From (UTC)', { exact: true }).fill(new Date(historicalEnd - 900_000).toISOString().slice(0, 16));
    await page.getByLabel('To (UTC, exclusive)').fill(new Date(historicalEnd).toISOString().slice(0, 16));
    await page.getByRole('button', { name: 'Apply', exact: true }).click();
    await page.getByLabel('Region', { exact: true }).focus();
    const range = await page.getByLabel('From (UTC)', { exact: true }).inputValue();
    await page.evaluate(() => window.scrollTo(0, 300));
    const scroll = await page.evaluate(() => window.scrollY);
    const catalogueReads = () => results.requests.filter(request => request.path === '/api/geography/cities' && request.status === 200).length;
    const beforeReconnect = catalogueReads();
    // A browser offline toggle need not close an established SSE socket. Restart
    // the local proxy to interrupt transport while retaining the backend session.
    command('docker', ['compose', 'stop', 'proxy']);
    try {
      await expect(page.getByText('Live connection interrupted. Existing evidence is still shown; reconnecting…', { exact: true })).toBeVisible();
    } finally {
      command('docker', ['compose', 'start', 'proxy']);
    }
    await expect.poll(catalogueReads, { timeout: 60_000 }).toBeGreaterThan(beforeReconnect);
    await expect(page.getByLabel('Region', { exact: true })).toHaveValue('Orhei');
    await expect(page.getByLabel('Region', { exact: true })).toBeFocused();
    await expect(page.getByLabel('From (UTC)', { exact: true })).toHaveValue(range);
    expect(Math.abs(await page.evaluate(() => window.scrollY) - scroll)).toBeLessThan(2);
    results.checks.push('Real SSE reconnect refreshed REST and retained city, history range, focus and scroll');

    for (const scopeId of ['VOLTE-MD-ORH', 'SMS-MD-ORH']) {
      await page.getByLabel('Region', { exact: true }).fill('Orhei');
      const opened = page.waitForResponse(response => new URL(response.url()).pathname === '/api/incidents/stream' && response.status() === 200, { timeout: 60_000 });
      await page.getByRole('link', { name: scopeId.startsWith('VOLTE') ? 'Open Orhei VoLTE setup →' : 'Open Orhei SMS delivery →', exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`/services/${scopeId}$`));
      await expect(page.locator('.service-hero')).toBeVisible();
      await opened;
      for (const width of [1366, 768, 390]) await capture(page, scopeId, width);
      expect(results.requests.some(request => request.path.startsWith(`/api/services/${scopeId}/kpis?`) && request.status === 200)).toBe(true);
      await page.getByRole('link', { name: 'Service overview', exact: true }).click();
    }
    await page.locator('details.source-inventory > summary').click();
    await page.getByRole('link', { name: 'Open scope overview', exact: true }).click();
    await expect(page.locator('app-connected-overview')).toHaveCount(0);
    await expect(page.locator('.service-assurance-card')).not.toHaveCount(0);
    await capture(page, 'live-scope-fallback', 1366);
    await page.getByRole('link', { name: 'Open connected overview' }).click();
    await expect(page.locator('app-connected-overview')).toBeVisible();
    results.checks.push('Authenticated fallback and return to connected overview');

    for (const run of results.runs) {
      await expect.poll(async () => {
        const response = await context.request.get(`/api/simulator/runs/${run.runId}`);
        expect(response.status()).toBe(200);
        run.status = (await response.json()).status;
        return run.status;
      }, { timeout: 600_000, intervals: [5000] }).toBe('COMPLETED');
      let incident: any;
      await expect.poll(async () => {
        const response = await context.request.get(`/api/incidents?scopeId=${run.scopeId}&size=100`);
        expect(response.status()).toBe(200);
        incident = (await response.json()).items.find((item: any) => Date.parse(item.firstObservedAt) >= Date.parse(run.scheduledStartAt)
          && Date.parse(item.firstObservedAt) < Date.parse(run.scheduledEndAt));
        return incident?.technicalState;
      }, { timeout: 120_000, intervals: [5000] }).toBe('RECOVERED');
      expect(incident.status).not.toBe('RESOLVED');
      run.incidentId = incident.id;
      run.technicalState = incident.technicalState;
      run.workflowState = incident.status;
      await page.getByLabel('Region', { exact: true }).fill('');
      await page.getByRole('button', { name: /^Incidents \(/ }).click();
      const opened = page.waitForResponse(response => new URL(response.url()).pathname === '/api/incidents/stream' && response.status() === 200, { timeout: 60_000 });
      await page.locator(`a[href="/incidents/${incident.id}"]`).click();
      await expect(page).toHaveURL(new RegExp(`/incidents/${incident.id}$`));
      await expect(page.locator('.incident-summary-bar')).toContainText('Awaiting analyst resolution');
      await expect(page.locator('[data-detection-id]')).not.toHaveCount(0);
      await opened;
      for (const width of [1366, 768, 390]) await capture(page, `recovery-${run.type}`, width);
      await page.getByRole('button', { name: 'Refresh incident' }).click();
      await expect(page.locator('.incident-summary-bar')).toContainText('RECOVERED');
      await page.getByRole('link', { name: 'Back to service', exact: true }).click();
      await page.getByRole('link', { name: 'Service overview', exact: true }).click();
    }
    results.checks.push('Both approved scenarios completed; real recovery retained unresolved analyst workflow');
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page).toHaveURL(/\/signed-out$/);
    expect((await context.request.get('/api/geography/cities')).status()).toBe(401);
    results.checks.push('Real sign-in and logout; geography remains protected');
    results.passed = true;
  } finally {
    await context.setOffline(false).catch(() => {});
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Live demonstration';`);
    }
    writeFileSync(info.outputPath('live-results.json'), JSON.stringify(results, null, 2));
  }
});
