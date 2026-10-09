import { test, expect, type BrowserContext } from '@playwright/test';
import { randomBytes, randomUUID } from 'node:crypto';
import { admin, command, sql } from '../helpers/live-stack';

test.skip(process.env.E2E_DEPENDENCY_LIVE !== '1', 'Requires the real local Compose stack.');
test.setTimeout(240_000);

test('database outage returns bounded errors and an identical command succeeds once after recovery', async ({ browser }) => {
  const username = `dependency-check-${randomBytes(5).toString('hex')}`;
  const password = `${randomBytes(24).toString('base64url')}!Aa1`;
  const context = await browser.newContext({ baseURL: 'http://telecom.test:8080' });
  let userId = '';
  let databaseStopped = false;
  let databaseTouched = false;
  let identityRestarted = false;
  try {
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Dependency', lastName: 'Check',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    expect(userId).toMatch(/^[0-9a-f-]{36}$/);
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'SUPERVISOR']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Dependency Check']);

    const page = await context.newPage();
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in' }).click();
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect(page).toHaveURL(/\/dashboard$/);
    expect((await context.request.get('/api/auth/me')).status()).toBe(200);
    const csrf = await (await context.request.get('/api/auth/csrf')).json();
    const requestId = randomUUID();
    const payload = { requestId, seed: 194071, scopeId: 'VOLTE-MD-CENTRAL' };
    const commandRowsBefore = Number(sql('SELECT count(*) FROM app.scenario_commands;'));

    command('docker', ['compose', 'stop', 'postgres']);
    databaseStopped = true;
    databaseTouched = true;
    const startedAt = Date.now();
    const responses = await Promise.all([
      context.request.get('/api/auth/me', { timeout: 10_000 }),
      context.request.get('/api/incidents?size=5', { timeout: 10_000 }),
      context.request.post('/api/simulator/scenarios/VOLTE_IMS_OVERLOAD', {
        data: payload, headers: { [csrf.headerName]: csrf.token }, timeout: 10_000,
      }),
    ]);
    const elapsedMs = Date.now() - startedAt;
    console.log(JSON.stringify({ databaseOutage: { elapsedMs, statuses: responses.map(r => r.status()), requestId } }));
    for (const response of responses) {
      expect(response.status(), response.url()).toBe(503);
      expect((await response.json()).code).toBe('UNAVAILABLE');
    }
    expect(elapsedMs).toBeLessThan(10_000);

    command('docker', ['compose', 'start', 'postgres']);
    command('docker', ['compose', 'up', '-d', '--wait', 'postgres']);
    // PostgreSQL restart can leave Keycloak's existing pool with closed connections.
    command('docker', ['compose', 'restart', 'keycloak']);
    command('docker', ['compose', 'up', '-d', '--wait', 'keycloak']);
    identityRestarted = true;
    databaseStopped = false;
    await expect.poll(async () => (await context.request.get('/api/auth/me')).status(), { timeout: 30_000 }).toBe(200);
    const accepted = await context.request.post('/api/simulator/scenarios/VOLTE_IMS_OVERLOAD', {
      data: payload, headers: { [csrf.headerName]: csrf.token },
    });
    expect(accepted.status()).toBe(202);
    const run = await accepted.json();
    const repeated = await context.request.post('/api/simulator/scenarios/VOLTE_IMS_OVERLOAD', {
      data: payload, headers: { [csrf.headerName]: csrf.token },
    });
    expect(repeated.status()).toBe(202);
    expect((await repeated.json()).runId).toBe(run.runId);
    expect(Number(sql(`SELECT count(*) FROM app.scenario_commands WHERE request_id='${requestId}';`))).toBe(1);
    expect(Number(sql('SELECT count(*) FROM app.scenario_commands;'))).toBe(commandRowsBefore + 1);
    console.log(JSON.stringify({ databaseRecovery: { requestId, runId: run.runId, scheduledStartAt: run.scheduledStartAt, commandRowsBefore } }));
    const restoredContext = await browser.newContext({ baseURL: 'http://telecom.test:8080' });
    try {
      const restoredPage = await restoredContext.newPage();
      await restoredPage.goto('/login');
      await restoredPage.getByRole('button', { name: 'Continue to sign in' }).click();
      await restoredPage.getByLabel(/username|email/i).fill(username);
      await restoredPage.getByLabel('Password', { exact: true }).fill(password);
      await restoredPage.getByRole('button', { name: /sign in/i }).click();
      await expect(restoredPage).toHaveURL(/\/dashboard$/);
      expect((await restoredContext.request.get('/api/auth/me')).status()).toBe(200);
      console.log(JSON.stringify({ newLoginAfterDatabaseRecovery: 200 }));
    } finally { await restoredContext.close(); }
  } finally {
    if (databaseStopped) {
      command('docker', ['compose', 'start', 'postgres']);
      command('docker', ['compose', 'up', '-d', '--wait', 'postgres']);
    }
    if (databaseTouched && !identityRestarted) {
      command('docker', ['compose', 'restart', 'keycloak']);
      command('docker', ['compose', 'up', '-d', '--wait', 'keycloak']);
    }
    await context.close();
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      command('docker', ['compose', 'up', '-d', '--wait', 'keycloak']);
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Dependency Check';`);
    }
  }
});

test('identity outage blocks new login while an existing session remains usable, then login recovers', async ({ browser }) => {
  const username = `identity-check-${randomBytes(5).toString('hex')}`;
  const password = `${randomBytes(24).toString('base64url')}!Aa1`;
  const existing = await browser.newContext({ baseURL: 'http://telecom.test:8080' });
  let userId = '';
  let identityStopped = false;
  try {
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Identity', lastName: 'Check',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    expect(userId).toMatch(/^[0-9a-f-]{36}$/);
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'SUPERVISOR']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Identity Check']);
    const first = await existing.newPage();
    await first.goto('/login');
    await first.getByRole('button', { name: 'Continue to sign in' }).click();
    await first.getByLabel(/username|email/i).fill(username);
    await first.getByLabel('Password', { exact: true }).fill(password);
    await first.getByRole('button', { name: /sign in/i }).click();
    await expect(first).toHaveURL(/\/dashboard$/);
    expect((await existing.request.get('/api/auth/me')).status()).toBe(200);

    command('docker', ['compose', 'stop', 'keycloak']);
    identityStopped = true;
    expect((await existing.request.get('/api/auth/me')).status()).toBe(200);
    expect((await existing.request.get('/api/incidents?size=5')).status()).toBe(200);
    const denied = await browser.newContext({ baseURL: 'http://telecom.test:8080' });
    try {
      const page = await denied.newPage();
      await page.goto('/login');
      const [response] = await Promise.all([
        page.waitForNavigation(),
        page.getByRole('button', { name: 'Continue to sign in' }).click(),
      ]);
      expect(response?.status()).toBe(502);
    } finally { await denied.close(); }
    console.log(JSON.stringify({ identityOutage: { existingAuth: 200, existingIncidents: 200, newLogin: 502 } }));

    command('docker', ['compose', 'start', 'keycloak']);
    command('docker', ['compose', 'up', '-d', '--wait', 'keycloak']);
    identityStopped = false;
    const restored = await browser.newContext({ baseURL: 'http://telecom.test:8080' });
    try {
      const page = await restored.newPage();
      await page.goto('/login');
      await page.getByRole('button', { name: 'Continue to sign in' }).click();
      await page.getByLabel(/username|email/i).fill(username);
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect(page).toHaveURL(/\/dashboard$/);
      expect((await restored.request.get('/api/auth/me')).status()).toBe(200);
      console.log(JSON.stringify({ identityRecovery: { newLogin: 200 } }));
    } finally { await restored.close(); }
  } finally {
    if (identityStopped) {
      command('docker', ['compose', 'start', 'keycloak']);
      command('docker', ['compose', 'up', '-d', '--wait', 'keycloak']);
    }
    await existing.close();
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Identity Check';`);
    }
  }
});
