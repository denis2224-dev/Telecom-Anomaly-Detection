import { test, expect, type BrowserContext, type Page } from '@playwright/test';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { command, admin, sql } from '../helpers/live-stack';

test.skip(process.env.E2E_RELEASE_LIVE !== '1', 'Opt-in real authenticated release stack; no fixture acceptance.');

test('release roles complete authenticated investigation and scenario workflows', async ({ browser }, info) => {
  const actors: { role: string; userId: string; context: BrowserContext; page: Page; analystId: string; csrf: { headerName: string; token: string } }[] = [];
  const results = {
    startedAtUTC: new Date().toISOString(), revision: command('git', ['rev-parse', 'HEAD']),
    patchSha256: createHash('sha256').update(command('git', ['diff', 'HEAD'])).digest('hex'),
    mode: 'Authenticated local backend; synthetic telemetry; no interception or fixture fallback',
    checks: [] as string[], requests: [] as { role: string; path: string; status: number }[],
    runs: [] as any[], captures: [] as { file: string; capturedAtUTC: string }[], passed: false,
  };
  if (process.env.RELEASE_RESUME_RESULTS) {
    const previous = JSON.parse(readFileSync(process.env.RELEASE_RESUME_RESULTS, 'utf8'));
    if (previous.runs?.length !== 2 || previous.runs.some((run: any) => !/^[0-9a-f-]{36}$/.test(run.runId)
      || !['VOLTE-MD-CENTRAL', 'SMS-MD-ROUTE-A'].includes(run.scopeId))) throw new Error('Resume requires recorded real release runs.');
    results.runs = previous.runs;
    results.checks.push(...(previous.checks ?? []));
    results.checks.push(`Resuming actual profiles started by the audit at ${previous.startedAtUTC}`);
  }
  const capture = async (page: Page, name: string, width = 1366) => {
    await page.setViewportSize({ width, height: 768 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    const file = `${name}-${width}.png`;
    await page.screenshot({ path: info.outputPath(file), fullPage: true, animations: 'disabled' });
    results.captures.push({ file, capturedAtUTC: new Date().toISOString() });
  };
  const read = async (actor: typeof actors[number], path: string) => {
    const response = await actor.context.request.get(path);
    expect(response.status(), path).toBe(200);
    return response.json();
  };
  const post = (actor: typeof actors[number], path: string, data: unknown) => actor.context.request.post(path, {
    data, headers: { [actor.csrf.headerName]: actor.csrf.token },
  });
  const clickWrite = async (page: Page, name: string, path: string, status = 200) => {
    const response = page.waitForResponse(response => new URL(response.url()).pathname === path && response.request().method() === 'POST');
    await page.getByRole('button', { name, exact: true }).click();
    const saved = await response;
    expect(saved.status()).toBe(status);
    return saved;
  };
  const showWorkflow = async (page: Page) => {
    await page.getByRole('button', { name: 'Details & workflow', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'Details & workflow', exact: true })).toBeVisible();
  };
  let generatorStopped = false;
  try {
    for (const role of ['ANALYST', 'SUPERVISOR']) {
      const username = 'release-check-' + randomBytes(6).toString('hex');
      const password = randomBytes(24).toString('base64url') + '!Aa1';
      const context = await browser.newContext({ baseURL: 'http://telecom.test:8080', viewport: { width: 1366, height: 768 } });
      const page = await context.newPage();
      const actor = { role, userId: '', context, page, analystId: '', csrf: { headerName: '', token: '' } };
      actors.push(actor);
      page.on('response', response => {
        const url = new URL(response.url());
        if (/^\/api\//.test(url.pathname)) results.requests.push({ role, path: url.pathname + url.search, status: response.status() });
      });
      expect((await context.request.get('/api/geography/cities')).status()).toBe(401);
      const oldCsrf = await (await context.request.get('/api/auth/csrf')).json();
      const before = (await context.cookies()).find(cookie => cookie.name === 'JSESSIONID')?.value;
      actor.userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
        username, enabled: true, firstName: 'Release', lastName: role,
        email: `${username}@example.invalid`, emailVerified: true,
        credentials: [{ type: 'password', value: password, temporary: false }],
      })).replaceAll('"', '');
      if (!/^[0-9a-f-]{36}$/.test(actor.userId)) throw new Error('Unexpected temporary identity; sensitive output omitted.');
      admin(['add-roles', '-r', 'telecom', '--uid', actor.userId, '--rolename', role]);
      command('./scripts/provision-analyst', ['--username', username, '--display-name', `Release ${role}`]);
      await page.goto('/login');
      await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
      try {
        await page.getByLabel(/username|email/i).fill(username);
        await page.getByLabel('Password', { exact: true }).fill(password);
        await page.getByRole('button', { name: /sign in/i }).click();
        await expect(page).toHaveURL(/\/dashboard$/);
      } catch { throw new Error(`Real ${role} sign-in failed; credentials omitted.`); }
      const me = await read(actor, '/api/auth/me');
      expect(me.roles).toContain(role);
      if (role === 'ANALYST') expect(me.roles).not.toContain('SUPERVISOR');
      actor.analystId = me.analystId;
      actor.csrf = await read(actor, '/api/auth/csrf');
      const cookie = (await context.cookies()).find(cookie => cookie.name === 'JSESSIONID');
      expect(Boolean(cookie?.value && cookie.value !== before && cookie.httpOnly && cookie.sameSite === 'Lax')).toBe(true);
      expect(Object.keys(me).sort()).toEqual(['analystId', 'displayName', 'expiresAt', 'roles']);
      const badCsrf = await context.request.post('/api/incidents/00000000-0000-0000-0000-000000000000/status', {
        headers: { [oldCsrf.headerName]: oldCsrf.token }, data: { status: 'INVESTIGATING', version: 0 },
      });
      expect(badCsrf.status()).toBe(403);
      await expect(page.getByText('SYNTHETIC FIXTURE PREVIEW', { exact: false })).toHaveCount(0);
      expect(await page.evaluate(() => [localStorage, sessionStorage].some(storage => Object.keys(storage).some(key =>
        /access[_-]?token|refresh[_-]?token|id[_-]?token|authorization|csrf/i.test(key)
        || /eyJ[\w-]+\.[\w-]+\.[\w-]+/.test(storage.getItem(key) ?? ''))))).toBe(false);
      results.checks.push(`${role}: real OIDC login, protected geography, rotated session/CSRF, token-free browser storage`);
    }
    const [analyst, supervisor] = actors;
    await analyst.page.goto('/scenarios');
    await expect(analyst.page.getByRole('button', { name: 'Start scenario', exact: true })).toHaveCount(0);
    const prohibited = await post(analyst, '/api/simulator/scenarios/NORMAL_CONTROL', { requestId: randomUUID(), scopeId: 'VOLTE-MD-CENTRAL', seed: 42 });
    expect(prohibited.status()).toBe(403);
    results.checks.push('ANALYST cannot start scenarios in the UI or protected API');

    const runners: Page[] = [];
    if (!results.runs.length) for (const [type, scopeId] of [['VOLTE_IMS_OVERLOAD', 'VOLTE-MD-CENTRAL'], ['SMS_QUEUE_DELAY', 'SMS-MD-ROUTE-A']]) {
      const runner = await supervisor.context.newPage(); runners.push(runner);
      await runner.goto('/scenarios');
      await runner.getByLabel('Scenario', { exact: true }).selectOption(type);
      await runner.getByLabel('Service scope', { exact: true }).selectOption(scopeId);
      await runner.getByLabel('Seed', { exact: true }).fill('42');
      const response = await clickWrite(runner, 'Start scenario', `/api/simulator/scenarios/${type}`, 202);
      results.runs.push({ type, scopeId, ...await response.json() });
      await expect(runner.getByRole('heading', { name: 'Server run' })).toBeVisible();
      await capture(runner, type);
    }
    for (const run of results.runs) {
      const authoritative = await read(supervisor, `/api/simulator/runs/${run.runId}`);
      expect(authoritative.scopeId).toBe(run.scopeId);
      expect(authoritative.scenarioType).toBe(run.type);
      expect(authoritative.scheduledStartAt).toBe(run.scheduledStartAt);
      expect((await analyst.context.request.get(`/api/simulator/runs/${run.runId}`)).status()).toBe(403);
    }
    console.log('Release scenarios scheduled; verifying analyst and supervisor investigations.');

    await analyst.page.goto('/dashboard');
    await analyst.page.getByLabel('Region', { exact: true }).fill('Orhei');
    await analyst.page.getByText('Orhei · City coverage and history', { exact: true }).click();
    await expect(analyst.page.locator('app-city-evidence [data-city-window]').first()).toBeVisible();
    for (const width of [1366, 768, 390]) await capture(analyst.page, 'orhei-release', width);
    await analyst.page.setViewportSize({ width: 1366, height: 768 });
    for (const [scopeId, name] of [['VOLTE-MD-ORH', 'Open Orhei VoLTE setup →'], ['SMS-MD-ORH', 'Open Orhei SMS delivery →']]) {
      await analyst.page.getByRole('link', { name, exact: true }).click();
      await expect(analyst.page).toHaveURL(new RegExp(`/services/${scopeId}$`));
      await expect(analyst.page.locator('.service-hero .actual-line')).not.toHaveAttribute('d', '');
      await capture(analyst.page, scopeId);
      await analyst.page.locator('.back-link').click();
      await analyst.page.getByLabel('Region', { exact: true }).fill('Orhei');
    }
    const catalogueReads = () => results.requests.filter(request => request.role === 'ANALYST' && request.path === '/api/geography/cities' && request.status === 200).length;
    const beforeReconnect = catalogueReads();
    await analyst.page.getByLabel('Region', { exact: true }).focus();
    command('docker', ['compose', 'stop', 'proxy']);
    try {
      await expect(analyst.page.getByText('Live connection interrupted. Existing evidence is still shown; reconnecting…', { exact: true })).toBeVisible();
    } finally { command('docker', ['compose', 'start', 'proxy']); }
    await expect.poll(catalogueReads, { timeout: 60_000 }).toBeGreaterThan(beforeReconnect);
    await expect(analyst.page.getByLabel('Region', { exact: true })).toHaveValue('Orhei');
    await expect(analyst.page.getByLabel('Region', { exact: true })).toBeFocused();
    results.checks.push('Orhei real city/service histories and actual SSE reconnect REST refresh with selection/focus preserved');

    const findIncident = async (run: any) => (await read(supervisor, `/api/incidents?scopeId=${run.scopeId}&size=100`)).items.find((item: any) =>
      Date.parse(item.firstObservedAt) >= Date.parse(run.scheduledStartAt) && Date.parse(item.firstObservedAt) < Date.parse(run.scheduledEndAt));
    for (const [index, run] of results.runs.entries()) {
      if (run.status === 'COMPLETED' && run.technicalState === 'RECOVERED'
        && run.workflowState === 'RESOLVED' && run.incidentId) continue;
      await expect.poll(async () => Boolean(await findIncident(run)), { timeout: 360_000, intervals: [5000] }).toBe(true);
      const incident = await findIncident(run);
      run.incidentId = incident.id;
      const actor = index === 0 ? analyst : supervisor;
      const observer = index === 0 ? supervisor : analyst;
      await actor.page.goto(`/incidents/${incident.id}`);
      await observer.page.goto(`/incidents/${incident.id}`);
      await showWorkflow(actor.page);
      await showWorkflow(observer.page);
      await expect(actor.page.getByLabel('Investigation comment', { exact: true })).toBeDisabled();
      if (index === 0) {
        await expect(actor.page.getByLabel('Assign to enabled analyst')).toHaveCount(0);
        await clickWrite(actor.page, 'Claim for myself', `/api/incidents/${incident.id}/assignment`);
      } else {
        await actor.page.getByLabel('Assign to enabled analyst').selectOption(analyst.analystId);
        await clickWrite(actor.page, 'Assign', `/api/incidents/${incident.id}/assignment`);
      }
      await clickWrite(actor.page, 'Start investigation', `/api/incidents/${incident.id}/status`);
      await expect(observer.page.locator('app-incident-actions')).toContainText('INVESTIGATING', { timeout: 60_000 });
      const note = `Release ${actor.role} evidence review ${randomUUID()}`;
      await actor.page.getByLabel('Investigation comment', { exact: true }).fill(note);
      const posted = await clickWrite(actor.page, 'Add comment', `/api/incidents/${incident.id}/comments`);
      await expect(observer.page.getByRole('region', { name: 'Investigation timeline' })).toContainText(note, { timeout: 60_000 });
      expect((await post(actor, `/api/incidents/${incident.id}/comments`, posted.request().postDataJSON())).status()).toBe(200);
      const timeline = await read(actor, `/api/incidents/${incident.id}/timeline?size=100`);
      expect(timeline.items.filter((event: any) => event.note === note)).toHaveLength(1);
      const stale = await post(actor, `/api/incidents/${incident.id}/status`, { status: 'INVESTIGATING', version: incident.version });
      expect(stale.status()).toBe(409);
      const current = await read(actor, `/api/incidents/${incident.id}`);
      if (current.technicalState !== 'RECOVERED') {
        await actor.page.getByLabel('Resolution note', { exact: true }).fill('Recovery has not been observed yet.');
        await expect(actor.page.getByRole('button', { name: 'Resolve incident', exact: true })).toBeDisabled();
        expect((await post(actor, `/api/incidents/${incident.id}/status`, { status: 'RESOLVED', version: current.version, resolutionNote: 'Premature resolution' })).status()).toBe(409);
      }
      if (index === 0) {
        await supervisor.page.getByLabel('Assign to enabled analyst').selectOption(supervisor.analystId);
        await clickWrite(supervisor.page, 'Reassign', `/api/incidents/${incident.id}/assignment`);
        await expect(analyst.page.getByLabel('Investigation comment', { exact: true })).toBeDisabled({ timeout: 60_000 });
        const assigned = await read(analyst, `/api/incidents/${incident.id}`);
        expect((await post(analyst, `/api/incidents/${incident.id}/comments`, { text: 'Forbidden non-assignee comment', requestId: randomUUID(), version: assigned.version })).status()).toBe(403);
        await supervisor.page.getByLabel('Assign to enabled analyst').selectOption(analyst.analystId);
        await clickWrite(supervisor.page, 'Reassign', `/api/incidents/${incident.id}/assignment`);
        await expect(analyst.page.getByLabel('Investigation comment', { exact: true })).toBeEnabled({ timeout: 60_000 });
      }
      results.checks.push(`${actor.role}: real assignment/investigation/comment, second-session updates, comment idempotency, stale version and premature resolution rejection`);
      await actor.page.getByRole('button', { name: 'Close details and workflow', exact: true }).click();
      await observer.page.getByRole('button', { name: 'Close details and workflow', exact: true }).click();
    }
    console.log('Release investigations verified; awaiting genuine scenario recovery.');
    for (const [index, run] of results.runs.entries()) {
      if (run.status === 'COMPLETED' && run.technicalState === 'RECOVERED'
        && run.workflowState === 'RESOLVED' && run.incidentId) continue;
      await expect.poll(async () => (await findIncident(run))?.technicalState, { timeout: 360_000, intervals: [5000] }).toBe('RECOVERED');
      await expect.poll(async () => (await read(supervisor, `/api/simulator/runs/${run.runId}`)).status, { timeout: 90_000 }).toBe('COMPLETED');
      const actor = index === 0 ? analyst : supervisor;
      const observer = index === 0 ? supervisor : analyst;
      await actor.page.goto(`/incidents/${run.incidentId}`);
      await observer.page.goto(`/incidents/${run.incidentId}`);
      await expect(actor.page.locator('.incident-summary-bar')).toContainText('Awaiting analyst resolution');
      await showWorkflow(actor.page);
      await showWorkflow(observer.page);
      await actor.page.getByLabel('Resolution note', { exact: true }).fill('Release acceptance: technical recovery verified from authoritative detection evidence.');
      await clickWrite(actor.page, 'Resolve incident', `/api/incidents/${run.incidentId}/status`);
      await expect(observer.page.locator('app-incident-actions')).toContainText('RESOLVED', { timeout: 60_000 });
      const saved = await read(actor, `/api/incidents/${run.incidentId}`);
      expect(saved.technicalState).toBe('RECOVERED');
      expect(saved.status).toBe('RESOLVED');
      run.status = 'COMPLETED'; run.technicalState = saved.technicalState; run.workflowState = saved.status;
      await capture(actor.page, `resolved-${actor.role}`);
      await capture(actor.page, `resolved-${actor.role}`, 390);
      await actor.page.setViewportSize({ width: 1366, height: 768 });
      await actor.page.getByRole('button', { name: 'Close details and workflow', exact: true }).click();
      await observer.page.getByRole('button', { name: 'Close details and workflow', exact: true }).click();
    }
    results.checks.push('Both real fault profiles completed and recovered; ANALYST/SUPERVISOR resolved only after technical recovery');

    const unavailable = await supervisor.context.newPage();
    await unavailable.goto('/scenarios');
    await unavailable.getByLabel('Scenario', { exact: true }).selectOption('NORMAL_CONTROL');
    await unavailable.getByLabel('Service scope', { exact: true }).selectOption('VOLTE-MD-CENTRAL');
    command('docker', ['compose', 'stop', 'event-generator']); generatorStopped = true;
    const failed = await clickWrite(unavailable, 'Start scenario', '/api/simulator/scenarios/NORMAL_CONTROL', 503);
    await expect(unavailable.getByRole('alert').first()).toContainText('uncertain');
    await expect(unavailable.getByRole('heading', { name: 'Server run' })).toHaveCount(0);
    command('docker', ['compose', 'start', 'event-generator']); generatorStopped = false;
    await expect.poll(() => {
      try { return command('docker', ['compose', 'exec', '-T', 'event-generator', 'curl', '--silent', '--fail', 'http://localhost:8081/actuator/health/readiness']); }
      catch { return ''; }
    }, { timeout: 60_000 }).toContain('UP');
    let retry = failed;
    for (let attempt = 0; attempt < 15; attempt++) {
      const button = unavailable.getByRole('button', { name: 'Retry same command', exact: true });
      await expect(button).toBeEnabled();
      const response = unavailable.waitForResponse(saved =>
        new URL(saved.url()).pathname === '/api/simulator/scenarios/NORMAL_CONTROL'
        && saved.request().method() === 'POST');
      await button.click();
      retry = await response;
      expect(retry.request().postDataJSON()).toEqual(failed.request().postDataJSON());
      if (retry.status() === 202) break;
      expect(retry.status()).toBe(503);
      await unavailable.waitForTimeout(3000);
    }
    expect(retry.status()).toBe(202);
    let control = await retry.json();
    const conflict = await supervisor.context.newPage();
    await conflict.goto('/scenarios');
    await conflict.getByLabel('Scenario', { exact: true }).selectOption('NORMAL_CONTROL');
    await conflict.getByLabel('Service scope', { exact: true }).selectOption('VOLTE-MD-CENTRAL');
    await clickWrite(conflict, 'Start scenario', '/api/simulator/scenarios/NORMAL_CONTROL', 409);
    await expect(conflict.getByRole('alert').first()).toContainText('conflicts');
    await expect(conflict.getByRole('heading', { name: 'Server run' })).toHaveCount(0);
    await conflict.close();
    const currentControl = await read(supervisor, `/api/simulator/runs/${control.runId}`);
    if (currentControl.status === 'FAILED') {
      await expect(unavailable.locator('.server-run')).toContainText('FAILED');
      await expect(unavailable.getByRole('button', { name: 'Stop telemetry', exact: true })).toHaveCount(0);
      await unavailable.getByRole('button', { name: 'Prepare another command', exact: true }).click();
      control = await (await clickWrite(unavailable, 'Start scenario', '/api/simulator/scenarios/NORMAL_CONTROL', 202)).json();
      results.checks.push('Generator restart failure stays FAILED; the UI requires a fresh command instead of stopping a terminal run');
    }
    await clickWrite(unavailable, 'Stop telemetry', `/api/simulator/runs/${control.runId}/stop`);
    await expect(unavailable.locator('.server-run')).toContainText('STOPPED');
    results.checks.push('Real generator 503 shows uncertainty, retries identical command, rejects a conflicting run and stops telemetry without claiming recovery');
    await unavailable.close();
    for (const runner of runners) await runner.close();
    for (const actor of actors) {
      await actor.page.getByRole('button', { name: 'Sign out', exact: true }).click();
      await expect(actor.page).toHaveURL(/\/signed-out$/);
      expect((await actor.context.request.get('/api/auth/me')).status()).toBe(401);
      expect((await actor.context.request.get('/api/geography/cities')).status()).toBe(401);
      expect((await actor.context.request.get('/api/incidents/stream')).status()).toBe(401);
      results.checks.push(`${actor.role}: real logout ends protected REST and stream access`);
    }
    results.passed = true;
  } finally {
    writeFileSync(info.outputPath('release-results.json'), JSON.stringify(results, null, 2));
    if (generatorStopped) command('docker', ['compose', 'start', 'event-generator']);
    for (const actor of actors) {
      try { await actor.context.close(); } catch { /* Preserve the test failure after a browser timeout. */ }
      if (/^[0-9a-f-]{36}$/.test(actor.userId)) {
        admin(['delete', `users/${actor.userId}`, '-r', 'telecom']);
        sql(`UPDATE app.analysts SET enabled=false WHERE subject='${actor.userId}' AND display_name='Release ${actor.role}';`);
      }
    }
  }
});

test('matches live incident fields across API, database and browser', async ({ browser }, info) => {
  const reportPath = process.env.RELEASE_RESUME_RESULTS;
  if (!reportPath) { test.skip(true, 'Set RELEASE_RESUME_RESULTS to a saved real release report.'); return; }
  const runs = JSON.parse(readFileSync(reportPath, 'utf8')).runs as { incidentId: string; scopeId: string }[];
  expect(runs).toHaveLength(2);
  const username = 'contract-check-' + randomBytes(6).toString('hex');
  const password = randomBytes(24).toString('base64url') + '!Aa1';
  let userId = '';
  const context = await browser.newContext({ baseURL: 'http://telecom.test:8080' });
  const page = await context.newPage();
  const comparisons: Record<string, unknown>[] = [];
  try {
    userId = admin(['create', 'users', '-r', 'telecom', '-i', '-f', '/dev/stdin'], JSON.stringify({
      username, enabled: true, firstName: 'Contract', lastName: 'Verification',
      email: `${username}@example.invalid`, emailVerified: true,
      credentials: [{ type: 'password', value: password, temporary: false }],
    })).replaceAll('"', '');
    expect(userId).toMatch(/^[0-9a-f-]{36}$/);
    admin(['add-roles', '-r', 'telecom', '--uid', userId, '--rolename', 'ANALYST']);
    command('./scripts/provision-analyst', ['--username', username, '--display-name', 'Contract verification']);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Continue to sign in', exact: true }).click();
    await page.getByLabel(/username|email/i).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect(page).toHaveURL(/\/dashboard$/);

    for (const run of runs) {
      expect(run.incidentId).toMatch(/^[0-9a-f-]{36}$/);
      const response = await context.request.get(`/api/incidents/${run.incidentId}`);
      expect(response.status()).toBe(200);
      const api = await response.json();
      const database = JSON.parse(sql(`SELECT row_to_json(i) FROM (
        SELECT id,episode_id,service,scope_id,status,technical_state,severity,
               first_observed_at,detected_at,last_observed_at,version,latest_sequence
        FROM app.incidents WHERE id='${run.incidentId}'
      ) i;`));
      const evidence = JSON.parse(sql(`SELECT payload FROM app.detection_evidence
        WHERE episode_id='${database.episode_id}' AND sequence=${database.latest_sequence};`));
      for (const [apiName, dbName] of Object.entries({
        id: 'id', episodeId: 'episode_id', service: 'service', scopeId: 'scope_id',
        status: 'status', technicalState: 'technical_state', severity: 'severity',
        version: 'version', latestSequence: 'latest_sequence',
      })) expect(api[apiName], apiName).toEqual(database[dbName]);
      for (const [apiName, dbName] of Object.entries({
        firstObservedAt: 'first_observed_at', detectedAt: 'detected_at',
        lastObservedAt: 'last_observed_at',
      })) expect(Date.parse(api[apiName]), apiName).toBe(Date.parse(database[dbName]));
      for (const field of ['detectionId', 'sequence', 'phase', 'technicalState',
        'severity', 'probableCause', 'causeConfidence']) {
        expect(api.latestDetection[field], field).toEqual(evidence[field]);
      }
      await page.goto(`/incidents/${run.incidentId}`);
      const summary = page.locator('.incident-summary-bar');
      await expect(summary).toContainText(api.service === 'VOLTE' ? 'VoLTE setup' : 'SMS delivery');
      await expect(summary.locator(`[data-state="${api.severity}"]`)).toBeVisible();
      await expect(summary.locator(`[data-state="${api.technicalState}"]`)).toBeVisible();
      await expect(summary).toContainText(api.status);
      comparisons.push({ incidentId: api.id, service: api.service, scopeId: api.scopeId,
        status: api.status, technicalState: api.technicalState, severity: api.severity,
        version: api.version, latestSequence: api.latestSequence,
        firstObservedAt: api.firstObservedAt, detectedAt: api.detectedAt,
        lastObservedAt: api.lastObservedAt, latestPhase: api.latestDetection.phase,
        probableCause: api.latestDetection.probableCause,
        causeConfidence: api.latestDetection.causeConfidence });
    }
  } finally {
    writeFileSync(info.outputPath('contract-comparison.json'), JSON.stringify({
      revision: command('git', ['rev-parse', 'HEAD']), comparisons,
    }, null, 2));
    await context.close();
    if (/^[0-9a-f-]{36}$/.test(userId)) {
      admin(['delete', `users/${userId}`, '-r', 'telecom']);
      sql(`UPDATE app.analysts SET enabled=false WHERE subject='${userId}' AND display_name='Contract verification';`);
    }
  }
});
