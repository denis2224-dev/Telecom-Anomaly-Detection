import { test, expect } from '@playwright/test';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { admin, command, sql } from '../helpers/live-stack';

test.skip(process.env.E2E_GEOGRAPHIC_LIVE !== '1', 'Requires the authenticated local stack with active geography.');

test('real city commands reach the processor and protected city routes', async ({ browser }, info) => {
  const username = 'geographic-check-' + randomBytes(6).toString('hex');
  const password = randomBytes(24).toString('base64url') + '!Aa1';
  const context = await browser.newContext({ baseURL: 'http://telecom.test:8080' });
  const page = await context.newPage();
  const result = {
    startedAt: new Date().toISOString(),
    revision: command('git', ['rev-parse', 'HEAD']),
    patchSha256: createHash('sha256').update(command('git', ['diff', 'HEAD'])).digest('hex'),
    mode: 'Authenticated live API, generator, Kafka, processor and UI; no intercepted responses or fixture fallback',
    routeChecks: 0, checks: [] as string[], runs: [] as Array<{
      runId: string; scopeId: string; scenarioType: string; scheduledStartAt: string;
      scheduledEndAt: string; status?: string; incidentId?: string;
    }>, passed: false,
  };
  let userId = '';
  let csrf: { headerName: string; token: string } | null = null;
  const read = async (path: string) => {
    const response = await context.request.get(path);
    expect(response.status(), path).toBe(200);
    return response.json();
  };
  const post = (type: string, body: object, withCsrf = true) => context.request.post(`/api/simulator/scenarios/${type}`, {
    data: body, headers: withCsrf && csrf ? { [csrf.headerName]: csrf.token } : {},
  });
  const processingCount = (scopeId: string, start: string) => Number(command('docker', [
    'compose', 'exec', '-T', 'postgres', 'bash', '-ec',
    'exec psql -X -q -tA -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d processing_db',
  ], `SELECT count(*) FROM app.observation_receipt WHERE scope_id='${scopeId}' AND window_start >= '${start}'::timestamptz;`));
  try {
    expect((await context.request.get('/api/geography/cities')).status()).toBe(401);
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Geographic', lastName: 'Acceptance',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Temporary identity creation failed.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'SUPERVISOR']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Geographic acceptance']);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect(page).toHaveURL(/\/dashboard$/);
    await page.getByLabel('Region', { exact: true }).fill('Orhei');
    await expect(page.getByRole('heading', { name: 'Orhei investigation' })).toBeVisible();
    await page.getByText('Orhei · City coverage and history', { exact: true }).click();
    await expect(page.locator('app-city-evidence')).toBeVisible();
    if (await page.locator('app-city-evidence [data-city-window]').count() === 0) {
      await expect(page.locator('app-city-evidence')).toContainText('No city KPI history in this range.');
    }
    result.checks.push('Real authenticated Orhei dashboard shows persisted history or an explicit missing state');

    const catalogue = await read('/api/geography/cities');
    expect(catalogue.cities).toHaveLength(10);
    const to = new Date();
    const from = new Date(to.getTime() - 60 * 60_000);
    for (const city of catalogue.cities) {
      const topology = await read(`/api/geography/cities/${city.cityId}/topology`);
      expect(topology.cityId).toBe(city.cityId);
      result.routeChecks++;
      for (const service of ['VOLTE', 'SMS']) {
        const params = new URLSearchParams({ service, from: from.toISOString(), to: to.toISOString(), page: '0', size: '20' });
        const history = await read(`/api/geography/cities/${city.cityId}/kpis?${params}`);
        expect(history.cityId).toBe(city.cityId);
        result.routeChecks++;
      }
    }
    expect((await context.request.get('/api/geography/cities/ORH/topology?size=101')).status()).toBe(400);
    const priority = await read('/api/operations/priority?page=0&size=20');
    expect(priority.policyVersion).toBe('geographic-priority-v1');
    expect(priority.policyStatus).toBe('ACTIVE');
    expect((await context.request.get('/api/operations/priority?size=101')).status()).toBe(400);
    result.checks.push('Ten live topology routes, twenty bounded histories and active priority queue');
    console.log('Authenticated city route matrix and priority response passed.');

    csrf = await read('/api/auth/csrf');
    const voice = { requestId: randomUUID(), seed: 42, scopeId: 'VOLTE-MD-ORH' };
    const sms = { requestId: randomUUID(), seed: 42, scopeId: 'SMS-MD-ORH' };
    expect((await post('VOLTE_IMS_OVERLOAD', voice, false)).status()).toBe(403);
    expect((await post('VOLTE_IMS_OVERLOAD', { ...voice, scopeId: 'SMS-MD-ORH' })).status()).toBe(400);
    expect((await post('SMS_QUEUE_DELAY', { ...sms, scopeId: 'VOLTE-MD-ORH' })).status()).toBe(400);
    for (const [type, body] of [['VOLTE_IMS_OVERLOAD', voice], ['SMS_QUEUE_DELAY', sms]] as const) {
      const started = await post(type, body);
      expect(started.status(), type).toBe(202);
      const run = await started.json();
      expect(run.scopeId).toBe(body.scopeId);
      expect(run.scenarioType).toBe(type);
      result.runs.push(run);
      const retried = await post(type, body);
      expect(retried.status()).toBe(202);
      expect((await retried.json()).runId).toBe(run.runId);
      expect((await post(type, { ...body, seed: 43 })).status()).toBe(409);
      expect((await post(type, { ...body, requestId: randomUUID() })).status()).toBe(409);
    }
    result.checks.push('Real public command, private generator delivery, idempotency, CSRF and scope reservations');
    console.log('Both service-specific Orhei commands were accepted and retried safely.');

    for (const run of result.runs) {
      await expect.poll(() => processingCount(run.scopeId, run.scheduledStartAt),
        { timeout: 240_000, intervals: [5000] }).toBeGreaterThan(0);
      result.checks.push(`${run.scopeId}: published city observations reached processor receipt table`);
    }
    console.log('Both city scopes reached the processor receipt table.');
    for (const run of result.runs) {
      const saved = await read(`/api/simulator/runs/${run.runId}`);
      expect(['SCHEDULED', 'RUNNING', 'COMPLETED']).toContain(saved.status);
      run.status = saved.status;
      const incidents = await read(`/api/incidents?scopeId=${run.scopeId}&size=100`);
      const incident = incidents.items.find((item: { firstObservedAt: string }) =>
        Date.parse(item.firstObservedAt) >= Date.parse(run.scheduledStartAt)
        && Date.parse(item.firstObservedAt) < Date.parse(run.scheduledEndAt));
      if (!incident) continue;
      const detail = await read(`/api/incidents/${incident.id}`);
      expect(detail.location.cityId).toBe('ORH');
      expect(detail.location.measuredScopeId).toBe(run.scopeId);
      expect(detail.latestDetection.scopeId).toBe(run.scopeId);
      expect(detail.location.containmentPath).toContain('CITY-MD-ORH');
      run.incidentId = incident.id;
      result.checks.push(`${run.scopeId}: persisted incident and source-pinned Orhei path`);
    }
    result.passed = true;
  } finally {
    if (!result.passed && csrf) for (const run of result.runs) {
      try { await context.request.post(`/api/simulator/runs/${run.runId}/stop`, {
        headers: { [csrf.headerName]: csrf.token },
      }); } catch { /* Keep the original failure; reconciliation remains authoritative. */ }
    }
    try { await context.close(); } catch { /* Playwright may have closed the context on timeout. */ }
    writeFileSync(info.outputPath('geographic-results.json'), JSON.stringify(result, null, 2));
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Geographic acceptance';`);
    }
  }
});
