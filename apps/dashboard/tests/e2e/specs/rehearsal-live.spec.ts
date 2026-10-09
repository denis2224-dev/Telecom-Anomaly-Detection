import { test, expect, type Page } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { command, admin, sql } from '../helpers/live-stack';
import type { Incident, ScenarioRun } from '../../../src/app/core/api/telecom-client';

test.skip(process.env.E2E_REHEARSAL_LIVE !== '1', 'Opt-in local authenticated rehearsal, no intercepted requests.');

test('replays connected city, evidence, recovery and reconnect with authoritative APIs', async ({ page, context }, info) => {
  let userId = '';
  let csrf: { headerName: string; token: string } | undefined;
  const report = { startedAtUTC: new Date().toISOString(), revision: command('git', ['rev-parse', 'HEAD']),
    patchSha256: createHash('sha256').update(command('git', ['diff', 'HEAD'])).digest('hex'),
    mode: 'Real OIDC, REST, native SSE, generator and detector; synthetic local telemetry; no interception or fixture fallback',
    runs: [] as ScenarioRun[], incidentStates: [] as Record<string, unknown>[],
    requests: [] as { path: string; status: number; receivedAtUTC: string }[],
    captures: [] as { file: string; capturedAtUTC: string }[], checks: [] as string[], passed: false };
  const read = async (path: string) => {
    const response = await context.request.get(path);
    expect(response.status(), path).toBe(200);
    return response.json();
  };
  const capture = async (target: Page, name: string) => {
    const file = `${name}.png`;
    await target.screenshot({ path: info.outputPath(file), fullPage: true });
    report.captures.push({ file, capturedAtUTC: new Date().toISOString() });
  };
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/api/') && !/auth|stream/.test(url.pathname) && report.requests.length < 1500)
      report.requests.push({ path: url.pathname + url.search, status: response.status(), receivedAtUTC: new Date().toISOString() });
  });
  try {
    expect((await context.request.get('/api/geography/cities')).status()).toBe(401);
    const username = 'rehearsal-check-' + randomBytes(6).toString('hex');
    const password = randomBytes(24).toString('base64url') + '!Aa1';
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Rehearsal', lastName: 'Verification',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Temporary identity creation failed.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'SUPERVISOR']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Rehearsal verification']);
    await page.addInitScript(() => {
      const NativeSource = window.EventSource;
      const state = { opened: 0, interrupted: 0 };
      window.EventSource = class extends NativeSource {
        constructor(url: string | URL, options?: EventSourceInit) {
          super(url, options);
          this.addEventListener('open', () => state.opened++);
          this.addEventListener('error', () => state.interrupted++);
        }
      };
      (window as any).__rehearsalStream = state;
    });
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    try {
      await page.getByLabel(/username|email/i).fill(username);
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect(page).toHaveURL(/\/dashboard$/);
    } catch { throw new Error('Authenticated rehearsal sign-in failed; credentials omitted.'); }
    csrf = await read('/api/auth/csrf');
    const runners: Page[] = [];
    for (const [type, scopeId] of [['VOLTE_IMS_OVERLOAD', 'VOLTE-MD-ORH'], ['SMS_QUEUE_DELAY', 'SMS-MD-ORH']]) {
      const runner = await context.newPage(); runners.push(runner);
      await runner.goto('/scenarios');
      await runner.getByLabel('Scenario', { exact: true }).selectOption(type);
      await runner.getByLabel('Service scope', { exact: true }).selectOption(scopeId);
      await runner.getByLabel('Seed', { exact: true }).fill('42');
      const posted = runner.waitForResponse(response => new URL(response.url()).pathname === `/api/simulator/scenarios/${type}`
        && response.request().method() === 'POST');
      await runner.getByRole('button', { name: 'Start scenario', exact: true }).click();
      const response = await posted;
      expect(response.status()).toBe(202);
      const run = await response.json() as ScenarioRun;
      expect(run.scopeId).toBe(scopeId);
      report.runs.push(run);
      await capture(runner, `scheduled-${run.scenarioType}`);
    }
    console.log('Real Orhei VoLTE and SMS scenarios scheduled; checking connected navigation while they execute.');
    const catalogue = await read('/api/geography/cities');
    expect(catalogue.cities).toHaveLength(10);
    for (const city of catalogue.cities) {
      await page.getByLabel('Region', { exact: true }).fill(city.displayName);
      await expect(page.getByRole('heading', { name: `${city.displayName} investigation`, exact: true })).toBeVisible();
      await expect(page.locator('.chart-scope')).toHaveCount(2);
      for (const state of city.services) {
        await expect(page.locator(`.chart-scope[data-scope="${state.scopeId}"]`)).toBeVisible();
        await expect(page.getByRole('link', { name: `Open ${city.displayName} ${state.service === 'VOLTE' ? 'VoLTE setup' : 'SMS delivery'}`, exact: false }))
          .toHaveAttribute('href', `/services/${state.scopeId}`);
      }
      await expect.poll(() => report.requests.some(row => row.path.startsWith(`/api/geography/cities/${city.cityId}/topology`) && row.status === 200)).toBe(true);
      for (const state of city.services) await expect.poll(() => report.requests.some(row =>
        row.path.startsWith(`/api/services/${state.scopeId}/kpis`) && row.status === 200)).toBe(true);
    }
    report.checks.push('All ten city selections connect both authoritative service scopes, histories and implemented topology');
    await page.getByLabel('Region', { exact: true }).fill('Orhei');
    await page.getByText('Containment and dependencies', { exact: true }).click();
    for (const kind of ['AGGREGATION', 'SITE']) {
      const node = page.locator('app-city-evidence').getByRole('button', { name: new RegExp(`^${kind} `) }).first();
      await expect(node).toBeEnabled();
      await node.click();
    }
    await expect(page.locator('app-city-evidence').getByRole('button', { name: /^CELL / }).first()).toBeDisabled();
    await expect(page.locator('app-city-evidence')).toContainText('Local device measurements: Unavailable');
    await page.getByText('Orhei · City coverage and history', { exact: true }).click();
    await expect(page.locator('app-city-evidence [data-city-window]').first()).toBeVisible();
    const reads = () => report.requests.filter(row => row.path === '/api/geography/cities' && row.status === 200).length;
    const beforeReads = reads();
    const beforeOpen = await page.evaluate(() => (window as any).__rehearsalStream.opened);
    await page.getByLabel('Region', { exact: true }).focus();
    const scroll = await page.evaluate(() => window.scrollY);
    await expect.poll(() => page.evaluate(() => (window as any).__rehearsalStream.opened), { timeout: 90_000 }).toBeGreaterThan(beforeOpen);
    await expect.poll(reads).toBeGreaterThan(beforeReads);
    await expect(page.getByLabel('Region', { exact: true })).toHaveValue('Orhei');
    await expect(page.getByLabel('Region', { exact: true })).toBeFocused();
    expect(await page.evaluate(() => window.scrollY)).toBe(scroll);
    report.checks.push('Native SSE lease reconnect refetches REST and preserves Orhei, focus and scroll');
    await capture(page, 'orhei-connected');
    const find = async (run: ScenarioRun): Promise<Incident | undefined> => (await read(`/api/incidents?scopeId=${run.scopeId}&size=100`)).items.find((item: Incident) =>
      Date.parse(item.firstObservedAt) >= Date.parse(run.scheduledStartAt) && Date.parse(item.firstObservedAt) < Date.parse(run.scheduledEndAt));
    for (const run of report.runs) {
      await expect.poll(async () => Boolean(await find(run)), { timeout: 360_000, intervals: [5000] }).toBe(true);
      const incident = (await find(run))!;
      expect(incident.location?.cityId).toBe('ORH');
      await page.goto('/dashboard');
      await page.getByLabel('Region', { exact: true }).fill('Orhei');
      await page.getByRole('button', { name: 'Open incident investigation', exact: true }).click();
      await page.locator(`.queue-item a[href="/incidents/${incident.id}"]`).click();
      await expect(page.locator('[data-detection-id]').first()).toBeVisible();
      report.incidentStates.push({ capturedAtUTC: new Date().toISOString(), stage: 'detected', id: incident.id,
        version: incident.version, technicalState: incident.technicalState, workflowState: incident.status,
        runId: run.runId, latestDetectionId: incident.latestDetection.detectionId, phase: incident.latestDetection.phase });
      await capture(page, `detected-${run.scenarioType}`);
    }
    console.log('Both scenarios produced real Orhei incident evidence; waiting for measured recovery.');
    for (const [index, run] of report.runs.entries()) {
      await expect.poll(async () => (await find(run))?.technicalState, { timeout: 360_000, intervals: [5000] }).toBe('RECOVERED');
      await expect.poll(async () => (await read(`/api/simulator/runs/${run.runId}`)).status).toBe('COMPLETED');
      const incident = (await find(run))!;
      expect(incident.status).not.toBe('RESOLVED');
      await page.goto(`/incidents/${incident.id}`);
      await expect(page.locator('.incident-summary-bar')).toContainText('Awaiting analyst resolution');
      await expect(page.locator('.incident-summary-bar')).toContainText('RECOVERED');
      const history = await read(`/api/incidents/${incident.id}/detections?page=0&size=20`);
      expect(history.items.at(-1).phase).toBe('RECOVERY');
      report.incidentStates.push({ capturedAtUTC: new Date().toISOString(), stage: 'recovered-open', id: incident.id,
        version: incident.version, technicalState: incident.technicalState, workflowState: incident.status,
        runId: run.runId, latestDetectionId: incident.latestDetection.detectionId,
        sequences: history.items.map((item: any) => ({ sequence: item.sequence, phase: item.phase, detectedAt: item.detectedAt })) });
      for (const width of [1366, 768, 390]) {
        await page.setViewportSize({ width, height: 768 });
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
        await capture(page, `recovered-${run.scenarioType}-${width}`);
      }
      await page.setViewportSize({ width: 1366, height: 768 });
      await page.getByRole('button', { name: 'Refresh incident', exact: true }).click();
      await expect(page.locator('.incident-summary-bar')).toContainText('Awaiting analyst resolution');
      await expect(runners[index].locator('.server-run')).toContainText('COMPLETED');
      const authoritative = await read(`/api/simulator/runs/${run.runId}`);
      Object.assign(run, authoritative);
    }
    report.checks.push('VoLTE/SMS actual scenario completion, persisted recovery, unresolved analyst workflow, refresh and three viewport sizes');
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page).toHaveURL(/\/signed-out$/);
    expect((await context.request.get('/api/incidents')).status()).toBe(401);
    report.passed = true;
  } finally {
    if (!report.passed && csrf) for (const run of report.runs) {
      try { await context.request.post(`/api/simulator/runs/${run.runId}/stop`, { headers: { [csrf.headerName]: csrf.token } }); }
      catch { /* Preserve the failure and record the run for reconciliation. */ }
    }
    writeFileSync(info.outputPath('rehearsal-live.json'), JSON.stringify(report, null, 2));
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Rehearsal verification';`);
    }
  }
});

test('extended history uses real protected requests up to 30 periods', async ({ page, context }, info) => {
  let userId = '';
  const requests: { path: string; status: number }[] = [];
  try {
    const username = 'history-check-' + randomBytes(6).toString('hex');
    const password = randomBytes(24).toString('base64url') + '!Aa1';
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'History', lastName: 'Verification', emailVerified: true,
      email: `${username}@example.invalid`, credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Temporary identity creation failed.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'ANALYST']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'History verification']);
    page.on('response', response => {
      const url = new URL(response.url());
      if (url.pathname.endsWith('/kpis')) requests.push({ path: url.pathname + url.search, status: response.status() });
    });
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    try {
      await page.getByLabel(/username|email/i).fill(username);
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect(page).toHaveURL(/\/dashboard$/);
    } catch { throw new Error('Authenticated history sign-in failed; credentials omitted.'); }
    await page.getByLabel('Region', { exact: true }).fill('Orhei');
    await page.getByRole('button', { name: '30d', exact: true }).click();
    await expect.poll(() => requests.filter(row => row.path.startsWith('/api/services/')).length).toBeGreaterThanOrEqual(60);
    await expect(page.locator('[data-chart=cssrPct] .actual-line')).not.toHaveAttribute('d', '');
    await expect(page.locator('.chart-error')).toHaveCount(0);
    await page.getByText('Orhei · City coverage and history', { exact: true }).click();
    const intervals = page.getByRole('navigation', { name: 'City history intervals', exact: true });
    await expect(intervals).toContainText('1/30');
    await intervals.getByRole('button', { name: 'Later interval', exact: true }).click();
    await expect(intervals).toContainText('2/30');
    for (const width of [1366, 768, 390]) {
      await page.setViewportSize({ width, height: 900 });
      await page.evaluate(() => window.scrollTo(0, 0));
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
      await page.screenshot({ path: info.outputPath(`history-periods-live-${width}.png`), animations: 'disabled' });
    }
    expect(requests.every(row => row.status === 200)).toBe(true);
    for (const row of requests) {
      const query = new URL(row.path, 'http://test.invalid').searchParams;
      expect(Date.parse(query.get('to')!) - Date.parse(query.get('from')!)).toBeLessThanOrEqual(86_400_000);
      expect(Number(query.get('size'))).toBeLessThanOrEqual(100);
    }
    writeFileSync(info.outputPath('extended-history-live.json'), JSON.stringify({ capturedAtUTC: new Date().toISOString(),
      mode: 'Real OIDC and protected REST; no interception or fixture fallback', requests, passed: true }, null, 2));
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page).toHaveURL(/\/signed-out$/);
    expect((await context.request.get('/api/geography/cities')).status()).toBe(401);
  } finally {
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='History verification';`);
    }
  }
});
