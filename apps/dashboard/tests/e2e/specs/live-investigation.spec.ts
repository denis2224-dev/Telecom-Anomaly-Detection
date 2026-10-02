import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';

const root = resolve(__dirname, '../../../../..');
function command(file: string, args: string[], input?: string) {
  try { return execFileSync(file, args, { cwd: root, input, encoding: 'utf8', timeout: 60000,
    stdio: ['pipe', 'pipe', 'pipe'] }).trim(); }
  catch { throw new Error(`Local live-test prerequisite failed (${file}); sensitive output omitted.`); }
}
function admin(args: string[], input?: string) {
  return command('docker', ['compose', 'exec', '-T', 'keycloak', 'bash', '-ec', `
    config=$(mktemp)
    trap 'rm -f "$config"' EXIT
    /opt/keycloak/bin/kcadm.sh config credentials --config "$config" --server http://localhost:8080/auth \
      --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
    /opt/keycloak/bin/kcadm.sh "$@" --config "$config"
  `, '--', ...args], input);
}
function sql(query: string) {
  return command('docker', ['compose', 'exec', '-T', 'postgres', 'bash', '-ec',
    'exec psql -X -q -tA -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d incidents_db'], query);
}

test('real session-bound investigation and logout', async ({ page, context, browser }) => {
  const username = 'live-check-' + randomBytes(6).toString('hex');
  const password = randomBytes(24).toString('base64url') + '!Aa1';
  let userId = '';
  const results: any = { executedAt: new Date().toISOString(), runs: [], checks: [] };
  const resume = process.env.LIVE_RESUME_RESULTS;
  if (resume) {
    const previous = JSON.parse(readFileSync(resume, 'utf8'));
    results.runs = previous.runs;
    results.resumedFrom = previous.executedAt;
    results.checks = previous.checks;
  }
  try {
    const pre = await (await context.request.get('/api/auth/csrf')).json();
    const before = (await context.cookies()).find(c => c.name === 'JSESSIONID')?.value;
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Live', lastName: 'Verification',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error('Unexpected temporary user identity.');
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'SUPERVISOR']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Live verification']);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    try {
      await page.getByLabel(/username|email/i).fill(username);
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect.poll(() => new URL(page.url()).pathname).toBe('/dashboard');
    } catch { throw new Error('Real identity login failed; credentials omitted.'); }
    const cookie = (await context.cookies()).find(c => c.name === 'JSESSIONID');
    expect(Boolean(cookie?.value && cookie.value !== before)).toBe(true);
    expect({ httpOnly: cookie?.httpOnly, sameSite: cookie?.sameSite, secure: cookie?.secure })
      .toEqual({ httpOnly: true, sameSite: 'Lax', secure: false });
    const me = await (await context.request.get('/api/auth/me')).json();
    expect(Object.keys(me).sort()).toEqual(['analystId', 'displayName', 'expiresAt', 'roles']);
    const csrf = await (await context.request.get('/api/auth/csrf')).json();
    const invalid = await context.request.post('/api/incidents/00000000-0000-0000-0000-000000000000/status', {
      headers: { [pre.headerName]: pre.token }, data: { status: 'INVESTIGATING', version: 0 },
    });
    expect(invalid.status()).toBe(403);
    results.checks.push('real OIDC session rotation', 'old CSRF rejected', 'HTTP cookie flags', 'token-free session DTO');
    await context.addInitScript(() => {
      const Native = window.EventSource;
      (window as any).__streamCounts = { ready: 0, hints: 0, errors: 0 };
      window.EventSource = class extends Native {
        constructor(url: string | URL, options?: EventSourceInit) {
          super(url, options);
          this.addEventListener('ready', () => (window as any).__streamCounts.ready++);
          this.addEventListener('incident-upsert', () => (window as any).__streamCounts.hints++);
          this.addEventListener('error', () => (window as any).__streamCounts.errors++);
        }
      };
    });
    if (process.env.LIVE_SESSION_ONLY === '1') {
      results.mode = 'session logout regression';
      await page.goto('/services/VOLTE-MD-CENTRAL');
      const observer = await context.newPage();
      await observer.goto('/services/VOLTE-MD-CENTRAL');
      for (const tab of [page, observer]) {
        await expect.poll(() => tab.evaluate(() => (window as any).__streamCounts.ready)).toBeGreaterThan(0);
      }
      const recoveredId = process.env.LIVE_RECOVERED_INCIDENT_ID;
      if (recoveredId) {
        await page.goto(`/incidents/${recoveredId}`);
        await observer.goto(`/incidents/${recoveredId}`);
        await expect(page.locator('app-incident-actions')).toContainText('RECOVERED');
        await page.getByRole('button', { name: 'Claim for myself' }).click();
        await expect(page.getByRole('button', { name: 'Start investigation' })).toBeEnabled();
        await page.getByRole('button', { name: 'Start investigation' }).click();
        await expect(observer.locator('app-incident-actions')).toContainText('INVESTIGATING');
        const note = 'Verified recovered SMS delivery and queue evidence ' + randomUUID();
        await page.getByLabel('Investigation comment', { exact: true }).fill(note);
        await page.getByRole('button', { name: 'Add comment', exact: true }).click();
        await expect(observer.getByRole('region', { name: 'Investigation timeline' })).toContainText(note);
        await page.getByLabel('Resolution note', { exact: true }).fill('SMS recovery verified from three healthy windows.');
        await page.getByRole('button', { name: 'Resolve incident', exact: true }).click();
        await expect(observer.locator('app-incident-actions')).toContainText('RESOLVED');
        results.additionalIncident = recoveredId;
        results.checks.push('recovered SMS UI claim/investigate/comment/resolve with second-tab updates');
      }
      await page.getByRole('button', { name: 'Sign out', exact: true }).click();
      await expect.poll(() => new URL(page.url()).pathname).toBe('/signed-out');
      await expect.poll(() => observer.evaluate(() => (window as any).__streamCounts.errors)).toBeGreaterThan(0);
      expect((await context.request.get('/api/auth/me', { maxRedirects: 0 })).status()).toBe(401);
      expect((await context.request.get('/api/incidents/stream', { maxRedirects: 0 })).status()).toBe(401);
      const logs = command('docker', ['compose', 'logs', '--no-color', '--since', results.executedAt, 'incident-service']);
      expect(/Unable to handle.*already committed|Cannot render error page/.test(logs)).toBe(false);
      results.checks.push('real two-tab logout completion without committed-response errors', 'new stream remains protected');
      results.result = 'PASS';
      return;
    }
    if (!resume) for (const [type, scopeId] of [['VOLTE_IMS_OVERLOAD', 'VOLTE-MD-CENTRAL'], ['SMS_QUEUE_DELAY', 'SMS-MD-ROUTE-A']]) {
      const runner = await context.newPage();
      await runner.goto('/scenarios');
      await runner.getByLabel('Scenario', { exact: true }).selectOption(type);
      await runner.getByLabel('Service scope').selectOption(scopeId);
      await runner.getByLabel('Seed', { exact: true }).fill(String(Date.now() % 1000000000));
      const started = runner.waitForResponse(r => new URL(r.url()).pathname === `/api/simulator/scenarios/${type}` && r.request().method() === 'POST');
      await runner.getByRole('button', { name: 'Start scenario', exact: true }).click();
      const response = await started;
      expect([200, 201, 202]).toContain(response.status());
      const run = await response.json();
      results.runs.push({ type, scopeId, runId: run.runId, scheduledStartAt: run.scheduledStartAt,
        scheduledEndAt: run.scheduledEndAt });
      await runner.close();
    }
    console.log('Both public scenarios scheduled; waiting for genuine detector episodes.');
    const findIncident = async (run: any) => {
      const response = await context.request.get(`/api/incidents?scopeId=${run.scopeId}&size=100`);
      expect(response.status()).toBe(200);
      return (await response.json()).items.find((i: any) => Date.parse(i.firstObservedAt) >= Date.parse(run.scheduledStartAt)
        && Date.parse(i.firstObservedAt) < Date.parse(run.scheduledEndAt));
    };
    await expect.poll(async () => Boolean(await findIncident(results.runs[0])), { timeout: 360_000, intervals: [5000] }).toBe(true);
    let incident = await findIncident(results.runs[0]);
    results.runs[0].incidentId = incident.id;
    results.runs[0].episodeId = incident.episodeId;
    await page.goto(`/incidents/${incident.id}`);
    const observer = await context.newPage();
    await observer.goto(`/incidents/${incident.id}`);
    await expect.poll(() => observer.evaluate(() => (window as any).__streamCounts.ready)).toBeGreaterThan(0);
    if (!incident.assigneeId) {
      await page.getByRole('button', { name: 'Claim for myself' }).click();
    } else {
      await expect(page.getByLabel('Assign to enabled analyst')).toBeEnabled();
      await page.getByLabel('Assign to enabled analyst').selectOption(me.analystId);
      await page.getByRole('button', { name: 'Reassign', exact: true }).click();
    }
    if (incident.status === 'OPEN') {
      await expect(page.getByRole('button', { name: 'Start investigation' })).toBeEnabled();
      await page.getByRole('button', { name: 'Start investigation' }).click();
    }
    await expect(observer.locator('app-incident-actions')).toContainText('INVESTIGATING');
    const note = 'Verified live evidence and immutable event sequence ' + randomUUID();
    await page.getByLabel('Investigation comment', { exact: true }).fill(note);
    const commentResponse = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/comments') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Add comment', exact: true }).click();
    const posted = await commentResponse;
    expect(posted.status()).toBe(200);
    await expect(observer.getByRole('region', { name: 'Investigation timeline' })).toContainText(note);
    const repeated = await context.request.post(`/api/incidents/${incident.id}/comments`, {
      data: posted.request().postDataJSON(), headers: { [csrf.headerName]: csrf.token },
    });
    expect(repeated.status()).toBe(200);
    const timeline = await (await context.request.get(`/api/incidents/${incident.id}/timeline?size=100`)).json();
    expect(timeline.items.filter((i: any) => i.note === note)).toHaveLength(1);
    incident = await (await context.request.get(`/api/incidents/${incident.id}`)).json();
    if (incident.technicalState !== 'RECOVERED') {
      const early = await context.request.post(`/api/incidents/${incident.id}/status`, {
        headers: { [csrf.headerName]: csrf.token }, data: { status: 'RESOLVED', version: incident.version, resolutionNote: 'Too early' },
      });
      expect(early.status()).toBe(409);
      results.checks.push('premature resolution rejected');
    }
    results.checks.push('UI claim/reassign/investigate/comment', 'comment retry idempotency', 'second-tab committed updates');
    const readyBefore = await observer.evaluate(() => (window as any).__streamCounts.ready);
    const other = await browser.newContext({ baseURL: 'http://telecom.test:8080', storageState: await context.storageState() });
    await context.setOffline(true);
    // Chromium offline emulation alone may leave established EventSource sockets alive.
    // A real proxy restart closes those sockets while offline mode prevents reconnect.
    command('docker', ['compose', 'restart', 'proxy']);
    await expect.poll(async () => {
      try { return (await other.request.get('/api/auth/me')).status(); } catch { return 0; }
    }).toBe(200);
    await expect.poll(() => observer.evaluate(() => (window as any).__streamCounts.errors)).toBeGreaterThan(0);
    const current = await (await other.request.get(`/api/incidents/${incident.id}`)).json();
    const missed = 'Comment created while the viewing browser was disconnected';
    expect((await other.request.post(`/api/incidents/${incident.id}/comments`, {
      headers: { [csrf.headerName]: csrf.token }, data: { text: missed, version: current.version, requestId: randomUUID() },
    })).status()).toBe(200);
    await context.setOffline(false);
    await expect.poll(() => observer.evaluate(() => (window as any).__streamCounts.ready)).toBeGreaterThan(readyBefore);
    await expect(observer.getByRole('region', { name: 'Investigation timeline' })).toContainText(missed);
    await other.close();
    results.checks.push('proxy restart and offline reconnect REST reconciliation');
    console.log('Investigation and reconnect passed; waiting for actual service recovery.');
    await expect.poll(async () => (await findIncident(results.runs[0]))?.technicalState,
      { timeout: 360_000, intervals: [5000] }).toBe('RECOVERED');
    await expect(page.locator('app-incident-actions')).toContainText('RECOVERED');
    await page.getByLabel('Resolution note', { exact: true }).fill('Service recovery verified from three healthy windows.');
    await page.getByRole('button', { name: 'Resolve incident', exact: true }).click();
    await expect(observer.locator('app-incident-actions')).toContainText('RESOLVED');
    for (const run of results.runs) {
      await expect.poll(async () => (await findIncident(run))?.technicalState, { timeout: 90000, intervals: [5000] }).toBe('RECOVERED');
      const saved = await findIncident(run);
      run.incidentId = saved.id; run.episodeId = saved.episodeId;
      run.technicalState = saved.technicalState; run.status = saved.status;
      const detections = await (await context.request.get(`/api/incidents/${saved.id}/detections?size=100`)).json();
      run.phases = detections.items.map((i: any) => i.phase);
      run.modelStatuses = [...new Set(detections.items.map((i: any) => i.mlStatus))];
      expect(run.modelStatuses).toEqual(['OK']);
      expect(run.phases[run.phases.length - 1]).toBe('RECOVERY');
    }
    results.checks.push('both generated services recovered with real models', 'explicit UI resolution');
    const errorsBefore = await observer.evaluate(() => (window as any).__streamCounts.errors);
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect.poll(() => new URL(page.url()).pathname).toBe('/signed-out');
    await expect.poll(() => observer.evaluate(() => (window as any).__streamCounts.errors)).toBeGreaterThan(errorsBefore);
    expect((await context.request.get('/api/auth/me', { maxRedirects: 0 })).status()).toBe(401);
    results.checks.push('provider/local logout', 'other-tab session stream closed', 'post-logout API 401');
    results.result = 'PASS';
  } finally {
    await context.setOffline(false).catch(() => {});
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      // Keep the audit's local identity, disabled, instead of deleting referenced history.
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Live verification';`);
    }
    mkdirSync(resolve(root, 'apps/dashboard/test-results'), { recursive: true });
    writeFileSync(resolve(root, 'apps/dashboard/test-results/live-investigation-results.json'), JSON.stringify(results, null, 2));
    console.log(`Live checks: ${results.result ?? 'INCOMPLETE'}; ${results.checks.join('; ')}`);
  }
});
