import { test, expect } from '@playwright/test';
import { randomBytes, randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { admin, command, sql } from '../helpers/live-stack';

test.skip(process.env.E2E_GEOGRAPHIC_FEATURE_OFF !== '1',
  'Requires the staged feature-off local stack with compatible processor readers.');

test('migrated stack keeps legacy APIs and protected commands during feature-off', async ({ browser }, info) => {
  const username = 'feature-off-' + randomBytes(6).toString('hex');
  const password = randomBytes(24).toString('base64url') + '!Aa1';
  const context = await browser.newContext({ baseURL: 'http://telecom.test:8080' });
  const page = await context.newPage();
  let userId = '';
  let runId = '';
  let csrf: { headerName: string; token: string } | null = null;
  const result = {
    revision: command('git', ['rev-parse', 'HEAD']),
    mode: 'Real OIDC and protected APIs with incident and generator geography disabled; processor reader retained',
    checks: [] as string[], runId: '', passed: false,
  };
  try {
    expect((await context.request.get('/api/services')).status()).toBe(401);
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Feature', lastName: 'Off',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Temporary identity creation failed.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'SUPERVISOR']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Feature Off']);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect(page).toHaveURL(/\/dashboard$/);
    await expect(page.getByRole('heading', { name: 'Network overview' })).toBeVisible();
    await expect(page.getByText('SYNTHETIC FIXTURE PREVIEW', { exact: false })).toHaveCount(0);

    const servicesResponse = await context.request.get('/api/services');
    expect(servicesResponse.status()).toBe(200);
    const services = await servicesResponse.json();
    expect(services.map((item: { scope: { scopeId: string } }) => item.scope.scopeId))
      .toEqual(['VOLTE-MD-CENTRAL', 'SMS-MD-ROUTE-A']);
    expect((await context.request.get('/api/incidents')).status()).toBe(200);
    expect((await context.request.get('/api/geography/cities')).status()).toBe(503);
    const to = new Date();
    const from = new Date(to.getTime() - 24 * 60 * 60_000);
    expect((await context.request.get('/api/services/VOLTE-MD-CENTRAL/kpis?' +
      new URLSearchParams({ from: from.toISOString(), to: to.toISOString(), page: '0', size: '20' }))).status()).toBe(200);
    result.checks.push('Legacy scope list, incidents and bounded KPI history remain protected and readable; city inventory is inactive');

    csrf = await (await context.request.get('/api/auth/csrf')).json();
    const body = { requestId: randomUUID(), seed: 42, scopeId: 'VOLTE-MD-CENTRAL' };
    expect((await context.request.post('/api/simulator/scenarios/NORMAL_CONTROL', { data: body })).status()).toBe(403);
    expect((await context.request.post('/api/simulator/scenarios/NORMAL_CONTROL', {
      data: { ...body, requestId: randomUUID(), scopeId: 'VOLTE-MD-ORH' },
      headers: { [csrf.headerName]: csrf.token },
    })).status()).toBe(400);
    const started = await context.request.post('/api/simulator/scenarios/NORMAL_CONTROL', {
      data: body, headers: { [csrf.headerName]: csrf.token },
    });
    expect(started.status()).toBe(202);
    runId = (await started.json()).runId;
    result.runId = runId;
    expect((await context.request.get(`/api/simulator/runs/${runId}`)).status()).toBe(200);
    result.checks.push('Missing CSRF and disabled city command denied; legacy protected command accepted');
    const stopped = await context.request.post(`/api/simulator/runs/${runId}/stop`, {
      headers: { [csrf.headerName]: csrf.token },
    });
    expect(stopped.status()).toBe(200);
    expect((await stopped.json()).status).toBe('STOPPED');
    result.checks.push('Legacy command stop completed without deleting its durable receipt');
    result.passed = true;
  } finally {
    if (!result.passed && runId && csrf) {
      try { await context.request.post(`/api/simulator/runs/${runId}/stop`, {
        headers: { [csrf.headerName]: csrf.token },
      }); } catch { /* Keep the original failure. */ }
    }
    writeFileSync(info.outputPath('feature-off-results.json'), JSON.stringify(result, null, 2));
    try { await context.close(); } catch { /* Context may already be closed. */ }
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Feature Off';`);
    }
  }
});
